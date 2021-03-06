/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sql.impl.rel;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.beam.vendor.guava.v20_0.com.google.common.base.Preconditions.checkArgument;
import static org.apache.calcite.avatica.util.DateTimeUtils.MILLIS_PER_DAY;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.beam.runners.direct.DirectOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.Pipeline.PipelineVisitor;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.PipelineResult.State;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.MetricNameFilter;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricResult;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.apache.beam.sdk.options.ApplicationNameOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.runners.TransformHierarchy.Node;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollection.IsBounded;
import org.apache.beam.sdk.values.PValue;
import org.apache.beam.sdk.values.Row;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Linq4j;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterImpl;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.joda.time.Duration;
import org.joda.time.ReadableInstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** BeamRelNode to replace a {@code Enumerable} node. */
public class BeamEnumerableConverter extends ConverterImpl implements EnumerableRel {
  private static final Logger LOG = LoggerFactory.getLogger(BeamEnumerableConverter.class);

  public BeamEnumerableConverter(RelOptCluster cluster, RelTraitSet traits, RelNode input) {
    super(cluster, ConventionTraitDef.INSTANCE, traits, input);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new BeamEnumerableConverter(getCluster(), traitSet, sole(inputs));
  }

  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    // This should always be a last resort.
    return planner.getCostFactory().makeHugeCost();
  }

  @Override
  public Result implement(EnumerableRelImplementor implementor, Prefer prefer) {
    final BlockBuilder list = new BlockBuilder();
    final RelDataType rowType = getRowType();
    final PhysType physType =
        PhysTypeImpl.of(implementor.getTypeFactory(), rowType, prefer.preferArray());
    final Expression node = implementor.stash((BeamRelNode) getInput(), BeamRelNode.class);
    list.add(Expressions.call(BeamEnumerableConverter.class, "toEnumerable", node));
    return implementor.result(physType, list.toBlock());
  }

  public static Enumerable<Object> toEnumerable(BeamRelNode node) {
    final ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(BeamEnumerableConverter.class.getClassLoader());
      final PipelineOptions options = createPipelineOptions(node.getPipelineOptions());
      return toEnumerable(options, node);
    } finally {
      Thread.currentThread().setContextClassLoader(originalClassLoader);
    }
  }

  public static PipelineOptions createPipelineOptions(Map<String, String> map) {
    final String[] args = new String[map.size()];
    int i = 0;
    for (Map.Entry<String, String> entry : map.entrySet()) {
      args[i++] = "--" + entry.getKey() + "=" + entry.getValue();
    }
    PipelineOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().create();
    options.as(ApplicationNameOptions.class).setAppName("BeamSql");
    return options;
  }

  static Enumerable<Object> toEnumerable(PipelineOptions options, BeamRelNode node) {
    if (node instanceof BeamIOSinkRel) {
      return count(options, node);
    } else if (isLimitQuery(node)) {
      return limitCollect(options, node);
    }

    return collect(options, node);
  }

  private static PipelineResult limitRun(
      PipelineOptions options,
      BeamRelNode node,
      DoFn<Row, Void> doFn,
      Queue<Object[]> values,
      int limitCount) {
    options.as(DirectOptions.class).setBlockOnRun(false);
    Pipeline pipeline = Pipeline.create(options);
    PCollection<Row> resultCollection = BeamSqlRelUtils.toPCollection(pipeline, node);
    resultCollection.apply(ParDo.of(doFn));

    PipelineResult result = pipeline.run();

    State state;
    while (true) {
      // Check pipeline state in every second
      state = result.waitUntilFinish(Duration.standardSeconds(1));
      if (state != null && state.isTerminal()) {
        break;
      }

      try {
        if (values.size() >= limitCount) {
          result.cancel();
          break;
        }
      } catch (IOException e) {
        LOG.warn(e.toString());
        break;
      }
    }

    return result;
  }

  private static Enumerable<Object> collect(PipelineOptions options, BeamRelNode node) {
    long id = options.getOptionsId();
    Queue<Object[]> values = new ConcurrentLinkedQueue<>();

    checkArgument(
        options
            .getRunner()
            .getCanonicalName()
            .equals("org.apache.beam.runners.direct.DirectRunner"),
        "SELECT without INSERT is only supported in DirectRunner in SQL Shell.");

    Collector.globalValues.put(id, values);

    Pipeline pipeline = Pipeline.create(options);
    PCollection<Row> resultCollection = BeamSqlRelUtils.toPCollection(pipeline, node);
    resultCollection.apply(ParDo.of(new Collector()));
    PipelineResult result = pipeline.run();
    result.waitUntilFinish();

    Collector.globalValues.remove(id);

    return Linq4j.asEnumerable(unboxValues(values));
  }

  private static Enumerable<Object> limitCollect(PipelineOptions options, BeamRelNode node) {
    long id = options.getOptionsId();
    ConcurrentLinkedQueue<Object[]> values = new ConcurrentLinkedQueue<>();

    checkArgument(
        options
            .getRunner()
            .getCanonicalName()
            .equals("org.apache.beam.runners.direct.DirectRunner"),
        "SELECT without INSERT is only supported in DirectRunner in SQL Shell.");

    int limitCount = getLimitCount(node);

    Collector.globalValues.put(id, values);
    limitRun(options, node, new Collector(), values, limitCount);
    Collector.globalValues.remove(id);

    // remove extra retrieved values
    while (values.size() > limitCount) {
      values.remove();
    }

    return Linq4j.asEnumerable(unboxValues(values));
  }

  private static class Collector extends DoFn<Row, Void> {

    // This will only work on the direct runner.
    private static final Map<Long, Queue<Object[]>> globalValues = new ConcurrentHashMap<>();

    @Nullable private volatile Queue<Object[]> values;

    @StartBundle
    public void startBundle(StartBundleContext context) {
      long id = context.getPipelineOptions().getOptionsId();
      values = globalValues.get(id);
    }

    @ProcessElement
    public void processElement(ProcessContext context) {
      values.add(rowToAvatica(context.element()));
    }
  }

  private static List<Object> unboxValues(Queue<Object[]> values) {
    return values.stream()
        .map(
            objects -> {
              if (objects.length == 1) {
                // if objects.length == 1, that means input Row contains only 1 column/element,
                // then an Object instead of Object[] should be returned because of
                // CalciteResultSet's behaviour that tries to convert one column row to an Object.
                return objects[0];
              } else {
                return objects;
              }
            })
        .collect(Collectors.toList());
  }

  private static Object[] rowToAvatica(Row row) {
    Schema schema = row.getSchema();
    Object[] convertedColumns = new Object[schema.getFields().size()];
    int i = 0;
    for (Schema.Field field : schema.getFields()) {
      convertedColumns[i] = fieldToAvatica(field.getType(), row.getValue(i));
      ++i;
    }
    return convertedColumns;
  }

  private static Object fieldToAvatica(Schema.FieldType type, Object beamValue) {
    switch (type.getTypeName()) {
      case DATETIME:
        if (Arrays.equals(type.getMetadata(), CalciteUtils.TIMESTAMP.getMetadata())) {
          return ((ReadableInstant) beamValue).getMillis();
        } else if (Arrays.equals(type.getMetadata(), CalciteUtils.TIME.getMetadata())) {
          return (int) ((ReadableInstant) beamValue).getMillis();
        } else if (Arrays.equals(type.getMetadata(), CalciteUtils.DATE.getMetadata())) {
          return (int) (((ReadableInstant) beamValue).getMillis() / MILLIS_PER_DAY);
        } else {
          throw new IllegalArgumentException(
              "Unknown DateTime type " + new String(type.getMetadata(), UTF_8));
        }
      case BYTE:
      case INT16:
      case INT32:
      case INT64:
      case DECIMAL:
      case FLOAT:
      case DOUBLE:
      case STRING:
      case BOOLEAN:
        return beamValue;
      case ARRAY:
        return ((List<?>) beamValue)
            .stream()
                .map(elem -> fieldToAvatica(type.getCollectionElementType(), elem))
                .collect(Collectors.toList());
      case MAP:
        return ((Map<?, ?>) beamValue)
            .entrySet().stream()
                .collect(
                    Collectors.toMap(
                        entry -> entry.getKey(),
                        entry ->
                            fieldToAvatica(type.getCollectionElementType(), entry.getValue())));
      case ROW:
        // TODO: needs to be a Struct
        return beamValue;
      default:
        throw new IllegalStateException(
            String.format("Unreachable case for Beam typename %s", type.getTypeName()));
    }
  }

  private static Enumerable<Object> count(PipelineOptions options, BeamRelNode node) {
    Pipeline pipeline = Pipeline.create(options);
    BeamSqlRelUtils.toPCollection(pipeline, node).apply(ParDo.of(new RowCounter()));
    PipelineResult result = pipeline.run();

    long count = 0;
    if (!containsUnboundedPCollection(pipeline)) {
      result.waitUntilFinish();
      MetricQueryResults metrics =
          result
              .metrics()
              .queryMetrics(
                  MetricsFilter.builder()
                      .addNameFilter(MetricNameFilter.named(BeamEnumerableConverter.class, "rows"))
                      .build());
      Iterator<MetricResult<Long>> iterator = metrics.getCounters().iterator();
      if (iterator.hasNext()) {
        count = iterator.next().getAttempted();
      }
    }
    return Linq4j.singletonEnumerable(count);
  }

  private static class RowCounter extends DoFn<Row, Void> {
    final Counter rows = Metrics.counter(BeamEnumerableConverter.class, "rows");

    @ProcessElement
    public void processElement(ProcessContext context) {
      rows.inc();
    }
  }

  private static boolean isLimitQuery(BeamRelNode node) {
    return (node instanceof BeamSortRel && ((BeamSortRel) node).isLimitOnly())
        || (node instanceof BeamCalcRel && ((BeamCalcRel) node).isInputSortRelAndLimitOnly());
  }

  private static int getLimitCount(BeamRelNode node) {
    if (node instanceof BeamSortRel) {
      return ((BeamSortRel) node).getCount();
    } else if (node instanceof BeamCalcRel) {
      return ((BeamCalcRel) node).getLimitCountOfSortRel();
    }

    throw new RuntimeException(
        "Cannot get limit count from RelNode tree with root " + node.getRelTypeName());
  }

  private static boolean containsUnboundedPCollection(Pipeline p) {
    class BoundednessVisitor extends PipelineVisitor.Defaults {
      IsBounded boundedness = IsBounded.BOUNDED;

      @Override
      public void visitValue(PValue value, Node producer) {
        if (value instanceof PCollection) {
          boundedness = boundedness.and(((PCollection) value).isBounded());
        }
      }
    }

    BoundednessVisitor visitor = new BoundednessVisitor();
    p.traverseTopologically(visitor);
    return visitor.boundedness == IsBounded.UNBOUNDED;
  }
}
