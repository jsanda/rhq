package org.rhq.cassandra.schema;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.datastax.driver.core.Host;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.querybuilder.Batch;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.FutureFallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.RateLimiter;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricsRegistry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.Seconds;

import org.rhq.core.util.exception.ThrowableUtil;

/**
 * <p>
 * Migrates aggregate metrics from the one_hour_metrics, six_hour_metrics, and twenty_four_hour_metrics tables to the
 * new aggregate_metrics table. The failure to migrate data for a single measurement schedule will result in an
 * exception being thrown that causes the upgrade to fail; however, all schedules will be processed even if there are
 * failures. An exception is thrown only after going through data for all schedules.
 * </p>
 * <p>
 * When data for a measurement schedule is successfully migrated, the schedule id is recorded in a log. There are
 * separate log files for each of the 1 hour, 6 hour, and 24 hour tables. They are stored in the server data directory.
 * Each table log is read prior to starting the migration to determine what schedule ids have data to be migrated.
 * </p>
 * <p>
 * After all data has been successfully migrated, the one_hour_metrics, six_hour_metrics, and twenty_four_hour_metrics
 * tables are dropped.
 * </p>
 *
 *
 * @author John Sanda
 */
public class MigrateAggregateMetrics implements Step {

    private static final Log log = LogFactory.getLog(MigrateAggregateMetrics.class);

    public static enum Bucket {

        ONE_HOUR("one_hour"),

        SIX_HOUR("six_hour"),

        TWENTY_FOUR_HOUR("twenty_four_hour");

        private String tableName;

        private Bucket(String tableName) {
            this.tableName = tableName;
        }

        @Override
        public String toString() {
            return tableName;
        }
    }

    public static final int DEFAULT_WARM_UP = 20;

    private static final double RATE_INCREASE_PER_NODE = 0.3;

    private Session session;

    private String dataDir;

    private DBConnectionFactory dbConnectionFactory;

    private AtomicReference<RateLimiter> readPermitsRef = new AtomicReference<RateLimiter>();

    private AtomicReference<RateLimiter> writePermitsRef = new AtomicReference<RateLimiter>();

    private AtomicInteger remaining1HourMetrics = new AtomicInteger();

    private AtomicInteger remaining6HourMetrics = new AtomicInteger();

    private AtomicInteger remaining24HourMetrics = new AtomicInteger();

    private AtomicInteger migrated1HourMetrics = new AtomicInteger();

    private AtomicInteger migrated6HourMetrics = new AtomicInteger();

    private AtomicInteger migrated24HourMetrics = new AtomicInteger();

    private ListeningExecutorService threadPool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(6,
        new SchemaUpdateThreadFactory()));

    private MetricsRegistry metricsRegistry;

    private Meter migrationsMeter;

    private CountDownLatch migrations;

    private RateMonitor rateMonitor;

    private KeyScanner keyScanner;

    private MigrationProgressLogger progressLogger;

    private AtomicInteger readErrors = new AtomicInteger();

    private AtomicInteger writeErrors = new AtomicInteger();

    private FutureFallback<ResultSet> countReadErrors = new FutureFallback<ResultSet>() {
        @Override
        public ListenableFuture<ResultSet> create(Throwable t) throws Exception {
            readErrors.incrementAndGet();
            return Futures.immediateFailedFuture(t);
        }
    };

    private FutureFallback<List<ResultSet>> countWriteErrors = new FutureFallback<List<ResultSet>>() {
        @Override
        public ListenableFuture<List<ResultSet>> create(Throwable t) throws Exception {
            writeErrors.incrementAndGet();
            return Futures.immediateFailedFuture(t);
        }
    };

    @Override
    public void setSession(Session session) {
        this.session = session;
    }

    @Override
    public void bind(Properties properties) {
        dbConnectionFactory = (DBConnectionFactory) properties.get(SchemaManager.RELATIONAL_DB_CONNECTION_FACTORY_PROP);
        dataDir = properties.getProperty("data.dir", System.getProperty("jboss.server.data.dir"));
    }

    @Override
    public void execute() {
        log.info("Starting data migration");
        metricsRegistry = new MetricsRegistry();
        migrationsMeter = metricsRegistry.newMeter(MigrateAggregateMetrics.class, "migrations", "migrations",
            TimeUnit.MINUTES);

        Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            // dbConnectionFactory can be null in test environments which is fine because we start tests with a brand
            // new schema and cluster. In this case, we do not need to do anything since it is not an upgrade scenario.
            if (dbConnectionFactory == null) {
                log.info("The relational database connection factory is not set. No data migration necessary");
                return;
            }

            progressLogger = new MigrationProgressLogger();
            rateMonitor = new RateMonitor(readPermitsRef, writePermitsRef);
            if (System.getProperty("rate.monitor.2") != null) {
                rateMonitor = new RateMonitor2(readPermitsRef, writePermitsRef);
            }

            keyScanner = new KeyScanner(session);
            Set<Integer> scheduleIdsWith1HourData = keyScanner.scanFor1HourKeys();
            Set<Integer> scheduleIdsWith6HourData = keyScanner.scanFor6HourKeys();
            Set<Integer> scheduleIdsWith24HourData = keyScanner.scanFor24HourKeys();
            keyScanner.shutdown();

            log.info("There are " + scheduleIdsWith1HourData.size() + " schedule ids with " +
                Bucket.ONE_HOUR + " data");
            log.info("There are " + scheduleIdsWith6HourData.size() + " schedule ids with " +
                Bucket.SIX_HOUR + " data");
            log.info("There are " + scheduleIdsWith24HourData.size() + " schedule ids with " +
                Bucket.TWENTY_FOUR_HOUR + " data");

            writePermitsRef.set(RateLimiter.create(getWriteLimit(getNumberOfUpNodes()), DEFAULT_WARM_UP,
                TimeUnit.SECONDS));
            readPermitsRef.set(RateLimiter.create(getReadLimit(getNumberOfUpNodes()), DEFAULT_WARM_UP,
                TimeUnit.SECONDS));

            log.info("The request limits are " + writePermitsRef.get().getRate() + " writes/sec and " +
                readPermitsRef.get().getRate() + " reads/sec");

            remaining1HourMetrics.set(scheduleIdsWith1HourData.size());
            remaining6HourMetrics.set(scheduleIdsWith6HourData.size());
            remaining24HourMetrics.set(scheduleIdsWith24HourData.size());

            migrations = new CountDownLatch(scheduleIdsWith1HourData.size() + scheduleIdsWith6HourData.size() +
                scheduleIdsWith24HourData.size());

            threadPool.submit(progressLogger);
            threadPool.submit(rateMonitor);

            migrate1HourData(scheduleIdsWith1HourData);
            migrate6HourData(scheduleIdsWith6HourData);
            migrate24HourData(scheduleIdsWith24HourData);

//            migrations.await();

            if (remaining1HourMetrics.get() > 0 || remaining6HourMetrics.get() > 0 ||
                remaining24HourMetrics.get() > 0) {
                throw new RuntimeException("There are unfinished metrics migrations - {one_hour: " +
                    remaining1HourMetrics + ", six_hour: " + remaining6HourMetrics + ", twenty_four_hour: " +
                    remaining24HourMetrics + "}. The upgrade will have to be " + "run again.");
            }

            dropTables();
        } catch (IOException e) {
            throw new RuntimeException("There was an unexpected I/O error. The are still " +
                migrations.getCount() + " outstanding migration tasks. The upgrade must be run again to " +
                "complete the migration.", e);
        } catch (AbortedException e) {
            throw new RuntimeException("The migration was aborted. There are are still " +
                migrations.getCount() +" outstanding migration tasks. The upgrade must be run again to " +
                "complete the migration.", e);
        } catch (InterruptedException e) {
            throw new RuntimeException("The migration was interrupted. There are are still " +
                migrations.getCount() +" outstanding migration tasks. The upgrade must be run again to " +
                "complete the migration.", e);
        } finally {
            stopwatch.stop();
            log.info("Finished migrating " + migrated1HourMetrics + " " + Bucket.ONE_HOUR + ", " +
                migrated6HourMetrics + " " + Bucket.SIX_HOUR + ", and " + migrated24HourMetrics + " " +
                Bucket.TWENTY_FOUR_HOUR + " metrics in " + stopwatch.elapsed(TimeUnit.SECONDS) + " sec");
            shutdown();
        }
    }

    private double getWriteLimit(int numNodes) {
        int baseLimit = Integer.parseInt(System.getProperty("rhq.storage.request.write-limit", "10000"));
        double increase = baseLimit * RATE_INCREASE_PER_NODE;
        return baseLimit + (increase * (numNodes - 1));
    }

    private double getReadLimit(int numNodes) {
        int baseLimit = Integer.parseInt(System.getProperty("rhq.storage.request.read-limit", "25"));
        double increase = baseLimit * RATE_INCREASE_PER_NODE;
        return baseLimit + (increase * (numNodes - 1));
    }

    private int getNumberOfUpNodes() {
        int count = 0;
        for (Host host : session.getCluster().getMetadata().getAllHosts()) {
            if (host.isUp()) {
                ++count;
            }
        }
        return count;
    }

    private void shutdown() {
        try {
            log.info("Shutting down migration thread pools...");
            rateMonitor.shutdown();
            progressLogger.finished();
            keyScanner.shutdown();
            threadPool.shutdown();
            threadPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
    }

    private void migrate1HourData(Set<Integer> scheduleIds) throws IOException, InterruptedException {
        DateTime endTime = DateUtils.get1HourTimeSlice(DateTime.now());
        DateTime startTime = endTime.minus(Days.days(14));
        PreparedStatement query = session.prepare(
            "SELECT time, type, value FROM rhq.one_hour_metrics " +
            "WHERE schedule_id = ? AND time >= " + startTime.getMillis() + " AND time <= " + endTime.getMillis());
//        migrateData(scheduleIds, query, delete, Bucket.ONE_HOUR, remaining1HourMetrics, migrated1HourMetrics,
//            Days.days(14));
        migrate(scheduleIds, query, Bucket.ONE_HOUR, remaining1HourMetrics, Days.days(14));
    }

    private void migrate6HourData(Set<Integer> scheduleIds) throws IOException, InterruptedException {
        DateTime endTime = DateUtils.get1HourTimeSlice(DateTime.now());
        DateTime startTime = endTime.minus(Days.days(31));
        PreparedStatement query = session.prepare(
            "SELECT time, type, value FROM rhq.six_hour_metrics " +
            "WHERE schedule_id = ? AND time >= " + startTime.getMillis() + " AND time <= " + endTime.getMillis());
//        migrateData(scheduleIds, query, delete, Bucket.SIX_HOUR, remaining6HourMetrics, migrated6HourMetrics,
//            Days.days(31));
        migrate(scheduleIds, query, Bucket.SIX_HOUR, remaining6HourMetrics, Days.days(31));
    }

    private void migrate24HourData(Set<Integer> scheduleIds) throws IOException, InterruptedException {
        DateTime endTime = DateUtils.get1HourTimeSlice(DateTime.now());
        DateTime startTime = endTime.minus(Days.days(365));
        PreparedStatement query = session.prepare(
            "SELECT time, type, value FROM rhq.twenty_four_hour_metrics " +
            "WHERE schedule_id = ? AND time >= " + startTime.getMillis() + " AND time <= " + endTime.getMillis());
//        migrateData(scheduleIds, query, delete, Bucket.TWENTY_FOUR_HOUR, remaining24HourMetrics, migrated24HourMetrics,
//            Days.days(365));
        migrate(scheduleIds, query, Bucket.TWENTY_FOUR_HOUR, remaining24HourMetrics, Days.days(365));
    }

    private void migrate(Set<Integer> scheduleIds, PreparedStatement query, Bucket bucket,
        AtomicInteger remainingMetrics, Days ttl) throws IOException, InterruptedException {

        Stopwatch stopwatch = Stopwatch.createStarted();
        MigrationLog migrationLog = new MigrationLog(new File(dataDir, bucket + "_migration.log"));
        Set<Integer> migratedScheduleIds = migrationLog.read();
        Queue<Integer> remainingScheduleIds = new ArrayDeque<Integer>(scheduleIds);

        while (!remainingScheduleIds.isEmpty()) {
            List<Integer> batch = getNextBatch(remainingScheduleIds, migratedScheduleIds);
            ReadResults readResults = readData(batch, query, bucket);
            for (Integer scheduleId : readResults.failedReads) {
                remainingScheduleIds.offer(scheduleId);
            }
            Map<Integer, ResultSet> failedWrites = writeData(readResults.resultSets, bucket, ttl, remainingMetrics);
            while (!failedWrites.isEmpty()) {
                failedWrites = writeData(failedWrites, bucket, ttl, remainingMetrics);
            }
        }

        stopwatch.stop();
        log.info("Finished migrating " + bucket + " data in " + stopwatch.elapsed(TimeUnit.SECONDS) + " sec");
    }

    private List<Integer> getNextBatch(Queue<Integer> scheduleIds, Set<Integer> migratedScheduleIds) {
        List<Integer> batch = new ArrayList<Integer>(500);
        while (!scheduleIds.isEmpty() && batch.size() < 500) {
            Integer scheduleId = scheduleIds.poll();
            if (!migratedScheduleIds.contains(scheduleId)) {
                batch.add(scheduleId);
            }
        }
        return batch;
    }

    private static class ReadResults {
        Set<Integer> failedReads = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

        Map<Integer, ResultSet> resultSets = new ConcurrentHashMap<Integer, ResultSet>();
    }

    private ReadResults readData(List<Integer> scheduleIds, PreparedStatement query, final Bucket bucket)
        throws InterruptedException {

        final ReadResults results = new ReadResults();
        final CountDownLatch queries = new CountDownLatch(scheduleIds.size());

        for (final Integer scheduleId : scheduleIds) {
            readPermitsRef.get().acquire();
            ResultSetFuture future = session.executeAsync(query.bind(scheduleId));
            Futures.addCallback(future, new FutureCallback<ResultSet>() {
                @Override
                public void onSuccess(ResultSet resultSet) {
                    try {
                        results.resultSets.put(scheduleId, resultSet);
                        rateMonitor.requestSucceeded();
                    } finally {
                        queries.countDown();
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("Failed to read " + bucket + " data for schedule id " + scheduleId, t);
                        } else {
                            log.info("Failed to read " + bucket + " data for schedule id " + scheduleId + ": " +
                                ThrowableUtil.getRootMessage(t));
                        }
                        results.failedReads.add(scheduleId);
                        rateMonitor.requestFailed();
                        readErrors.incrementAndGet();
                    } finally {
                        queries.countDown();
                    }
                }
            });
        }
        queries.await();
        return results;
    }

    private Map<Integer, ResultSet> writeData(Map<Integer, ResultSet> resultSets, final Bucket bucket, final Days ttl,
        final AtomicInteger remainingMetrics) throws InterruptedException{

        final CountDownLatch writes = new CountDownLatch(resultSets.size());
        final Map<Integer, ResultSet> failedWrites = new ConcurrentHashMap<Integer, ResultSet>();

        for (final Map.Entry<Integer, ResultSet> entry : resultSets.entrySet()) {
            threadPool.submit(new Runnable() {
                @Override
                public void run() {
                    ListenableFuture<List<ResultSet>> insertsFuture = writeData(entry.getKey(), bucket,
                        entry.getValue(), ttl.toStandardSeconds());
                    Futures.addCallback(insertsFuture, new FutureCallback<List<ResultSet>>() {
                        @Override
                        public void onSuccess(List<ResultSet> results) {
                            try {
                                if (log.isDebugEnabled()) {
                                    log.debug("Finished " + bucket + " data migration for schedule id " +
                                        entry.getKey());
                                }
                                rateMonitor.requestSucceeded();
                                remainingMetrics.decrementAndGet();
                            } finally {
                                writes.countDown();
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            try {
                                if (log.isDebugEnabled()) {
                                    log.debug("Failed to write " + bucket + " data for schedule id " + entry.getKey(),
                                        t);
                                } else {
                                    log.info("Failed to write " + bucket + " data for schedule id " + entry.getKey() +
                                        ": " + ThrowableUtil.getRootMessage(t));
                                }
                                rateMonitor.requestFailed();
                                failedWrites.put(entry.getKey(), entry.getValue());
                            } finally {
                                writes.countDown();
                            }
                        }
                    });
                }
            });
        }
        writes.await();
        return failedWrites;
    }

    private ListenableFuture<List<ResultSet>> writeData(Integer scheduleId, Bucket bucket, ResultSet resultSet,
        Seconds ttl) {
        try {
            List<Row> rows = resultSet.all();
            List<ResultSetFuture> insertFutures = new ArrayList<ResultSetFuture>();
            Date time = rows.get(0).getDate(0);
            Date nextTime;
            Double max = null;
            Double min = null;
            Double avg = null;
            List<Statement> statements = new ArrayList<Statement>(45);

            for (Row row : rows) {
                nextTime = row.getDate(0);
                if (nextTime.equals(time)) {
                    int type = row.getInt(1);
                    switch (type) {
                    case 0:
                        max = row.getDouble(2);
                        break;
                    case 1:
                        min = row.getDouble(2);
                        break;
                    default:
                        avg = row.getDouble(2);
                    }
                } else {
                    Seconds elapsedSeconds = Seconds.secondsBetween(new DateTime(time), DateTime.now());
                    if (elapsedSeconds.isLessThan(ttl)) {
                        if (isDataMissing(avg, max, min)) {
                            if (log.isDebugEnabled()) {
                                log.debug("We only have a partial " + bucket + " metric for {scheduleId: " +
                                    scheduleId + ", time: " + time.getTime() + "}. It will not be migrated.");
                            }
                        } else {
                            int newTTL = ttl.getSeconds() - elapsedSeconds.getSeconds();
                            statements.add(createInsertStatement(scheduleId, bucket, time, avg, max, min, newTTL));
                            if (statements.size() == 45) {
                                insertFutures.add(writeBatch(statements));
                                statements.clear();
                            }
                        }
                    }
                    time = nextTime;
                    max = row.getDouble(2);
                    min = null;
                    avg = null;
                }
            }
            if (!statements.isEmpty()) {
                insertFutures.add(writeBatch(statements));
            }
            return Futures.allAsList(insertFutures);
        } catch (Exception e) {
            return Futures.immediateFailedFuture(new Exception("There was an error writing " + bucket +
                " data for schedule id " + scheduleId, e));
        }
    }

    private boolean isDataMissing(Double avg, Double max, Double min) {
        if (avg == null || Double.isNaN(avg)) return true;
        if (max == null || Double.isNaN(max)) return true;
        if (min == null || Double.isNaN(min)) return true;

        return false;
    }

    private ResultSetFuture writeBatch(List<Statement> statements) {
        Batch batch = QueryBuilder.batch(statements.toArray(new Statement[statements.size()]));
        writePermitsRef.get().acquire();
        return session.executeAsync(batch);
    }

    private SimpleStatement createInsertStatement(Integer scheduleId, Bucket bucket, Date time, Double avg, Double max,
        Double min, int newTTL) {
        return new SimpleStatement("INSERT INTO rhq.aggregate_metrics(schedule_id, bucket, time, avg, max, min) " +
            "VALUES (" + scheduleId + ", '" + bucket + "', " + time.getTime() + ", " + avg + ", " + max + ", " + min +
            ") USING TTL " + newTTL);
    }

    private void migrateData(Set<Integer> scheduleIds, PreparedStatement query, final PreparedStatement delete, 
        Bucket bucket, final AtomicInteger remainingMetrics, final AtomicInteger migratedMetrics, Days ttl)
        throws IOException {

        Stopwatch stopwatch = Stopwatch.createStarted();
        log.info("Scheduling data migration tasks for " + bucket + " data");
        final MigrationLog migrationLog = new MigrationLog(new File(dataDir, bucket + "_migration.log"));
        final Set<Integer> migratedScheduleIds = migrationLog.read();
        for (final Integer scheduleId : scheduleIds) {
            if (migratedScheduleIds.contains(scheduleId)) {
                migrations.countDown();
                remainingMetrics.decrementAndGet();
            } else {
                migrateData(query, delete, bucket, remainingMetrics, migratedMetrics, migrationLog, scheduleId, ttl);
            }
        }

        stopwatch.stop();
        log.info("Finished scheduling migration tasks for " + bucket + " data in " +
            stopwatch.elapsed(TimeUnit.SECONDS) + " sec");
    }

    private void migrateData(PreparedStatement query, final PreparedStatement delete, final Bucket bucket,
        AtomicInteger remainingMetrics, AtomicInteger migratedMetrics, MigrationLog migrationLog,
        final Integer scheduleId, Days ttl) {
        readPermitsRef.get().acquire();
        try {
            ListenableFuture<ResultSet> queryFuture = session.executeAsync(query.bind(scheduleId));
            queryFuture = Futures.withFallback(queryFuture, countReadErrors);
            ListenableFuture<List<ResultSet>> migrationFuture = Futures.transform(queryFuture,
                new MigrateData(scheduleId, bucket, writePermitsRef.get(), session, ttl.toStandardSeconds()), threadPool);
            migrationFuture = Futures.withFallback(migrationFuture, countWriteErrors);

//            ListenableFuture<ResultSet> deleteFuture = Futures.transform(migrationFuture,
//                new AsyncFunction<List<ResultSet>, ResultSet>() {
//                    @Override
//                    public ListenableFuture<ResultSet> apply(List<ResultSet> resultSets) throws Exception {
//                        writePermitsRef.get().acquire();
//                        return session.executeAsync(delete.bind(scheduleId));
//                    }
//                }, threadPool);
//            Futures.addCallback(deleteFuture, migrationFinished(query, delete, scheduleId, bucket, migrationLog,
//                remainingMetrics, migratedMetrics, ttl), threadPool);
            Futures.addCallback(migrationFuture, migrationFinished(query, delete, scheduleId, bucket, migrationLog,
                remainingMetrics, migratedMetrics, ttl), threadPool);
        } catch (Exception e) {
            log.warn("FAILED to submit " + bucket + " data migration tasks for schedule id " + scheduleId, e);
        }
    }

    private FutureCallback<List<ResultSet>> migrationFinished(final PreparedStatement query, final PreparedStatement delete,
        final Integer scheduleId, final Bucket bucket, final MigrationLog migrationLog, final AtomicInteger
        remainingMetrics, final AtomicInteger migratedMetrics, final Days ttl) {

        return new FutureCallback<List<ResultSet>>() {
            @Override
            public void onSuccess(List<ResultSet> resultSets) {
                try {
                    migrations.countDown();
                    remainingMetrics.decrementAndGet();
                    migratedMetrics.incrementAndGet();
                    migrationLog.write(scheduleId);
                    rateMonitor.requestSucceeded();
                    migrationsMeter.mark();
                    if (log.isDebugEnabled()) {
                        log.debug("Finished migrating " + bucket + " data for schedule id " + scheduleId);
                    }
                } catch (IOException e) {
                    log.warn("Failed to log successful migration of " + bucket + " data for schedule id " +
                        scheduleId, e);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                if (log.isDebugEnabled()) {
                    log.debug("Failed to migrate " + bucket + " data for schedule id " + scheduleId +
                        ". Migration will be rescheduled.", t);
                } else {
                    log.info("Failed to migrate " + bucket + " data for schedule id " + scheduleId + ": " +
                        ThrowableUtil.getRootMessage(t) + ". Migration will be rescheduled");
                }
                rateMonitor.requestFailed();
                try {
                    migrateData(query, delete, bucket, remainingMetrics, migratedMetrics, migrationLog, scheduleId, ttl);
                } catch (Exception e) {
                    log.warn("FAILED to resubmit " + bucket + " data migration task for schedule id " + scheduleId);
                }
            }
        };
    }

    private class MigrationProgressLogger implements Runnable {

        private boolean finished;

        private boolean reportMigrationRates;

        public void finished() {
            finished = true;
        }

        @Override
        public void run() {
            try {
                while (!finished) {
                    log.info("Remaining metrics to migrate\n" +
                        Bucket.ONE_HOUR + ": " + remaining1HourMetrics + "\n" +
                        Bucket.SIX_HOUR + ": " + remaining6HourMetrics + "\n" +
                        Bucket.TWENTY_FOUR_HOUR + ": " + remaining24HourMetrics + "\n");
                    log.info("ErrorCounts{read:" + readErrors + ", write: " + writeErrors + "}");
                    if (reportMigrationRates) {
                        log.info("Metrics migration rates:\n" +
                            "1 min rate: "  + migrationsMeter.oneMinuteRate() + "\n" +
                            "5 min rate: " + migrationsMeter.fiveMinuteRate() + " \n" +
                            "15 min rate: " + migrationsMeter.fifteenMinuteRate() + "\n");
                        reportMigrationRates = false;
                    } else {
                        reportMigrationRates = true;
                    }

                    Thread.sleep(30000);
                }
            } catch (InterruptedException e) {
            }
        }
    }

    private void dropTables() {
        ResultSet resultSet = session.execute("SELECT columnfamily_name FROM system.schema_columnfamilies " +
            "WHERE keyspace_name = 'rhq'");
        for (com.datastax.driver.core.Row row : resultSet) {
            String table = row.getString(0);
            if (table.equals("one_hour_metrics") || table.equals("six_hour_metrics") ||
                table.equals("twenty_four_hour_metrics")) {
                log.info("Dropping table " +  table);
                session.execute("DROP table rhq." + table);
            }
        }
    }

}
