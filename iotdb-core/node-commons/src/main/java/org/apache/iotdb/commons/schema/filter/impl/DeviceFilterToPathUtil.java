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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.commons.schema.filter.impl;

import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.commons.schema.filter.SchemaFilter;
import org.apache.iotdb.commons.schema.filter.SchemaFilterType;
import org.apache.iotdb.commons.schema.table.TsTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.iotdb.commons.conf.IoTDBConstant.ONE_LEVEL_PATH_WILDCARD;
import static org.apache.iotdb.commons.conf.IoTDBConstant.PATH_ROOT;

public class DeviceFilterToPathUtil {

  private DeviceFilterToPathUtil() {
    // do nothing
  }

  public static List<PartialPath> convertToDevicePattern(
      String database, String tableName, TsTable table, List<SchemaFilter> idDeterminedFilterList) {
    int length = table.getIdNums() + 3;
    String[] nodes = new String[length];
    Arrays.fill(nodes, ONE_LEVEL_PATH_WILDCARD);
    nodes[0] = PATH_ROOT;
    nodes[1] = database;
    nodes[2] = tableName;
    Map<Integer, List<String>> orValueMap = new HashMap<>();
    for (SchemaFilter schemaFilter : idDeterminedFilterList) {
      if (schemaFilter.getSchemaFilterType().equals(SchemaFilterType.DEVICE_ID)) {
        DeviceIdFilter deviceIdFilter = (DeviceIdFilter) schemaFilter;
        nodes[deviceIdFilter.getIndex() + 3] = deviceIdFilter.getValue();
      } else if (schemaFilter.getSchemaFilterType().equals(SchemaFilterType.OR)) {
        OrFilter orFilter = (OrFilter) schemaFilter;
        if (orFilter.getLeft().getSchemaFilterType().equals(SchemaFilterType.DEVICE_ID)
            && orFilter.getRight().getSchemaFilterType().equals(SchemaFilterType.DEVICE_ID)) {
          // todo nested orFilter
          DeviceIdFilter deviceIdFilter = (DeviceIdFilter) orFilter.getLeft();
          nodes[deviceIdFilter.getIndex() + 3] = deviceIdFilter.getValue();
          deviceIdFilter = (DeviceIdFilter) orFilter.getLeft();
          orValueMap
              .computeIfAbsent(deviceIdFilter.getIndex(), k -> new ArrayList<>())
              .add(deviceIdFilter.getValue());
        }
      }
    }

    PartialPath path = new PartialPath(nodes);
    List<PartialPath> pathList = new ArrayList<>();
    pathList.add(path);
    for (Map.Entry<Integer, List<String>> entry : orValueMap.entrySet()) {
      for (int i = 0, size = pathList.size(); i < size; i++) {
        for (String value : entry.getValue()) {
          nodes = Arrays.copyOf(pathList.get(i).getNodes(), length);
          nodes[entry.getKey() + 3] = value;
          path = new PartialPath(nodes);
          pathList.add(path);
        }
      }
    }

    return pathList;
  }

  // if the element in idDeterminedFilterList isEmpty, the corresponding pattern is
  // root.db.table.*.*..
  public static List<PartialPath> convertToDevicePattern(
      String database, TsTable table, List<List<SchemaFilter>> idDeterminedFilterList) {
    List<PartialPath> pathList = new ArrayList<>();
    int length = table.getIdNums() + 3;
    for (List<SchemaFilter> idFilterList : idDeterminedFilterList) {
      String[] nodes = new String[length];
      Arrays.fill(nodes, ONE_LEVEL_PATH_WILDCARD);
      nodes[0] = PATH_ROOT;
      nodes[1] = database;
      nodes[2] = table.getTableName();
      for (SchemaFilter schemaFilter : idFilterList) {
        if (schemaFilter.getSchemaFilterType().equals(SchemaFilterType.DEVICE_ID)) {
          DeviceIdFilter deviceIdFilter = (DeviceIdFilter) schemaFilter;
          nodes[deviceIdFilter.getIndex() + 3] = deviceIdFilter.getValue();
        } else {
          throw new IllegalStateException("Input single filter must be DeviceIdFilter");
        }
      }
      pathList.add(new PartialPath(nodes));
    }

    return pathList;
  }

  public static List<PartialPath> convertToDevicePath(
      String database, String tableName, List<String[]> deviceIdList) {
    List<PartialPath> devicePathList = new ArrayList<>(deviceIdList.size());
    String[] nodes;
    for (String[] idValues : deviceIdList) {
      nodes = new String[idValues.length + 3];
      nodes[0] = PATH_ROOT;
      nodes[1] = database;
      nodes[2] = tableName;
      System.arraycopy(idValues, 0, nodes, 3, idValues.length);
      devicePathList.add(new PartialPath(nodes));
    }
    return devicePathList;
  }

  // input and-concat filter list
  // return or concat filter list, inner which all filter is and concat
  public static List<List<SchemaFilter>> convertSchemaFilterToOrConcatList(
      List<SchemaFilter> schemaFilterList) {
    List<List<SchemaFilter>> orConcatList =
        schemaFilterList.stream()
            .map(DeviceFilterToPathUtil::convertOneSchemaFilterToOrConcat)
            .collect(Collectors.toList());
    int orSize = orConcatList.size();
    int finalResultSize = 1;
    for (List<SchemaFilter> filterList : orConcatList) {
      finalResultSize *= filterList.size();
    }
    List<List<SchemaFilter>> result = new ArrayList<>(finalResultSize);
    int[] indexes = new int[orSize];
    while (finalResultSize > 0) {
      List<SchemaFilter> oneCase = new ArrayList<>(orConcatList.size());
      for (int j = 0; j < orSize; j++) {
        oneCase.add(orConcatList.get(j).get(indexes[j]));
      }
      result.add(oneCase);
      for (int k = orSize - 1; k >= 0; k--) {
        indexes[k]++;
        if (indexes[k] < orConcatList.get(k).size()) {
          break;
        }
        indexes[k] = 0;
      }
      finalResultSize--;
    }
    return result;
  }

  private static List<SchemaFilter> convertOneSchemaFilterToOrConcat(SchemaFilter schemaFilter) {
    List<SchemaFilter> result = new ArrayList<>();
    if (schemaFilter.getSchemaFilterType().equals(SchemaFilterType.OR)) {
      OrFilter orFilter = (OrFilter) schemaFilter;
      result.addAll(convertOneSchemaFilterToOrConcat(orFilter.getLeft()));
      result.addAll(convertOneSchemaFilterToOrConcat(orFilter.getRight()));
    } else if (schemaFilter.getSchemaFilterType().equals(SchemaFilterType.AND)) {
      throw new IllegalStateException("Input filter shall not be AND operation");
    } else {
      result.add(schemaFilter);
    }
    return result;
  }
}