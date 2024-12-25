/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.queryengine.execution.operator.source.relational;

import org.apache.iotdb.commons.schema.table.column.TsTableColumnCategory;
import org.apache.iotdb.db.queryengine.execution.MemoryEstimationHelper;
import org.apache.iotdb.db.queryengine.execution.aggregation.timerangeiterator.ITableTimeRangeIterator;
import org.apache.iotdb.db.queryengine.execution.operator.OperatorContext;
import org.apache.iotdb.db.queryengine.execution.operator.process.last.LastQueryUtil;
import org.apache.iotdb.db.queryengine.execution.operator.source.relational.aggregation.LastAccumulator;
import org.apache.iotdb.db.queryengine.execution.operator.source.relational.aggregation.LastByDescAccumulator;
import org.apache.iotdb.db.queryengine.execution.operator.source.relational.aggregation.LastDescAccumulator;
import org.apache.iotdb.db.queryengine.execution.operator.source.relational.aggregation.TableAggregator;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.queryengine.plan.planner.plan.parameter.SeriesScanOptions;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.ColumnSchema;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.DeviceEntry;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.QualifiedObjectName;
import org.apache.iotdb.db.queryengine.plan.relational.metadata.fetcher.cache.TableDeviceSchemaCache;

import org.apache.tsfile.block.column.ColumnBuilder;
import org.apache.tsfile.common.conf.TSFileConfig;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.TimeValuePair;
import org.apache.tsfile.read.common.block.TsBlock;
import org.apache.tsfile.utils.Binary;
import org.apache.tsfile.utils.Pair;
import org.apache.tsfile.utils.RamUsageEstimator;
import org.apache.tsfile.utils.TsPrimitiveType;
import org.apache.tsfile.write.UnSupportedDataTypeException;
import org.apache.tsfile.write.schema.IMeasurementSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.apache.iotdb.db.queryengine.execution.operator.source.relational.aggregation.Utils.serializeTimeValue;
import static org.apache.iotdb.db.queryengine.plan.relational.metadata.fetcher.cache.TableDeviceLastCache.EMPTY_PRIMITIVE_TYPE;
import static org.apache.iotdb.db.queryengine.plan.relational.type.InternalTypeManager.getTSDataType;

public class TableLastQueryOperator extends TableAggregationTableScanOperator {

  private static final long INSTANCE_SIZE =
      RamUsageEstimator.shallowSizeOfInstance(TableLastQueryOperator.class);

  private static final TableDeviceSchemaCache TABLE_DEVICE_SCHEMA_CACHE =
      TableDeviceSchemaCache.getInstance();

  private boolean finished = false;
  private int outputDeviceIndex;
  private DeviceEntry currentDeviceEntry;

  private final String dbName;
  private final int groupKeySize;
  private final boolean needUpdateCache;
  private final boolean needUpdateNullEntry;
  private int currentHitCacheIndex = 0;
  private final List<Integer> hitCachesIndexes;
  private final List<Pair<OptionalLong, TsPrimitiveType[]>> hitCachedResults;
  // indicates the index of last(time) aggregation
  private int lastTimeAggregationIdx = -1;

  public TableLastQueryOperator(
      PlanNodeId sourceId,
      OperatorContext context,
      List<ColumnSchema> aggColumnSchemas,
      int[] aggColumnsIndexArray,
      List<DeviceEntry> deviceEntries,
      SeriesScanOptions seriesScanOptions,
      List<String> measurementColumnNames,
      Set<String> allSensors,
      List<IMeasurementSchema> measurementSchemas,
      List<TableAggregator> tableAggregators,
      List<ColumnSchema> groupingKeySchemas,
      int[] groupingKeyIndex,
      ITableTimeRangeIterator tableTimeRangeIterator,
      boolean ascending,
      boolean canUseStatistics,
      List<Integer> aggregatorInputChannels,
      QualifiedObjectName qualifiedObjectName,
      List<Integer> hitCachesIndexes,
      List<Pair<OptionalLong, TsPrimitiveType[]>> hitCachedResults) {

    super(
        sourceId,
        context,
        aggColumnSchemas,
        aggColumnsIndexArray,
        deviceEntries,
        deviceEntries.size(),
        seriesScanOptions,
        measurementColumnNames,
        allSensors,
        measurementSchemas,
        tableAggregators,
        groupingKeySchemas,
        groupingKeyIndex,
        tableTimeRangeIterator,
        ascending,
        canUseStatistics,
        aggregatorInputChannels);

    this.needUpdateCache = LastQueryUtil.needUpdateCache(seriesScanOptions.getGlobalTimeFilter());
    this.needUpdateNullEntry =
        LastQueryUtil.needUpdateNullEntry(seriesScanOptions.getGlobalTimeFilter());
    this.hitCachesIndexes = hitCachesIndexes;
    this.hitCachedResults = hitCachedResults;
    this.dbName = qualifiedObjectName.getDatabaseName();
    this.groupKeySize = groupingKeySchemas == null ? 0 : groupingKeySchemas.size();

    for (int i = 0; i < tableAggregators.size(); i++) {
      if (tableAggregators.get(i).getAccumulator() instanceof LastAccumulator) {
        lastTimeAggregationIdx = i;
      }
    }
  }

  @Override
  public boolean isFinished() throws Exception {
    if (!finished) {
      finished = !hasNextWithTimer();
    }
    return finished;
  }

  @Override
  public boolean hasNext() throws Exception {
    if (retainedTsBlock != null) {
      return true;
    }

    return outputDeviceIndex < deviceEntries.size();
  }

  @Override
  public TsBlock next() throws Exception {
    long maxRuntime = operatorContext.getMaxRunTime().roundTo(TimeUnit.NANOSECONDS);
    long start = System.nanoTime();

    if (retainedTsBlock != null) {
      return getResultFromRetainedTsBlock();
    }

    while (System.nanoTime() - start < maxRuntime
        && !resultTsBlockBuilder.isFull()
        && outputDeviceIndex < deviceEntries.size()) {
      processCurrentDevice();
    }

    if (resultTsBlockBuilder.isEmpty()) {
      return null;
    }

    buildResultTsBlock();
    return checkTsBlockSizeAndGetResult();
  }

  /** Main process logic, calc the last aggregation results of current device. */
  private void processCurrentDevice() {
    currentDeviceEntry = deviceEntries.get(outputDeviceIndex);

    if (currentHitCacheIndex < hitCachesIndexes.size()
        && outputDeviceIndex == hitCachesIndexes.get(currentHitCacheIndex)) {
      appendGroupByColumns();
      Pair<OptionalLong, TsPrimitiveType[]> currentHitResult =
          hitCachedResults.get(currentHitCacheIndex);
      long lastTime = currentHitResult.getLeft().getAsLong();
      int channel = 0;
      for (int i = 0; i < tableAggregators.size(); i++) {
        TableAggregator aggregator = tableAggregators.get(i);
        ColumnBuilder columnBuilder = resultTsBlockBuilder.getColumnBuilder(groupKeySize + i);
        int columnIdx = aggregatorInputChannels.get(channel);
        ColumnSchema schema = aggColumnSchemas.get(columnIdx);
        TsTableColumnCategory category = schema.getColumnCategory();
        if (TsTableColumnCategory.ID == category) {
          String id =
              (String)
                  deviceEntries
                      .get(outputDeviceIndex)
                      .getNthSegment(aggColumnsIndexArray[columnIdx] + 1);
          if (id == null) {
            if (aggregator.getStep().isOutputPartial()) {
              columnBuilder.writeBinary(
                  new Binary(
                      serializeTimeValue(getTSDataType(schema.getType()), lastTime, true, null)));
            } else {
              columnBuilder.appendNull();
            }
          } else {
            if (aggregator.getStep().isOutputPartial()) {
              columnBuilder.writeBinary(
                  new Binary(
                      serializeTimeValue(
                          getTSDataType(schema.getType()),
                          lastTime,
                          false,
                          new TsPrimitiveType.TsBinary(
                              new Binary(id, TSFileConfig.STRING_CHARSET)))));
            } else {
              columnBuilder.writeBinary(new Binary(id, TSFileConfig.STRING_CHARSET));
            }
          }
        } else if (TsTableColumnCategory.ATTRIBUTE == category) {
          Binary attribute =
              deviceEntries
                  .get(outputDeviceIndex)
                  .getAttributeColumnValues()
                  .get(aggColumnsIndexArray[columnIdx]);
          if (attribute == null) {
            if (aggregator.getStep().isOutputPartial()) {
              columnBuilder.writeBinary(
                  new Binary(
                      serializeTimeValue(getTSDataType(schema.getType()), lastTime, true, null)));
            } else {
              columnBuilder.appendNull();
            }
          } else {
            if (aggregator.getStep().isOutputPartial()) {
              columnBuilder.writeBinary(
                  new Binary(
                      serializeTimeValue(
                          getTSDataType(schema.getType()),
                          lastTime,
                          false,
                          new TsPrimitiveType.TsBinary(attribute))));
            } else {
              columnBuilder.writeBinary(attribute);
            }
          }
        } else if (TsTableColumnCategory.TIME == category) {

          if (aggregator.getAccumulator() instanceof LastDescAccumulator) {
            // for last(time) aggregation

            if (aggregator.getStep().isOutputPartial()) {
              columnBuilder.writeBinary(
                  new Binary(
                      serializeTimeValue(
                          getTSDataType(schema.getType()),
                          lastTime,
                          new TsPrimitiveType.TsLong(lastTime))));
            } else {
              columnBuilder.writeTsPrimitiveType(new TsPrimitiveType.TsLong(lastTime));
            }
          } else {
            // for last_by(time,time) aggregation
            if (aggregator.getStep().isOutputPartial()) {
              columnBuilder.writeBinary(
                  new Binary(
                      serializeTimeValue(
                          getTSDataType(schema.getType()),
                          lastTime,
                          false,
                          new TsPrimitiveType.TsLong(lastTime))));
            } else {
              columnBuilder.writeTsPrimitiveType(new TsPrimitiveType.TsLong(lastTime));
            }
          }
        } else {
          int measurementIdx = aggColumnsIndexArray[aggregatorInputChannels.get(channel)];
          TsPrimitiveType tsPrimitiveType =
              hitCachedResults.get(currentHitCacheIndex).getRight()[measurementIdx];
          long lastByTime = hitCachedResults.get(currentHitCacheIndex).getLeft().getAsLong();
          if (tsPrimitiveType == EMPTY_PRIMITIVE_TYPE) {
            // there is no data for this time series
            if (aggregator.getStep().isOutputPartial()) {
              columnBuilder.writeBinary(
                  new Binary(
                      serializeTimeValue(getTSDataType(schema.getType()), lastByTime, true, null)));
            } else {
              columnBuilder.appendNull();
            }
          } else {
            if (aggregator.getStep().isOutputPartial()) {
              columnBuilder.writeBinary(
                  new Binary(
                      serializeTimeValue(
                          getTSDataType(schema.getType()), lastByTime, false, tsPrimitiveType)));
            } else {
              columnBuilder.writeTsPrimitiveType(tsPrimitiveType);
            }
          }
        }
        channel += aggregator.getChannelCount();
      }

      resultTsBlockBuilder.declarePosition();
      outputDeviceIndex++;
      currentHitCacheIndex++;
      return;
    }

    if (calculateAggregationResultForCurrentTimeRange()) {
      if (!needUpdateCache) {
        appendGroupByColumns();
        outputDeviceIndex++;
        resetTableAggregators();
        // resultTsBlockBuilder.declarePosition();
        return;
      }

      int channel = 0;
      List<String> updateMeasurementList = new ArrayList<>();
      List<TimeValuePair> updateTimeValuePairList = new ArrayList<>();
      boolean hasSetLastTime = false;
      for (int i = 0; i < tableAggregators.size(); i++) {
        TableAggregator tableAggregator = tableAggregators.get(i);
        ColumnSchema schema = aggColumnSchemas.get(aggregatorInputChannels.get(channel));

        switch (schema.getColumnCategory()) {
          case TIME:
            if (!hasSetLastTime) {
              hasSetLastTime = true;
              if (i == lastTimeAggregationIdx) {
                LastDescAccumulator lastAccumulator =
                    (LastDescAccumulator) tableAggregator.getAccumulator();
                if (lastAccumulator.hasInitResult()) {
                  updateMeasurementList.add("");
                  updateTimeValuePairList.add(
                      new TimeValuePair(
                          lastAccumulator.getMaxTime(),
                          new TsPrimitiveType.TsLong(lastAccumulator.getMaxTime())));
                }
              } else {
                LastByDescAccumulator lastByAccumulator =
                    (LastByDescAccumulator) tableAggregator.getAccumulator();
                if (lastByAccumulator.hasInitResult() && !lastByAccumulator.isXNull()) {
                  updateMeasurementList.add("");
                  updateTimeValuePairList.add(
                      new TimeValuePair(
                          lastByAccumulator.getLastTimeOfY(),
                          new TsPrimitiveType.TsLong(lastByAccumulator.getLastTimeOfY())));
                }
              }
            }
            break;
          case MEASUREMENT:
            LastByDescAccumulator lastByAccumulator =
                (LastByDescAccumulator) tableAggregator.getAccumulator();
            // only can update LastCache when last_by return non-null value
            if (lastByAccumulator.hasInitResult() && !lastByAccumulator.isXNull()) {
              long lastByTime = lastByAccumulator.getLastTimeOfY();

              if (!hasSetLastTime) {
                hasSetLastTime = true;
                updateMeasurementList.add("");
                updateTimeValuePairList.add(
                    new TimeValuePair(lastByTime, new TsPrimitiveType.TsLong(lastByTime)));
              }

              updateMeasurementList.add(schema.getName());
              updateTimeValuePairList.add(
                  new TimeValuePair(
                      lastByTime, cloneTsPrimitiveType(lastByAccumulator.getXResult())));
            }
            break;
          default:
            break;
        }

        channel += tableAggregator.getChannelCount();
      }

      appendGroupByColumns();
      outputDeviceIndex++;
      resetTableAggregators();
      // resultTsBlockBuilder.declarePosition();

      if (!updateMeasurementList.isEmpty()) {
        String[] updateMeasurementArray = updateMeasurementList.toArray(new String[0]);
        TimeValuePair[] updateTimeValuePairArray =
            updateTimeValuePairList.toArray(new TimeValuePair[0]);

        TABLE_DEVICE_SCHEMA_CACHE.initOrInvalidateLastCache(
            dbName, currentDeviceEntry.getDeviceID(), updateMeasurementArray, false);
        TABLE_DEVICE_SCHEMA_CACHE.updateLastCacheIfExists(
            dbName,
            currentDeviceEntry.getDeviceID(),
            updateMeasurementArray,
            updateTimeValuePairArray);
      }
    }
  }

  @Override
  protected void updateResultTsBlock() {
    appendAggregationResult();
    // after appendAggregationResult invoked, aggregators must be cleared
    // resetTableAggregators();
  }

  /** Append a row of aggregation results to the result tsBlock. */
  public void appendAggregationResult() {

    // no data in current time range, just output empty
    if (!timeIterator.hasCachedTimeRange()) {
      return;
    }

    ColumnBuilder[] columnBuilders = resultTsBlockBuilder.getValueColumnBuilders();

    for (int i = 0; i < tableAggregators.size(); i++) {
      tableAggregators.get(i).evaluate(columnBuilders[groupKeySize + i]);
    }

    resultTsBlockBuilder.declarePosition();
  }

  private void appendGroupByColumns() {
    ColumnBuilder[] columnBuilders = resultTsBlockBuilder.getValueColumnBuilders();

    if (groupingKeyIndex != null) {
      for (int i = 0; i < groupKeySize; i++) {
        if (TsTableColumnCategory.ID == groupingKeySchemas.get(i).getColumnCategory()) {
          String id =
              (String) deviceEntries.get(outputDeviceIndex).getNthSegment(groupingKeyIndex[i] + 1);
          if (id == null) {
            columnBuilders[i].appendNull();
          } else {
            columnBuilders[i].writeBinary(new Binary(id, TSFileConfig.STRING_CHARSET));
          }
        } else {
          Binary attribute =
              deviceEntries
                  .get(outputDeviceIndex)
                  .getAttributeColumnValues()
                  .get(groupingKeyIndex[i]);
          if (attribute == null) {
            columnBuilders[i].appendNull();
          } else {
            columnBuilders[i].writeBinary(attribute);
          }
        }
      }
    }
  }

  private TsPrimitiveType cloneTsPrimitiveType(TsPrimitiveType originalValue) {
    switch (originalValue.getDataType()) {
      case BOOLEAN:
        return new TsPrimitiveType.TsBoolean(originalValue.getBoolean());
      case INT32:
      case DATE:
        return new TsPrimitiveType.TsInt(originalValue.getInt());
      case INT64:
      case TIMESTAMP:
        return new TsPrimitiveType.TsLong(originalValue.getLong());
      case FLOAT:
        return new TsPrimitiveType.TsFloat(originalValue.getFloat());
      case DOUBLE:
        return new TsPrimitiveType.TsDouble(originalValue.getDouble());
      case TEXT:
      case BLOB:
      case STRING:
        return new TsPrimitiveType.TsBinary(originalValue.getBinary());
      case VECTOR:
        return new TsPrimitiveType.TsVector(originalValue.getVector());
      default:
        throw new UnSupportedDataTypeException(
            "Unsupported data type:" + originalValue.getDataType());
    }
  }

  @Override
  public List<TSDataType> getResultDataTypes() {
    List<TSDataType> resultDataTypes = new ArrayList<>(groupKeySize + tableAggregators.size());

    if (groupingKeySchemas != null) {
      for (int i = 0; i < groupingKeySchemas.size(); i++) {
        resultDataTypes.add(TSDataType.STRING);
      }
    }

    for (TableAggregator aggregator : tableAggregators) {
      resultDataTypes.add(aggregator.getType());
    }

    return resultDataTypes;
  }

  //  @Override
  //  public void initQueryDataSource(IQueryDataSource dataSource) {
  //    this.queryDataSource = (QueryDataSource) dataSource;
  //    this.seriesScanUtil.initQueryDataSource(queryDataSource);
  //    this.resultTsBlockBuilder = new TsBlockBuilder(getResultDataTypes());
  //  }

  @Override
  public long ramBytesUsed() {
    return INSTANCE_SIZE
        + MemoryEstimationHelper.getEstimatedSizeOfAccountableObject(seriesScanUtil)
        + MemoryEstimationHelper.getEstimatedSizeOfAccountableObject(operatorContext)
        + MemoryEstimationHelper.getEstimatedSizeOfAccountableObject(sourceId)
        + (resultTsBlockBuilder == null ? 0 : resultTsBlockBuilder.getRetainedSizeInBytes())
        + RamUsageEstimator.sizeOfCollection(deviceEntries);
    // TODO
  }
}
