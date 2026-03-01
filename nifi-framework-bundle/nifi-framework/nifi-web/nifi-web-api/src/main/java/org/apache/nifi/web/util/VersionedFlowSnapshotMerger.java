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
import org.apache.nifi.flow.VersionedConfigurableExtension;
import org.apache.nifi.flow.VersionedControllerService;
import org.apache.nifi.flow.VersionedNodeState;
import org.apache.nifi.flow.VersionedProcessGroup;
import org.apache.nifi.flow.VersionedProcessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility for merging component state across RegisteredFlowSnapshots returned by multiple cluster nodes.
 * In a cluster, when exporting a flow with component state, each node contributes only its own LOCAL state
 * entry (keyed by its ordinal). The coordinator combines the responses into a single snapshot that holds
 * every node's LOCAL state.
 */
public final class VersionedFlowSnapshotMerger {

    private VersionedFlowSnapshotMerger() {
    }

    /**
     * Recursively merges localNodeStates from a source process group into a target process group.
     * For each stateful component (processor or controller service), the LOCAL state entries from
     * the source are added to the target's localNodeStates list. Cluster state is identical across
     * nodes and is already present in the target.
     *
     * @param target the process group to merge into (from the first/client node response)
     * @param source the process group to merge from (from another node's response)
     */
    public static void mergeLocalNodeStates(final VersionedProcessGroup target, final VersionedProcessGroup source) {
        if (target == null || source == null) {
            return;
        }

        if (target.getProcessors() != null && source.getProcessors() != null) {
            final Map<String, VersionedProcessor> sourceProcessors = new HashMap<>();
            for (final VersionedProcessor sp : source.getProcessors()) {
                sourceProcessors.put(sp.getIdentifier(), sp);
            }
            for (final VersionedProcessor tp : target.getProcessors()) {
                mergeComponentState(tp, sourceProcessors.get(tp.getIdentifier()));
            }
        }

        if (target.getControllerServices() != null && source.getControllerServices() != null) {
            final Map<String, VersionedControllerService> sourceServices = new HashMap<>();
            for (final VersionedControllerService ss : source.getControllerServices()) {
                sourceServices.put(ss.getIdentifier(), ss);
            }
            for (final VersionedControllerService ts : target.getControllerServices()) {
                mergeComponentState(ts, sourceServices.get(ts.getIdentifier()));
            }
        }

        if (target.getProcessGroups() != null && source.getProcessGroups() != null) {
            final Map<String, VersionedProcessGroup> sourceGroups = new HashMap<>();
            for (final VersionedProcessGroup sg : source.getProcessGroups()) {
                sourceGroups.put(sg.getIdentifier(), sg);
            }
            for (final VersionedProcessGroup tg : target.getProcessGroups()) {
                final VersionedProcessGroup sg = sourceGroups.get(tg.getIdentifier());
                if (sg != null) {
                    mergeLocalNodeStates(tg, sg);
                }
            }
        }
    }

    /**
     * Merges the localNodeStates from a source component into a target component's VersionedComponentState.
     * Each node contributes its own ordinal entry to localNodeStates -- this method adds the source's entries
     * to the target's list at the corresponding index positions.
     *
     * @param target the target component (already contains state from first node)
     * @param source the source component (contains state from another node), may be null
     */
    public static void mergeComponentState(final VersionedConfigurableExtension target, final VersionedConfigurableExtension source) {
        if (source == null) {
            return;
        }

        final VersionedComponentState sourceState = source.getComponentState();
        if (sourceState == null || sourceState.getLocalNodeStates() == null) {
            return;
        }

        VersionedComponentState targetState = target.getComponentState();
        if (targetState == null) {
            targetState = new VersionedComponentState();
            target.setComponentState(targetState);
        }

        final List<VersionedNodeState> sourceList = sourceState.getLocalNodeStates();
        if (targetState.getLocalNodeStates() == null) {
            targetState.setLocalNodeStates(new ArrayList<>(sourceList));
        } else {
            final List<VersionedNodeState> targetList = targetState.getLocalNodeStates();
            while (targetList.size() < sourceList.size()) {
                targetList.add(null);
            }
            for (int i = 0; i < sourceList.size(); i++) {
                if (sourceList.get(i) != null) {
                    targetList.set(i, sourceList.get(i));
                }
            }
        }
    }
}
