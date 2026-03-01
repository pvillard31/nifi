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

import org.apache.nifi.tests.system.NiFiSystemIT;
import org.apache.nifi.toolkit.client.NiFiClientException;
import org.apache.nifi.web.api.dto.PositionDTO;
import org.apache.nifi.web.api.dto.flow.FlowDTO;
import org.apache.nifi.web.api.dto.flow.ProcessGroupFlowDTO;
import org.apache.nifi.web.api.entity.ConnectorEntity;
import org.apache.nifi.web.api.entity.ConnectorExportToCanvasRequestEntity;
import org.apache.nifi.web.api.entity.ParameterContextsEntity;
import org.apache.nifi.web.api.entity.ProcessGroupEntity;
import org.apache.nifi.web.api.entity.ProcessGroupFlowEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectorExportToCanvasIT extends NiFiSystemIT {

    @Test
    public void testExportToCanvasCreatesProcessGroupWithNestedStructure() throws NiFiClientException, IOException {
        final ConnectorEntity connector = getClientUtil().createConnector("NestedProcessGroupConnector");
        assertNotNull(connector);

        final ConnectorExportToCanvasRequestEntity request = new ConnectorExportToCanvasRequestEntity();
        request.setTargetParentProcessGroupId("root");
        request.setProcessGroupName("Exported From Connector");
        request.setPosition(new PositionDTO(100.0, 200.0));
        request.setClientId("it-client");

        final ProcessGroupEntity exported = getNifiClient().getConnectorClient().exportToCanvas(connector.getId(), false, request);
        assertNotNull(exported);
        assertNotNull(exported.getId());
        assertEquals("Exported From Connector", exported.getComponent().getName());

        final ProcessGroupFlowEntity exportedFlow = getNifiClient().getFlowClient().getProcessGroup(exported.getId());
        assertNotNull(exportedFlow);
        final ProcessGroupFlowDTO flowDto = exportedFlow.getProcessGroupFlow();
        final FlowDTO flow = flowDto.getFlow();
        final Set<ProcessGroupEntity> childGroups = flow.getProcessGroups();
        assertFalse(childGroups.isEmpty(), "Exported process group should contain the connector's nested child group");
        assertTrue(childGroups.stream().anyMatch(child -> "Child Process Group".equals(child.getComponent().getName())));
    }

    @Test
    public void testExportToCanvasWithIncludeStateOnRunningConnectorReturnsError() throws NiFiClientException, IOException, InterruptedException {
        final ConnectorEntity connector = getClientUtil().createConnector("NestedProcessGroupConnector");
        assertNotNull(connector);

        getNifiClient().getConnectorClient().startConnector(connector);

        final ConnectorExportToCanvasRequestEntity request = new ConnectorExportToCanvasRequestEntity();
        request.setTargetParentProcessGroupId("root");
        request.setProcessGroupName("Should Not Be Created");
        request.setPosition(new PositionDTO(0.0, 0.0));
        request.setClientId("it-client");

        try {
            assertThrows(NiFiClientException.class, () ->
                getNifiClient().getConnectorClient().exportToCanvas(connector.getId(), true, request));
        } finally {
            final ConnectorEntity updated = getNifiClient().getConnectorClient().getConnector(connector.getId());
            getNifiClient().getConnectorClient().stopConnector(updated);
        }
    }

    @Test
    public void testExportToCanvasCreatesParameterContextForConnector() throws NiFiClientException, IOException, InterruptedException {
        final ParameterContextsEntity contextsBefore = getNifiClient().getParamContextClient().getParamContexts();
        final int before = contextsBefore.getParameterContexts().size();

        final ConnectorEntity connector = getClientUtil().createConnector("ParameterContextConnector");
        assertNotNull(connector);

        getClientUtil().configureConnector(connector, "Parameter Context Configuration", java.util.Map.of(
            "Sensitive Value", "shhh",
            "Asset File", "target/test-asset.txt",
            "Sensitive Output File", "target/sensitive.txt",
            "Asset Output File", "target/asset.txt"));
        getClientUtil().applyConnectorUpdate(connector);

        final ConnectorExportToCanvasRequestEntity request = new ConnectorExportToCanvasRequestEntity();
        request.setTargetParentProcessGroupId("root");
        request.setProcessGroupName("Exported PC Connector");
        request.setParameterContextName("Connector Parameters");
        request.setPosition(new PositionDTO(0.0, 0.0));
        request.setClientId("it-client");

        final ProcessGroupEntity exported = getNifiClient().getConnectorClient().exportToCanvas(connector.getId(), false, request);
        assertNotNull(exported);

        final ParameterContextsEntity contextsAfter = getNifiClient().getParamContextClient().getParamContexts();
        assertTrue(contextsAfter.getParameterContexts().size() > before,
                "A new parameter context should be created for the exported connector flow");
        assertTrue(contextsAfter.getParameterContexts().stream()
                .anyMatch(entity -> "Connector Parameters".equals(entity.getComponent().getName())));
    }
}
