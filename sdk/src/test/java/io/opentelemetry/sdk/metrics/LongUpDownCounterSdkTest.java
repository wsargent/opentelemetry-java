/*
 * Copyright 2020, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentelemetry.sdk.metrics;

import static com.google.common.truth.Truth.assertThat;

import io.opentelemetry.common.AttributeValue;
import io.opentelemetry.metrics.LongUpDownCounter;
import io.opentelemetry.metrics.LongUpDownCounter.BoundLongUpDownCounter;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.internal.TestClock;
import io.opentelemetry.sdk.metrics.StressTestRunner.OperationUpdater;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricData.Descriptor;
import io.opentelemetry.sdk.metrics.data.MetricData.Descriptor.Type;
import io.opentelemetry.sdk.metrics.data.MetricData.LongPoint;
import io.opentelemetry.sdk.resources.Resource;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LongUpDownCounterSdk}. */
@RunWith(JUnit4.class)
public class LongUpDownCounterSdkTest {

  @Rule public ExpectedException thrown = ExpectedException.none();
  private static final long SECOND_NANOS = 1_000_000_000;
  private static final Resource RESOURCE =
      Resource.create(
          Collections.singletonMap(
              "resource_key", AttributeValue.stringAttributeValue("resource_value")));
  private static final InstrumentationLibraryInfo INSTRUMENTATION_LIBRARY_INFO =
      InstrumentationLibraryInfo.create(
          "io.opentelemetry.sdk.metrics.LongUpDownCounterSdkTest", null);
  private final TestClock testClock = TestClock.create();
  private final MeterProviderSharedState meterProviderSharedState =
      MeterProviderSharedState.create(testClock, RESOURCE);
  private final MeterSdk testSdk =
      new MeterSdk(meterProviderSharedState, INSTRUMENTATION_LIBRARY_INFO);

  @Test
  public void collectMetrics_NoRecords() {
    LongUpDownCounterSdk longUpDownCounter =
        testSdk
            .longUpDownCounterBuilder("testCounter")
            .setConstantLabels(Collections.singletonMap("sk1", "sv1"))
            .setDescription("My very own counter")
            .setUnit("ms")
            .build();
    List<MetricData> metricDataList = longUpDownCounter.collectAll();
    assertThat(metricDataList).hasSize(1);
    MetricData metricData = metricDataList.get(0);
    assertThat(metricData.getDescriptor())
        .isEqualTo(
            Descriptor.create(
                "testCounter",
                "My very own counter",
                "ms",
                Type.NON_MONOTONIC_LONG,
                Collections.singletonMap("sk1", "sv1")));
    assertThat(metricData.getResource()).isEqualTo(RESOURCE);
    assertThat(metricData.getInstrumentationLibraryInfo()).isEqualTo(INSTRUMENTATION_LIBRARY_INFO);
    assertThat(metricData.getPoints()).isEmpty();
  }

  @Test
  public void collectMetrics_WithOneRecord() {
    LongUpDownCounterSdk longUpDownCounter =
        testSdk.longUpDownCounterBuilder("testCounter").build();
    testClock.advanceNanos(SECOND_NANOS);
    longUpDownCounter.add(12);
    List<MetricData> metricDataList = longUpDownCounter.collectAll();
    assertThat(metricDataList).hasSize(1);
    MetricData metricData = metricDataList.get(0);
    assertThat(metricData.getResource()).isEqualTo(RESOURCE);
    assertThat(metricData.getInstrumentationLibraryInfo()).isEqualTo(INSTRUMENTATION_LIBRARY_INFO);
    assertThat(metricData.getPoints()).hasSize(1);
    assertThat(metricData.getPoints())
        .containsExactly(
            LongPoint.create(
                testClock.now() - SECOND_NANOS,
                testClock.now(),
                Collections.<String, String>emptyMap(),
                12));
  }

  @Test
  public void collectMetrics_WithMultipleCollects() {
    LabelSetSdk labelSet = LabelSetSdk.create("K", "V");
    LabelSetSdk emptyLabelSet = LabelSetSdk.create();
    long startTime = testClock.now();
    LongUpDownCounterSdk longUpDownCounter =
        testSdk.longUpDownCounterBuilder("testCounter").build();
    BoundLongUpDownCounter boundCounter = longUpDownCounter.bind("K", "V");
    try {
      // Do some records using bounds and direct calls and bindings.
      longUpDownCounter.add(12, emptyLabelSet);
      boundCounter.add(123);
      longUpDownCounter.add(21, emptyLabelSet);
      // Advancing time here should not matter.
      testClock.advanceNanos(SECOND_NANOS);
      boundCounter.add(321);
      longUpDownCounter.add(111, labelSet);

      long firstCollect = testClock.now();
      List<MetricData> metricDataList = longUpDownCounter.collectAll();
      assertThat(metricDataList).hasSize(1);
      MetricData metricData = metricDataList.get(0);
      assertThat(metricData.getPoints()).hasSize(2);
      assertThat(metricData.getPoints())
          .containsExactly(
              LongPoint.create(startTime, firstCollect, labelSet.getLabels(), 555),
              LongPoint.create(startTime, firstCollect, emptyLabelSet.getLabels(), 33));

      // Repeat to prove we keep previous values.
      testClock.advanceNanos(SECOND_NANOS);
      boundCounter.add(222);
      longUpDownCounter.add(11, emptyLabelSet);

      long secondCollect = testClock.now();
      metricDataList = longUpDownCounter.collectAll();
      assertThat(metricDataList).hasSize(1);
      metricData = metricDataList.get(0);
      assertThat(metricData.getPoints()).hasSize(2);
      assertThat(metricData.getPoints())
          .containsExactly(
              LongPoint.create(startTime, secondCollect, labelSet.getLabels(), 777),
              LongPoint.create(startTime, secondCollect, emptyLabelSet.getLabels(), 44));
    } finally {
      boundCounter.unbind();
    }
  }

  @Test
  public void sameBound_ForSameLabelSet() {
    LongUpDownCounterSdk longUpDownCounter =
        testSdk.longUpDownCounterBuilder("testCounter").build();
    BoundLongUpDownCounter boundCounter = longUpDownCounter.bind("K", "v");
    BoundLongUpDownCounter duplicateBoundCounter = longUpDownCounter.bind("K", "v");
    try {
      assertThat(duplicateBoundCounter).isEqualTo(boundCounter);
    } finally {
      boundCounter.unbind();
      duplicateBoundCounter.unbind();
    }
  }

  @Test
  public void sameBound_ForSameLabelSet_InDifferentCollectionCycles() {
    LongUpDownCounterSdk longUpDownCounter =
        testSdk.longUpDownCounterBuilder("testCounter").build();
    BoundLongUpDownCounter boundCounter = longUpDownCounter.bind("K", "v");
    try {
      longUpDownCounter.collectAll();
      BoundLongUpDownCounter duplicateBoundCounter = longUpDownCounter.bind("K", "v");
      try {
        assertThat(duplicateBoundCounter).isEqualTo(boundCounter);
      } finally {
        duplicateBoundCounter.unbind();
      }
    } finally {
      boundCounter.unbind();
    }
  }

  @Test
  public void stressTest() {
    final LongUpDownCounterSdk longUpDownCounter =
        testSdk.longUpDownCounterBuilder("testCounter").build();

    StressTestRunner.Builder stressTestBuilder =
        StressTestRunner.builder().setInstrument(longUpDownCounter).setCollectionIntervalMs(100);

    for (int i = 0; i < 4; i++) {
      stressTestBuilder.addOperation(
          StressTestRunner.Operation.create(
              2_000, 1, new OperationUpdaterDirectCall(longUpDownCounter, "K", "V")));
      stressTestBuilder.addOperation(
          StressTestRunner.Operation.create(
              2_000, 1, new OperationUpdaterWithBinding(longUpDownCounter.bind("K", "V"))));
    }

    stressTestBuilder.build().run();
    List<MetricData> metricDataList = longUpDownCounter.collectAll();
    assertThat(metricDataList).hasSize(1);
    assertThat(metricDataList.get(0).getPoints())
        .containsExactly(
            LongPoint.create(
                testClock.now(), testClock.now(), Collections.singletonMap("K", "V"), 160_000));
  }

  @Test
  public void stressTest_WithDifferentLabelSet() {
    final String[] keys = {"Key_1", "Key_2", "Key_3", "Key_4"};
    final String[] values = {"Value_1", "Value_2", "Value_3", "Value_4"};
    final LongUpDownCounterSdk longUpDownCounter =
        testSdk.longUpDownCounterBuilder("testCounter").build();

    StressTestRunner.Builder stressTestBuilder =
        StressTestRunner.builder().setInstrument(longUpDownCounter).setCollectionIntervalMs(100);

    for (int i = 0; i < 4; i++) {
      stressTestBuilder.addOperation(
          StressTestRunner.Operation.create(
              1_000, 2, new OperationUpdaterDirectCall(longUpDownCounter, keys[i], values[i])));

      stressTestBuilder.addOperation(
          StressTestRunner.Operation.create(
              1_000,
              2,
              new OperationUpdaterWithBinding(longUpDownCounter.bind(keys[i], values[i]))));
    }

    stressTestBuilder.build().run();
    List<MetricData> metricDataList = longUpDownCounter.collectAll();
    assertThat(metricDataList).hasSize(1);
    assertThat(metricDataList.get(0).getPoints())
        .containsExactly(
            LongPoint.create(
                testClock.now(),
                testClock.now(),
                Collections.singletonMap(keys[0], values[0]),
                20_000),
            LongPoint.create(
                testClock.now(),
                testClock.now(),
                Collections.singletonMap(keys[1], values[1]),
                20_000),
            LongPoint.create(
                testClock.now(),
                testClock.now(),
                Collections.singletonMap(keys[2], values[2]),
                20_000),
            LongPoint.create(
                testClock.now(),
                testClock.now(),
                Collections.singletonMap(keys[3], values[3]),
                20_000));
  }

  private static class OperationUpdaterWithBinding extends OperationUpdater {
    private final BoundLongUpDownCounter boundLongUpDownCounter;

    private OperationUpdaterWithBinding(BoundLongUpDownCounter boundLongUpDownCounter) {
      this.boundLongUpDownCounter = boundLongUpDownCounter;
    }

    @Override
    void update() {
      boundLongUpDownCounter.add(9);
    }

    @Override
    void cleanup() {
      boundLongUpDownCounter.unbind();
    }
  }

  private static class OperationUpdaterDirectCall extends OperationUpdater {

    private final LongUpDownCounter longUpDownCounter;
    private final String key;
    private final String value;

    private OperationUpdaterDirectCall(
        LongUpDownCounter longUpDownCounter, String key, String value) {
      this.longUpDownCounter = longUpDownCounter;
      this.key = key;
      this.value = value;
    }

    @Override
    void update() {
      longUpDownCounter.add(11, key, value);
    }

    @Override
    void cleanup() {}
  }
}
