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

package org.apache.iotdb.commons.udf.access;

import org.apache.iotdb.commons.udf.utils.UDFDataTypeTransformer;
import org.apache.iotdb.udf.api.relational.access.Record;
import org.apache.iotdb.udf.api.type.Binary;
import org.apache.iotdb.udf.api.type.Type;

import org.apache.tsfile.block.column.Column;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public class RecordIterator implements Iterator<Record> {

  private final List<Column> childrenColumns;
  private final int positionCount;
  private int currentIndex;

  public RecordIterator(List<Column> childrenColumns, int positionCount) {
    this.childrenColumns = childrenColumns;
    this.positionCount = positionCount;
  }

  @Override
  public boolean hasNext() {
    return currentIndex < positionCount;
  }

  @Override
  public Record next() {
    final int index = currentIndex++;
    return new Record() {
      @Override
      public int getInt(int columnIndex) throws IOException {
        return childrenColumns.get(columnIndex).getInt(index);
      }

      @Override
      public long getLong(int columnIndex) throws IOException {
        return childrenColumns.get(columnIndex).getLong(index);
      }

      @Override
      public float getFloat(int columnIndex) throws IOException {
        return childrenColumns.get(columnIndex).getFloat(index);
      }

      @Override
      public double getDouble(int columnIndex) throws IOException {
        return childrenColumns.get(columnIndex).getDouble(index);
      }

      @Override
      public boolean getBoolean(int columnIndex) throws IOException {
        return childrenColumns.get(columnIndex).getBoolean(index);
      }

      @Override
      public Binary getBinary(int columnIndex) throws IOException {
        return new Binary(childrenColumns.get(columnIndex).getBinary(index).getValues());
      }

      @Override
      public String getString(int columnIndex) throws IOException {
        return childrenColumns.get(columnIndex).getBinary(index).toString();
      }

      @Override
      public Object getObject(int columnIndex) throws IOException {
        return childrenColumns.get(columnIndex).getObject(index);
      }

      @Override
      public Type getDataType(int columnIndex) {
        return UDFDataTypeTransformer.transformToUDFDataType(
            childrenColumns.get(columnIndex).getDataType());
      }

      @Override
      public boolean isNull(int columnIndex) throws IOException {
        return childrenColumns.get(columnIndex).isNull(index);
      }

      @Override
      public int size() {
        return childrenColumns.size();
      }
    };
  }
}
