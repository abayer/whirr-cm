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
import static org.jclouds.scriptbuilder.domain.Statements.createOrOverwriteFile;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.whirr.Cluster.Instance;
import org.apache.whirr.service.ClusterActionEvent;
import org.apache.whirr.service.FirewallManager.Rule;

import com.cloudera.api.ApiRootResource;
import com.cloudera.api.ClouderaManagerClientBuilder;
import com.cloudera.api.DataView;
import com.cloudera.api.model.ApiBulkCommandList;
import com.cloudera.api.model.ApiCluster;
import com.cloudera.api.model.ApiClusterList;
import com.cloudera.api.model.ApiClusterVersion;
import com.cloudera.api.model.ApiCommand;
import com.cloudera.api.model.ApiConfig;
import com.cloudera.api.model.ApiConfigList;
import com.cloudera.api.model.ApiHost;
import com.cloudera.api.model.ApiHostList;
import com.cloudera.api.model.ApiHostRef;
import com.cloudera.api.model.ApiHostRefList;
import com.cloudera.api.model.ApiParcel;
import com.cloudera.api.model.ApiParcelList;
import com.cloudera.api.model.ApiRole;
import com.cloudera.api.model.ApiRoleConfigGroup;
import com.cloudera.api.model.ApiRoleConfigGroupRef;
import com.cloudera.api.model.ApiRoleNameList;
import com.cloudera.api.model.ApiRoleTypeConfig;
import com.cloudera.api.model.ApiService;
import com.cloudera.api.model.ApiServiceConfig;
import com.cloudera.api.model.ApiServiceList;
import com.cloudera.api.v1.CommandsResource;
import com.cloudera.api.v1.RoleCommandsResource;
import com.cloudera.api.v3.ClustersResourceV3;
import com.cloudera.api.v3.ClouderaManagerResourceV3;
import com.cloudera.api.v2.HostsResourceV2;
import com.cloudera.api.v3.ParcelResource;
import com.cloudera.api.v3.ParcelsResource;
import com.cloudera.api.v3.RootResourceV3;
import com.cloudera.api.v3.ServicesResourceV3;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.io.Resources;

public class CmServerHandler extends BaseHandler {

  public static final String ROLE = "cmserver";

  public static final String LICENSE_FILE = "cm-license.txt";
  public static final String AUTO_VARIABLE = "whirr.env.cmauto";

  public static final String PROPERTY_PARCELS = "cmserver.parcels";
  public static final String PROPERTY_PORTS = "cmserver.ports";
  public static final String PROPERTY_PORT_WEB = "cmserver.port.web";
  public static final String PROPERTY_PORT_COMMS = "cmserver.port.comms";

  private static final String CONSOLE_SPACER = "-------------------------------------------------------------------------------";

  @Override
  public String getRole() {
    return ROLE;
  }

  @Override
  protected void beforeBootstrap(ClusterActionEvent event) throws IOException {
    super.beforeBootstrap(event);
    addStatement(event, call("install_cm"));
    addStatement(event, call("install_cm_server"));
  }

  @Override
  protected void beforeConfigure(ClusterActionEvent event) throws IOException, InterruptedException {
    super.beforeConfigure(event);
    URL licenceConfigUri = null;
    if ((licenceConfigUri = CmServerHandler.class.getClassLoader().getResource(LICENSE_FILE)) != null) {
      addStatement(
        event,
        createOrOverwriteFile("/tmp/" + LICENSE_FILE,
          Splitter.on('\n').split(CharStreams.toString(Resources.newReaderSupplier(licenceConfigUri, Charsets.UTF_8)))));
    }
    addStatement(event, call("configure_cm_server"));
    @SuppressWarnings("unchecked")
    List<String> ports = getConfiguration(event.getClusterSpec()).getList(PROPERTY_PORTS);
    ports.add(getConfiguration(event.getClusterSpec()).getString(PROPERTY_PORT_WEB));
    ports.add(getConfiguration(event.getClusterSpec()).getString(PROPERTY_PORT_COMMS));
    for (String port : ports) {
      if (port != null && !"".equals(port))
        event.getFirewallManager().addRule(Rule.create().destination(role(ROLE)).port(Integer.parseInt(port)));
    }
    handleFirewallRules(event);
  }

  @Override
  protected void afterConfigure(ClusterActionEvent event) throws IOException, InterruptedException {
    super.afterConfigure(event);

    System.out.println();
    System.out.println(CONSOLE_SPACER);
    System.out.println("Cloudera Manager Server");
    System.out.println(CONSOLE_SPACER);
    System.out.println();
    System.out.println("Web Console:");
    System.out.println("http://" + event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp() + ":"
      + getConfiguration(event.getClusterSpec()).getString(PROPERTY_PORT_WEB));
    System.out.println();
    System.out.println("Web Console User/Password:");
    System.out.println("admin/admin");
    System.out.println();
    System.out.println("Nodes:");
    Set<Instance> nodesToInstall = event.getCluster().getInstancesMatching(role(CmNodeHandler.ROLE));
    if (nodesToInstall.isEmpty()) {
      System.out.println("<none>");
    } else {
      for (Instance instance : nodesToInstall) {
        System.out.println(instance.getPublicIp());
      }
    }

    if (event.getClusterSpec().getConfiguration().getBoolean(AUTO_VARIABLE, true)) {
      System.out.println();
      System.out.println("Starting services...");
      try {
          Thread.sleep(5000);
          startServices(event);
      } catch (Exception ex) {
        System.out.println("Failed to start services using CM API");
        ex.printStackTrace(System.out);
      }
    }

    System.out.println();
    System.out.println("User:");
    System.out.println(event.getClusterSpec().getClusterUser());
    System.out.println();
    System.out.println("Private Key Path:");
    System.out.println(event.getClusterSpec().getPrivateKeyFile() == null ? "<not-defined>" : event.getClusterSpec()
      .getPrivateKeyFile().getCanonicalPath());
    System.out.println();
    System.out.println("Console:");
    System.out.println("ssh -o StrictHostKeyChecking=no " + event.getClusterSpec().getClusterUser() + "@"
      + event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp());

    Set<Instance> nodes = event.getCluster().getInstancesMatching(role(CmNodeHandler.ROLE));
    if (!nodes.isEmpty()) {
      System.out.println();
      System.out.println(CONSOLE_SPACER);
      System.out.println("Cloudera Manager Nodes");
      System.out.println(CONSOLE_SPACER);
      System.out.println();
      System.out.println("Consoles:");
      for (Instance instance : nodes) {
        System.out.println("ssh -o StrictHostKeyChecking=no " + event.getClusterSpec().getClusterUser() + "@"
          + instance.getPublicIp());
      }
    }

    Set<Instance> agents = event.getCluster().getInstancesMatching(role(CmAgentHandler.ROLE));
    if (!agents.isEmpty()) {
      System.out.println();
      System.out.println(CONSOLE_SPACER);
      System.out.println("Cloudera Manager Agents");
      System.out.println(CONSOLE_SPACER);
      System.out.println();
      System.out.println("Consoles:");
      for (Instance instance : agents) {
        System.out.println("ssh -o StrictHostKeyChecking=no " + event.getClusterSpec().getClusterUser() + "@"
          + instance.getPublicIp());
      }
    }

    System.out.println();
    System.out.println(CONSOLE_SPACER);
    System.out.println();

  }

  private void installParcel(ClusterActionEvent event, ClustersResourceV3 clustersResource,
                             String clusterName, String parcelProduct, String parcelVersion)
      throws IllegalArgumentException, IOException, InterruptedException {
    // Install parcels
    ParcelsResource parcelsResource = clustersResource.getParcelsResource("cluster-1");

    List<ApiParcel> productParcels = parcelsForProduct(readParcels(parcelsResource), parcelProduct);
    if (productParcels.size() < 1) {
        throw new IllegalArgumentException("No parcels found for product " + parcelProduct);
    }
    
    List<ApiParcel> versionParcels = parcelsForVersionPrefix(productParcels, parcelVersion);
    if (versionParcels.size() < 1) {
        throw new IllegalArgumentException("No parcels found for product " + parcelProduct + " and version " + parcelVersion);
    }

    ApiParcel parcel = PARCEL_ORDERING.max(versionParcels);

    if (parcel == null) {
        throw new IllegalArgumentException("No parcel found for product " + parcelProduct + " and version " + parcelVersion);
    }
    
    ParcelResource parcelResource = parcelsResource.getParcelResource(parcel.getProduct(), parcel.getVersion());

    System.out.println();
    System.out.println(CONSOLE_SPACER);
    System.out.println("Downloading parcel for product " + parcel.getProduct() + ", version " + parcel.getVersion());
    System.out.println(CONSOLE_SPACER);
    System.out.println();

    ApiCommand downloadParcel = parcelResource.startDownloadCommand();
    while (!parcelResource.readParcel().getStage().equals("DOWNLOADED")) {
        Thread.sleep(5000);
    }
    
    System.out.println();
    System.out.println(CONSOLE_SPACER);
    System.out.println("Distributing parcel for product " + parcel.getProduct() + ", version " + parcel.getVersion());
    System.out.println(CONSOLE_SPACER);
    System.out.println();

    ApiCommand distributeParcel = parcelResource.startDistributionCommand();
    while (!parcelResource.readParcel().getStage().equals("DISTRIBUTED")) {
        Thread.sleep(5000);
    }
    
    System.out.println();
    System.out.println(CONSOLE_SPACER);
    System.out.println("Activating parcel for product " + parcel.getProduct() + ", version " + parcel.getVersion());
    System.out.println(CONSOLE_SPACER);
    System.out.println();

    ApiCommand activateParcel = parcelResource.activateCommand();
    while (!parcelResource.readParcel().getStage().equals("ACTIVATED")) {
        Thread.sleep(5000);
    }

  }
    
  private void startServices(ClusterActionEvent event) throws IllegalArgumentException, IOException, InterruptedException {
    ApiRootResource apiRoot = new ClouderaManagerClientBuilder()
        .withHost(event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp())
        .withUsernamePassword("admin", "admin")
        .build();
    RootResourceV3 root = apiRoot.getRootV3();
    ClouderaManagerResourceV3 cmResource = root.getClouderaManagerResource();
    CommandsResource commandsResource = root.getCommandsResource();

    ApiCommand inspectHosts = cmResource.inspectHostsCommand();
    waitForCommand(commandsResource, inspectHosts, 500);
        
    // make cluster
    ClustersResourceV3 clustersResource = root.getClustersResource();
    ApiClusterList clusterList = new ApiClusterList();
    ApiCluster apiCluster = new ApiCluster();
    apiCluster.setName("cluster-1");
    apiCluster.setVersion(ApiClusterVersion.CDH4);
    clusterList.add(apiCluster);
    clustersResource.createClusters(clusterList);
    
    List<ApiHost> hosts = readHosts(root); 
    List<ApiHostRef> hostRefs = Lists.newArrayList();

    for (ApiHost h: hosts) {
        hostRefs.add(new ApiHostRef(h.getHostId()));
    }
    ApiHostRefList hostRefList = new ApiHostRefList(hostRefs);

    clustersResource.addHosts("cluster-1", hostRefList);
    
    List<ApiHost> hdfsHosts = new ArrayList<ApiHost>(hosts);
    ApiHost nnHost = hdfsHosts.remove(0);
    ApiHost snnHost = hdfsHosts.remove(0);
    
    List<ApiHost> mrHosts = new ArrayList<ApiHost>(hosts);
    ApiHost jtHost = mrHosts.remove(0);
    
    ApiHost zkHost = hosts.get(0);
    
    List<ApiHost> hbaseHosts = new ArrayList<ApiHost>(hosts);
    ApiHost masterHost = hbaseHosts.remove(0);

    ApiHost hiveHost = hosts.get(0);
    ApiHost oozieHost = hosts.get(1);
    // install parcels

    for (Map.Entry<String,String> p: getParcelsFromConfig(event).entrySet()) {
        installParcel(event, clustersResource, "cluster-1", p.getKey(), p.getValue());
    }
    
    // make services
    ServicesResourceV3 servicesResource = clustersResource.getServicesResource("cluster-1");
    ApiServiceList serviceList = new ApiServiceList();
    ApiService hdfsService = buildHdfsService(nnHost, snnHost, hdfsHosts);
    ApiService mrService = buildMapReduceService(jtHost, mrHosts);
    ApiService zkService = buildZookeeperService(zkHost);
    ApiService hbaseService = buildHbaseService(masterHost, hbaseHosts);
    ApiService hiveService = buildHiveService(hiveHost, event);
    ApiService oozieService = buildOozieService(oozieHost, event);
    
    serviceList.add(hdfsService);
    serviceList.add(mrService);
    serviceList.add(zkService);
    serviceList.add(hbaseService);
    serviceList.add(hiveService);
    serviceList.add(oozieService);

    servicesResource.createServices(serviceList);

    formatHdfs(servicesResource, commandsResource);
    startService(servicesResource, commandsResource, "HDFS", "hdfs-1");
    createHdfsTmpDir(servicesResource, commandsResource);
    startService(servicesResource, commandsResource, "MAPREDUCE", "mapreduce-1");
    initZookeeper(servicesResource, commandsResource);
    startService(servicesResource, commandsResource, "ZOOKEEPER", "zookeeper-1");
    createHbaseRoot(servicesResource, commandsResource);
    startService(servicesResource, commandsResource, "HBASE", "hbase-1");
    initHiveMetastore(servicesResource, commandsResource);
    startService(servicesResource, commandsResource, "HIVE", "hive-1");
    initOozie(servicesResource, commandsResource);
    startService(servicesResource, commandsResource, "OOZIE", "oozie-1");
  }
  
  private ApiService buildHdfsService(ApiHost nnHost, ApiHost snnHost, List<ApiHost> dnHosts) {
    ApiService hdfsService = new ApiService();
    hdfsService.setType("HDFS");
    hdfsService.setName("hdfs-1");

    List<ApiRoleConfigGroup> groupList = Lists.newArrayList();

    ApiRoleConfigGroup nnGrp = new ApiRoleConfigGroup();
    groupList.add(nnGrp);
    ApiConfigList nnConfig = new ApiConfigList();
    nnConfig.add(new ApiConfig("dfs_name_dir_list", "/data/1/dfs/nn"));
    nnGrp.setRoleType("NAMENODE");
    nnGrp.setConfig(nnConfig);
    nnGrp.setName("whirr-nn-group");
    nnGrp.setBase(false);
    
    ApiRoleConfigGroup snnGrp = new ApiRoleConfigGroup();
    groupList.add(snnGrp);
    ApiConfigList snnConfig = new ApiConfigList();
    snnConfig.add(new ApiConfig("fs_checkpoint_dir_list", "/data/1/dfs/snn"));
    snnGrp.setRoleType("SECONDARYNAMENODE");
    snnGrp.setConfig(snnConfig);
    snnGrp.setName("whirr-snn-group");
    snnGrp.setBase(false);
    
    ApiRoleConfigGroup dnGrp = new ApiRoleConfigGroup();
    groupList.add(dnGrp);
    ApiConfigList dnConfig = new ApiConfigList();
    dnConfig.add(new ApiConfig("dfs_data_dir_list", "/mnt/data/1/dfs/dn"));
    dnGrp.setRoleType("DATANODE");
    dnGrp.setConfig(dnConfig);
    dnGrp.setName("whirr-dn-group");
    dnGrp.setBase(false);

    hdfsService.setRoleConfigGroups(groupList);
    
    List<ApiRole> roles = new ArrayList<ApiRole>();

    ApiRole nnRole = new ApiRole();
    nnRole.setName("nn");
    nnRole.setType("NAMENODE");
    nnRole.setHostRef(new ApiHostRef(nnHost.getHostId()));
    nnRole.setRoleConfigGroupRef(new ApiRoleConfigGroupRef("whirr-nn-group"));
    roles.add(nnRole);
    
    ApiRole snnRole = new ApiRole();
    snnRole.setName("snn"); 
    snnRole.setType("SECONDARYNAMENODE");
    snnRole.setHostRef(new ApiHostRef(snnHost.getHostId()));
    snnRole.setRoleConfigGroupRef(new ApiRoleConfigGroupRef("whirr-snn-group"));
    roles.add(snnRole);
    
    for (int i = 0; i < dnHosts.size(); i++) {
      ApiHost dnHost = dnHosts.get(i);
      ApiRole dnRole = new ApiRole();
      dnRole.setName("dn" + i);
      dnRole.setType("DATANODE");
      dnRole.setHostRef(new ApiHostRef(dnHost.getHostId()));
      dnRole.setRoleConfigGroupRef(new ApiRoleConfigGroupRef("whirr-dn-group"));
      roles.add(dnRole);
    }
    
    hdfsService.setRoles(roles);
    return hdfsService;
  }
  
  private ApiService buildMapReduceService(ApiHost jtHost, List<ApiHost> ttHosts) {
    ApiService mrService = new ApiService();
    mrService.setType("MAPREDUCE");
    mrService.setName("mapreduce-1");
    ApiServiceConfig serviceConf = new ApiServiceConfig();
    serviceConf.add(new ApiConfig("hdfs_service", "hdfs-1"));

    List<ApiRoleConfigGroup> groupList = Lists.newArrayList();
    
    ApiRoleConfigGroup jtGrp = new ApiRoleConfigGroup();
    groupList.add(jtGrp);
    ApiConfigList jtConfig = new ApiConfigList();
    jtConfig.add(new ApiConfig("jobtracker_mapred_local_dir_list", "/data/1/mapred/jt"));
    jtGrp.setRoleType("JOBTRACKER");
    jtGrp.setConfig(jtConfig);
    jtGrp.setName("whirr-jt-group");
    jtGrp.setBase(false);

    ApiRoleConfigGroup ttGrp = new ApiRoleConfigGroup();
    groupList.add(ttGrp);
    ApiConfigList ttConfig = new ApiConfigList();
    ttConfig.add(new ApiConfig("tasktracker_mapred_local_dir_list", "/data/1/mapred/local"));
    ttGrp.setRoleType("TASKTRACKER");
    ttGrp.setConfig(ttConfig);
    ttGrp.setName("whirr-tt-group");
    ttGrp.setBase(false);

    mrService.setRoleConfigGroups(groupList);
    mrService.setConfig(serviceConf);
    
    List<ApiRole> roles = new ArrayList<ApiRole>();
    ApiRole jtRole = new ApiRole();
    jtRole.setName("jt");
    jtRole.setType("JOBTRACKER");
    jtRole.setHostRef(new ApiHostRef(jtHost.getHostId()));
    jtRole.setRoleConfigGroupRef(new ApiRoleConfigGroupRef("whirr-jt-group"));
    roles.add(jtRole);
    
    for (int i = 0; i < ttHosts.size(); i++) {
      ApiHost ttHost = ttHosts.get(i);
      ApiRole ttRole = new ApiRole();
      ttRole.setName("tt" + i);
      ttRole.setType("TASKTRACKER");
      ttRole.setHostRef(new ApiHostRef(ttHost.getHostId()));
      ttRole.setRoleConfigGroupRef(new ApiRoleConfigGroupRef("whirr-tt-group"));
      roles.add(ttRole);
    }
    
    mrService.setRoles(roles);
    return mrService;
  }
  
  private ApiService buildHiveService(ApiHost hiveHost, ClusterActionEvent event) {
    String masterAddress;

    if (event.getCluster().getInstanceMatching(role(ROLE)).getPrivateIp() != null
        && !event.getCluster().getInstanceMatching(role(ROLE)).getPrivateIp().equals("")) {
        masterAddress = event.getCluster().getInstanceMatching(role(ROLE)).getPrivateIp();
    } else {
        masterAddress = event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp();
    }

    ApiService hiveService = new ApiService();
    hiveService.setType("HIVE");
    hiveService.setName("hive-1");
    ApiServiceConfig serviceConf = new ApiServiceConfig();

    serviceConf.add(new ApiConfig("mapreduce_yarn_service", "mapreduce-1"));
    serviceConf.add(new ApiConfig("hive_metastore_database_type", "postgresql"));
    serviceConf.add(new ApiConfig("hive_metastore_database_host", masterAddress));
    serviceConf.add(new ApiConfig("hive_metastore_database_password", "hive"));
    serviceConf.add(new ApiConfig("hive_metastore_database_port", "5432"));

    hiveService.setConfig(serviceConf);

    List<ApiRole> roles = new ArrayList<ApiRole>();

    ApiRole serverRole = new ApiRole();
    serverRole.setType("HIVEMETASTORE");
    serverRole.setName("hivemetastore");
    serverRole.setHostRef(new ApiHostRef(hiveHost.getHostId()));
    roles.add(serverRole);
    
    ApiRole gatewayRole = new ApiRole();
    gatewayRole.setType("GATEWAY");
    gatewayRole.setName("hivegateway");
    gatewayRole.setHostRef(new ApiHostRef(hiveHost.getHostId()));
    roles.add(gatewayRole);

    hiveService.setRoles(roles);
    return hiveService;
  }
    
  private ApiService buildZookeeperService(ApiHost zkHost) {
    ApiService zkService = new ApiService();
    zkService.setType("ZOOKEEPER");
    zkService.setName("zookeeper-1");

    List<ApiRole> roles = new ArrayList<ApiRole>();

    ApiRole serverRole = new ApiRole();
    serverRole.setType("SERVER");
    serverRole.setName("zkserver");
    serverRole.setHostRef(new ApiHostRef(zkHost.getHostId()));
    roles.add(serverRole);

    zkService.setRoles(roles);
    return zkService;
  }

  private ApiService buildHbaseService(ApiHost masterHost, List<ApiHost> rsHosts) {
    ApiService hbaseService = new ApiService();
    hbaseService.setType("HBASE");
    hbaseService.setName("hbase-1");

    ApiServiceConfig serviceConf = new ApiServiceConfig();
    serviceConf.add(new ApiConfig("hdfs_service", "hdfs-1"));
    serviceConf.add(new ApiConfig("zookeeper_service", "zookeeper-1"));
    hbaseService.setConfig(serviceConf);
    
    List<ApiRole> roles = new ArrayList<ApiRole>();
    
    ApiRole masterRole = new ApiRole();
    masterRole.setType("MASTER");
    masterRole.setName("master");
    masterRole.setHostRef(new ApiHostRef(masterHost.getHostId()));
    roles.add(masterRole);
    
    for (int i = 0; i < rsHosts.size(); i++) {
      ApiHost rsHost = rsHosts.get(i);
      ApiRole rsRole = new ApiRole();
      rsRole.setName("rs" + i);
      rsRole.setType("REGIONSERVER");
      rsRole.setHostRef(new ApiHostRef(rsHost.getHostId()));
      roles.add(rsRole);
    }
    
    hbaseService.setRoles(roles);
    return hbaseService;
  }

    
  private ApiService buildOozieService(ApiHost oozieHost, ClusterActionEvent event) {
    String masterAddress;

    if (event.getCluster().getInstanceMatching(role(ROLE)).getPrivateIp() != null
        && !event.getCluster().getInstanceMatching(role(ROLE)).getPrivateIp().equals("")) {
        masterAddress = event.getCluster().getInstanceMatching(role(ROLE)).getPrivateIp();
    } else {
        masterAddress = event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp();
    }
    
    ApiService oozieService = new ApiService();
    oozieService.setType("OOZIE");
    oozieService.setName("oozie-1");

    ApiServiceConfig serviceConf = new ApiServiceConfig();
    serviceConf.add(new ApiConfig("mapreduce_yarn_service", "mapreduce-1"));

    List<ApiRoleConfigGroup> groupList = Lists.newArrayList();
    
    ApiRoleConfigGroup oozieServerGrp = new ApiRoleConfigGroup();
    groupList.add(oozieServerGrp);
    ApiConfigList oozieServerConfig = new ApiConfigList();
    oozieServerConfig.add(new ApiConfig("oozie_database_type", "postgresql"));
    oozieServerConfig.add(new ApiConfig("oozie_database_host", masterAddress + ":5432"));
    oozieServerConfig.add(new ApiConfig("oozie_database_name", "oozie"));
    oozieServerConfig.add(new ApiConfig("oozie_database_user", "oozie"));
    oozieServerConfig.add(new ApiConfig("oozie_database_password", "oozie"));
    oozieServerGrp.setRoleType("OOZIE_SERVER");
    oozieServerGrp.setConfig(oozieServerConfig);
    oozieServerGrp.setName("whirr-oozie-server-group");
    oozieServerGrp.setBase(false);

    oozieService.setRoleConfigGroups(groupList);
    oozieService.setConfig(serviceConf);
    
    List<ApiRole> roles = new ArrayList<ApiRole>();
    
    ApiRole oozieRole = new ApiRole();
    oozieRole.setType("OOZIE_SERVER");
    oozieRole.setName("oozie-server");
    oozieRole.setHostRef(new ApiHostRef(oozieHost.getHostId()));
    oozieRole.setRoleConfigGroupRef(new ApiRoleConfigGroupRef("whirr-oozie-server-group"));
    roles.add(oozieRole);
    
    oozieService.setRoles(roles);
    return oozieService;
  }

  private void formatHdfs(ServicesResourceV3 servicesResource, 
      CommandsResource commandsResource) {
    RoleCommandsResource roleCommands = servicesResource
        .getRoleCommandsResource("hdfs-1");
    
    System.out.println("Formatting HDFS...");
    ApiRoleNameList formatList = new ApiRoleNameList();
    formatList.add("nn");
    ApiBulkCommandList formatCommand = roleCommands.formatCommand(formatList);
    for (ApiCommand subcommand : formatCommand) {
      waitForCommand(commandsResource, subcommand, 500);
    }
    System.out.println("Finished formatting HDFS");
  }
  
  private void createHdfsTmpDir(ServicesResourceV3 servicesResource, 
      CommandsResource commandsResource) {
    System.out.println("Creating HDFS tmp dir...");
    ApiCommand createTmpCommand = servicesResource.hdfsCreateTmpDir("hdfs-1");
    waitForCommand(commandsResource, createTmpCommand, 500);
    System.out.println("Finished creating HDFS tmp dir...");
  }
  
  private void initZookeeper(ServicesResourceV3 servicesResource,
      CommandsResource commandsResource) {
    System.out.println("Initializing ZOOKEEPER...");
    ApiCommand initCommand = servicesResource.zooKeeperInitCommand("zookeeper-1");
    waitForCommand(commandsResource, initCommand, 500);
    System.out.println("Finished initializing ZOOKEEPER");
  }

  private void initHiveMetastore(ServicesResourceV3 servicesResource,
      CommandsResource commandsResource) {
    System.out.println("Initializing HIVE Metastore...");
    ApiCommand initCommand = servicesResource.hiveCreateMetastoreDatabaseTablesCommand("hive-1");
    waitForCommand(commandsResource, initCommand, 500);
    System.out.println("Finished initializing HIVE Metastore");
  }
  
  private void initOozie(ServicesResourceV3 servicesResource,
      CommandsResource commandsResource) {
    System.out.println("Installing Oozie Sharelib...");
    ApiCommand sharelibCommand = servicesResource.installOozieShareLib("oozie-1");
    waitForCommand(commandsResource, sharelibCommand, 500);
    System.out.println("Finished installing Oozie Sharelib");
    System.out.println("Creating Oozie DB...");
    ApiCommand dbCommand = servicesResource.createOozieDb("oozie-1");
    waitForCommand(commandsResource, dbCommand, 500);
    System.out.println("Finished creating Oozie DB");
  }

  private void createHbaseRoot(ServicesResourceV3 servicesResource,
      CommandsResource commandsResource) {
    System.out.println("Configuring HBASE...");
    ApiCommand createRootCommand = servicesResource.createHBaseRootCommand("hbase-1");
    waitForCommand(commandsResource, createRootCommand, 500);
    System.out.println("Finished configuring HBASE");
  }
  
  private void startService(ServicesResourceV3 servicesResource,
      CommandsResource commandsResource, String serviceType, String serviceName) {
    System.out.println("Starting " + serviceType + "...");
    ApiCommand startCommand = servicesResource.startCommand(serviceName);
    waitForCommand(commandsResource, startCommand, 500);
    System.out.println("Finished starting " + serviceType);
  }
  
  private List<ApiHost> readHosts(RootResourceV3 v2) {
    HostsResourceV2 hostsResource = v2.getHostsResource();
    ApiHostList apiHostList = hostsResource.readHosts(DataView.SUMMARY);
    return apiHostList.getHosts();
  }

  private List<ApiParcel> parcelsForProduct(List<ApiParcel> parcels, final String product) {
      return ImmutableList.copyOf(Iterables.filter(parcels, new Predicate<ApiParcel>() {
                  @Override
                  public boolean apply(ApiParcel parcel) {
                      return parcel.getProduct().equalsIgnoreCase(product);
                  }
              }));
  }
    
  static final Ordering<ApiParcel> PARCEL_ORDERING = new Ordering<ApiParcel>() {
      public int compare(ApiParcel left, ApiParcel right) {
          return left.getVersion().compareTo(right.getVersion());
      }
  };

  private List<ApiParcel> parcelsForVersionPrefix(List<ApiParcel> parcels, final String versionPrefix) {
      return ImmutableList.copyOf(Iterables.filter(parcels, new Predicate<ApiParcel>() {
                  @Override
                  public boolean apply(ApiParcel parcel) {
                      return parcel.getVersion().toLowerCase().startsWith(versionPrefix.toLowerCase());
                  }
              }));
  }
      
  private List<ApiParcel> readParcels(ParcelsResource parcelsResource) {
    ApiParcelList apiParcelList = parcelsResource.readParcels(DataView.FULL);
    return apiParcelList.getParcels();
  }
  
  private void waitForCommand(CommandsResource commandsResource, ApiCommand 
      command, long sleepMs) {
    long id = command.getId();
    while (true) {
      command = commandsResource.readCommand(id);
      if (command.getEndTime() != null) {
        return;
      }
      try {
        Thread.sleep(sleepMs);
      } catch (InterruptedException ex) {}
    }
  }
    
  Map<String,String> getParcelsFromConfig(ClusterActionEvent event) throws IOException {
      List<String> parcelConfigs = getConfiguration(event.getClusterSpec()).getList(PROPERTY_PARCELS);
      
      Builder<String, String> parcels = ImmutableMap.<String, String> builder();
      if (parcelConfigs.size() > 0) {
          parcels.putAll(Splitter.on(',').withKeyValueSeparator("=")
                         .split(Joiner.on(',').join(parcelConfigs)));
      }

      return parcels.build();
  }
}
