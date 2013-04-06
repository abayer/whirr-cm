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
package com.cloudera.whirr.cm.cmd.integration;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.whirr.ClusterSpec;
import org.junit.Before;

import com.cloudera.whirr.cm.BaseTestIntegration;
import com.cloudera.whirr.cm.server.CmServerCommand;

public abstract class BaseTestIntegrationCommand extends BaseTestIntegration {

  protected CmServerCommand command;
  protected ClusterSpec specification;

  @Override
  @Before
  public void provisionCluster() throws Exception {
    super.provisionCluster();
    command = CmServerCommand.get().host(CM_HOST).cluster(cluster).client(DIR_CLIENT_CONFIG.getAbsolutePath());
    Configuration configuration = new PropertiesConfiguration();
    configuration.setProperty(CONFIG_WHIRR_USER, CLUSTER_USER);
    specification = new ClusterSpec(configuration);
  }

}
