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

import static org.apache.beam.sdk.schemas.Schema.FieldType;
import static org.apache.beam.sdk.schemas.Schema.TypeName;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.calcite.avatica.util.DateTimeUtils.MILLIS_PER_DAY;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.beam.sdk.extensions.sql.impl.planner.BeamJavaTypeFactory;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils.CharType;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils.DateType;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils.TimeType;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils.TimeWithLocalTzType;
import org.apache.beam.sdk.extensions.sql.impl.utils.CalciteUtils.TimestampWithLocalTzType;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.ImmutableMap;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.collect.Maps;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.avatica.util.ByteString;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.GotoExpressionKind;
import org.apache.calcite.linq4j.tree.MemberDeclaration;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexSimplify;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.util.BuiltInMethod;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.ScriptEvaluator;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.ReadableInstant;

/** BeamRelNode to replace a {@code Project} node. */
public class BeamCalcRel extends Calc implements BeamRelNode {

  private static final ParameterExpression outputSchemaParam =
      Expressions.parameter(Schema.class, "outputSchema");
  private static final ParameterExpression processContextParam =
      Expressions.parameter(DoFn.ProcessContext.class, "c");

  public BeamCalcRel(RelOptCluster cluster, RelTraitSet traits, RelNode input, RexProgram program) {
    super(cluster, traits, input, program);
  }

  @Override
  public Calc copy(RelTraitSet traitSet, RelNode input, RexProgram program) {
    return new BeamCalcRel(getCluster(), traitSet, input, program);
  }

  @Override
  public PTransform<PCollectionList<Row>, PCollection<Row>> buildPTransform() {
    return new Transform();
  }

  private class Transform extends PTransform<PCollectionList<Row>, PCollection<Row>> {

    /**
     * expand is based on calcite's EnumerableCalc.implement(). This function generates java code
     * executed in the processElement in CalcFn using Calcite's linq4j library. It generates a block
     * of code using a BlockBuilder. The root of the block is an if statement with any conditions.
     * Inside that if statement, a new record is output with a row containing transformed fields.
     * The InputGetterImpl class generates code to read from the input record and convert to Calcite
     * types. Calcite then generates code for any function calls or other operations. Then the
     * castOutput method generates code to convert back to Beam Schema types.
     */
    @Override
    public PCollection<Row> expand(PCollectionList<Row> pinput) {
      checkArgument(
          pinput.size() == 1,
          "Wrong number of inputs for %s: %s",
          BeamCalcRel.class.getSimpleName(),
          pinput);
      PCollection<Row> upstream = pinput.get(0);
      Schema outputSchema = CalciteUtils.toSchema(getRowType());

      final SqlConformance conformance = SqlConformanceEnum.MYSQL_5;
      final JavaTypeFactory typeFactory = BeamJavaTypeFactory.INSTANCE;
      final BlockBuilder builder = new BlockBuilder();

      final PhysType physType =
          PhysTypeImpl.of(typeFactory, getRowType(), JavaRowFormat.ARRAY, false);

      Expression input =
          Expressions.convert_(Expressions.call(processContextParam, "element"), Row.class);

      final RexBuilder rexBuilder = getCluster().getRexBuilder();
      final RelMetadataQuery mq = RelMetadataQuery.instance();
      final RelOptPredicateList predicates = mq.getPulledUpPredicates(getInput());
      final RexSimplify simplify = new RexSimplify(rexBuilder, predicates, false, RexUtil.EXECUTOR);
      final RexProgram program = BeamCalcRel.this.program.normalize(rexBuilder, simplify);

      Expression condition =
          RexToLixTranslator.translateCondition(
              program,
              typeFactory,
              builder,
              new InputGetterImpl(input, upstream.getSchema()),
              null,
              conformance);

      List<Expression> expressions =
          RexToLixTranslator.translateProjects(
              program,
              typeFactory,
              conformance,
              builder,
              physType,
              DataContext.ROOT,
              new InputGetterImpl(input, upstream.getSchema()),
              null);

      // Expressions.call is equivalent to: output = Row.withSchema(outputSchema)
      Expression output = Expressions.call(Row.class, "withSchema", outputSchemaParam);
      Method addValue = Types.lookupMethod(Row.Builder.class, "addValue", Object.class);

      for (int index = 0; index < expressions.size(); index++) {
        Expression value = expressions.get(index);
        FieldType toType = outputSchema.getField(index).getType();

        // Expressions.call is equivalent to: .addValue(value)
        output = Expressions.call(output, addValue, castOutput(value, toType));
      }

      // Expressions.call is equivalent to: .build();
      output = Expressions.call(output, "build");

      builder.add(
          // Expressions.ifThen is equivalent to:
          //   if (condition) {
          //     c.output(output);
          //   }
          Expressions.ifThen(
              condition,
              Expressions.makeGoto(
                  GotoExpressionKind.Sequence,
                  null,
                  Expressions.call(
                      processContextParam,
                      Types.lookupMethod(DoFn.ProcessContext.class, "output", Object.class),
                      output))));

      CalcFn calcFn = new CalcFn(builder.toBlock().toString(), outputSchema);

      // validate generated code
      calcFn.compile();

      PCollection<Row> projectStream = upstream.apply(ParDo.of(calcFn)).setRowSchema(outputSchema);

      return projectStream;
    }
  }

  public int getLimitCountOfSortRel() {
    if (input instanceof BeamSortRel) {
      return ((BeamSortRel) input).getCount();
    }

    throw new RuntimeException("Could not get the limit count from a non BeamSortRel input.");
  }

  public boolean isInputSortRelAndLimitOnly() {
    return (input instanceof BeamSortRel) && ((BeamSortRel) input).isLimitOnly();
  }

  /** {@code CalcFn} is the executor for a {@link BeamCalcRel} step. */
  private static class CalcFn extends DoFn<Row, Row> {
    private final String processElementBlock;
    private final Schema outputSchema;
    private transient @Nullable ScriptEvaluator se = null;

    public CalcFn(String processElementBlock, Schema outputSchema) {
      this.processElementBlock = processElementBlock;
      this.outputSchema = outputSchema;
    }

    ScriptEvaluator compile() {
      ScriptEvaluator se = new ScriptEvaluator();
      se.setParameters(
          new String[] {outputSchemaParam.name, processContextParam.name, DataContext.ROOT.name},
          new Class[] {
            (Class) outputSchemaParam.getType(),
            (Class) processContextParam.getType(),
            (Class) DataContext.ROOT.getType()
          });
      try {
        se.cook(processElementBlock);
      } catch (CompileException e) {
        throw new RuntimeException("Could not compile CalcFn: " + processElementBlock, e);
      }
      return se;
    }

    @Setup
    public void setup() {
      this.se = compile();
    }

    @ProcessElement
    public void processElement(ProcessContext c) {
      assert se != null;
      try {
        se.evaluate(new Object[] {outputSchema, c, CONTEXT_INSTANCE});
      } catch (InvocationTargetException e) {
        throw new RuntimeException(
            "CalcFn failed to evaluate: " + processElementBlock, e.getCause());
      }
    }
  }

  private static final Map<TypeName, Type> rawTypeMap =
      ImmutableMap.<TypeName, Type>builder()
          .put(TypeName.BYTE, Byte.class)
          .put(TypeName.INT16, Short.class)
          .put(TypeName.INT32, Integer.class)
          .put(TypeName.INT64, Long.class)
          .put(TypeName.FLOAT, Float.class)
          .put(TypeName.DOUBLE, Double.class)
          .build();

  private Expression castOutput(Expression value, FieldType toType) {
    if (value.getType() == Object.class || !(value.getType() instanceof Class)) {
      // fast copy path, just pass object through
      return value;
    } else if (CalciteUtils.isDateTimeType(toType)
        && !Types.isAssignableFrom(ReadableInstant.class, (Class) value.getType())) {
      return castOutputTime(value, toType);

    } else if (toType.getTypeName() == TypeName.DECIMAL
        && !Types.isAssignableFrom(BigDecimal.class, (Class) value.getType())) {
      return Expressions.new_(BigDecimal.class, value);
    } else if (toType.getTypeName() == TypeName.BYTES
        && Types.isAssignableFrom(ByteString.class, (Class) value.getType())) {

      return Expressions.condition(
          Expressions.equal(value, Expressions.constant(null)),
          Expressions.constant(null),
          Expressions.call(value, "getBytes"));
    } else if (((Class) value.getType()).isPrimitive()
        || Types.isAssignableFrom(Number.class, (Class) value.getType())) {
      Type rawType = rawTypeMap.get(toType.getTypeName());
      if (rawType != null) {
        return Types.castIfNecessary(rawType, value);
      }
    }
    return value;
  }

  private Expression castOutputTime(Expression value, FieldType toType) {
    Expression valueDateTime = value;

    // First, convert to millis
    if (CalciteUtils.TIMESTAMP.typesEqual(toType)) {
      if (value.getType() == java.sql.Timestamp.class) {
        valueDateTime = Expressions.call(BuiltInMethod.TIMESTAMP_TO_LONG.method, valueDateTime);
      }
    } else if (CalciteUtils.TIME.typesEqual(toType)) {
      if (value.getType() == java.sql.Time.class) {
        valueDateTime = Expressions.call(BuiltInMethod.TIME_TO_INT.method, valueDateTime);
      }
    } else if (CalciteUtils.DATE.typesEqual(toType)) {
      if (value.getType() == java.sql.Date.class) {
        valueDateTime = Expressions.call(BuiltInMethod.DATE_TO_INT.method, valueDateTime);
      }
      valueDateTime = Expressions.multiply(valueDateTime, Expressions.constant(MILLIS_PER_DAY));
    } else {
      throw new IllegalArgumentException("Unknown DateTime type " + toType);
    }

    // Second, convert to joda DateTime
    valueDateTime =
        Expressions.new_(
            DateTime.class,
            valueDateTime,
            Expressions.parameter(DateTimeZone.class, "org.joda.time.DateTimeZone.UTC"));

    // Third, make conversion conditional on non-null input.
    if (!((Class) value.getType()).isPrimitive()) {
      valueDateTime =
          Expressions.condition(
              Expressions.equal(value, Expressions.constant(null)),
              Expressions.constant(null),
              valueDateTime);
    }

    return valueDateTime;
  }

  private static class InputGetterImpl implements RexToLixTranslator.InputGetter {
    private static final Map<TypeName, String> TYPE_GETTER_MAP =
        ImmutableMap.<TypeName, String>builder()
            .put(TypeName.BYTE, "getByte")
            .put(TypeName.BYTES, "getBytes")
            .put(TypeName.INT16, "getInt16")
            .put(TypeName.INT32, "getInt32")
            .put(TypeName.INT64, "getInt64")
            .put(TypeName.DECIMAL, "getDecimal")
            .put(TypeName.FLOAT, "getFloat")
            .put(TypeName.DOUBLE, "getDouble")
            .put(TypeName.STRING, "getString")
            .put(TypeName.DATETIME, "getDateTime")
            .put(TypeName.BOOLEAN, "getBoolean")
            .put(TypeName.MAP, "getMap")
            .put(TypeName.ARRAY, "getArray")
            .put(TypeName.ROW, "getRow")
            .build();

    private static final Map<String, String> LOGICAL_TYPE_GETTER_MAP =
        ImmutableMap.<String, String>builder()
            .put(DateType.IDENTIFIER, "getDateTime")
            .put(TimeType.IDENTIFIER, "getDateTime")
            .put(TimeWithLocalTzType.IDENTIFIER, "getDateTime")
            .put(TimestampWithLocalTzType.IDENTIFIER, "getDateTime")
            .put(CharType.IDENTIFIER, "getString")
            .build();

    private final Expression input;
    private final Schema inputSchema;

    private InputGetterImpl(Expression input, Schema inputSchema) {
      this.input = input;
      this.inputSchema = inputSchema;
    }

    @Override
    public Expression field(BlockBuilder list, int index, Type storageType) {
      return value(list, index, storageType, input, inputSchema);
    }

    private static Expression value(
        BlockBuilder list, int index, Type storageType, Expression input, Schema schema) {
      if (index >= schema.getFieldCount() || index < 0) {
        throw new IllegalArgumentException("Unable to find value #" + index);
      }

      final Expression expression = list.append(list.newName("current"), input);
      if (storageType == Object.class) {
        return Expressions.convert_(
            Expressions.call(expression, "getValue", Expressions.constant(index)), Object.class);
      }
      FieldType fromType = schema.getField(index).getType();
      String getter;
      if (fromType.getTypeName().isLogicalType()) {
        getter = LOGICAL_TYPE_GETTER_MAP.get(fromType.getLogicalType().getIdentifier());
      } else {
        getter = TYPE_GETTER_MAP.get(fromType.getTypeName());
      }
      if (getter == null) {
        throw new IllegalArgumentException("Unable to get " + fromType.getTypeName());
      }

      Expression value = Expressions.call(expression, getter, Expressions.constant(index));

      return value(value, fromType);
    }

    private static Expression value(Expression value, Schema.FieldType type) {
      if (type.getTypeName().isLogicalType()) {
        Expression millisField = Expressions.call(value, "getMillis");
        String logicalId = type.getLogicalType().getIdentifier();
        if (logicalId.equals(TimeType.IDENTIFIER)) {
          return nullOr(value, Expressions.convert_(millisField, int.class));
        } else if (logicalId.equals(DateType.IDENTIFIER)) {
          value =
              nullOr(
                  value,
                  Expressions.convert_(
                      Expressions.divide(millisField, Expressions.constant(MILLIS_PER_DAY)),
                      int.class));
        } else if (!logicalId.equals(CharType.IDENTIFIER)) {
          throw new IllegalArgumentException(
              "Unknown LogicalType " + type.getLogicalType().getIdentifier());
        }
      } else if (type.getTypeName().isMapType()) {
        return nullOr(value, map(value, type.getMapValueType()));
      } else if (CalciteUtils.isDateTimeType(type)) {
        return nullOr(value, Expressions.call(value, "getMillis"));
      } else if (type.getTypeName().isCompositeType()) {
        return nullOr(value, row(value, type.getRowSchema()));
      } else if (type.getTypeName().isCollectionType()) {
        return nullOr(value, list(value, type.getCollectionElementType()));
      } else if (type.getTypeName() == TypeName.BYTES) {
        return nullOr(
            value, Expressions.new_(ByteString.class, Types.castIfNecessary(byte[].class, value)));
      }

      return value;
    }

    private static Expression list(Expression input, FieldType elementType) {
      ParameterExpression value = Expressions.parameter(Object.class);

      BlockBuilder block = new BlockBuilder();
      block.add(value(value, elementType));

      return Expressions.new_(
          WrappedList.class,
          ImmutableList.of(Types.castIfNecessary(List.class, input)),
          ImmutableList.<MemberDeclaration>of(
              Expressions.methodDecl(
                  Modifier.PUBLIC,
                  Object.class,
                  "value",
                  ImmutableList.of(value),
                  block.toBlock())));
    }

    private static Expression map(Expression input, FieldType mapValueType) {
      ParameterExpression value = Expressions.parameter(Object.class);

      BlockBuilder block = new BlockBuilder();
      block.add(value(value, mapValueType));

      return Expressions.new_(
          WrappedMap.class,
          ImmutableList.of(Types.castIfNecessary(Map.class, input)),
          ImmutableList.<MemberDeclaration>of(
              Expressions.methodDecl(
                  Modifier.PUBLIC,
                  Object.class,
                  "value",
                  ImmutableList.of(value),
                  block.toBlock())));
    }

    private static Expression row(Expression input, Schema schema) {
      ParameterExpression row = Expressions.parameter(Row.class);
      ParameterExpression index = Expressions.parameter(int.class);
      BlockBuilder body = new BlockBuilder(/* optimizing= */ false);

      for (int i = 0; i < schema.getFieldCount(); i++) {
        BlockBuilder list = new BlockBuilder(/* optimizing= */ false, body);
        Expression returnValue = value(list, i, /* storageType= */ null, row, schema);

        list.append(returnValue);

        body.append(
            "if i=" + i,
            Expressions.block(
                Expressions.ifThen(
                    Expressions.equal(index, Expressions.constant(i, int.class)), list.toBlock())));
      }

      body.add(Expressions.throw_(Expressions.new_(IndexOutOfBoundsException.class)));

      return Expressions.new_(
          WrappedRow.class,
          ImmutableList.of(Types.castIfNecessary(Row.class, input)),
          ImmutableList.<MemberDeclaration>of(
              Expressions.methodDecl(
                  Modifier.PUBLIC,
                  Object.class,
                  "field",
                  ImmutableList.of(row, index),
                  body.toBlock())));
    }
  }

  private static Expression nullOr(Expression field, Expression ifNotNull) {
    return Expressions.condition(
        Expressions.equal(field, Expressions.constant(null)),
        Expressions.constant(null),
        Expressions.box(ifNotNull));
  }

  private static final DataContext CONTEXT_INSTANCE = new SlimDataContext();

  private static class SlimDataContext implements DataContext {
    @Override
    public SchemaPlus getRootSchema() {
      return null;
    }

    @Override
    public JavaTypeFactory getTypeFactory() {
      return null;
    }

    @Override
    public QueryProvider getQueryProvider() {
      return null;
    }

    /* DataContext.get is used to fetch "global" state inside the generated code */
    @Override
    public Object get(String name) {
      if (name.equals(DataContext.Variable.UTC_TIMESTAMP.camelName)
          || name.equals(DataContext.Variable.CURRENT_TIMESTAMP.camelName)
          || name.equals(DataContext.Variable.LOCAL_TIMESTAMP.camelName)) {
        return System.currentTimeMillis();
      }
      return null;
    }
  }

  /** WrappedRow translates {@code Row} on access. */
  public abstract static class WrappedRow extends AbstractList<Object> {
    private final Row row;

    protected WrappedRow(Row row) {
      this.row = row;
    }

    @Override
    public Object get(int index) {
      return field(row, index);
    }

    // we could override get(int index) if we knew how to access `this.row` in linq4j
    // for now we keep it consistent with WrappedList
    protected abstract Object field(Row row, int index);

    @Override
    public int size() {
      return row.getFieldCount();
    }
  }

  /** WrappedMap translates {@code Map} on access. */
  public abstract static class WrappedMap<V> extends AbstractMap<Object, V> {
    private final Map<Object, Object> map;

    protected WrappedMap(Map<Object, Object> map) {
      this.map = map;
    }

    // TODO transform keys, in this case, we need to do lookup, so it should be both ways:
    //
    // public abstract Object fromKey(K key)
    // public abstract K toKey(Object key)

    @Override
    public Set<Entry<Object, V>> entrySet() {
      return Maps.transformValues(map, val -> (val == null) ? null : value(val)).entrySet();
    }

    @Override
    public V get(Object key) {
      return value(map.get(key));
    }

    protected abstract V value(Object value);
  }

  /** WrappedList translates {@code List} on access. */
  public abstract static class WrappedList<T> extends AbstractList<T> {
    private final List<Object> values;

    protected WrappedList(List<Object> values) {
      this.values = values;
    }

    @Override
    public T get(int index) {
      return value(values.get(index));
    }

    protected abstract T value(Object value);

    @Override
    public int size() {
      return values.size();
    }
  }
}
