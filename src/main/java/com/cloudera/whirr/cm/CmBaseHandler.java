/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  
 * http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.whirr.cm;

import static org.apache.whirr.RolePredicates.role;
import static org.jclouds.scriptbuilder.domain.Statements.call;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.apache.whirr.Cluster.Instance;
import org.apache.whirr.service.ClusterActionEvent;
import org.apache.whirr.service.FirewallManager.Rule;
import org.apache.whirr.service.hadoop.VolumeManager;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;

public abstract class CmBaseHandler extends BaseHandler {
  protected Map<String,String> deviceMappings;

  @Override
  protected void beforeConfigure(ClusterActionEvent event) throws IOException, InterruptedException {
    super.beforeConfigure(event);
        
    deviceMappings = getDeviceMappings(event);
    String devMappings = VolumeManager.asString(deviceMappings);
    addStatement(event, call("prepare_all_disks", "'" + devMappings + "'"));
  }

  protected Map<String, String> getDeviceMappings(ClusterActionEvent event) {
      Set<Instance> instances = event.getCluster().getInstancesMatching(role(getRole()));
      Instance prototype = Iterables.getFirst(instances, null);
      if (prototype == null) {
          throw new IllegalStateException("No instances found in role " + getRole());
      }
      VolumeManager volumeManager = new VolumeManager();
      return volumeManager.getDeviceMappings(event.getClusterSpec(), prototype);
  }
}
