package timely.store;

import static org.apache.accumulo.core.conf.AccumuloConfiguration.getMemoryInBytes;
import static org.apache.accumulo.core.conf.AccumuloConfiguration.getTimeInMillis;
import static timely.adapter.accumulo.MetricAdapter.VISIBILITY_TAG;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.netty.handler.codec.http.HttpResponseStatus;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.ClientConfiguration;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.NamespaceExistsException;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.ScannerBase;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorUtil.IteratorScope;
import org.apache.accumulo.core.iterators.user.RegExFilter;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.accumulo.core.security.VisibilityEvaluator;
import org.apache.accumulo.core.util.Pair;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import timely.Server;
import timely.adapter.accumulo.MetricAdapter;
import timely.api.model.Meta;
import timely.api.request.AuthenticatedRequest;
import timely.api.request.timeseries.QueryRequest;
import timely.api.request.timeseries.QueryRequest.RateOption;
import timely.api.request.timeseries.QueryRequest.SubQuery;
import timely.api.request.timeseries.SearchLookupRequest;
import timely.api.request.timeseries.SuggestRequest;
import timely.api.response.TimelyException;
import timely.api.response.timeseries.QueryResponse;
import timely.api.response.timeseries.SearchLookupResponse;
import timely.api.response.timeseries.SearchLookupResponse.Result;
import timely.api.response.timeseries.SuggestResponse;
import timely.auth.AuthCache;
import timely.auth.util.ScannerHelper;
import timely.configuration.Accumulo;
import timely.configuration.Configuration;
import timely.configuration.Security;
import timely.model.Metric;
import timely.model.Tag;
import timely.sample.Aggregation;
import timely.sample.Aggregator;
import timely.sample.Sample;
import timely.sample.iterators.AggregationIterator;
import timely.sample.iterators.DownsampleIterator;
import timely.store.cache.DataStoreCache;
import timely.store.iterators.RateIterator;
import timely.util.MetaKeySet;

public class DataStoreImpl implements DataStore {

    private static final Logger LOG = LoggerFactory.getLogger(DataStoreImpl.class);

    private static final long METRICS_PERIOD = 30000;
    private static final Pattern REGEX_TEST = Pattern.compile("^\\w+$");

    /*
     * Pair doesn't implement Comparable
     */
    private static class MetricTagK extends Pair<String, String> implements Comparable<MetricTagK> {

        public MetricTagK(String f, String s) {
            super(f, s);
        }

        @Override
        public int compareTo(MetricTagK o) {
            int result = getFirst().compareTo(o.getFirst());
            if (result != 0) {
                return result;
            }
            return getSecond().compareTo(o.getSecond());
        }

    }

    private final Connector connector;
    private MetaCache metaCache = null;
    private final AtomicLong lastCountTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicReference<SortedMap<MetricTagK, Integer>> metaCounts = new AtomicReference<>(new TreeMap<>());
    private final String metricsTable;
    private final String metaTable;
    private final InternalMetrics internalMetrics;
    private final Timer internalMetricsTimer = new Timer("InternalMetricsTimer", true);
    private final int scannerThreads;
    private final long maxDownsampleMemory;
    private final BatchWriterConfig bwConfig;
    private final List<BatchWriter> writers = new ArrayList<>();
    private final ThreadLocal<BatchWriter> metaWriter = new ThreadLocal<>();
    private final ThreadLocal<BatchWriter> batchWriter = new ThreadLocal<>();
    private final Security security;
    private final Map<String, String> ageOff;
    private final long defaultAgeOffMilliSec;
    private DataStoreCache cache = null;
    private final String defaultVisibility;

    public DataStoreImpl(Configuration conf, int numWriteThreads) throws TimelyException {

        try {
            final Map<String, String> properties = new HashMap<>();
            Accumulo accumuloConf = conf.getAccumulo();
            properties.put("instance.name", accumuloConf.getInstanceName());
            properties.put("instance.zookeeper.host", accumuloConf.getZookeepers());
            final ClientConfiguration aconf = ClientConfiguration.fromMap(properties);
            final Instance instance = new ZooKeeperInstance(aconf);
            connector = instance.getConnector(accumuloConf.getUsername(),
                    new PasswordToken(accumuloConf.getPassword()));
            bwConfig = new BatchWriterConfig();
            bwConfig.setMaxLatency(getTimeInMillis(accumuloConf.getWrite().getLatency()), TimeUnit.MILLISECONDS);
            bwConfig.setMaxMemory(getMemoryInBytes(accumuloConf.getWrite().getBufferSize()) / numWriteThreads);
            bwConfig.setMaxWriteThreads(accumuloConf.getWrite().getThreads());
            scannerThreads = accumuloConf.getScan().getThreads();
            maxDownsampleMemory = accumuloConf.getScan().getMaxDownsampleMemory();
            security = conf.getSecurity();
            defaultVisibility = conf.getDefaultVisibility();
            if (StringUtils.isNotBlank(defaultVisibility)) {
                ColumnVisibility vis;
                try {
                    // validate visibility
                    vis = new ColumnVisibility(defaultVisibility);
                    LOG.info("Using defaultVisibility: {}", vis.toString());
                } catch (RuntimeException e) {
                    LOG.error("Error validating defaultVisibility " + defaultVisibility + " " + e.getMessage(), e);
                    throw e;
                }

                try {
                    String whoami = connector.whoami();
                    Authorizations auths = connector.securityOperations().getUserAuthorizations(whoami);
                    VisibilityEvaluator eval = new VisibilityEvaluator(auths);
                    if (!eval.evaluate(vis)) {
                        throw new IllegalArgumentException("Accumulo user " + whoami + " with authorization "
                                + auths.toString() + " can not see data with defaultVisibility " + vis.toString());
                    }
                } catch (RuntimeException e) {
                    LOG.error(e.getMessage(), e);
                    throw e;
                }
            }

            metricsTable = conf.getMetricsTable();
            if (metricsTable.contains(".")) {
                final String[] parts = metricsTable.split("\\.", 2);
                final String namespace = parts[0];
                if (!connector.namespaceOperations().exists(namespace)) {
                    try {
                        LOG.info("Creating namespace " + namespace);
                        connector.namespaceOperations().create(namespace);
                    } catch (final NamespaceExistsException ex) {
                        // don't care
                    }
                }
            }
            ageOff = getAgeOff(conf);
            defaultAgeOffMilliSec = this.getAgeOffForMetric(MetricAgeOffIterator.DEFAULT_AGEOFF_KEY);

            final Map<String, String> tableIdMap = connector.tableOperations().tableIdMap();
            if (!tableIdMap.containsKey(metricsTable)) {
                try {
                    LOG.info("Creating table " + metricsTable);
                    connector.tableOperations().create(metricsTable);
                } catch (final TableExistsException ex) {
                    // don't care
                }
            }
            try {
                this.removeAgeOffIterators(connector, metricsTable);
                this.applyMetricAgeOffIterator(connector, metricsTable);
            } catch (Exception e1) {
                Throwable cause = e1.getCause();
                if (cause.getMessage().contains("conflict")) {
                    LOG.info("ignoring iterator conflict due to multiple instances starting up");
                } else {
                    throw e1;
                }
            }

            metaTable = conf.getMetaTable();
            if (!tableIdMap.containsKey(metaTable)) {
                try {
                    LOG.info("Creating table " + metaTable);
                    connector.tableOperations().create(metaTable);
                } catch (final TableExistsException ex) {
                    // don't care
                }
            }
            try {
                this.removeAgeOffIterators(connector, metaTable);
                this.applyMetaAgeOffIterator(connector, metaTable);
            } catch (Exception e1) {
                Throwable cause = e1.getCause();
                if (cause.getMessage().contains("conflict")) {
                    LOG.info("ignoring iterator conflict due to multiple instances starting up");
                } else {
                    throw e1;
                }
            }

            internalMetrics = new InternalMetrics(conf);
            internalMetricsTimer.schedule(new TimerTask() {

                @Override
                public void run() {
                    internalMetrics.getMetricsAndReset().forEach(m -> store(m, false));
                }

            }, METRICS_PERIOD, METRICS_PERIOD);

            this.metaCache = MetaCacheFactory.getCache(conf);
        } catch (Exception e2) {
            throw new TimelyException(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), "Error creating DataStoreImpl",
                    e2.getMessage(), e2);
        }
    }

    @Override
    public InternalMetrics getInternalMetrics() {
        return internalMetrics;
    }

    @Override
    public void setCache(DataStoreCache cache) {
        this.cache = cache;
    }

    @Override
    public DataStoreCache getCache() {
        return cache;
    }

    private static final EnumSet<IteratorScope> AGEOFF_SCOPES = EnumSet.allOf(IteratorScope.class);

    private void removeAgeOffIterators(Connector con, String tableName) throws Exception {
        Map<String, EnumSet<IteratorScope>> iters = con.tableOperations().listIterators(tableName);
        for (String name : iters.keySet()) {
            if (name.startsWith("ageoff")) {
                con.tableOperations().removeIterator(tableName, name, AGEOFF_SCOPES);
            }
        }
    }

    private void applyMetricAgeOffIterator(Connector con, String tableName) throws Exception {
        IteratorSetting ageOffIteratorSettings = new IteratorSetting(100, "ageoffmetrics", MetricAgeOffIterator.class,
                this.ageOff);
        connector.tableOperations().attachIterator(tableName, ageOffIteratorSettings, AGEOFF_SCOPES);
    }

    private void applyMetaAgeOffIterator(Connector con, String tableName) throws Exception {
        IteratorSetting ageOffIteratorSettings = new IteratorSetting(100, "ageoffmeta", MetaAgeOffIterator.class,
                this.ageOff);
        connector.tableOperations().attachIterator(tableName, ageOffIteratorSettings, AGEOFF_SCOPES);
    }

    private Map<String, String> getAgeOff(Configuration conf) {
        Map<String, String> ageOffOptions = new HashMap<>();
        conf.getMetricAgeOffDays().forEach((k, v) -> {
            String ageoff = Long.toString(v * 86400000L);
            LOG.trace("Adding age off for metric: {} of {} days", k, v);
            ageOffOptions.put(MetricAgeOffIterator.AGE_OFF_PREFIX + k, ageoff);
        });
        return ageOffOptions;
    }

    @Override
    public void store(Metric metric) {
        store(metric, true);
    }

    public void store(Metric metric, boolean cacheEnabled) {
        LOG.trace("Received Store Request for: {}", metric);

        // if default visibility is configured and current metric does not contain a
        // visibility
        // add it here, so that the cache and accumulo stores both have the correct
        // visibility
        if (StringUtils.isNotBlank(defaultVisibility)) {
            Optional<Tag> visTag = metric.getTags().stream().filter(t -> t.getKey().equals(VISIBILITY_TAG)).findFirst();
            if (!visTag.isPresent()) {
                metric.addTag(new Tag(VISIBILITY_TAG, defaultVisibility));
            }
        }

        if (cache != null && cacheEnabled) {
            cache.store(metric);
        }

        if (null == metaWriter.get()) {
            try {
                BatchWriter w = connector.createBatchWriter(metaTable, bwConfig);
                metaWriter.set(w);
                writers.add(w);
            } catch (TableNotFoundException e) {
                LOG.error("Error creating meta batch writer", e);
                return;
            }
        }
        if (null == batchWriter.get()) {
            try {
                BatchWriter w = connector.createBatchWriter(metricsTable, bwConfig);
                batchWriter.set(w);
                writers.add(w);
            } catch (TableNotFoundException e) {
                LOG.error("Error creating metric batch writer", e);
                return;
            }
        }

        internalMetrics.incrementMetricsReceived(1);
        List<Meta> toCache = new ArrayList<>(metric.getTags().size());
        for (final Tag tag : metric.getTags()) {
            Meta key = new Meta(metric.getName(), tag.getKey(), tag.getValue());
            if (!metaCache.contains(key)) {
                toCache.add(key);
            }
        }
        if (!toCache.isEmpty()) {
            final Set<Mutation> muts = new TreeSet<>(new Comparator<Mutation>() {

                @Override
                public int compare(Mutation o1, Mutation o2) {
                    if (o1.equals(o2)) {
                        return 0;
                    } else {
                        if (o1.hashCode() < o2.hashCode()) {
                            return -1;
                        } else {
                            return 1;
                        }
                    }
                }
            });
            MetaKeySet mks = new MetaKeySet();
            toCache.forEach(m -> mks.addAll(m.toKeys()));
            internalMetrics.incrementMetaKeysInserted(mks.size());
            muts.addAll(mks.toMutations());
            try {
                metaWriter.get().addMutations(muts);
            } catch (MutationsRejectedException e) {
                LOG.error("Unable to write to meta table", e);
                try {
                    try {
                        final BatchWriter w = metaWriter.get();
                        metaWriter.remove();
                        writers.remove(w);
                        w.close();
                    } catch (MutationsRejectedException e1) {
                        LOG.error("Error closing meta writer", e1);
                    }
                    final BatchWriter w = connector.createBatchWriter(metaTable, bwConfig);
                    metaWriter.set(w);
                    writers.add(w);
                } catch (TableNotFoundException e1) {
                    Server.fatal("Unexpected error recreating meta batch writer, shutting down Timely server", e1);
                }
            }
            metaCache.addAll(toCache);
        }
        try {
            batchWriter.get().addMutation(MetricAdapter.toMutation(metric));
            internalMetrics.incrementMetricKeysInserted(metric.getTags().size());
        } catch (MutationsRejectedException e) {
            LOG.error("Unable to write to metrics table", e);
            try {
                try {
                    final BatchWriter w = batchWriter.get();
                    batchWriter.remove();
                    writers.remove(w);
                    w.close();
                } catch (MutationsRejectedException e1) {
                    LOG.error("Error closing metric writer", e1);
                }
                final BatchWriter w = connector.createBatchWriter(metricsTable, bwConfig);
                batchWriter.set(w);
                writers.add(w);
            } catch (TableNotFoundException e1) {
                Server.fatal("Unexpected error recreating metrics batch writer, shutting down Timely server", e1);
            }
        }
    }

    private static final long FIVE_MINUTES_IN_MS = TimeUnit.MINUTES.toMillis(5);

    private void updateMetricCounts() {
        long now = System.currentTimeMillis();
        if (now - lastCountTime.get() > FIVE_MINUTES_IN_MS) {
            this.lastCountTime.set(now);
            SortedMap<MetricTagK, Integer> update = new TreeMap<>();
            for (Meta meta : this.metaCache) {
                MetricTagK key = new MetricTagK(meta.getMetric(), meta.getTagKey());
                Integer count = update.getOrDefault(key, 0);
                update.put(key, count + 1);
            }
            this.metaCounts.set(update);
        }
    }

    @Override
    public SuggestResponse suggest(SuggestRequest request) throws TimelyException {
        SuggestResponse result = new SuggestResponse();
        Scanner scanner = null;
        try {
            if (request.getType().equals("metrics")) {
                Range range;
                if (request.getQuery().isPresent()) {
                    Text start = new Text(Meta.METRIC_PREFIX + request.getQuery().get());
                    Text endRow = new Text(start);
                    endRow.append(new byte[] { (byte) 0xff }, 0, 1);
                    range = new Range(start, endRow);
                } else {
                    // kind of a hack, maybe someone wants a metric with >100
                    // 0xff bytes?
                    Text start = new Text(Meta.METRIC_PREFIX);
                    byte last = (byte) 0xff;
                    byte[] lastBytes = new byte[100];
                    Arrays.fill(lastBytes, last);
                    Text end = new Text(Meta.METRIC_PREFIX);
                    end.append(lastBytes, 0, lastBytes.length);
                    range = new Range(start, end);
                }
                scanner = connector.createScanner(metaTable, Authorizations.EMPTY);
                scanner.setRange(range);
                List<String> metrics = new ArrayList<>();
                for (Entry<Key, Value> metric : scanner) {
                    metrics.add(metric.getKey().getRow().toString().substring(Meta.METRIC_PREFIX.length()));
                    if (metrics.size() >= request.getMax()) {
                        break;
                    }
                }
                result.setSuggestions(metrics);
            }
        } catch (Exception ex) {
            LOG.error("Error during suggest: " + ex.getMessage(), ex);
            throw new TimelyException(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                    "Error during suggest: " + ex.getMessage(), ex.getMessage(), ex);
        } finally {
            if (scanner != null) {
                scanner.close();
            }
        }
        return result;
    }

    @Override
    public void flush() {
        internalMetricsTimer.cancel();
        writers.forEach(w -> {
            try {
                w.close();
            } catch (final Exception ex) {
                LOG.warn("Error shutting down batchwriter", ex);
            }

        });
    }

    @Override
    public SearchLookupResponse lookup(SearchLookupRequest msg) throws TimelyException {
        long startMillis = System.currentTimeMillis();
        SearchLookupResponse result = new SearchLookupResponse();
        result.setType("LOOKUP");
        result.setMetric(msg.getQuery());
        Map<String, String> tags = new TreeMap<>();
        for (Tag tag : msg.getTags()) {
            tags.put(tag.getKey(), tag.getValue());
        }
        result.setTags(tags);
        result.setLimit(msg.getLimit());
        Map<String, Pattern> tagPatterns = new HashMap<>();
        tags.forEach((k, v) -> {
            tagPatterns.put(k, Pattern.compile(v));
        });
        try {
            final Scanner scanner = connector.createScanner(metaTable, Authorizations.EMPTY);
            try {
                List<Result> resultField = new ArrayList<>();
                Key start = new Key(Meta.VALUE_PREFIX + msg.getQuery());
                Key end = start.followingKey(PartialKey.ROW);
                Range range = new Range(start, end);
                scanner.setRange(range);
                tags.keySet().forEach(k -> scanner.fetchColumnFamily(new Text(k)));
                int total = 0;
                for (Entry<Key, Value> entry : scanner) {
                    Meta metaEntry = Meta.parse(entry.getKey(), entry.getValue());
                    if (matches(metaEntry.getTagKey(), metaEntry.getTagValue(), tagPatterns)) {
                        if (resultField.size() < msg.getLimit()) {
                            Result r = new Result();
                            r.putTag(metaEntry.getTagKey(), metaEntry.getTagValue());
                            resultField.add(r);
                        }
                        total++;
                    }
                }
                result.setResults(resultField);
                result.setTotalResults(total);
                result.setTime((int) (System.currentTimeMillis() - startMillis));
            } finally {
                if (scanner != null) {
                    scanner.close();
                }
            }
        } catch (Exception ex) {
            LOG.error("Error during lookup: " + ex.getMessage(), ex);
            throw new TimelyException(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                    "Error during lookup: " + ex.getMessage(), ex.getMessage(), ex);
        }
        return result;
    }

    private boolean matches(String tagk, String tagv, Map<String, Pattern> tags) {
        for (Entry<String, Pattern> entry : tags.entrySet()) {
            if (tagk.equals(entry.getKey()) && entry.getValue().matcher(tagv).matches()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<QueryResponse> query(QueryRequest msg) throws TimelyException {
        List<QueryResponse> result = new ArrayList<>();
        LOG.debug("Query for [{}] [{}]", msg.getUserName(), msg);
        long requestedStartTs = msg.getStart();
        long requestedEndTs = msg.getEnd();
        StringBuilder metricList = new StringBuilder();

        try {
            long numResults = 0;
            long now = System.currentTimeMillis();
            for (SubQuery query : msg.getQueries()) {
                Map<Set<Tag>, List<Aggregation>> allSeries = new HashMap<>();
                String metric = query.getMetric();
                if (metricList.length() > 0) {
                    metricList.append(",");
                }
                metricList.append(metric);
                Map<Set<Tag>, List<Aggregation>> cachedMetrics = new HashMap<>();
                long oldestTimestampFromCache = Long.MAX_VALUE;

                if (cache != null) {
                    long oldestCacheTimestamp = cache.getOldestTimestamp(query.getMetric());
                    if (requestedEndTs >= oldestCacheTimestamp) {
                        cachedMetrics = cache.subquery(msg, query);

                        int z = 0;
                        for (Collection<Aggregation> c : cachedMetrics.values()) {
                            z += c.size();
                        }
                        long totalRequestedTime = requestedEndTs - requestedStartTs;
                        long percentServedFromCache = 0;
                        if (!cachedMetrics.isEmpty() && oldestCacheTimestamp != Long.MAX_VALUE) {
                            if (requestedStartTs < oldestCacheTimestamp && requestedEndTs < oldestCacheTimestamp) {
                                percentServedFromCache = 0;
                            } else if (requestedStartTs >= oldestCacheTimestamp
                                    && requestedEndTs >= oldestCacheTimestamp) {
                                percentServedFromCache = 100;
                            } else {
                                long timeAnsweredFromCache = requestedEndTs - oldestCacheTimestamp;
                                percentServedFromCache = Math
                                        .round((double) timeAnsweredFromCache / (double) totalRequestedTime * 100);
                            }
                            if (percentServedFromCache > 100) {
                                percentServedFromCache = 100;
                            }
                            LOG.debug(
                                    "Cache query for [{}] time:{} duration (min):{} metrics:{} results:{} percentFromCache:{}",
                                    msg.getUserName(), (System.currentTimeMillis() - now),
                                    ((requestedEndTs - requestedStartTs) / (1000 * 60)), metricList.toString(), z,
                                    percentServedFromCache);
                            allSeries.putAll(cachedMetrics);
                        }
                    }
                    oldestTimestampFromCache = Math.max(oldestCacheTimestamp,
                            System.currentTimeMillis() - cache.getAgeOffForMetric(query.getMetric()) + 1);
                }

                if (cachedMetrics.isEmpty() || requestedStartTs < oldestTimestampFromCache) {
                    // we have already searched from oldestTimestampFromCache to
                    // requestedEndTs
                    long endTs = (oldestTimestampFromCache == Long.MAX_VALUE) ? requestedEndTs
                            : oldestTimestampFromCache - 1;

                    // Reset the start timestamp for the query to the
                    // beginning of the downsample period based on the epoch
                    long downsample = DownsampleIterator.getDownsamplePeriod(query);
                    LOG.trace("Downsample period {}", downsample);
                    long startOfFirstPeriod = requestedStartTs - (requestedStartTs % downsample);
                    long endDistanceFromDownSample = endTs % downsample;
                    long endOfLastPeriod = (endDistanceFromDownSample > 0
                            ? endTs + downsample - endDistanceFromDownSample
                            : endTs);

                    LOG.debug("startOfFirstPeriod:{} endOfLastPeriod:{}", startOfFirstPeriod, endOfLastPeriod);

                    if (endOfLastPeriod > startOfFirstPeriod) {
                        BatchScanner scanner = null;
                        try {
                            Collection<Authorizations> authorizations = getSessionAuthorizations(msg);
                            scanner = ScannerHelper.createBatchScanner(connector, metricsTable, authorizations,
                                    scannerThreads);
                            List<String> tagOrder = prioritizeTags(query.getMetric(), query.getTags());
                            Map<String, String> orderedTags = orderTags(tagOrder, query.getTags());
                            Set<Tag> colFamValues = getColumnFamilies(metric, orderedTags);
                            List<Range> ranges = getQueryRanges(metric, startOfFirstPeriod, endOfLastPeriod,
                                    colFamValues);
                            scanner.setRanges(ranges);
                            setQueryColumns(scanner, metric, orderedTags, colFamValues);

                            if (query.isRate()) {
                                LOG.trace("Adding rate iterator");
                                IteratorSetting rate = new IteratorSetting(499, RateIterator.class);
                                RateIterator.setRateOptions(rate, query.getRateOptions());
                                scanner.addScanIterator(rate);
                            }

                            Class<? extends Aggregator> daggClass = DownsampleIterator.getDownsampleAggregator(query);
                            if (daggClass == null) {
                                // we should always have a downsample iterator
                                // in the stack.
                                throw new TimelyException(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                                        "Error during query: programming error", "daggClass == null");
                            } else {
                                LOG.trace("Downsample Aggregator type {}", daggClass.getSimpleName());
                                IteratorSetting is = new IteratorSetting(500, DownsampleIterator.class);
                                DownsampleIterator.setDownsampleOptions(is, startOfFirstPeriod, endOfLastPeriod,
                                        downsample, maxDownsampleMemory, daggClass.getName());
                                scanner.addScanIterator(is);
                            }

                            Class<? extends Aggregator> aggClass = getAggregator(query);
                            // the aggregation iterator is optional
                            if (aggClass != null) {
                                LOG.trace("Aggregator type {}", aggClass.getSimpleName());
                                IteratorSetting is = new IteratorSetting(501, AggregationIterator.class);
                                AggregationIterator.setAggregationOptions(is, query.getTags(), aggClass.getName());
                                scanner.addScanIterator(is);
                            }

                            // tag -> array of results by period starting at
                            // start
                            for (Entry<Key, Value> encoded : scanner) {
                                // we can decode the value as a Map<Set<Tag>,
                                // Aggregation> even if an AggregationIterator
                                // is not used because Downsample is a subclass
                                // of Aggregation
                                Map<Set<Tag>, Aggregation> samples = AggregationIterator
                                        .decodeValue(encoded.getValue());
                                for (Entry<Set<Tag>, Aggregation> entry : samples.entrySet()) {
                                    Set<Tag> key = new HashSet<>();
                                    for (Tag tag : entry.getKey()) {
                                        if (query.getTags().keySet().contains(tag.getKey())) {
                                            key.add(tag);
                                        }
                                    }
                                    List<Aggregation> aggregations = allSeries.getOrDefault(key, new ArrayList<>());
                                    aggregations.add(entry.getValue());
                                    allSeries.put(key, aggregations);
                                }
                            }
                            LOG.trace("allSeries: {}", allSeries);
                        } finally {
                            if (scanner != null) {
                                scanner.close();
                            }
                        }
                    }
                }

                // TODO groupby here?
                long tsDivisor = msg.isMsResolution() ? 1 : 1000;
                for (Entry<Set<Tag>, List<Aggregation>> entry : allSeries.entrySet()) {
                    numResults += entry.getValue().size();
                    result.add(convertToQueryResponse(query, entry.getKey(), entry.getValue(), tsDivisor));
                }
            }
            LOG.debug("Query for [{}] time:{} duration:{} metrics:{} results:{}", msg.getUserName(),
                    (System.currentTimeMillis() - now), ((requestedEndTs - requestedStartTs) / (1000 * 60)),
                    metricList.toString(), numResults);
            internalMetrics.addQueryResponse(result.size(), (System.currentTimeMillis() - now));
            return result;
        } catch (ClassNotFoundException | IOException | TableNotFoundException ex) {
            LOG.error("Error during query: " + ex.getMessage(), ex);
            throw new TimelyException(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                    "Error during query: " + ex.getMessage(), ex.getMessage(), ex);
        }
    }

    private Map<String, String> orderTags(List<String> tagOrder, Map<String, String> tags) {
        Map<String, String> order = new LinkedHashMap<>(tags.size());
        tagOrder.forEach(t -> order.put(t, tags.get(t)));
        if (tagOrder.size() > tags.size()) {
            tags.entrySet().forEach(k -> {
                if (!tagOrder.contains(k.getKey())) {
                    order.put(k.getKey(), k.getValue());
                }
            });
        }
        return order;
    }

    /**
     *
     * @return ordered list of most specific to least specific tags in the query
     */
    private List<String> prioritizeTags(String metric, Map<String, String> tags) {
        // trivial cases
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        if (tags.size() == 1) {
            return Collections.singletonList(tags.keySet().iterator().next());
        }
        // favor tags with fewer values
        Map<String, Integer> priority = new HashMap<>();
        // Count matching tags
        updateMetricCounts();
        for (Entry<String, String> entry : tags.entrySet()) {
            String tagk = entry.getKey();
            String tagv = entry.getValue();
            if (!isTagValueRegex(tagv)) {
                MetricTagK start = new MetricTagK(metric, tagk);
                int count = 0;
                for (Entry<MetricTagK, Integer> metricCount : metaCounts.get().tailMap(start).entrySet()) {
                    Pair<String, String> metricTagk = metricCount.getKey();
                    if (!metricTagk.getFirst().equals(metric) || !metricTagk.getSecond().startsWith(tagk)) {
                        break;
                    } else {
                        count += metricCount.getValue();
                    }
                }
                priority.put(tagk, count);
            } else {
                priority.put(tagk, Integer.MAX_VALUE);
            }
        }
        List<String> result = new ArrayList<>(tags.keySet());
        Collections.sort(result, new Comparator<String>() {

            @Override
            public int compare(String o1, String o2) {
                // greater count lowers priority
                return priority.get(o1).intValue() - priority.get(o2).intValue();
            }
        });
        LOG.trace("Tag priority {}", result);
        return result;
    }

    private QueryResponse convertToQueryResponse(SubQuery query, Set<Tag> tags, Collection<Aggregation> values,
            long tsDivisor) {
        QueryResponse response = new QueryResponse();
        response.setMetric(query.getMetric());
        for (Tag tag : tags) {
            response.putTag(tag.getKey(), tag.getValue());
        }
        RateOption rateOptions = query.getRateOptions();
        Aggregation combined = Aggregation.combineAggregation(values, rateOptions);
        for (Sample entry : combined) {
            long ts = entry.timestamp / tsDivisor;
            response.putDps(Long.toString(ts), entry.value);
        }
        LOG.trace("Created query response {}", response);
        return response;
    }

    private boolean isTagValueRegex(String value) {
        return !REGEX_TEST.matcher(value).matches();
    }

    @Override
    public Set<Tag> getColumnFamilies(String metric, Map<String, String> requestedTags) throws TableNotFoundException {

        Scanner meta = null;
        try {
            Map<String, String> tags = (requestedTags == null) ? new LinkedHashMap<>() : requestedTags;
            LOG.trace("Looking for requested tags: {}", tags);
            meta = connector.createScanner(metaTable, Authorizations.EMPTY);
            Text start = new Text(Meta.VALUE_PREFIX + metric);
            Text end = new Text(Meta.VALUE_PREFIX + metric + "\\x0000");
            end.append(new byte[] { (byte) 0xff }, 0, 1);
            meta.setRange(new Range(start, end));
            // Only look for the meta entries that match our tags, if any
            boolean onlyFirstRow = false;
            Entry<String, String> first = null;
            // Set the columns on the meta scanner based on the first tag
            // in the set of tags passed in the query. If no tags are present
            // then we are only going to return the first tag name present in the
            // meta table.
            Iterator<Entry<String, String>> tagIter = tags.entrySet().iterator();
            if (tagIter.hasNext()) {
                first = tagIter.next();
                if (isTagValueRegex(first.getValue())) {
                    meta.fetchColumnFamily(new Text(first.getKey()));
                } else {
                    meta.fetchColumn(new Text(first.getKey()), new Text(first.getValue()));
                }
            } else {
                // grab all of the values found for the first tag for the metric
                onlyFirstRow = true;
            }
            final boolean ONLY_RETURN_FIRST_TAG = onlyFirstRow;
            Iterator<Entry<Key, Value>> iter = meta.iterator();
            Iterator<Pair<String, String>> knownKeyValues = new Iterator<Pair<String, String>>() {

                Text firstTag = null;
                Text tagName = null;
                Text tagValue = null;

                @Override
                public boolean hasNext() {
                    if (iter.hasNext()) {
                        Entry<Key, Value> metaEntry = iter.next();
                        if (null == firstTag) {
                            firstTag = metaEntry.getKey().getColumnFamily();
                        }
                        tagName = metaEntry.getKey().getColumnFamily();
                        tagValue = metaEntry.getKey().getColumnQualifier();
                        LOG.trace("Found tag entry {}={}", tagName, tagValue);

                        if (ONLY_RETURN_FIRST_TAG && !tagName.equals(firstTag)) {
                            return false;
                        }
                        return true;
                    }
                    return false;
                }

                @Override
                public Pair<String, String> next() {
                    LOG.trace("Returning tag {}={}", tagName, tagValue);
                    return new Pair<>(tagName.toString(), tagValue.toString());
                }
            };
            // Expand the list of tags in the meta table for this metric that
            // matches
            // the pattern of the first tag in the query. The resulting set of tags
            // will be used to fetch specific columns from the metric table.
            return expandTagValues(first, knownKeyValues);
        } finally {
            if (meta != null) {
                meta.close();
            }
        }
    }

    private void setQueryColumns(ScannerBase scanner, String metric, Map<String, String> tags, Set<Tag> colFamValues)
            throws TimelyException {

        if (colFamValues.size() == 0) {
            throw new TimelyException(HttpResponseStatus.BAD_REQUEST.code(), "No matching tags",
                    "No tags were found " + " that matched the submitted tags. Please fix and retry");
        }
        LOG.trace("Found matching tags: {}", colFamValues);
        for (Tag tag : colFamValues) {
            Text colf = new Text(tag.getKey() + "=" + tag.getValue());
            scanner.fetchColumnFamily(colf);
            LOG.trace("Fetching metric table column family: {}", colf);
        }
        // Add the regular expression to filter the other tags
        int priority = 100;
        Iterator<Entry<String, String>> tagIter = tags.entrySet().iterator();
        // skip over first tag which was already expanded into colFamValues
        if (tagIter.hasNext()) {
            tagIter.next();
        }
        Entry<String, String> tag = null;
        while (tagIter.hasNext()) {
            tag = tagIter.next();
            LOG.trace("Adding regex filter for tag {}", tag);
            StringBuffer pattern = new StringBuffer();
            pattern.append("(^.*\\x00|.*,)");
            pattern.append(tag.getKey());
            pattern.append("=");
            pattern.append(tag.getValue());
            pattern.append("(,.*|$)");

            IteratorSetting setting = new IteratorSetting(priority++, tag.getKey() + " tag filter", RegExFilter.class);
            LOG.trace("Using {} additional filter on tag: {}", pattern, tag.getKey());
            RegExFilter.setRegexs(setting, null, null, pattern.toString(), null, false, true);
            scanner.addScanIterator(setting);
        }
    }

    private Set<Tag> expandTagValues(Entry<String, String> firstTag, Iterator<Pair<String, String>> knownKeyValues) {
        Set<Tag> result = new HashSet<>();
        Matcher matcher = null;
        if (null != firstTag && isTagValueRegex(firstTag.getValue())) {
            matcher = Pattern.compile(firstTag.getValue()).matcher("");
        }
        while (knownKeyValues.hasNext()) {
            Pair<String, String> knownKeyValue = knownKeyValues.next();
            if (firstTag == null) {
                LOG.trace("Adding tag {}={}", knownKeyValue.getFirst(), knownKeyValue.getSecond());
                result.add(new Tag(knownKeyValue.getFirst(), knownKeyValue.getSecond()));
            } else {
                LOG.trace("Testing requested tag {}={}", firstTag.getKey(), firstTag.getValue());
                if (firstTag.getKey().equals(knownKeyValue.getFirst())) {
                    if (null != matcher) {
                        matcher.reset(knownKeyValue.getSecond());
                        if (matcher.matches()) {
                            LOG.trace("Adding tag {}={}", knownKeyValue.getFirst(), knownKeyValue.getSecond());
                            result.add(new Tag(knownKeyValue.getFirst(), knownKeyValue.getSecond()));
                        }
                    } else {
                        LOG.trace("Adding tag {}={}", knownKeyValue.getFirst(), knownKeyValue.getSecond());
                        result.add(new Tag(knownKeyValue.getFirst(), knownKeyValue.getSecond()));
                    }
                }
            }
        }
        return result;
    }

    @Override
    public List<Range> getQueryRanges(String metric, long start, long end, Set<Tag> colFamValues) {
        List<Range> ranges = new ArrayList<>();
        long beginRangeRounded = MetricAdapter.roundTimestampToLastHour(start);
        if (colFamValues.isEmpty()) {
            final byte[] start_row = MetricAdapter.encodeRowKey(metric, beginRangeRounded);
            Key startKey = new Key(new Text(start_row));
            LOG.trace("Start key for metric {} and time {} is {}", metric, beginRangeRounded,
                    startKey.toStringNoTime());
            final byte[] end_row = MetricAdapter.encodeRowKey(metric, beginRangeRounded);
            Key endKey = new Key(new Text(end_row));
            LOG.trace("End key for metric {} and time {} is {}", metric, MetricAdapter.roundTimestampToNextHour(end),
                    endKey.toStringNoTime());
            Range range = new Range(startKey, true, endKey, false);
            LOG.trace("Set query range to {}", range);
            ranges.add(range);
        } else {
            try {
                long lastBeginRangeRounded = MetricAdapter.roundTimestampToLastHour(end);
                while (beginRangeRounded <= lastBeginRangeRounded) {
                    // use end timestamp of begin + 1 hour and one msec
                    // except the last range where we use end + 1 msec
                    long endRangeTimestamp = (beginRangeRounded == lastBeginRangeRounded) ? end + 1
                            : beginRangeRounded + (1000 * 60 * 60) + 1;
                    long beginRangeTimestamp = (beginRangeRounded == MetricAdapter.roundTimestampToLastHour(start))
                            ? start
                            : beginRangeRounded;
                    for (Tag t : colFamValues) {
                        final byte[] start_row = MetricAdapter.encodeRowKey(metric, beginRangeRounded);
                        Key startKey = new Key(new Text(start_row),
                                new Text(t.join().getBytes(Charset.forName("UTF-8"))),
                                new Text(MetricAdapter.encodeColQual(beginRangeTimestamp, "")), new Text(new byte[0]),
                                beginRangeTimestamp);
                        LOG.trace("Start key for metric {} and time {} is {}", metric, beginRangeTimestamp,
                                startKey.toStringNoTime());
                        final byte[] end_row = MetricAdapter.encodeRowKey(metric, beginRangeRounded);
                        Key endKey = new Key(new Text(end_row), new Text(t.join().getBytes(Charset.forName("UTF-8"))),
                                new Text(MetricAdapter.encodeColQual(endRangeTimestamp, "")), new Text(new byte[0]),
                                endRangeTimestamp);
                        LOG.trace("End key for metric {} and time {} is {}", metric, beginRangeTimestamp,
                                endKey.toStringNoTime());
                        Range range = new Range(startKey, true, endKey, false);
                        LOG.trace("Set query range to {}", range);
                        ranges.add(range);
                    }
                    beginRangeRounded += (1000 * 60 * 60); // add an hour
                }
            } catch (Exception e) {
                LOG.error("Error creating query ranges", e);
            }
        }

        return ranges;
    }

    private Class<? extends Aggregator> getAggregator(SubQuery query) {
        return Aggregator.getAggregator(query.getAggregator());
    }

    private Collection<Authorizations> getSessionAuthorizations(AuthenticatedRequest request) {
        return AuthCache.getAuthorizations(request, security);
    }

    @Override
    public long getAgeOffForMetric(String metricName) {
        String age = this.ageOff.get(MetricAgeOffIterator.AGE_OFF_PREFIX + metricName);
        if (null == age) {
            return this.defaultAgeOffMilliSec;
        } else {
            return Long.parseLong(age);
        }
    }

    @Override
    public Scanner createScannerForMetric(AuthenticatedRequest request, String metric, Map<String, String> tags,
            int scannerBatchSize, int scannerReadAhead) throws TimelyException {
        try {
            if (null == metric) {
                throw new IllegalArgumentException("metric name must be specified");
            }
            Collection<Authorizations> auths = getSessionAuthorizations(request);
            LOG.debug("Creating metric scanner for [{}] with auths: {}", request.getUserName(), auths);
            Scanner s = ScannerHelper.createScanner(connector, this.metricsTable, auths);
            if (tags == null) {
                tags = new LinkedHashMap<>();
            }
            List<String> tagOrder = prioritizeTags(metric, tags);
            Map<String, String> orderedTags = orderTags(tagOrder, tags);
            Set<Tag> colFamValues = getColumnFamilies(metric, orderedTags);
            setQueryColumns(s, metric, orderedTags, colFamValues);
            s.setBatchSize(scannerBatchSize);
            s.setReadaheadThreshold(scannerReadAhead);
            return s;
        } catch (IllegalArgumentException | TableNotFoundException ex) {
            LOG.error("Error during lookup: " + ex.getMessage(), ex);
            throw new TimelyException(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(),
                    "Error during lookup: " + ex.getMessage(), ex.getMessage(), ex);
        }
    }

}
