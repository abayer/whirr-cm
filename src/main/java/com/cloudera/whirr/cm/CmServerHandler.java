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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.whirr.Cluster.Instance;
import org.apache.whirr.service.ClusterActionEvent;
import org.apache.whirr.service.FirewallManager.Rule;

import com.cloudera.whirr.cm.api.CmServerApi;
import com.cloudera.whirr.cm.api.CmServerApiFactory;
import com.cloudera.whirr.cm.api.CmServerApiLog;
import com.cloudera.whirr.cm.api.CmServerService;
import com.cloudera.whirr.cm.api.CmServerServiceType;
import com.cloudera.whirr.cm.cdh.BaseHandlerCmCdh;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.io.CharStreams;
import com.google.common.io.Resources;

public class CmServerHandler extends BaseHandlerCm {

  public static final String ROLE = "cm-server";

  public static final String LICENSE_FILE = "cm-license.txt";

  public static final String PROPERTY_PARCEL_PRODUCT = "cm-server.parcel.product";
  public static final String PROPERTY_PARCEL_VERSION = "cm-server.parcel.version";
  public static final String PROPERTY_PORTS = "cm-server.ports";
  public static final String PROPERTY_PORT_WEB = "cm-server.port.web";
  public static final String PROPERTY_PORT_COMMS = "cm-server.port.comms";

  public static final String CM_USER = "admin";
  public static final String CM_PASSWORD = "admin";

  @Override
  public String getRole() {
    return ROLE;
  }

  @Override
  protected void beforeBootstrap(ClusterActionEvent event) throws IOException, InterruptedException {
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
          createOrOverwriteFile(
              "/tmp/" + LICENSE_FILE,
              Splitter.on('\n').split(
                  CharStreams.toString(Resources.newReaderSupplier(licenceConfigUri, Charsets.UTF_8)))));
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
    
    logHeader("Cloudera Manager Server");
    logLineItem("Web Console:");
    logLineItemDetail("http://" + event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp() + ":"
        + getConfiguration(event.getClusterSpec()).getString(PROPERTY_PORT_WEB));
    logLineItem("Web Console User/Password (change these!):");
    logLineItemDetail(CM_USER + "/" + CM_PASSWORD);
    logLineItem("Automatically provision and start Cloudera Manager services [" + CONFIG_WHIRR_AUTO_VARIABLE + "],\n"
        + "progress via terminal (below) and web (above) consoles:");
    logLineItemDetail("" + event.getClusterSpec().getConfiguration().getBoolean(CONFIG_WHIRR_AUTO_VARIABLE, true));

    logLineItemDetail("User:");
    logLineItem(event.getClusterSpec().getClusterUser());
    logLineItemDetail("Private Key Path:");
    logLineItem(event.getClusterSpec().getPrivateKeyFile() == null ? "<not-defined>" : event.getClusterSpec()
        .getPrivateKeyFile().getCanonicalPath());
    logLineItemDetail("Console:");
    logLineItem("ssh -o StrictHostKeyChecking=no " + event.getClusterSpec().getClusterUser() + "@"
        + event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp());

    Set<Instance> nodes = event.getCluster().getInstancesMatching(role(CmNodeHandler.ROLE));
    if (!nodes.isEmpty()) {
      logHeader("Cloudera Manager Nodes");
      logLineItem("Consoles:");
      for (Instance instance : nodes) {
        logLineItemDetail("ssh -o StrictHostKeyChecking=no " + event.getClusterSpec().getClusterUser() + "@"
            + instance.getPublicIp());
      }
    }

    Set<Instance> agents = event.getCluster().getInstancesMatching(role(CmAgentHandler.ROLE));
    if (!agents.isEmpty()) {
      logHeader("Cloudera Manager Agents");
      logLineItem("Consoles:");
      for (Instance instance : agents) {
        logLineItemDetail("ssh -o StrictHostKeyChecking=no " + event.getClusterSpec().getClusterUser() + "@"
            + instance.getPublicIp());
      }
    }

    if (!BaseHandlerCmCdh.CmServerClusterSingleton.getInstance().isEmpty()) {

      logHeader("Cloudera Manager Cluster Provision");

      if (!event.getClusterSpec().getConfiguration().getBoolean(CONFIG_WHIRR_AUTO_VARIABLE, true)) {

        logLineItem("Warning, Cloudera Manager services found but whirr property [" + CONFIG_WHIRR_AUTO_VARIABLE
            + "]\n" + "set to false so not provsioning:");
        for (String role : BaseHandlerCmCdh.getRoles()) {
          logLineItemDetail(role);
        }

      } else if (event.getClusterSpec().getConfiguration().getBoolean(CONFIG_WHIRR_AUTO_VARIABLE, true)) {

        try {

          if (event.getClusterSpec().getConfiguration().getBoolean(CONFIG_WHIRR_USE_PACKAGES, false)) {
            BaseHandlerCmCdh.CmServerClusterSingleton.getInstance().setIsParcel(false);
          } else {
            BaseHandlerCmCdh.CmServerClusterSingleton.getInstance().setIsParcel(true);
          }
          
          BaseHandlerCmCdh.CmServerClusterSingleton.getInstance().setDataMounts(getDataMounts(event));

          logLineItem("Roles:");
          BaseHandlerCmCdh.CmServerClusterSingleton.getInstance().clearServices();
          for (Instance instance : event.getCluster().getInstances()) {
            for (String role : instance.getRoles()) {
              CmServerServiceType type = BaseHandlerCmCdh.getType(role);
              if (type != null) {
                CmServerService service = new CmServerService(type, event.getClusterSpec().getConfiguration()
                    .getString(CONFIG_WHIRR_NAME, CM_CLUSTER_NAME), ""
                    + (BaseHandlerCmCdh.CmServerClusterSingleton.getInstance().getServices(type).size() + 1),
                    instance.getPublicHostName(), instance.getPublicIp(), instance.getPrivateIp());
                BaseHandlerCmCdh.CmServerClusterSingleton.getInstance().add(service);
                logLineItemDetail(service.getName() + "@" + instance.getPublicIp());
              }
            }
          }

          logLineItem("Provision:");
          Map<String, String> config = new HashMap<String, String>();
          @SuppressWarnings("unchecked")
          Iterator<String> keys = event.getClusterSpec().getConfiguration().getKeys();
          while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith(CONFIG_WHIRR_CM_PREFIX)) {
              config.put(key.replaceFirst(CONFIG_WHIRR_CM_PREFIX, ""), event.getClusterSpec().getConfiguration()
                  .getString(key));
            }
          }

          CmServerApi cmServerApi = CmServerApiFactory.getCmServerApi(event.getCluster()
              .getInstanceMatching(role(ROLE)).getPublicIp(), 7180, CM_USER, CM_PASSWORD,
              new CmServerApiLog.CmServerApiLogSysOut());
          cmServerApi.initialise(config);
          cmServerApi.provision(BaseHandlerCmCdh.CmServerClusterSingleton.getInstance());
          cmServerApi.configure(BaseHandlerCmCdh.CmServerClusterSingleton.getInstance());
          cmServerApi.getServiceConfigs(BaseHandlerCmCdh.CmServerClusterSingleton.getInstance(), event.getClusterSpec()
              .getClusterDirectory());

        } catch (Exception e) {

          logException(
              "Failed to execute Cloudera Manager Cluster Provision, please review the proceeding exception and log into the web console [http://"
                  + event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp()
                  + ":"
                  + getConfiguration(event.getClusterSpec()).getString(PROPERTY_PORT_WEB) + "] to resolve", e);

        }

      }

    }

  }

  @Override
  protected void afterStart(ClusterActionEvent event) throws IOException, InterruptedException {
    super.afterStart(event);

    if (!BaseHandlerCmCdh.CmServerClusterSingleton.getInstance().isEmpty()) {

      logHeader("Cloudera Manager Cluster Start");

      if (!event.getClusterSpec().getConfiguration().getBoolean(CONFIG_WHIRR_AUTO_VARIABLE, true)) {

        logLineItem("Warning, Cloudera Manager services found but whirr property [" + CONFIG_WHIRR_AUTO_VARIABLE
            + "]\n" + "set to false so not starting:");
        for (String role : BaseHandlerCmCdh.getRoles()) {
          logLineItemDetail(role);
        }

      } else if (event.getClusterSpec().getConfiguration().getBoolean(CONFIG_WHIRR_AUTO_VARIABLE, true)) {

        try {

          logLineItem("Services:");
          for (CmServerServiceType type : BaseHandlerCmCdh.CmServerClusterSingleton.getInstance().getServiceTypes()) {
            logLineItemDetail(type.toString());
          }

          logLineItem("Start:");
          CmServerApi cmServerApi = CmServerApiFactory.getCmServerApi(event.getCluster().getInstanceMatching(role(ROLE))
              .getPublicIp(), 7180, CM_USER, CM_PASSWORD, new CmServerApiLog.CmServerApiLogSysOut());
          cmServerApi.start(BaseHandlerCmCdh.CmServerClusterSingleton.getInstance());

        } catch (Exception e) {

          logException(
              "Failed to execute Cloudera Manager Cluster Start, please review the proceeding exception and log into the web console [http://"
                  + event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp()
                  + ":"
                  + getConfiguration(event.getClusterSpec()).getString(PROPERTY_PORT_WEB) + "] to resolve", e);

        }

      }

      logHeader("Cloudera Manager Server");
      logLineItem("Web Console:");
      logLineItemDetail("http://" + event.getCluster().getInstanceMatching(role(ROLE)).getPublicIp() + ":"
          + getConfiguration(event.getClusterSpec()).getString(PROPERTY_PORT_WEB));
      logLineItem("Web Console User/Password (change these!):");
      logLineItemDetail(CM_USER + "/" + CM_PASSWORD);

      logFooter();

    }

  }

  private Set<String> getDataMounts(ClusterActionEvent event) throws IOException {
    Set<String> mnts = new HashSet<String>();

    String overrideMnt = getConfiguration(event.getClusterSpec()).getString(DATA_DIRS_ROOT);

    if (overrideMnt != null) {
      mnts.add(overrideMnt);
    } else if (!getDeviceMappings(event).isEmpty()) {
      mnts.addAll(getDeviceMappings(event).keySet());
    } else {
      mnts.add(getConfiguration(event.getClusterSpec()).getString(DATA_DIRS_DEFAULT));
    }

    return mnts;
  }

}
