/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;

import com.google.common.collect.Maps;
import com.linkedin.data.template.StringMap;
import com.linkedin.restli.common.ComplexResourceKey;
import com.linkedin.restli.common.EmptyRecord;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.server.CreateResponse;
import com.linkedin.restli.server.UpdateResponse;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.config.ConfigBuilder;
import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.runtime.api.FlowSpec;
import org.apache.gobblin.runtime.api.SpecNotFoundException;
import org.apache.gobblin.runtime.spec_catalog.FlowCatalog;

/**
 * A {@link FlowConfigsResourceHandler} that handles Restli locally.
 */
@Slf4j
public class FlowConfigResourceLocalHandler implements FlowConfigsResourceHandler {
  @Getter
  protected FlowCatalog flowCatalog;
  public FlowConfigResourceLocalHandler(FlowCatalog flowCatalog) {
    this.flowCatalog = flowCatalog;
  }

  /**
   * Get flow config
   */
  public FlowConfig getFlowConfig(FlowId flowId) throws FlowConfigLoggedException {
    log.info("[GAAS-REST] Get called with flowGroup {} flowName {}", flowId.getFlowGroup(), flowId.getFlowName());

    try {
      URI flowCatalogURI = new URI("gobblin-flow", null, "/", null, null);
      URI flowUri = new URI(flowCatalogURI.getScheme(), flowCatalogURI.getAuthority(),
          "/" + flowId.getFlowGroup() + "/" + flowId.getFlowName(), null, null);
      FlowSpec spec = (FlowSpec) flowCatalog.getSpec(flowUri);
      FlowConfig flowConfig = new FlowConfig();
      Properties flowProps = spec.getConfigAsProperties();
      Schedule schedule = null;

      if (flowProps.containsKey(ConfigurationKeys.JOB_SCHEDULE_KEY)) {
        schedule = new Schedule();
        schedule.setCronSchedule(flowProps.getProperty(ConfigurationKeys.JOB_SCHEDULE_KEY));
      }
      if (flowProps.containsKey(ConfigurationKeys.JOB_TEMPLATE_PATH)) {
        flowConfig.setTemplateUris(flowProps.getProperty(ConfigurationKeys.JOB_TEMPLATE_PATH));
      } else if (spec.getTemplateURIs().isPresent()) {
        flowConfig.setTemplateUris(StringUtils.join(spec.getTemplateURIs().get(), ","));
      } else {
        flowConfig.setTemplateUris("NA");
      }
      if (schedule != null) {
        if (flowProps.containsKey(ConfigurationKeys.FLOW_RUN_IMMEDIATELY)) {
          schedule.setRunImmediately(Boolean.valueOf(flowProps.getProperty(ConfigurationKeys.FLOW_RUN_IMMEDIATELY)));
        }

        flowConfig.setSchedule(schedule);
      }

      // remove keys that were injected as part of flowSpec creation
      flowProps.remove(ConfigurationKeys.JOB_SCHEDULE_KEY);
      flowProps.remove(ConfigurationKeys.JOB_TEMPLATE_PATH);

      StringMap flowPropsAsStringMap = new StringMap();
      flowPropsAsStringMap.putAll(Maps.fromProperties(flowProps));

      return flowConfig.setId(new FlowId().setFlowGroup(flowId.getFlowGroup()).setFlowName(flowId.getFlowName()))
          .setProperties(flowPropsAsStringMap);
    } catch (URISyntaxException e) {
      throw new FlowConfigLoggedException(HttpStatus.S_400_BAD_REQUEST, "bad URI " + flowId.getFlowName(), e);
    } catch (SpecNotFoundException e) {
      throw new FlowConfigLoggedException(HttpStatus.S_404_NOT_FOUND, "Flow requested does not exist: " + flowId.getFlowName(), null);
    }
  }

  /**
   * Add flowConfig locally and trigger all listeners iff @param triggerListener is set to true
   */
  public CreateResponse createFlowConfig(FlowConfig flowConfig, boolean triggerListener) throws FlowConfigLoggedException {
    log.info("[GAAS-REST] Create called with flowGroup " + flowConfig.getId().getFlowGroup() + " flowName " + flowConfig.getId().getFlowName());
    FlowSpec flowSpec = createFlowSpecForConfig(flowConfig);
    // Existence of a flow spec in the flow catalog implies that the flow is currently running.
    // If the new flow spec has a schedule we should allow submission of the new flow to accept the new schedule.
    // However, if the new flow spec does not have a schedule, we should allow submission only if it is not running.
    if (!flowConfig.hasSchedule() && this.flowCatalog.exists(flowSpec.getUri())) {
      return new CreateResponse(new ComplexResourceKey<>(flowConfig.getId(), new EmptyRecord()), HttpStatus.S_409_CONFLICT);
    } else {
      this.flowCatalog.put(flowSpec, triggerListener);
      return new CreateResponse(new ComplexResourceKey<>(flowConfig.getId(), new EmptyRecord()), HttpStatus.S_201_CREATED);
    }
  }

  /**
   * Add flowConfig locally and trigger all listeners
   */
  public CreateResponse createFlowConfig(FlowConfig flowConfig) throws FlowConfigLoggedException {
    return this.createFlowConfig(flowConfig, true);
  }

  /**
   * Update flowConfig locally and trigger all listeners iff @param triggerListener is set to true
   */
  public UpdateResponse updateFlowConfig(FlowId flowId, FlowConfig flowConfig, boolean triggerListener) {
    log.info("[GAAS-REST] Update called with flowGroup {} flowName {}", flowId.getFlowGroup(), flowId.getFlowName());

    if (!flowId.getFlowGroup().equals(flowConfig.getId().getFlowGroup()) || !flowId.getFlowName().equals(flowConfig.getId().getFlowName())) {
      throw new FlowConfigLoggedException(HttpStatus.S_400_BAD_REQUEST,
          "flowName and flowGroup cannot be changed in update", null);
    }

    this.flowCatalog.put(createFlowSpecForConfig(flowConfig), triggerListener);
    return new UpdateResponse(HttpStatus.S_200_OK);
  }

  /**
   * Update flowConfig locally and trigger all listeners
   */
  public UpdateResponse updateFlowConfig(FlowId flowId, FlowConfig flowConfig) throws FlowConfigLoggedException {
    return updateFlowConfig(flowId, flowConfig, true);
  }

  /**
   * Delete flowConfig locally and trigger all listeners iff @param triggerListener is set to true
   */
  public UpdateResponse deleteFlowConfig(FlowId flowId, Properties header, boolean triggerListener) throws FlowConfigLoggedException {

    log.info("[GAAS-REST] Delete called with flowGroup {} flowName {}", flowId.getFlowGroup(), flowId.getFlowName());
    URI flowUri = null;

    try {
      flowUri = createFlowSpecUri(flowId);
      this.flowCatalog.remove(flowUri, header, triggerListener);
      return new UpdateResponse(HttpStatus.S_200_OK);
    } catch (URISyntaxException e) {
      throw new FlowConfigLoggedException(HttpStatus.S_400_BAD_REQUEST, "bad URI " + flowUri, e);
    }
  }

  /**
   * Delete flowConfig locally and trigger all listeners
   */
  public UpdateResponse deleteFlowConfig(FlowId flowId, Properties header)  throws FlowConfigLoggedException {
    return deleteFlowConfig(flowId, header, true);
  }

  public static URI createFlowSpecUri (FlowId flowId) throws URISyntaxException {
    URI flowCatalogURI = new URI("gobblin-flow", null, "/", null, null);
    URI flowUri = new URI(flowCatalogURI.getScheme(), flowCatalogURI.getAuthority(),
        "/" + flowId.getFlowGroup() + "/" + flowId.getFlowName(), null, null);
    return flowUri;
  }

  /**
   * Build a {@link FlowSpec} from a {@link FlowConfig}
   * @param flowConfig flow configuration
   * @return {@link FlowSpec} created with attributes from flowConfig
   */
  public static FlowSpec createFlowSpecForConfig(FlowConfig flowConfig) {
    ConfigBuilder configBuilder = ConfigBuilder.create()
        .addPrimitive(ConfigurationKeys.FLOW_GROUP_KEY, flowConfig.getId().getFlowGroup())
        .addPrimitive(ConfigurationKeys.FLOW_NAME_KEY, flowConfig.getId().getFlowName());

    if (flowConfig.hasSchedule()) {
      Schedule schedule = flowConfig.getSchedule();
      configBuilder.addPrimitive(ConfigurationKeys.JOB_SCHEDULE_KEY, schedule.getCronSchedule());
      configBuilder.addPrimitive(ConfigurationKeys.FLOW_RUN_IMMEDIATELY, schedule.isRunImmediately());
    } else {
      // If the job does not have schedule, it is a run-once job.
      // In this case, we add flow execution id to the flow spec now to be able to send this id back to the user for
      // flow status tracking purpose.
      // If it is not a run-once job, we should not add flow execution id here,
      // because execution id is generated for every scheduled execution of the flow and cannot be materialized to
      // the flow catalog. In this case, this id is added during flow compilation.
      configBuilder.addPrimitive(ConfigurationKeys.FLOW_EXECUTION_ID_KEY, String.valueOf(System.currentTimeMillis()));
    }

    Config config = configBuilder.build();
    Config configWithFallback = config.withFallback(ConfigFactory.parseMap(flowConfig.getProperties()));

    try {
      URI templateURI = new URI(flowConfig.getTemplateUris());
      return FlowSpec.builder().withConfig(configWithFallback).withTemplate(templateURI).build();
    } catch (URISyntaxException e) {
      throw new FlowConfigLoggedException(HttpStatus.S_400_BAD_REQUEST, "bad URI " + flowConfig.getTemplateUris(), e);
    }
  }
}
