/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.api.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.xml.bind.annotation.XmlType;
import org.apache.nifi.registry.flow.RegisteredFlowSnapshot;
import org.apache.nifi.web.api.dto.PositionDTO;

/**
 * A request to export a connector's running flow to the canvas as a new Process Group under a target parent.
 */
@XmlType(name = "connectorExportToCanvasRequestEntity")
public class ConnectorExportToCanvasRequestEntity extends Entity {

    private String targetParentProcessGroupId;
    private String processGroupName;
    private PositionDTO position;
    private String parameterContextName;
    private String clientId;
    private Boolean disconnectedNodeAcknowledged;
    private RegisteredFlowSnapshot flowSnapshot;

    @Schema(description = "The ID of the parent Process Group on the canvas under which the exported Process Group will be created.")
    public String getTargetParentProcessGroupId() {
        return targetParentProcessGroupId;
    }

    public void setTargetParentProcessGroupId(final String targetParentProcessGroupId) {
        this.targetParentProcessGroupId = targetParentProcessGroupId;
    }

    @Schema(description = "Name to assign to the newly created Process Group. Defaults to the connector's name when not supplied.")
    public String getProcessGroupName() {
        return processGroupName;
    }

    public void setProcessGroupName(final String processGroupName) {
        this.processGroupName = processGroupName;
    }

    @Schema(description = "Position of the newly created Process Group on the canvas.")
    public PositionDTO getPosition() {
        return position;
    }

    public void setPosition(final PositionDTO position) {
        this.position = position;
    }

    @Schema(description = "Name to assign to the Parameter Context created from the connector's implicit Parameter Context. "
            + "Defaults to a name derived from the connector when not supplied.")
    public String getParameterContextName() {
        return parameterContextName;
    }

    public void setParameterContextName(final String parameterContextName) {
        this.parameterContextName = parameterContextName;
    }

    @Schema(description = "The client id used for requests tracking")
    public String getClientId() {
        return clientId;
    }

    public void setClientId(final String clientId) {
        this.clientId = clientId;
    }

    @Schema(description = "Acknowledges that this node is disconnected to allow for mutable requests to proceed.")
    public Boolean getDisconnectedNodeAcknowledged() {
        return disconnectedNodeAcknowledged;
    }

    public void setDisconnectedNodeAcknowledged(final Boolean disconnectedNodeAcknowledged) {
        this.disconnectedNodeAcknowledged = disconnectedNodeAcknowledged;
    }

    @Schema(description = "The flow snapshot to instantiate. Populated internally by the cluster coordinator before the "
            + "request is replicated to the cluster nodes; clients should leave this empty.")
    public RegisteredFlowSnapshot getFlowSnapshot() {
        return flowSnapshot;
    }

    public void setFlowSnapshot(final RegisteredFlowSnapshot flowSnapshot) {
        this.flowSnapshot = flowSnapshot;
    }
}
