/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.pingcap.tikv.statistics;

import com.google.protobuf.ByteString;
import com.pingcap.tidb.tipb.Chunk;
import com.pingcap.tidb.tipb.RowMeta;
import com.pingcap.tikv.codec.CodecDataInput;
import com.pingcap.tikv.operation.ChunkIterator;
import com.pingcap.tikv.row.ObjectRowImpl;
import com.pingcap.tikv.row.Row;
import com.pingcap.tikv.types.DataTypeFactory;
import com.pingcap.tikv.util.Bucket;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.pingcap.tikv.types.Types.TYPE_BLOB;
import static com.pingcap.tikv.types.Types.TYPE_LONG;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Created by birdstorm on 2017/8/13.
 *
 */
public class HistogramTest {
  private static List<Chunk> chunks = new ArrayList<>();
  private static Histogram histogram = new Histogram();

  @Before
  public void histogramValidationTests() throws Exception {
    /*
     * +----------+----------+---------+-----------+-------+---------+-------------+-------------+
     * | table_id | is_index | hist_id | bucket_id | count | repeats | upper_bound | lower_bound |
     * +----------+----------+---------+-----------+-------+---------+-------------+-------------+
     * |       27 |        0 |       0 |         0 |     2 |       1 | 1           | 0           |
     * |       27 |        0 |       0 |         1 |     3 |       1 | 3           | 2           |
     * +----------+----------+---------+-----------+-------+---------+-------------+-------------+
     */
    String histogramStr = "\b6\b\000\b\000\b\000\b\004\b\002\002\0021\002\0020\b6\b\000\b\000\b\002\b\006\b\002\002\0023\002\0022";
    Chunk chunk =
        Chunk.newBuilder()
            .setRowsData(ByteString.copyFromUtf8(histogramStr))
            .addRowsMeta(0, RowMeta.newBuilder().setHandle(6).setLength(18))
            .addRowsMeta(1, RowMeta.newBuilder().setHandle(7).setLength(18))
            .build();

    chunks.add(chunk);
    ChunkIterator chunkIterator = new ChunkIterator(chunks);
    com.pingcap.tikv.types.DataType blobs = DataTypeFactory.of(TYPE_BLOB);
    com.pingcap.tikv.types.DataType ints = DataTypeFactory.of(TYPE_LONG);
    Row row = ObjectRowImpl.create(24);
    CodecDataInput cdi = new CodecDataInput(chunkIterator.next());
    ints.decodeValueToRow(cdi, row, 0);
    ints.decodeValueToRow(cdi, row, 1);
    ints.decodeValueToRow(cdi, row, 2);
    ints.decodeValueToRow(cdi, row, 3);
    ints.decodeValueToRow(cdi, row, 4);
    ints.decodeValueToRow(cdi, row, 5);
    blobs.decodeValueToRow(cdi, row, 6);
    blobs.decodeValueToRow(cdi, row, 7);
    cdi = new CodecDataInput(chunkIterator.next());
    ints.decodeValueToRow(cdi, row, 8);
    ints.decodeValueToRow(cdi, row, 9);
    ints.decodeValueToRow(cdi, row, 10);
    ints.decodeValueToRow(cdi, row, 11);
    ints.decodeValueToRow(cdi, row, 12);
    ints.decodeValueToRow(cdi, row, 13);
    blobs.decodeValueToRow(cdi, row, 14);
    blobs.decodeValueToRow(cdi, row, 15);

    assertEquals(row.getLong(0), 27);
    assertEquals(row.getLong(1), 0);
    assertEquals(row.getLong(2), 0);
    assertEquals(row.getLong(3), 0);
    assertEquals(row.getLong(4), 2);
    assertEquals(row.getLong(5), 1);
    assertArrayEquals(row.getBytes(6), ByteString.copyFromUtf8("1").toByteArray());
    assertArrayEquals(row.getBytes(7), ByteString.copyFromUtf8("0").toByteArray());
    assertEquals(row.getLong(8), 27);
    assertEquals(row.getLong(9), 0);
    assertEquals(row.getLong(10), 0);
    assertEquals(row.getLong(11), 1);
    assertEquals(row.getLong(12), 3);
    assertEquals(row.getLong(13), 1);
    assertArrayEquals(row.getBytes(14), ByteString.copyFromUtf8("3").toByteArray());
    assertArrayEquals(row.getBytes(15), ByteString.copyFromUtf8("2").toByteArray());
  }

  @Before
  //a temperate function to allow unit tests
  public void createSampleHistogram() throws Exception {

    /*
     * +---------+-----------+-------+---------+-------------+-------------+
     * | hist_id | bucket_id | count | repeats | upper_bound | lower_bound |
     * +---------+-----------+-------+---------+-------------+-------------+
     * |      10 |         0 |     5 |       2 | 3           | 0           |
     * |      10 |         1 |    10 |       1 | 7           | 4           |
     * |      10 |         2 |    25 |       4 | 11          | 8           |
     * |      10 |         3 |    30 |       0 | 15          | 12          |
     * +---------+-----------+-------+---------+-------------+-------------+
     */

    histogram.setId(10);
    histogram.setNullCount(4);
    histogram.setLastUpdateVersion(23333333);
    histogram.setNumberOfDistinctValue(10);
    ArrayList<Bucket> buckets = new ArrayList<>(4);
    buckets.add(new Bucket(5, 2, 0, 3));
    buckets.add(new Bucket(10, 1, 4, 7));
    buckets.add(new Bucket(25, 4, 8, 11));
    buckets.add(new Bucket(30, 0, 12, 15));
    histogram.setBuckets(buckets);
  }

  @Test
  public void testEqualRowCount() throws Exception {
    assertEquals(histogram.equalRowCount(4), 3.0, 0.000001);
    assertEquals(histogram.equalRowCount(11), 4.0, 0.000001);
  }

  @Test
  public void testGreaterRowCount() throws Exception {
    assertEquals(histogram.greaterRowCount(-1), 30.0, 0.000001);
    assertEquals(histogram.greaterRowCount(0), 27.0, 0.000001);
    assertEquals(histogram.greaterRowCount(4), 22.0, 0.000001);
    assertEquals(histogram.greaterRowCount(9), 11.5, 0.000001);
    assertEquals(histogram.greaterRowCount(11), 10.5, 0.000001); //shouldn't this be 5.0?
    assertEquals(histogram.greaterRowCount(12), 2.0, 0.000001);
    assertEquals(histogram.greaterRowCount(19), 0.0, 0.000001);
  }

  @Test
  public void testBetweenRowCount() throws Exception {
    assertEquals(histogram.betweenRowCount(2, 6), 5.5, 0.000001);
    assertEquals(histogram.betweenRowCount(8, 10), 5.5, 0.000001);
  }

  @Test
  public void testTotalRowCount() throws Exception {
    assertEquals(histogram.totalRowCount(), 30.0, 0.000001);
  }

  @Test
  public void testLessRowCount() throws Exception {
    assertEquals(histogram.lessRowCount(0), 0.0, 0.000001);
    assertEquals(histogram.lessRowCount(3), 1.5, 0.000001);
    assertEquals(histogram.lessRowCount(4), 5.0, 0.000001);
    assertEquals(histogram.lessRowCount(7), 7.0, 0.000001);
    assertEquals(histogram.lessRowCount(9), 15.5, 0.000001);
    assertEquals(histogram.lessRowCount(12), 25.0, 0.000001);
    assertEquals(histogram.lessRowCount(15), 27.5, 0.000001); //shouldn't this be 30.0?
  }

  @Test
  public void testLowerBound() throws Exception {
    assertEquals(histogram.lowerBound(0), -1);
    assertEquals(histogram.lowerBound(3), 0);
    assertEquals(histogram.lowerBound(4), -2);
    assertEquals(histogram.lowerBound(7), 1);
    assertEquals(histogram.lowerBound(9), -3);
    assertEquals(histogram.lowerBound(11), 2);
    assertEquals(histogram.lowerBound(13), -4);
    assertEquals(histogram.lowerBound(19), -5);
  }

  @Test
  public void testMergeBlock() throws Exception {
    histogram.mergeBlock(histogram.getBuckets().size() - 1);
    assertEquals(histogram.getBuckets().size(), 2);
  }

}