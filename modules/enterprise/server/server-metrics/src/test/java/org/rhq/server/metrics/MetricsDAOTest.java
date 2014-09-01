/*
 *
 *  * RHQ Management Platform
 *  * Copyright (C) 2005-2012 Red Hat, Inc.
 *  * All rights reserved.
 *  *
 *  * This program is free software; you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License, version 2, as
 *  * published by the Free Software Foundation, and/or the GNU Lesser
 *  * General Public License, version 2.1, also as published by the Free
 *  * Software Foundation.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  * GNU General Public License and the GNU Lesser General Public License
 *  * for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * and the GNU Lesser General Public License along with this program;
 *  * if not, write to the Free Software Foundation, Inc.,
 *  * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *
 */

package org.rhq.server.metrics;

import static java.util.Arrays.asList;
import static org.rhq.test.AssertUtils.assertCollectionMatchesNoOrder;
import static org.rhq.test.AssertUtils.assertPropertiesMatch;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.rhq.core.domain.measurement.MeasurementDataNumeric;
import org.rhq.server.metrics.domain.AggregateNumericMetric;
import org.rhq.server.metrics.domain.AggregateSimpleNumericMetric;
import org.rhq.server.metrics.domain.AggregateType;
import org.rhq.server.metrics.domain.Bucket;
import org.rhq.server.metrics.domain.CacheIndexEntry;
import org.rhq.server.metrics.domain.CacheIndexEntryMapper;
import org.rhq.server.metrics.domain.IndexEntry;
import org.rhq.server.metrics.domain.MetricsTable;
import org.rhq.server.metrics.domain.RawNumericMetric;
import org.rhq.server.metrics.domain.RawNumericMetricMapper;

/**
 * @author John Sanda
 */
public class MetricsDAOTest extends CassandraIntegrationTest {

    private final Log log = LogFactory.getLog(MetricsDAOTest.class);

    private static final boolean ENABLED = true;

    private final long SECOND = 1000;

    private final long MINUTE = 60 * SECOND;

    private final long HOUR = 60 * MINUTE;

    private MetricsDAO dao;

    private AggregateCacheMapper aggregateCacheMapper = new AggregateCacheMapper();

    private RawCacheMapper rawCacheMapper = new RawCacheMapper();

    @BeforeClass
    public void initDAO() throws Exception {
        dao = new MetricsDAO(storageSession, new MetricsConfiguration());
    }

    @BeforeMethod
    public void resetDB() throws Exception {
        session.execute("TRUNCATE " + MetricsTable.INDEX);
        session.execute("TRUNCATE " + MetricsTable.RAW);
        session.execute("TRUNCATE " + MetricsTable.AGGREGATE);
        session.execute("TRUNCATE " + MetricsTable.METRICS_CACHE);
        session.execute("TRUNCATE " + MetricsTable.METRICS_CACHE_INDEX);
    }

    @Test(enabled = ENABLED)
    public void insertAndFindRawData() throws Exception {
        DateTime hour0 = hour0();
        DateTime currentTime = hour0.plusHours(4).plusMinutes(44);
        DateTime threeMinutesAgo = currentTime.minusMinutes(3);

        int scheduleId = 1;
        MeasurementDataNumeric expected = new MeasurementDataNumeric(threeMinutesAgo.getMillis(), scheduleId, 1.23);

        WaitForWrite waitForResults = new WaitForWrite(1);

        StorageResultSetFuture resultSetFuture = dao.insertRawData(expected);
        Futures.addCallback(resultSetFuture, waitForResults);
        waitForResults.await("Failed to insert raw data");

        List<RawNumericMetric> actualMetrics = Lists.newArrayList(dao.findRawMetrics(scheduleId,
            threeMinutesAgo.minusSeconds(1).getMillis(), threeMinutesAgo.plusSeconds(1).getMillis()));

        assertEquals(actualMetrics.size(), 1, "Expected to get back one raw metric");
        assertEquals(actualMetrics.get(0), new RawNumericMetric(scheduleId, expected.getTimestamp(),
            expected.getValue()), "The raw metric does not match the expected value");
    }

    @Test(enabled = ENABLED)
    public void findLatestRawMetric() throws Exception {
        DateTime hour0 = hour0();
        DateTime currentTime = hour0.plusHours(4).plusMinutes(44);
        DateTime threeMinutesAgo = currentTime.minusMinutes(3);
        DateTime twoMinutesAgo = currentTime.minusMinutes(2);
        DateTime oneMinuteAgo = currentTime.minusMinutes(1);

        int scheduleId = 1;

        List<MeasurementDataNumeric> data = new ArrayList<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(threeMinutesAgo.getMillis(), scheduleId, 3.2));
        data.add(new MeasurementDataNumeric(twoMinutesAgo.getMillis(), scheduleId, 3.9));
        data.add(new MeasurementDataNumeric(oneMinuteAgo.getMillis(), scheduleId, 2.6));

        WaitForWrite waitForWrite = new WaitForWrite(data.size());

        for (MeasurementDataNumeric raw : data) {
            StorageResultSetFuture resultSetFuture = dao.insertRawData(raw);
            Futures.addCallback(resultSetFuture, waitForWrite);
        }
        waitForWrite.await("Failed to insert raw data");

        RawNumericMetric actual = dao.findLatestRawMetric(scheduleId);
        RawNumericMetric expected = new RawNumericMetric(scheduleId, oneMinuteAgo.getMillis(), 2.6);

        assertEquals(actual, expected, "Failed to find latest raw metric");
    }

    @Test(enabled = ENABLED)
    public void findRawDataAsync() throws Exception {
        DateTime hour0 = hour0();
        DateTime currentTime = hour0.plusHours(4).plusMinutes(44);
        DateTime threeMinutesAgo = currentTime.minusMinutes(3);
        DateTime twoMinutesAgo = currentTime.minusMinutes(2);
        DateTime oneMinuteAgo = currentTime.minusMinutes(1);

        int scheduleId = 1;

        List<MeasurementDataNumeric> data = new ArrayList<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(threeMinutesAgo.getMillis(), scheduleId, 3.2));
        data.add(new MeasurementDataNumeric(twoMinutesAgo.getMillis(), scheduleId, 3.9));
        data.add(new MeasurementDataNumeric(oneMinuteAgo.getMillis(), scheduleId, 2.6));

        WaitForWrite waitForWrite = new WaitForWrite(data.size());

        for (MeasurementDataNumeric raw : data) {
            StorageResultSetFuture resultSetFuture = dao.insertRawData(raw);
            Futures.addCallback(resultSetFuture, waitForWrite);
        }
        waitForWrite.await("Failed to insert raw data");

        RawNumericMetricMapper mapper = new RawNumericMetricMapper();
        WaitForRead<RawNumericMetric> waitForRead = new WaitForRead<RawNumericMetric>(mapper);
        StorageResultSetFuture resultSetFuture = dao.findRawMetricsAsync(scheduleId,
            threeMinutesAgo.minusSeconds(5).getMillis(), oneMinuteAgo.plusSeconds(5).getMillis());
        Futures.addCallback(resultSetFuture, waitForRead);

        waitForRead.await("Failed to fetch raw data");
        List<RawNumericMetric> actual = waitForRead.getResults();
        List<RawNumericMetric> expected = map(data);

        assertEquals(actual, expected, "Async read of raw data failed");
    }

    @Test(enabled = ENABLED)
    public void findRawMetricsForMultipleSchedules() throws Exception {
        DateTime currentTime = hour0().plusHours(4).plusMinutes(44);
        DateTime currentHour = currentTime.hourOfDay().roundFloorCopy();
        DateTime threeMinutesAgo = currentTime.minusMinutes(3);
        DateTime twoMinutesAgo = currentTime.minusMinutes(2);
        DateTime oneMinuteAgo = currentTime.minusMinutes(1);

        int scheduleId1 = 1;
        int scheduleId2 = 2;

        Set<MeasurementDataNumeric> data = new HashSet<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(threeMinutesAgo.getMillis(), scheduleId1, 1.1));
        data.add(new MeasurementDataNumeric(threeMinutesAgo.getMillis(), scheduleId2, 1.2));
        data.add(new MeasurementDataNumeric(twoMinutesAgo.getMillis(), scheduleId1, 2.1));
        data.add(new MeasurementDataNumeric(twoMinutesAgo.getMillis(), scheduleId2, 2.2));
        data.add(new MeasurementDataNumeric(oneMinuteAgo.getMillis(), scheduleId1, 3.1));
        data.add(new MeasurementDataNumeric(oneMinuteAgo.getMillis(), scheduleId2, 3.2));

        WaitForWrite waitForWrite = new WaitForWrite(data.size());

        for (MeasurementDataNumeric raw : data) {
            StorageResultSetFuture resultSetFuture = dao.insertRawData(raw);
            Futures.addCallback(resultSetFuture, waitForWrite);
        }
        waitForWrite.await("Failed to insert raw data");

        List<RawNumericMetric> actualMetrics = Lists.newArrayList(dao.findRawMetrics(asList(scheduleId1, scheduleId2),
            currentHour.getMillis(), currentHour.plusHours(1).getMillis()));
        List<RawNumericMetric> expectedMetrics = asList(
            new RawNumericMetric(scheduleId1, threeMinutesAgo.getMillis(), 1.1),
            new RawNumericMetric(scheduleId1, twoMinutesAgo.getMillis(), 2.1),
            new RawNumericMetric(scheduleId1, oneMinuteAgo.getMillis(), 3.1),
            new RawNumericMetric(scheduleId2, threeMinutesAgo.getMillis(), 1.2),
            new RawNumericMetric(scheduleId2, twoMinutesAgo.getMillis(), 2.2),
            new RawNumericMetric(scheduleId2, oneMinuteAgo.getMillis(), 3.2)
        );
        assertEquals(actualMetrics, expectedMetrics, "Failed to find raw metrics for multiple schedules");
    }

    @Test(enabled = ENABLED)
    public void insertAndFind1HourMetrics() {
        int scheduleId = 100;
        AggregateNumericMetric metric1 = new AggregateNumericMetric(scheduleId, Bucket.ONE_HOUR, 3.0, 1.0, 8.0,
            hour(0).getMillis());
        AggregateNumericMetric metric2 = new AggregateNumericMetric(scheduleId, Bucket.ONE_HOUR, 4.0, 2.0, 10.0,
            hour(0).plusMinutes(5).getMillis());
        AggregateNumericMetric metric3 = new AggregateNumericMetric(scheduleId + 1, Bucket.ONE_HOUR, 2.0, 2.0, 2.0,
            hour(0).getMillis());

        dao.insert1HourData(metric1).get();
        dao.insert1HourData(metric2).get();
        dao.insert1HourData(metric3).get();

        List<AggregateNumericMetric> expected = asList(metric1, metric2);
        List<AggregateNumericMetric> actual = dao.findAggregateMetrics(scheduleId, Bucket.ONE_HOUR, hour0().getMillis(),
            hour(1).getMillis());

        assertEquals(actual, expected, "Failed to find 1 hour metrics");
    }

    @Test(enabled = ENABLED)
    public void insertAndFind6HourMetrics() {
        int scheduleId = 100;
        AggregateNumericMetric metric1 = new AggregateNumericMetric(scheduleId, Bucket.SIX_HOUR, 3.0, 3.0, 3.0,
            hour(0).getMillis());
        AggregateNumericMetric metric2 = new AggregateNumericMetric(scheduleId, Bucket.SIX_HOUR, 4.0, 4.0, 4.0,
            hour(6).getMillis());
        AggregateNumericMetric metric3 = new AggregateNumericMetric(scheduleId, Bucket.SIX_HOUR, 5.0, 5.0, 5.0,
            hour(12).getMillis());
        AggregateNumericMetric metric4 = new AggregateNumericMetric(scheduleId + 1, Bucket.SIX_HOUR, 5.0, 5.0, 5.0,
            hour(6).getMillis());

        dao.insert6HourData(metric1).get();
        dao.insert6HourData(metric2).get();
        dao.insert6HourData(metric3).get();
        dao.insert6HourData(metric4).get();

        List<AggregateNumericMetric> expected = asList(metric2, metric3);
        List<AggregateNumericMetric> actual = dao.findAggregateMetrics(scheduleId, Bucket.SIX_HOUR, hour(6).getMillis(),
            hour(18).getMillis());

        assertEquals(actual, expected, "Failed to find 6 hour metrics");
    }

    @Test(enabled = ENABLED)
    public void insertAndFind24HourMetrics() {
        int scheduleId = 100;
        AggregateNumericMetric metric1 = new AggregateNumericMetric(scheduleId, Bucket.TWENTY_FOUR_HOUR, 3.0, 3.0, 3.0,
            hour(0).getMillis());
        AggregateNumericMetric metric2 = new AggregateNumericMetric(scheduleId, Bucket.TWENTY_FOUR_HOUR, 4.0, 4.0, 4.0,
            hour(0).plusDays(2).getMillis());
        AggregateNumericMetric metric3 = new AggregateNumericMetric(scheduleId, Bucket.TWENTY_FOUR_HOUR, 5.0, 5.0, 5.0,
            hour(0).plusDays(3).getMillis());
        AggregateNumericMetric metric4 = new AggregateNumericMetric(scheduleId, Bucket.TWENTY_FOUR_HOUR, 6.0, 6.0, 6.0,
            hour(0).plusDays(4).getMillis());
        AggregateNumericMetric metric5 = new AggregateNumericMetric(scheduleId + 1, Bucket.TWENTY_FOUR_HOUR, 4.0, 4.0,
            4.0, hour(0).plusDays(2).getMillis());

        dao.insert24HourData(metric1).get();
        dao.insert24HourData(metric2).get();
        dao.insert24HourData(metric3).get();
        dao.insert24HourData(metric4).get();
        dao.insert24HourData(metric5).get();

        List<AggregateNumericMetric> expected = asList(metric2, metric3);
        List<AggregateNumericMetric> actual = dao.findAggregateMetrics(scheduleId, Bucket.TWENTY_FOUR_HOUR,
            hour(0).plusDays(2).getMillis(), hour(0).plusDays(4).getMillis());

        assertEquals(actual, expected, "Failed to find 24 hour metrics");
    }

    @Test(enabled = ENABLED)
    public void updateSixHourCache() throws Exception {
        int startScheduleId = 100;
        int scheduleId1 = 101;
        int scheduleId2= 102;

        WaitForWrite waitForWrite = new WaitForWrite(2);

        StorageResultSetFuture resultSetFuture1 = dao.updateMetricsCache(MetricsTable.SIX_HOUR,
            hour0().getMillis(), startScheduleId, scheduleId1, hour0().getMillis(), ImmutableMap.of(
            AggregateType.MIN.ordinal(), 3.14,
            AggregateType.AVG.ordinal(), 3.14,
            AggregateType.MAX.ordinal(), 3.14));
        StorageResultSetFuture resultSetFuture2 = dao.updateMetricsCache(MetricsTable.SIX_HOUR,
            hour0().getMillis(), startScheduleId, scheduleId2, hour0().getMillis(), ImmutableMap.of(
            AggregateType.MIN.ordinal(), 3.14,
            AggregateType.AVG.ordinal(), 3.14,
            AggregateType.MAX.ordinal(), 3.14));

        Futures.addCallback(resultSetFuture1, waitForWrite);
        Futures.addCallback(resultSetFuture2, waitForWrite);

        waitForWrite.await("Failed to update metrics cache");

        List<AggregateNumericMetric> expected = asList(
            new AggregateNumericMetric(scheduleId1, 3.14, 3.14, 3.14, hour0().getMillis()),
            new AggregateNumericMetric(scheduleId2, 3.14, 3.14, 3.14, hour0().getMillis())
        );

        StorageResultSetFuture cacheFuture = dao.findCacheEntriesAsync(MetricsTable.SIX_HOUR,
            hour0().getMillis(), startScheduleId);
        ResultSet resultSet = cacheFuture.get();
        List<Row> rows = resultSet.all();

        assertEquals(rows.size(), expected.size(), "Expected to get back two rows from cache query");

        List<AggregateNumericMetric> actual = asList(aggregateCacheMapper.map(rows.get(0)),
            aggregateCacheMapper.map(rows.get(1)));
        assertCollectionMatchesNoOrder(expected, actual, "Failed to update or retrieve metrics cache entries");
    }

    @Test(enabled = ENABLED)
    public void insertAndGetRawCacheEntries() throws Exception {
        int startScheduleId = 100;
        int scheduleId1 = 101;
        int scheduleId2 = 102;
        long timeSlice = hour0().plusHours(7).getMillis();
        long timestamp1 = hour0().plusHours(7).plusMinutes(3).getMillis();
        long timestamp2 = hour0().plusHours(7).plusMinutes(5).getMillis();

        WaitForWrite waitForWrite = new WaitForWrite(2);
        StorageResultSetFuture insertFuture1 = dao.updateMetricsCache(MetricsTable.RAW, timeSlice, startScheduleId,
            scheduleId1, timestamp1, ImmutableMap.of(AggregateType.VALUE.ordinal(), 2.14));
        StorageResultSetFuture insertFuture2 = dao.updateMetricsCache(MetricsTable.RAW, timeSlice, startScheduleId,
            scheduleId2, timestamp2, ImmutableMap.of(AggregateType.VALUE.ordinal(), 1.01));
        Futures.addCallback(insertFuture1, waitForWrite);
        Futures.addCallback(insertFuture2, waitForWrite);
        waitForWrite.await("Failed to update raw cache");

        List<RawNumericMetric> expected = asList(new RawNumericMetric(scheduleId1, timestamp1, 2.14),
            new RawNumericMetric(scheduleId2, timestamp2, 1.01));
        StorageResultSetFuture queryFuture = dao.findCacheEntriesAsync(MetricsTable.RAW, timeSlice,
            startScheduleId);
        ResultSet resultSet = queryFuture.get();
        List<Row> rows = resultSet.all();

        assertEquals(rows.size(), expected.size(), "Expected to get back two rows from raw cache query");

        List<RawNumericMetric> actual = asList(rawCacheMapper.map(rows.get(0)), rawCacheMapper.map(rows.get(1)));

        assertEquals(actual, expected, "The raw cache entries do not match");
    }

    @Test(enabled = ENABLED)
    public void insertAndFindIndexEntries() {
        IndexEntry entry1 = new IndexEntry(MetricsTable.RAW, 0, hour(2).getMillis(), 100);
        IndexEntry entry2 = new IndexEntry(MetricsTable.RAW, 0, hour(2).getMillis(), 101);
        IndexEntry entry3 = new IndexEntry(MetricsTable.RAW, 1, hour(2).getMillis(), 102);
        IndexEntry entry4 = new IndexEntry(MetricsTable.RAW, 0, hour(3).getMillis(), 101);

        dao.insertIndexEntry(entry1).get();
        dao.insertIndexEntry(entry2).get();
        dao.insertIndexEntry(entry3).get();
        dao.insertIndexEntry(entry4).get();

        List<IndexEntry> expected = asList(entry1, entry2);
        List<IndexEntry> actual = new ArrayList<IndexEntry>();

        ResultSet resultSet = dao.findIndexEntries(MetricsTable.RAW, 0, hour(2).getMillis()).get();
        for (Row row : resultSet) {
            actual.add(new IndexEntry(MetricsTable.RAW, 0, hour(2).getMillis(), row.getInt(0)));
        }

        assertEquals(actual, expected, "The index entries do not match");
    }

    @Test(enabled = ENABLED)
    public void deleteRawCacheEntries() {
        int startScheduleId = 100;
        int scheduleId1 = 101;
        int scheduleId2 = 102;
        DateTime timeSlice = hour0().plusHours(2);

        dao.updateMetricsCache(MetricsTable.RAW, timeSlice.getMillis(), startScheduleId, scheduleId1,
            timeSlice.plusMinutes(2).getMillis(), ImmutableMap.of(AggregateType.VALUE.ordinal(), 3.14)).get();
        dao.updateMetricsCache(MetricsTable.RAW, timeSlice.getMillis(), startScheduleId, scheduleId2,
            timeSlice.plusMinutes(4).getMillis(), ImmutableMap.of(AggregateType.VALUE.ordinal(), 3.14)).get();

        dao.deleteCacheEntries(MetricsTable.RAW, timeSlice.getMillis(), startScheduleId).get();

        ResultSet resultSet = dao.findCacheEntriesAsync(MetricsTable.RAW,
            timeSlice.getMillis(), startScheduleId).get();

        assertTrue(resultSet.isExhausted(), "Expected an empty result set");
    }

    @Test(enabled = ENABLED)
    public void insertAndFindCacheIndexEntries() throws Exception {
        DateTime currentTimeSlice = hour0().plusHours(9);
        DateTime pastTimeSlice = hour0().plusHours(8);
        int partition = 0;
        int startScheduleId = 100;
        Set<Integer> scheduleIds = ImmutableSet.of(122, 123);

        WaitForWrite indexUpdates = new WaitForWrite(1);

        StorageResultSetFuture indexFuture = dao.updateCacheIndex(MetricsTable.RAW, hour0().getMillis(), 0,
            pastTimeSlice.getMillis(), startScheduleId, currentTimeSlice.getMillis(), scheduleIds);
        Futures.addCallback(indexFuture, indexUpdates);

        indexUpdates.await("Failed to update " + MetricsTable.METRICS_CACHE_INDEX);

        List<CacheIndexEntry> expected = asList(
            newCacheIndexEntry(MetricsTable.RAW, hour0(), partition, pastTimeSlice, startScheduleId, currentTimeSlice,
                scheduleIds)
        );

        StorageResultSetFuture queryFuture = dao.findPastCacheIndexEntriesBeforeToday(MetricsTable.RAW,
            hour0().getMillis(), partition, pastTimeSlice.getMillis());
        ResultSet resultSet = queryFuture.get();
        CacheIndexEntryMapper mapper = new CacheIndexEntryMapper();
        List<CacheIndexEntry> actual = new ArrayList<CacheIndexEntry>(2);

        for (Row row : resultSet) {
            actual.add(mapper.map(row));
        }

        assertCacheIndexEntriesEqual(actual, expected);
    }

    @Test(enabled = ENABLED)
    public void deleteCacheIndexEntries() throws Exception {
        DateTime currentTimeSlice = hour0().plusHours(9);
        DateTime pastTimeSlice = hour0().plusHours(8);
        int partition = 0;
        int startScheduleId = 100;
        Set<Integer> scheduleIds = ImmutableSet.of(122, 123);

        StorageResultSetFuture indexFuture = dao.updateCacheIndex(MetricsTable.RAW, hour0().getMillis(), 0,
            currentTimeSlice.getMillis(), startScheduleId, currentTimeSlice.getMillis(), scheduleIds);
        indexFuture.get();

        StorageResultSetFuture deleteFuture = dao.deleteCacheIndexEntry(MetricsTable.RAW, hour0().getMillis(),
            partition, currentTimeSlice.getMillis(), startScheduleId, currentTimeSlice.getMillis());
        deleteFuture.get();

        StorageResultSetFuture queryFuture = dao.findCurrentCacheIndexEntries(MetricsTable.RAW,
            hour0().getMillis(), partition, currentTimeSlice.getMillis());
        ResultSet resultSet = queryFuture.get();

        assertTrue(resultSet.isExhausted(), "Expected an empty result set");

//        List<CacheIndexEntry> expected = asList(newCacheIndexEntry(MetricsTable.RAW, hour0(), partition,
//            currentTimeSlice, startScheduleId, currentTimeSlice, scheduleIds));
//        CacheIndexEntryMapper mapper = new CacheIndexEntryMapper();
//        List<CacheIndexEntry> actual = new ArrayList<CacheIndexEntry>(2);
//
//        for (Row row : resultSet) {
//            actual.add(mapper.map(row));
//        }
//
//        assertCacheIndexEntriesEqual(actual, expected);
    }

    private CacheIndexEntry newCacheIndexEntry(MetricsTable table, DateTime day, int partition,
        DateTime collectionTimeSlice, int startScheduleId, DateTime insertTimeSlice, Set<Integer> scheduleIds) {
        CacheIndexEntry entry = new CacheIndexEntry();
        entry.setBucket(table);
        entry.setDay(day.getMillis());
        entry.setInsertTimeSlice(insertTimeSlice.getMillis());
        entry.setPartition(partition);
        entry.setStartScheduleId(startScheduleId);
        entry.setCollectionTimeSlice(collectionTimeSlice.getMillis());
        entry.setScheduleIds(scheduleIds);

        return entry;
    }

    private void assertCacheIndexEntriesEqual(List<CacheIndexEntry> actual, List<CacheIndexEntry> expected) {
        assertEquals(actual.size(), expected.size(), "The number of cache index entries is wrong");
        for (int i = 0; i < expected.size(); ++i) {
            assertPropertiesMatch(expected.get(i), actual.get(i), "The cache index entry does not match the expected " +
                "value");
        }
    }

    @Test(enabled = ENABLED)
    public void findAggregatedSimpleMetrics() throws InterruptedException {
        List<AggregateNumericMetric> metrics;

        long startTime = System.currentTimeMillis();
        long endTime = startTime + HOUR;

        int scheduleId = 123;
        int numberOfAggregatedMetrics = 250;

        metrics = this.generateRandomAggregatedMetrics(scheduleId, numberOfAggregatedMetrics, startTime);
        for (AggregateNumericMetric metric : metrics) {
            metric.setBucket(Bucket.ONE_HOUR);
            dao.insert1HourData(metric).get();

        }
        double expectedAverageSum = 0;

        for (AggregateNumericMetric aggregatedMetric : metrics) {
            expectedAverageSum += aggregatedMetric.getAvg();
        }

        int alternateScheduleId = 321;
        int alternateNumberOfAggregatedMetrics = 75;
        metrics = this.generateRandomAggregatedMetrics(alternateScheduleId, alternateNumberOfAggregatedMetrics,
            startTime);
        for (AggregateNumericMetric metric : metrics) {
            metric.setBucket(Bucket.ONE_HOUR);
            dao.insert1HourData(metric).get();
        }

        List<AggregateSimpleNumericMetric> retrievedItems = Lists.newArrayList(dao.findAggregatedSimpleOneHourMetric(
            scheduleId, startTime, endTime));
        assertEquals(numberOfAggregatedMetrics, retrievedItems.size());
        double actualAverageSum = 0;
        double actualMinSum = 0;
        double actualMaxSum = 0;
        for (AggregateSimpleNumericMetric metric : retrievedItems) {
            if (AggregateType.AVG.equals(metric.getType())) {
                actualAverageSum += metric.getValue();
            } else if (AggregateType.MAX.equals(metric.getType())) {
                actualMaxSum += metric.getValue();
            } else if (AggregateType.MIN.equals(metric.getType())) {
                actualMinSum += metric.getValue();
            }
        }

        assertEquals(expectedAverageSum, actualAverageSum);
        assertEquals(actualMaxSum, 0.0);
        assertEquals(actualMinSum, 0.0);
    }

    /**
     * Generates a set of aggregated metrics with randomized data. Using the schedule id provided the
     * aggregated metrics are 1 millisecond apart beginning with start time and min<average<max.
     *
     * @param scheduleId
     * @param numberOfAggregatedMetrics
     * @param startTime
     * @return
     */
    private List<AggregateNumericMetric> generateRandomAggregatedMetrics(int scheduleId,
        int numberOfAggregatedMetrics, long startTime) {
        double max;
        double min;
        double average;
        double temp;

        Random random = new Random();
        List<AggregateNumericMetric> generatedMetrics = new ArrayList<AggregateNumericMetric>();

        for (int i = 0; i < numberOfAggregatedMetrics; i++) {
            max = random.nextDouble() * 1000;
            average = random.nextDouble() * 1000;
            if (average > max) {
                temp = max;
                max = average;
                average = temp;
            }

            min = random.nextDouble() * 1000;
            if (min > max) {
                temp = min;
                min = average;
                average = max;
                max = temp;
            } else if (min > average) {
                temp = average;
                average = min;
                min = temp;
            }

            generatedMetrics.add(new AggregateNumericMetric(scheduleId, average, min, max, startTime + i));
        }

        return generatedMetrics;
    }

    private List<RawNumericMetric> map(List<MeasurementDataNumeric> data) {
        List<RawNumericMetric> raw = new ArrayList<RawNumericMetric>(data.size());
        for (MeasurementDataNumeric datum : data) {
            raw.add(new RawNumericMetric(datum.getScheduleId(), datum.getTimestamp(), datum.getValue()));
        }
        return raw;
    }

}
