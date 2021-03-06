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
package com.cloudera.whirr.cm.server;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class CmServerCluster {

  private String name;
  private boolean isParcel = true;
  private CmServerService server;
  private Set<CmServerService> agents = new HashSet<CmServerService>();
  private Set<CmServerService> nodes = new HashSet<CmServerService>();
  private Map<String, Map<String, String>> configuration = new HashMap<String, Map<String, String>>();
  private Map<CmServerServiceType, Set<CmServerService>> services = new HashMap<CmServerServiceType, Set<CmServerService>>();

  public CmServerCluster() {
  }

  public synchronized boolean isEmpty() {
    for (CmServerServiceType type : services.keySet()) {
      if (!services.get(type).isEmpty()) {
        return server == null || (agents.isEmpty() && nodes.isEmpty());
      }
    }
    return true;
  }

  public synchronized String setName(String name) {
    return this.name = name;
  }

  public synchronized void addServiceConfiguration(String group, String setting, String value) throws CmServerException {
    if (configuration.get(group) == null) {
      configuration.put(group, new HashMap<String, String>());
    }
    configuration.get(group).put(setting, value);
  }

  public synchronized void addServiceConfigurationAll(Map<String, Map<String, String>> configuration)
      throws CmServerException {
    for (String group : configuration.keySet()) {
      for (String setting : configuration.get(group).keySet()) {
        addServiceConfiguration(group, setting, configuration.get(group).get(setting));
      }
    }
  }

  public synchronized boolean addServiceType(CmServerServiceType type) throws CmServerException {

    if (type.getParent() == null || type.getParent().getParent() == null) {
      throw new CmServerException("Invalid cluster topology: Attempt to add non leaf type [" + type + "]");
    }
    switch (type) {
    case HDFS_NAMENODE:
      if (getServices(CmServerServiceType.HDFS_NAMENODE).size() > 0) {
        throw new CmServerException("Invalid cluster topology: Attempt to add multiple types [" + type + "]");
      }
      break;
    default:
      break;
    }

    if (!services.containsKey(type.getParent())) {
      services.put(type.getParent(), new TreeSet<CmServerService>());
      return true;
    }
    return false;
  }

  public synchronized boolean addService(CmServerService service) throws CmServerException {
    addServiceType(service.getType());
    services.get(service.getType().getParent()).add(service);
    return true;
  }

  public synchronized boolean setServer(CmServerService server) throws CmServerException {
    if (this.server != null) {
      throw new CmServerException("Invalid cluster topology: Attempt to add multiple servers with existing server " + this.server + " and new server " + server);
    }
    return (this.server = server) != null;
  }

  public synchronized boolean addAgent(CmServerService agent) throws CmServerException {
    if (!agents.add(agent)) {
      throw new CmServerException("Invalid cluster topology: Attempt to add col-located agents");
    }
    return true;
  }

  public synchronized boolean addNode(CmServerService node) throws CmServerException {
    if (!nodes.add(node)) {
      throw new CmServerException("Invalid cluster topology: Attempt to add co-located nodes");
    }
    return true;
  }

  public synchronized Set<CmServerServiceType> getServiceTypes() {
    return new TreeSet<CmServerServiceType>(services.keySet());
  }

  public synchronized Set<CmServerServiceType> getServiceTypes(CmServerServiceType type) {
    Set<CmServerServiceType> types = new TreeSet<CmServerServiceType>();
    if (type.equals(CmServerServiceType.CLUSTER)) {
      for (CmServerServiceType serviceType : services.keySet()) {
        for (CmServerService service : services.get(serviceType)) {
          types.add(service.getType());
        }
      }
    } else if (services.containsKey(type)) {
      for (CmServerService service : services.get(type)) {
        types.add(service.getType());
      }
    } else if (services.containsKey(type.getParent())) {
      for (CmServerService service : services.get(type.getParent())) {
        if (service.getType().equals(type)) {
          types.add(service.getType());
        }
      }
    }
    return types;
  }

  public synchronized CmServerService getService(CmServerServiceType type) {
    Set<CmServerService> serviceCopy = getServices(type);
    return serviceCopy.size() == 0 ? null : serviceCopy.iterator().next();
  }

  public synchronized Set<CmServerService> getServices(CmServerServiceType type) {
    Set<CmServerService> servicesCopy = new TreeSet<CmServerService>();
    if (type.equals(CmServerServiceType.CLUSTER)) {
      for (CmServerServiceType serviceType : services.keySet()) {
        servicesCopy.addAll(services.get(serviceType));
      }
    } else if (services.containsKey(type)) {
      servicesCopy.addAll(services.get(type));
    } else if (services.containsKey(type.getParent())) {
      for (CmServerService service : services.get(type.getParent())) {
        if (service.getType().equals(type)) {
          servicesCopy.add(service);
        }
      }
    }
    return servicesCopy;
  }

  public synchronized String getServiceName(CmServerServiceType type) throws IOException {
    if (type.equals(CmServerServiceType.CLUSTER) && name != null) {
      return name;
    }
    if (services.get(type) != null) {
      CmServerService service = services.get(type).iterator().next();
      if (service.getType().equals(type)) {
        return service.getName();
      } else {
        return new CmServerServiceBuilder().type(type).tag(service.getTag()).build().getName();
      }
    } else {
      Set<CmServerService> servicesChild = null;
      if (!services.isEmpty() && !(servicesChild = services.get(getServiceTypes().iterator().next())).isEmpty()) {
        return new CmServerServiceBuilder().type(type).tag(servicesChild.iterator().next().getTag()).build().getName();
      }
    }
    throw new IOException("Cannot determine service name, cluster is empty");
  }

  public synchronized CmServerService getServer() {
    return server;
  }

  public synchronized Set<CmServerService> getAgents() {
    return new HashSet<CmServerService>(agents);
  }

  public synchronized Set<CmServerService> getNodes() {
    return new HashSet<CmServerService>(nodes);
  }

  public synchronized Map<String, Map<String, String>> getServiceConfiguration() {
    Map<String, Map<String, String>> configuration = new HashMap<String, Map<String, String>>();
    for (String key : this.configuration.keySet()) {
      configuration.put(key, new HashMap<String, String>(this.configuration.get(key)));
    }
    return configuration;
  }

  public void setIsParcel(boolean isParcel) {
    this.isParcel = isParcel;
  }

  public boolean getIsParcel() {
    return isParcel;
  }

}
