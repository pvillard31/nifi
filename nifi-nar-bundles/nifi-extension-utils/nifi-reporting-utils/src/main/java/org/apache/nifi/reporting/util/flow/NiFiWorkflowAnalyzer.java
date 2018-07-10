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

package org.apache.nifi.reporting.util.flow;

import java.util.ArrayList;
import java.util.List;

import org.apache.nifi.controller.status.ConnectionStatus;
import org.apache.nifi.controller.status.PortStatus;
import org.apache.nifi.controller.status.ProcessGroupStatus;
import org.apache.nifi.controller.status.ProcessorStatus;
import org.apache.nifi.reporting.ReportingContext;

public class NiFiWorkflowAnalyzer {

    private List<ProcessorStatus> processors;
    private List<ConnectionStatus> connections;
    private List<PortStatus> inputPorts;
    private List<PortStatus> outputPorts;
    private List<ProcessGroupStatus> processGroups;

    public NiFiWorkflowAnalyzer(final ReportingContext context, final String processorUUID) {
     // retrieving the root process group in NiFi
        final ProcessGroupStatus rootProcessGroup = context.getEventAccess().getGroupStatus("root");
        // looping over the process groups to get the full list of processor statuses in NiFi and retrieve the one we're looking for
        final ProcessorStatus processorStatus = getWorkflowProcessorStatus(processorUUID, rootProcessGroup);

        if(processorStatus == null) {
            // TODO : throw exception, processor not found
        }


    }

    private ProcessorStatus getWorkflowProcessorStatus(String processorUUID, ProcessGroupStatus rootProcessGroup) {
        final List<ProcessorStatus> processorStatusList = new ArrayList<ProcessorStatus>();
        analyzeProcessGroup(rootProcessGroup, processorStatusList);

        for(ProcessorStatus status : processorStatusList) {
            if(status.getId().equals(processorUUID)) {
                return status;
            }
        }

        return null;
    }

    private void analyzeProcessGroup(ProcessGroupStatus processGroup, List<ProcessorStatus> processorStatusList) {
        processGroup.getProcessorStatus().forEach(p -> processorStatusList.add(p));
        for (ProcessGroupStatus child : processGroup.getProcessGroupStatus()) {
            analyzeProcessGroup(child, processorStatusList);
        }
    }

}
