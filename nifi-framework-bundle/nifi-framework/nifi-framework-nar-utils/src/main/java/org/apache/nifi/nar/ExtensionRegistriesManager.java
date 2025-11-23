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
package org.apache.nifi.nar;

import org.apache.nifi.bundle.Bundle;
import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.components.AsyncLoadedComponent;
import org.apache.nifi.registry.extension.ExtensionRegistryException;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Interface for the manager in charge of discovering extension via extension
 * registries
 */
public interface ExtensionRegistriesManager {

    void discoverExtensions();

    default void discoverExtensions(final boolean force) {
        discoverExtensions();
    }

    /**
     * @return the set of bundles discovered across all configured extension registry clients
     */
    java.util.Set<org.apache.nifi.flow.Bundle> getRemoteBundles();

    /**
     * @param bundle the remote bundle
     * @return the dependency coordinate for the given bundle, or {@code null} when none exists
     */
    BundleCoordinate getDependencyCoordinate(org.apache.nifi.flow.Bundle bundle);

    /**
     * @param bundle the remote bundle
     * @return the extensions contained in the bundle (empty when not known)
     */
    java.util.Set<ExtensionDefinition> getRemoteExtensions(org.apache.nifi.flow.Bundle bundle);

    Set<ExtensionDefinition> getExtensions(final Class<?> definition);

    boolean isRemoteBundle(final BundleCoordinate coordinate);

    List<BundleCoordinate> getBundleDependencies(final BundleCoordinate bundleCoordinate);

    NarInstallRequestFromRegistry getNarInstallRequest(final BundleCoordinate narDep) throws ExtensionRegistryException, IOException;

    Bundle getRemoteBundle(final BundleCoordinate bundleCoordinate);

    AsyncLoadedComponent createAsyncLoadedComponent(final String componentId);

    /**
     * Resolve a remote bundle for the requested group/artifact by selecting the most suitable available version.
     * Returns {@code null} when no remote bundle exists for the GA.
     */
    BundleCoordinate resolveRemoteBundle(final BundleCoordinate requested);

}
