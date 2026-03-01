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
package org.apache.nifi.tests.system.connectors;

import org.apache.nifi.components.state.Scope;
import org.apache.nifi.tests.system.NiFiInstanceFactory;
import org.apache.nifi.tests.system.NiFiSystemIT;
import org.apache.nifi.toolkit.client.NiFiClientException;
import org.apache.nifi.web.api.dto.ComponentStateDTO;
import org.apache.nifi.web.api.dto.PositionDTO;
import org.apache.nifi.web.api.dto.StateEntryDTO;
import org.apache.nifi.web.api.dto.flow.FlowDTO;
import org.apache.nifi.web.api.dto.flow.ProcessGroupFlowDTO;
import org.apache.nifi.web.api.entity.ComponentStateEntity;
import org.apache.nifi.web.api.entity.ConnectorEntity;
import org.apache.nifi.web.api.entity.ConnectorExportToCanvasRequestEntity;
import org.apache.nifi.web.api.entity.ProcessGroupEntity;
import org.apache.nifi.web.api.entity.ProcessGroupFlowEntity;
import org.apache.nifi.web.api.entity.ProcessorEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ClusteredConnectorExportToCanvasIT extends NiFiSystemIT {

    @Override
    public NiFiInstanceFactory getInstanceFactory() {
        return createTwoNodeInstanceFactory();
    }

    @Test
    public void testClusterExportToCanvasIncludesLocalStateFromBothNodes() throws NiFiClientException, IOException, InterruptedException {
        final ConnectorEntity connector = getClientUtil().createConnector("DataQueuingConnector");
        assertNotNull(connector);

        getClientUtil().startConnector(connector.getId());
        Thread.sleep(2000L);
        getClientUtil().stopConnector(connector.getId());

        final ConnectorExportToCanvasRequestEntity request = new ConnectorExportToCanvasRequestEntity();
        request.setTargetParentProcessGroupId("root");
        request.setProcessGroupName("Clustered Export");
        request.setPosition(new PositionDTO(0.0, 0.0));
        request.setClientId("cluster-it-client");

        final ProcessGroupEntity exported = getNifiClient().getConnectorClient().exportToCanvas(connector.getId(), true, request);
        assertNotNull(exported);

        final ProcessorEntity generate = findProcessorByTypeInGroup(exported.getId(), "GenerateFlowFile");
        assertNotNull(generate, "Exported process group should contain GenerateFlowFile processor");

        final Map<String, String> localState = getProcessorState(generate.getId(), Scope.LOCAL);
        assertEquals(false, localState.isEmpty(), "Exported processor should have local state restored from the connector run");
    }

    @Test
    public void testClusterExportToCanvasWithoutStatePreservesStructure() throws NiFiClientException, IOException, InterruptedException {
        final ConnectorEntity connector = getClientUtil().createConnector("NestedProcessGroupConnector");
        assertNotNull(connector);

        final ConnectorExportToCanvasRequestEntity request = new ConnectorExportToCanvasRequestEntity();
        request.setTargetParentProcessGroupId("root");
        request.setProcessGroupName("Clustered Structure Export");
        request.setPosition(new PositionDTO(0.0, 0.0));
        request.setClientId("cluster-it-client");

        final ProcessGroupEntity exported = getNifiClient().getConnectorClient().exportToCanvas(connector.getId(), false, request);
        assertNotNull(exported);
        assertEquals("Clustered Structure Export", exported.getComponent().getName());

        final ProcessGroupFlowEntity exportedFlow = getNifiClient().getFlowClient().getProcessGroup(exported.getId());
        final ProcessGroupFlowDTO flowDto = exportedFlow.getProcessGroupFlow();
        final FlowDTO flow = flowDto.getFlow();
        assertEquals(false, flow.getProcessGroups().isEmpty(), "Exported process group should contain the nested child group");
    }

    private ProcessorEntity findProcessorByTypeInGroup(final String groupId, final String typeSuffix) throws NiFiClientException, IOException {
        return getNifiClient().getFlowClient().getProcessGroup(groupId)
                .getProcessGroupFlow().getFlow().getProcessors().stream()
                .filter(pe -> pe.getComponent().getType().endsWith(typeSuffix))
                .findFirst().orElse(null);
    }

    private Map<String, String> getProcessorState(final String processorId, final Scope scope) throws NiFiClientException, IOException {
        final ComponentStateEntity stateEntity = getNifiClient().getProcessorClient().getProcessorState(processorId);
        final ComponentStateDTO componentState = stateEntity.getComponentState();
        final Map<String, String> result = new HashMap<>();

        if (componentState == null) {
            return result;
        }

        switch (scope) {
            case LOCAL:
                if (componentState.getLocalState() != null && componentState.getLocalState().getState() != null) {
                    for (final StateEntryDTO entry : componentState.getLocalState().getState()) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
                break;
            case CLUSTER:
                if (componentState.getClusterState() != null && componentState.getClusterState().getState() != null) {
                    for (final StateEntryDTO entry : componentState.getClusterState().getState()) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                }
                break;
        }
        return result;
    }
}
