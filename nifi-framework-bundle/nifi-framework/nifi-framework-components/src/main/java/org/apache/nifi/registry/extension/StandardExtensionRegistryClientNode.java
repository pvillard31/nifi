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

import org.apache.nifi.annotation.behavior.Restricted;
import org.apache.nifi.annotation.documentation.DeprecationNotice;
import org.apache.nifi.authorization.Resource;
import org.apache.nifi.authorization.resource.Authorizable;
import org.apache.nifi.authorization.resource.ResourceFactory;
import org.apache.nifi.authorization.resource.ResourceType;
import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.components.ConfigurableComponent;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.ConfigVerificationResult.Outcome;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.validation.ValidationStatus;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.validation.ValidationStatus;
import org.apache.nifi.components.validation.ValidationTrigger;
import org.apache.nifi.controller.AbstractComponentNode;
import org.apache.nifi.controller.LoggableComponent;
import org.apache.nifi.controller.ReloadComponent;
import org.apache.nifi.controller.TerminationAwareLogger;
import org.apache.nifi.controller.ValidationContextFactory;
import org.apache.nifi.controller.flow.FlowManager;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.flow.Bundle;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.InstanceClassLoader;
import org.apache.nifi.nar.NarCloseable;
import org.apache.nifi.parameter.ParameterContext;
import org.apache.nifi.parameter.ParameterLookup;
import org.apache.nifi.util.CharacterFilterUtils;
import org.apache.nifi.util.file.classloader.ClassLoaderUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class StandardExtensionRegistryClientNode extends AbstractComponentNode implements ExtensionRegistryClientNode {
    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(StandardExtensionRegistryClientNode.class);

    @SuppressWarnings("unused")
    private final FlowManager flowManager;
    private final Authorizable parent;
    private final AtomicReference<LoggableComponent<ExtensionRegistryClient>> client = new AtomicReference<>();
    private final ControllerServiceProvider serviceProvider;
    private volatile String description;

    public StandardExtensionRegistryClientNode(
            final Authorizable parent,
            final FlowManager flowManager,
            final LoggableComponent<ExtensionRegistryClient> client,
            final String id,
            final ValidationContextFactory validationContextFactory,
            final ControllerServiceProvider serviceProvider,
            final String componentType,
            final String componentCanonicalClass,
            final ReloadComponent reloadComponent,
            final ExtensionManager extensionManager,
            final ValidationTrigger validationTrigger,
            final boolean isExtensionMissing) {
        super(id, validationContextFactory, serviceProvider, componentType, componentCanonicalClass, reloadComponent, extensionManager, validationTrigger, isExtensionMissing);
        this.parent = parent;
        this.flowManager = flowManager;
        this.serviceProvider = serviceProvider;
        this.client.set(client);
    }

    @Override
    public Authorizable getParentAuthorizable() {
        return parent;
    }

    @Override
    public Resource getResource() {
        return ResourceFactory.getComponentResource(ResourceType.Controller, getIdentifier(), getName());
    }

    @Override
    public String getProcessGroupIdentifier() {
        return null;
    }

    @Override
    protected List<ValidationResult> validateConfig() {
        return Collections.emptyList();
    }

    @Override
    public void verifyModifiable() throws IllegalStateException {
        // FlowRegistryClients has no running state
    }

    @Override
    protected ParameterContext getParameterContext() {
        return null;
    }

    @Override
    public void reload(Set<URL> additionalUrls) throws Exception {
        final String additionalResourcesFingerprint = ClassLoaderUtils.generateAdditionalUrlsFingerprint(additionalUrls, determineClasloaderIsolationKey());
        setAdditionalResourcesFingerprint(additionalResourcesFingerprint);
        getReloadComponent().reload(this, getCanonicalClassName(), getBundleCoordinate(), additionalUrls);
    }

    @Override
    public BundleCoordinate getBundleCoordinate() {
        return client.get().getBundleCoordinate();
    }

    @Override
    public ConfigurableComponent getComponent() {
        return client.get().getComponent();
    }

    @Override
    public TerminationAwareLogger getLogger() {
        return client.get().getLogger();
    }

    @Override
    public Class<?> getComponentClass() {
        return client.get().getComponent().getClass();
    }

    @Override
    public boolean isRestricted() {
        return getComponentClass().isAnnotationPresent(Restricted.class);
    }

    @Override
    public boolean isDeprecated() {
        return getComponentClass().isAnnotationPresent(DeprecationNotice.class);
    }

    @Override
    public boolean isValidationNecessary() {
        return getValidationStatus() != ValidationStatus.VALID;
    }

    @Override
    public Optional<ProcessGroup> getParentProcessGroup() {
        return Optional.empty();
    }

    @Override
    public ParameterLookup getParameterLookup() {
        return ParameterLookup.EMPTY;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public void setDescription(final String description) {
        this.description = CharacterFilterUtils.filterInvalidXmlCharacters(description);
    }

    @Override
    public void setComponent(final LoggableComponent<ExtensionRegistryClient> component) {
        client.set(component);
    }

    @Override
    public InputStream getNARFile(Bundle bundle) throws ExtensionRegistryException, IOException {
        return client.get().getComponent().getNARFile(getConfigurationContext(), bundle);
    }

    @Override
    public InputStream getManifestFile(Bundle bundle) throws ExtensionRegistryException, IOException {
        return client.get().getComponent().getManifestFile(getConfigurationContext(), bundle);
    }

    @Override
    public Set<Bundle> listBundles() throws ExtensionRegistryException, IOException {
        return client.get().getComponent().listBundles(getConfigurationContext());
    }

    @Override
    public ExtensionBundleMetadata getBundleMetadata(Bundle bundle) throws ExtensionRegistryException, IOException {
        return client.get().getComponent().getBundleMetadata(getConfigurationContext(), bundle);
    }

    @Override
    public List<ConfigVerificationResult> verifyConfiguration(final Map<String, String> properties, final Map<String, String> variables,
                                                               final ComponentLog logger, final ExtensionManager extensionManager) {
        final List<ConfigVerificationResult> results = new java.util.ArrayList<>();

        try {
            final Map<PropertyDescriptor, String> propertyValues = new LinkedHashMap<>(getRawPropertyValues());
            if (properties != null) {
                for (final Map.Entry<String, String> entry : properties.entrySet()) {
                    final PropertyDescriptor descriptor = getPropertyDescriptor(entry.getKey());
                    propertyValues.put(descriptor, entry.getValue());
                }
            }

            final ExtensionRegistryClientConfigurationContext configurationContext =
                    new StandardExtensionRegistryClientConfigurationContext(null, propertyValues, this, serviceProvider);

            results.addAll(super.verifyConfig(propertyValues, getAnnotationData(), null));
            if (!results.isEmpty() && results.stream().anyMatch(result -> result.getOutcome() == Outcome.FAILED)) {
                return results;
            }

            final ConfigurableComponent configurableComponent = client.get().getComponent();
            if (configurableComponent instanceof VerifiableExtensionRegistryClient verifiableClient) {
                final boolean classpathDifferent = isClasspathDifferent(propertyValues);
                final Map<String, String> verificationVariables = variables == null ? Collections.emptyMap() : variables;

                if (classpathDifferent) {
                    final org.apache.nifi.bundle.Bundle bundle = extensionManager.getBundle(getBundleCoordinate());
                    final Set<URL> classpathUrls = getAdditionalClasspathResources(propertyValues.keySet(),
                            descriptor -> configurationContext.getProperty(descriptor).getValue());
                    final ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();
                    final String isolationKey = getClassLoaderIsolationKey(configurationContext);

                    try (final InstanceClassLoader detectedClassLoader = extensionManager.createInstanceClassLoader(
                            getComponentType(), getIdentifier(), bundle, classpathUrls, false, isolationKey)) {
                        Thread.currentThread().setContextClassLoader(detectedClassLoader);
                        results.addAll(verifiableClient.verify(configurationContext, logger, verificationVariables));
                    } finally {
                        Thread.currentThread().setContextClassLoader(currentClassLoader);
                    }
                } else {
                    try (final NarCloseable ignored = NarCloseable.withComponentNarLoader(extensionManager,
                            configurableComponent.getClass(), getIdentifier())) {
                        results.addAll(verifiableClient.verify(configurationContext, logger, verificationVariables));
                    }
                }
            } else {
                getLogger().debug("{} does not support verification. Skipping additional verification beyond validation.", this);
            }
        } catch (final Throwable t) {
            getLogger().error("Failed to perform verification of Extension Registry Client configuration for {}", this, t);

            results.add(new ConfigVerificationResult.Builder()
                    .verificationStepName("Perform Verification")
                    .outcome(Outcome.FAILED)
                    .explanation("Encountered unexpected failure when attempting to perform verification: " + t)
                    .build());
        }

        return results;
    }

    private ExtensionRegistryClientConfigurationContext getConfigurationContext() {
        return new StandardExtensionRegistryClientConfigurationContext(null, getRawPropertyValues(), this, serviceProvider);
    }

}
