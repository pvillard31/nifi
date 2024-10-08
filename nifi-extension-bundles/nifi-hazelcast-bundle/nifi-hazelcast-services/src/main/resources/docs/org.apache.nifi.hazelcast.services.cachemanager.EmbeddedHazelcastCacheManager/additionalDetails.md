<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# EmbeddedHazelcastCacheManager

This service starts and manages an embedded Hazelcast instance. The cache manager has direct accesses to the instance -
and the data stored in it. However, the instance opens a port for potential clients to join and this cannot be
prevented. Note that this might leave the instance open for rogue clients to join.

It is possible to have multiple independent Hazelcast instances on the same host (whether via
EmbeddedHazelcastCacheManager or externally) without any interference by setting the properties accordingly. If there
are no other instances, the default cluster name and port number can simply be used.

The service supports multiple ways to set up a Hazelcast cluster. This is controlled by the property, named "Hazelcast
Clustering Strategy". The following strategies may be used:

### None

This is the default value. Used when sharing data between nodes is not required. With this value, every NiFi node in the
cluster (if it is clustered) connects to its local Hazelcast server only. The Hazelcast servers do not form a cluster.

### All Nodes

Can be used only in clustered node. Using this strategy will result a single Hazelcast cluster consisting of the
embedded instances of all the NiFi nodes. This strategy requires all Hazelcast servers listening on the same port.
Having different port numbers (based on expression for example) would prevent the cluster from forming.

The controller service automatically gathers the host list from the NiFi cluster itself when it is enabled. It is not
required for all the nodes to have been successfully joined at this point, but the join must have been initiated. When
the controller service is enabled at the start of the NiFi instance, the enabling of the service will be prevented until
the node is considered clustered.

Hazelcast can accept nodes that join at a later time. As the new node has a comprehensive list of the expected
instances - including the already existing ones and itself - Hazelcast will be able to reach the expected state. Beware:
this may take significant time.

### Explicit

Can be used only in clustered node. Explicit Clustering Strategy allows more control over the Hazelcast cluster members.
All Nodes Clustering Strategy, this strategy works with a list of Hazelcast servers, but instance discovery is not
automatic.

This strategy uses property named "Hazelcast Instances" to determine the members of the Hazelcast clusters. This list of
hosts must contain all the instances expected to be part of the cluster. The instance list may contain only hosts which
are part of the NiFi cluster. The port specified in the "Hazelcast Port" will be used as Hazelcast server port.

In case the current node is not part of the instance list, the service will start a Hazelcast client. The client then
will connect to the Hazelcast addresses specified in the instance list. Users of the service should not perceive any
difference in functionality.