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
 *
 */
package org.apache.iotdb.db.qp.logical.sys;

import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.sys.ShowChildNodesPlan;
import org.apache.iotdb.db.qp.physical.sys.ShowPlan.ShowContentType;
import org.apache.iotdb.db.qp.strategy.PhysicalGenerator;

public class ShowChildNodesOperator extends ShowOperator {

  private PartialPath path;

  private int limit = IoTDBDescriptor.getInstance().getConfig().getSchemaQueryFetchSize();
  private int offset = 0;

  public ShowChildNodesOperator(int tokenIntType, PartialPath path) {
    super(tokenIntType);
    this.path = path;
  }

  public PartialPath getPath() {
    return path;
  }

  public int getLimit() {
    return limit;
  }

  public void setLimit(int limit) {
    this.limit = limit;
  }

  public int getOffset() {
    return offset;
  }

  public void setOffset(int offset) {
    this.offset = offset;
  }

  @Override
  public PhysicalPlan generatePhysicalPlan(PhysicalGenerator generator)
      throws QueryProcessException {
    ShowChildNodesPlan plan = new ShowChildNodesPlan(ShowContentType.CHILD_NODE, path);
    plan.setLimit(limit);
    plan.setOffset(offset);
    return plan;
  }
}
