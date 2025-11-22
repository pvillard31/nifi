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
package org.apache.nifi.registry.extension;

import org.apache.nifi.controller.ComponentNode;
import org.apache.nifi.controller.LoggableComponent;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.flow.Bundle;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.nar.ExtensionManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ExtensionRegistryClientNode extends ComponentNode {

    String getDescription();

    void setDescription(String description);

    InputStream getNARFile(Bundle bundle) throws ExtensionRegistryException, IOException;

    InputStream getManifestFile(Bundle bundle) throws ExtensionRegistryException, IOException;

    Set<Bundle> listBundles() throws ExtensionRegistryException, IOException;

    ExtensionBundleMetadata getBundleMetadata(Bundle bundle) throws ExtensionRegistryException, IOException;

    void setComponent(LoggableComponent<ExtensionRegistryClient> component);

    /**
     * Verifies that the given configuration is valid for the Extension Registry Client
     *
     * @param properties the proposed property values keyed by property name
     * @param variables a map of variable names to values for resolving expression language
     * @param logger a logger that can be used during verification
     * @param extensionManager extension manager used for obtaining appropriate NAR ClassLoaders
     * @return a list of verification results describing the verification outcome
     */
    List<ConfigVerificationResult> verifyConfiguration(Map<String, String> properties, Map<String, String> variables,
                                                       ComponentLog logger, ExtensionManager extensionManager);
}
