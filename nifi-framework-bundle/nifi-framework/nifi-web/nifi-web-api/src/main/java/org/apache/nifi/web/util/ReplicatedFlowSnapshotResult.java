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

import jakarta.ws.rs.core.Response;
import org.apache.nifi.registry.flow.RegisteredFlowSnapshot;

/**
 * Result of a cluster-wide flow snapshot replicate-and-merge operation.
 * Exactly one of {@link #snapshot()} or {@link #passthroughResponse()} is non-null:
 * <ul>
 *     <li>{@code snapshot} is set when the coordinator successfully merged responses from all nodes.</li>
 *     <li>{@code passthroughResponse} is set when the call was forwarded (i.e. the current node is not the
 *     coordinator) or when any node returned a non-2xx response that must be propagated to the caller.</li>
 * </ul>
 */
public record ReplicatedFlowSnapshotResult(RegisteredFlowSnapshot snapshot, Response passthroughResponse) {

    public static ReplicatedFlowSnapshotResult merged(final RegisteredFlowSnapshot snapshot) {
        return new ReplicatedFlowSnapshotResult(snapshot, null);
    }

    public static ReplicatedFlowSnapshotResult passthrough(final Response response) {
        return new ReplicatedFlowSnapshotResult(null, response);
    }

    public boolean isPassthrough() {
        return passthroughResponse != null;
    }
}
