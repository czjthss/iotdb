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

package org.apache.iotdb.confignode.manager.load.balancer.region;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TDataNodeConfiguration;
import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

public class CopySetRegionGroupAllocator implements IRegionGroupAllocator {

  private final Random RANDOM = new Random();
  private final Map<Integer, List<List<Integer>>> COPY_SETS = new TreeMap<>();

  private final int dataNodeNum;

  public CopySetRegionGroupAllocator(int dataNodeNum, int replicationFactor, int loadFactor) {
    this.dataNodeNum = dataNodeNum;
    BitSet bitSet = new BitSet(dataNodeNum + 1);
    for (int p = 0; p < loadFactor || bitSet.cardinality() < dataNodeNum; p++) {
      List<Integer> permutation = new ArrayList<>();
      for (int i = 1; i <= dataNodeNum; i++) {
        permutation.add(i);
      }
      for (int i = 1; i < dataNodeNum; i++) {
        int pos = RANDOM.nextInt(i);
        int tmp = permutation.get(i);
        permutation.set(i, permutation.get(pos));
        permutation.set(pos, tmp);
      }
      for (int i = 0; i + replicationFactor < permutation.size(); i += replicationFactor) {
        List<Integer> copySet = new ArrayList<>();
        for (int j = 0; j < replicationFactor; j++) {
          int e = permutation.get(i + j);
          copySet.add(e);
          bitSet.set(e);
        }
        for (int c : copySet) {
          COPY_SETS.computeIfAbsent(c, k -> new ArrayList<>()).add(copySet);
        }
      }
    }
  }

  @Override
  public TRegionReplicaSet generateOptimalRegionReplicasDistribution(
      Map<Integer, TDataNodeConfiguration> availableDataNodeMap,
      Map<Integer, Double> freeDiskSpaceMap,
      List<TRegionReplicaSet> allocatedRegionGroups,
      List<TRegionReplicaSet> databaseAllocatedRegionGroups,
      int replicationFactor,
      TConsensusGroupId consensusGroupId) {
    TRegionReplicaSet result = new TRegionReplicaSet();
    Map<Integer, Integer> regionCounter = new TreeMap<>();
    for (int i = 1; i <= dataNodeNum; i++) {
      regionCounter.put(i, 0);
    }
    allocatedRegionGroups.forEach(
        regionGroup ->
            regionGroup
                .getDataNodeLocations()
                .forEach(
                    dataNodeLocation ->
                        regionCounter.merge(dataNodeLocation.getDataNodeId(), 1, Integer::sum)));
    int firstRegion = -1, minCount = Integer.MAX_VALUE;
    for (Map.Entry<Integer, Integer> counterEntry : regionCounter.entrySet()) {
      int dataNodeId = counterEntry.getKey();
      int regionCount = counterEntry.getValue();
      if (regionCount < minCount) {
        minCount = regionCount;
        firstRegion = dataNodeId;
      } else if (regionCount == minCount && RANDOM.nextBoolean()) {
        firstRegion = dataNodeId;
      }
    }
    List<Integer> copySet =
        COPY_SETS.get(firstRegion).get(RANDOM.nextInt(COPY_SETS.get(firstRegion).size()));
    for (int dataNodeId : copySet) {
      result.addToDataNodeLocations(availableDataNodeMap.get(dataNodeId).getLocation());
    }
    return result.setRegionId(consensusGroupId);
  }
}