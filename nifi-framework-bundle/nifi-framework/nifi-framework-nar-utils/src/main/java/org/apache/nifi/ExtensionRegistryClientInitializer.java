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
package org.apache.nifi;

import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.components.ConfigurableComponent;
import org.apache.nifi.init.ConfigurableComponentInitializer;
import org.apache.nifi.init.ReflectionUtils;
import org.apache.nifi.mock.MockComponentLogger;
import org.apache.nifi.mock.MockConfigurationContext;
import org.apache.nifi.mock.MockExtensionRegistryClientInitializationContext;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.NarCloseable;
import org.apache.nifi.registry.extension.ExtensionRegistryClient;
import org.apache.nifi.registry.extension.ExtensionRegistryClientInitializationContext;
import org.apache.nifi.reporting.InitializationException;

/**
 * Initializes an ExtensionRegistryClient using a
 * MockExtensionRegistryClientInitializationContext;
 */
public class ExtensionRegistryClientInitializer implements ConfigurableComponentInitializer {

    private final ExtensionManager extensionManager;


    public ExtensionRegistryClientInitializer(final ExtensionManager extensionManager) {
        this.extensionManager = extensionManager;
    }

    @Override
    public void initialize(final ConfigurableComponent component) throws InitializationException {
        ExtensionRegistryClient extensionRegistryClient = (ExtensionRegistryClient) component;
        ExtensionRegistryClientInitializationContext context = new MockExtensionRegistryClientInitializationContext();
        try (NarCloseable ignored = NarCloseable.withComponentNarLoader(extensionManager, component.getClass(), context.getIdentifier())) {
            extensionRegistryClient.initialize(context);
        }
    }

    @Override
    public void teardown(final ConfigurableComponent component) {
        ExtensionRegistryClient extensionRegistryClient = (ExtensionRegistryClient) component;
        try (NarCloseable ignored = NarCloseable.withComponentNarLoader(extensionManager, component.getClass(), component.getIdentifier())) {
            final MockConfigurationContext context = new MockConfigurationContext();
            ReflectionUtils.quietlyInvokeMethodsWithAnnotation(OnShutdown.class, extensionRegistryClient, new MockComponentLogger(), context);
        } finally {
            extensionManager.removeInstanceClassLoader(component.getIdentifier());
        }
    }
}
