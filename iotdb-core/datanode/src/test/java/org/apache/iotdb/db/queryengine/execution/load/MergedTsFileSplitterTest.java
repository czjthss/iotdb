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

package org.apache.iotdb.db.queryengine.execution.load;

import org.apache.iotdb.commons.concurrent.IoTDBThreadPoolFactory;
import org.apache.iotdb.commons.concurrent.IoTThreadFactory;
import org.apache.iotdb.commons.utils.TimePartitionUtils;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class MergedTsFileSplitterTest extends TestBase {

  private List<TsFileData> resultSet = new ArrayList<>();

  @Test
  public void testSplit() throws IOException {
    long start = System.currentTimeMillis();
    MergedTsFileSplitter splitter =
        new MergedTsFileSplitter(
            files,
            this::consumeSplit,
            IoTDBThreadPoolFactory.newThreadPool(
                16,
                Integer.MAX_VALUE,
                20,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new IoTThreadFactory("MergedTsFileSplitter"),
                "MergedTsFileSplitter"),
            TimePartitionUtils.getTimePartitionInterval(),
            fileNum);
    try {
      splitter.splitTsFileByDataPartition();
      for (TsFileData tsFileData : resultSet) {
        //         System.out.println(tsFileData);
      }
    } finally {
      splitter.close();
    }
    System.out.printf(
        "%d splits after %dms\n", resultSet.size(), System.currentTimeMillis() - start);
    assertEquals(expectedChunkNum(), resultSet.size());
  }

  public boolean consumeSplit(TsFileData data) {
    resultSet.add(data);
    if (resultSet.size() % 1000 == 0) {
      System.out.printf("%d chunks split\n", resultSet.size());
    }
    return true;
  }
}
