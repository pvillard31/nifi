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

package org.apache.nifi.web.util;

import org.apache.nifi.flow.VersionedComponentState;
import org.apache.nifi.flow.VersionedControllerService;
import org.apache.nifi.flow.VersionedNodeState;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flow.VersionedProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class VersionedFlowSnapshotMergerTest {

    @Test
    public void testMergeLocalNodeStatesAcrossProcessorsAndControllerServices() {
        final VersionedProcessor targetProcessor = createProcessor("proc-1", state(0, Map.of("key", "node-0")));
        final VersionedProcessor sourceProcessor = createProcessor("proc-1", state(1, Map.of("key", "node-1")));

        final VersionedControllerService targetService = createControllerService("svc-1", state(0, Map.of("a", "0")));
        final VersionedControllerService sourceService = createControllerService("svc-1", state(1, Map.of("a", "1")));

        final VersionedProcessGroup target = createProcessGroup("root", List.of(targetProcessor), List.of(targetService));
        final VersionedProcessGroup source = createProcessGroup("root", List.of(sourceProcessor), List.of(sourceService));

        VersionedFlowSnapshotMerger.mergeLocalNodeStates(target, source);

        final List<VersionedNodeState> mergedProcessorState = target.getProcessors().iterator().next().getComponentState().getLocalNodeStates();
        assertEquals(2, mergedProcessorState.size());
        assertEquals("node-0", mergedProcessorState.get(0).getState().get("key"));
        assertEquals("node-1", mergedProcessorState.get(1).getState().get("key"));

        final List<VersionedNodeState> mergedServiceState = target.getControllerServices().iterator().next().getComponentState().getLocalNodeStates();
        assertEquals(2, mergedServiceState.size());
        assertEquals("0", mergedServiceState.get(0).getState().get("a"));
        assertEquals("1", mergedServiceState.get(1).getState().get("a"));
    }

    @Test
    public void testMergeLocalNodeStatesRecursesIntoChildProcessGroups() {
        final VersionedProcessor targetChildProcessor = createProcessor("child-proc", state(0, Map.of("k", "v0")));
        final VersionedProcessor sourceChildProcessor = createProcessor("child-proc", state(1, Map.of("k", "v1")));

        final VersionedProcessGroup targetChild = createProcessGroup("child", List.of(targetChildProcessor), List.of());
        final VersionedProcessGroup sourceChild = createProcessGroup("child", List.of(sourceChildProcessor), List.of());

        final VersionedProcessGroup target = createProcessGroup("parent", List.of(), List.of());
        target.setProcessGroups(new LinkedHashSet<>(List.of(targetChild)));

        final VersionedProcessGroup source = createProcessGroup("parent", List.of(), List.of());
        source.setProcessGroups(new LinkedHashSet<>(List.of(sourceChild)));

        VersionedFlowSnapshotMerger.mergeLocalNodeStates(target, source);

        final VersionedProcessor mergedChild = target.getProcessGroups().iterator().next().getProcessors().iterator().next();
        final List<VersionedNodeState> merged = mergedChild.getComponentState().getLocalNodeStates();
        assertEquals(2, merged.size());
        assertEquals("v0", merged.get(0).getState().get("k"));
        assertEquals("v1", merged.get(1).getState().get("k"));
    }

    @Test
    public void testMergeLocalNodeStatesHandlesMissingSourceComponent() {
        final VersionedProcessor targetProcessor = createProcessor("proc-1", state(0, Map.of("key", "node-0")));
        final VersionedProcessGroup target = createProcessGroup("root", List.of(targetProcessor), List.of());
        final VersionedProcessGroup source = createProcessGroup("root", List.of(), List.of());

        VersionedFlowSnapshotMerger.mergeLocalNodeStates(target, source);

        final List<VersionedNodeState> states = target.getProcessors().iterator().next().getComponentState().getLocalNodeStates();
        assertEquals(1, states.size());
        assertEquals("node-0", states.get(0).getState().get("key"));
    }

    @Test
    public void testMergeLocalNodeStatesWithNullInputsDoesNothing() {
        VersionedFlowSnapshotMerger.mergeLocalNodeStates(null, null);
        VersionedFlowSnapshotMerger.mergeLocalNodeStates(createProcessGroup("a", List.of(), List.of()), null);
        VersionedFlowSnapshotMerger.mergeLocalNodeStates(null, createProcessGroup("a", List.of(), List.of()));
    }

    @Test
    public void testMergeComponentStateCreatesTargetStateWhenMissing() {
        final VersionedProcessor target = createProcessor("p", null);
        final VersionedProcessor source = createProcessor("p", state(1, Map.of("k", "from-1")));

        VersionedFlowSnapshotMerger.mergeComponentState(target, source);

        assertNotNull(target.getComponentState());
        final List<VersionedNodeState> merged = target.getComponentState().getLocalNodeStates();
        assertEquals(2, merged.size());
        assertNull(merged.get(0));
        assertEquals("from-1", merged.get(1).getState().get("k"));
    }

    @Test
    public void testMergeComponentStateOverlaysNonNullEntries() {
        final List<VersionedNodeState> targetList = new ArrayList<>(Arrays.asList(new VersionedNodeState(Map.of("k", "t0")), null));
        final VersionedComponentState targetState = new VersionedComponentState();
        targetState.setLocalNodeStates(targetList);
        final VersionedProcessor target = createProcessor("p", targetState);

        final List<VersionedNodeState> sourceList = new ArrayList<>(Arrays.asList(null, new VersionedNodeState(Map.of("k", "s1"))));
        final VersionedComponentState sourceState = new VersionedComponentState();
        sourceState.setLocalNodeStates(sourceList);
        final VersionedProcessor source = createProcessor("p", sourceState);

        VersionedFlowSnapshotMerger.mergeComponentState(target, source);

        final List<VersionedNodeState> merged = target.getComponentState().getLocalNodeStates();
        assertEquals(2, merged.size());
        assertEquals("t0", merged.get(0).getState().get("k"));
        assertEquals("s1", merged.get(1).getState().get("k"));
    }

    @Test
    public void testMergeComponentStateWithNullSourceIsNoOp() {
        final VersionedComponentState initialState = new VersionedComponentState();
        initialState.setLocalNodeStates(new ArrayList<>(List.of(new VersionedNodeState(Map.of("k", "v")))));
        final VersionedProcessor target = createProcessor("p", initialState);

        VersionedFlowSnapshotMerger.mergeComponentState(target, null);

        assertEquals(1, target.getComponentState().getLocalNodeStates().size());
        assertEquals("v", target.getComponentState().getLocalNodeStates().get(0).getState().get("k"));
    }

    private VersionedComponentState state(final int ordinal, final Map<String, String> values) {
        final VersionedComponentState state = new VersionedComponentState();
        final List<VersionedNodeState> list = new ArrayList<>();
        for (int i = 0; i <= ordinal; i++) {
            list.add(null);
        }
        list.set(ordinal, new VersionedNodeState(values));
        state.setLocalNodeStates(list);
        return state;
    }

    private VersionedProcessor createProcessor(final String id, final VersionedComponentState state) {
        final VersionedProcessor processor = new VersionedProcessor();
        processor.setIdentifier(id);
        processor.setComponentState(state);
        return processor;
    }

    private VersionedControllerService createControllerService(final String id, final VersionedComponentState state) {
        final VersionedControllerService service = new VersionedControllerService();
        service.setIdentifier(id);
        service.setComponentState(state);
        return service;
    }

    private VersionedProcessGroup createProcessGroup(final String id, final List<VersionedProcessor> processors, final List<VersionedControllerService> services) {
        final VersionedProcessGroup group = new VersionedProcessGroup();
        group.setIdentifier(id);
        group.setProcessors(new LinkedHashSet<>(processors));
        group.setControllerServices(new LinkedHashSet<>(services));
        group.setProcessGroups(Set.of());
        return group;
    }
}
