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

import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.bundle.BundleDetails;
import org.apache.nifi.components.AsyncLoadedComponent;
import org.apache.nifi.controller.flow.StandardFlowManager;
import org.apache.nifi.extension.manifest.Extension;
import org.apache.nifi.extension.manifest.ExtensionManifest;
import org.apache.nifi.extension.manifest.ExtensionType;
import org.apache.nifi.extension.manifest.ProvidedServiceAPI;
import org.apache.nifi.extension.manifest.parser.ExtensionManifestParser;
import org.apache.nifi.extension.manifest.parser.jaxb.JAXBExtensionManifestParser;
import org.apache.nifi.flow.Bundle;
import org.apache.nifi.components.validation.ValidationStatus;
import org.apache.nifi.nar.ExtensionDefinition.ExtensionRuntime;
import org.apache.nifi.registry.extension.ExtensionRegistryClientNode;
import org.apache.nifi.registry.extension.ExtensionRegistryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class StandardExtensionRegistriesManager implements ExtensionRegistriesManager {

    private static final Logger LOG = LoggerFactory.getLogger(StandardExtensionRegistriesManager.class);

    private final StandardFlowManager flowManager;
    private final Map<ExtensionRegistryClientNode, Set<Bundle>> allRemoteBundles = new HashMap<>();
    private final Map<Bundle, Set<ExtensionDefinition>> remoteBundlesExtensions = new HashMap<>();
    private final Map<Bundle, BundleCoordinate> bundleDependencies = new HashMap<>();
    private final ExtensionManifestParser manifestParser = new JAXBExtensionManifestParser();
    private Instant lastDiscoveryTime = Instant.EPOCH;
    private static final Duration MIN_DISCOVERY_INTERVAL = Duration.ofSeconds(30);

    public StandardExtensionRegistriesManager(StandardFlowManager flowManager) {
        this.flowManager = flowManager;
    }

    @Override
    public void discoverExtensions() {
        discoverExtensions(false);
    }

    @Override
    public void discoverExtensions(final boolean force) {
        final Instant now = Instant.now();
        if (!force && !allRemoteBundles.isEmpty() && now.isBefore(lastDiscoveryTime.plus(MIN_DISCOVERY_INTERVAL))) {
            LOG.debug("Skipping extension discovery; last run at {}", lastDiscoveryTime);
            return;
        }
        lastDiscoveryTime = now;

        allRemoteBundles.clear();
        remoteBundlesExtensions.clear();
        bundleDependencies.clear();
        for (ExtensionRegistryClientNode client : flowManager.getAllExtensionRegistryClients()) {
            if (client.getValidationStatus() != ValidationStatus.VALID) {
                LOG.info("Skipping extension discovery for {} because it is not valid", client.getName());
                continue;
            }
            LOG.info("Discovering extensions with extension registry client {}", client.getName());
            try {
                Set<Bundle> bundles = client.listBundles();
                LOG.info("Listed {} bundles with extension registry client {}", bundles.size(), client.getName());
                allRemoteBundles.put(client, bundles);

                for (Bundle bundle : bundles) {
                    try {
                        LOG.debug("Reading extension manifest for bundle {}", bundle);
                        try (InputStream manifestStream = client.getManifestFile(bundle)) {
                            if (manifestStream == null) {
                                LOG.warn("No extension manifest found for {}; skipping bundle", bundle);
                                continue;
                            }

                            ExtensionManifest manifest = manifestParser.parse(manifestStream);
                            if (manifest == null) {
                                LOG.warn("No extension manifest found for {}; skipping bundle", bundle);
                                continue;
                            }
                        List<Extension> extensions = manifest.getExtensions();
                        Set<ExtensionDefinition> extensionDefinitions = new HashSet<>();

                        BundleCoordinate dependencyCoordinate = buildDependencyCoordinate(bundle, manifest);
                        bundleDependencies.put(bundle, dependencyCoordinate);
                        LOG.debug("Registered remote bundle {} with dependency {}", bundle,
                                dependencyCoordinate == null ? "<none>" : dependencyCoordinate);

                        if (bundle.getSystemApiVersion() == null && manifest.getSystemApiVersion() != null) {
                            bundle.setSystemApiVersion(manifest.getSystemApiVersion());
                        }

                        BundleDetails bundleDetails = new BundleDetails.Builder()
                                .workingDir(new File(".")) // cannot be null but not relevant at this point
                                .coordinate(new BundleCoordinate(bundle.getGroup(), bundle.getArtifact(), bundle.getVersion()))
                                .dependencyCoordinate(dependencyCoordinate)
                                .buildTag(manifest.getBuildInfo().getTag())
                                .buildRevision(manifest.getBuildInfo().getRevision())
                                .buildBranch(manifest.getBuildInfo().getBranch())
                                .buildTimestamp(manifest.getBuildInfo().getTimestamp())
                                .buildJdk(manifest.getBuildInfo().getJdk())
                                .builtBy(manifest.getBuildInfo().getBuiltBy())
                                .build();

                        final org.apache.nifi.bundle.Bundle newBundle = new org.apache.nifi.bundle.Bundle(bundleDetails, getClass().getClassLoader(), true);

                        for (Extension extension : extensions) {
                            final List<ExtensionDefinition.ServiceAPI> providedApis = new ArrayList<>();
                            if (extension.getProvidedServiceAPIs() != null) {
                                for (ProvidedServiceAPI api : extension.getProvidedServiceAPIs()) {
                                    providedApis.add(new ExtensionDefinition.ServiceAPI(api.getClassName(), api.getGroupId(), api.getArtifactId(), api.getVersion()));
                                }
                            }

                            final ExtensionDefinition extensionDefinition = new ExtensionDefinition.Builder()
                                    .implementationClassName(extension.getName())
                                    .runtime(ExtensionRuntime.JAVA)
                                    .bundle(newBundle)
                                    .extensionType(getExtensionTypeClass(extension.getType()))
                                    .description(extension.getDescription())
                                    .tags(extension.getTags())
                                    .version(manifest.getVersion())
                                    .providedServiceAPIs(providedApis)
                                    .build();
                            extensionDefinitions.add(extensionDefinition);
                        }

                        remoteBundlesExtensions.put(bundle, extensionDefinitions);
                        }
                    } catch (IOException e) {
                        LOG.error("Failed to extension manifest for bundle " + bundle, e);
                    }

                }
            } catch (ExtensionRegistryException | IOException e) {
                LOG.error("Failed to list extensions with client " + client.getName(), e);
            }
        }
    }

    private BundleCoordinate buildDependencyCoordinate(final Bundle bundle, final ExtensionManifest manifest) {
        // Prefer dependency advertised directly on bundle
        if (bundle.getDependencyGroup() != null && bundle.getDependencyArtifact() != null && bundle.getDependencyVersion() != null) {
            return new BundleCoordinate(bundle.getDependencyGroup(), bundle.getDependencyArtifact(), bundle.getDependencyVersion());
        }

        // Fallback to parentNar in extension manifest if present
        if (manifest.getParentNar() != null) {
            return new BundleCoordinate(manifest.getParentNar().getGroupId(), manifest.getParentNar().getArtifactId(), manifest.getParentNar().getVersion());
        }

        return null;
    }

    private Class<?> getExtensionTypeClass(ExtensionType type) {
        switch (type) {
        case CONTROLLER_SERVICE:
            return org.apache.nifi.controller.ControllerService.class;
        case REPORTING_TASK:
            return org.apache.nifi.reporting.ReportingTask.class;
        case PROCESSOR:
            return org.apache.nifi.processor.Processor.class;
        case FLOW_ANALYSIS_RULE:
            return org.apache.nifi.flowanalysis.FlowAnalysisRuleContext.class;
        case PARAMETER_PROVIDER:
            return org.apache.nifi.parameter.ParameterProvider.class;
        default:
            return null;
        }
    }

    @Override
    public Set<ExtensionDefinition> getExtensions(Class<?> definition) {
        Set<ExtensionDefinition> extensions = new HashSet<>();

        for (Set<ExtensionDefinition> extensionDefinitions : remoteBundlesExtensions.values()) {
            for (ExtensionDefinition extensionDefinition : extensionDefinitions) {
                if (definition.isAssignableFrom(extensionDefinition.getExtensionType())) {
                    extensions.add(extensionDefinition);
                }
            }
        }

        return extensions;
    }

    @Override
    public boolean isRemoteBundle(BundleCoordinate coordinate) {
        return remoteBundlesExtensions.keySet()
                .stream()
                .filter(b -> b.getGroup().equals(coordinate.getGroup()) && b.getArtifact().equals(coordinate.getId()) && b.getVersion().equals(coordinate.getVersion()))
                .findAny()
                .isPresent();
    }

    @Override
    public List<BundleCoordinate> getBundleDependencies(BundleCoordinate bundleCoordinate) {
        return getBundleDependencies(bundleCoordinate, new HashSet<>());
    }

    private List<BundleCoordinate> getBundleDependencies(final BundleCoordinate bundleCoordinate, final Set<BundleCoordinate> visited) {
        List<BundleCoordinate> dependencies = new ArrayList<>();
        if (bundleCoordinate == null) {
            return dependencies;
        }

        if (visited.contains(bundleCoordinate)) {
            LOG.warn("Detected cyclic dependency while resolving bundle {}. Current chain: {}", bundleCoordinate, visited);
            return dependencies;
        }
        visited.add(bundleCoordinate);

        dependencies.add(bundleCoordinate);

        Optional<Bundle> bundle = remoteBundlesExtensions.keySet()
                .stream()
                .filter(b -> b.getGroup().equals(bundleCoordinate.getGroup()) && b.getArtifact().equals(bundleCoordinate.getId()) && b.getVersion().equals(bundleCoordinate.getVersion()))
                .findAny();

        if (bundle.isEmpty()) {
            LOG.debug("Bundle {} not found among remote bundles when resolving dependencies", bundleCoordinate);
            return dependencies;
        }

        BundleCoordinate parent = bundleDependencies.get(bundle.get());
        LOG.debug("Bundle {} dependency resolved to {}", bundleCoordinate, parent == null ? "<none>" : parent);
        if (parent != null) {
            dependencies.addAll(getBundleDependencies(parent, visited));
        }
        return dependencies;
    }

    @Override
    public NarInstallRequestFromRegistry getNarInstallRequest(BundleCoordinate narDep) throws ExtensionRegistryException, IOException {

        Optional<Bundle> bundle = remoteBundlesExtensions.keySet()
                .stream()
                .filter(b -> b.getGroup().equals(narDep.getGroup()) && b.getArtifact().equals(narDep.getId()) && b.getVersion().equals(narDep.getVersion()))
                .findAny();

        if (bundle.isEmpty()) {
            LOG.warn("Requested NAR {} not found among remote bundles; skipping download request.", narDep);
            return null;
        }

        final Optional<ExtensionRegistryClientNode> registryClient = allRemoteBundles.keySet()
                .stream()
                .filter(c -> allRemoteBundles.get(c).contains(bundle.get()))
                .findAny();

        if (registryClient.isEmpty()) {
            LOG.warn("Requested NAR {} not associated with any registry client; skipping download request.", narDep);
            return null;
        }

        return new NarInstallRequestFromRegistry(registryClient.get().getNARFile(bundle.get()), registryClient.get().getName());
    }

    @Override
    public org.apache.nifi.bundle.Bundle getRemoteBundle(BundleCoordinate bundleCoordinate) {
        final Optional<Bundle> bundle = remoteBundlesExtensions.keySet()
                .stream()
                .filter(b -> b.getGroup().equals(bundleCoordinate.getGroup()) && b.getArtifact().equals(bundleCoordinate.getId()) && b.getVersion().equals(bundleCoordinate.getVersion()))
                .findAny();

        if (bundle.isPresent()) {
            Optional<ExtensionDefinition> extension = remoteBundlesExtensions.get(bundle.get()).stream().findAny();
            return extension.isPresent() ? extension.get().getBundle() : null;
        } else {
            return null;
        }
    }

    @Override
    public AsyncLoadedComponent createAsyncLoadedComponent(final String componentId) {
        return new GenericAsyncLoadedComponent(componentId);
    }

    @Override
    public BundleCoordinate resolveRemoteBundle(final BundleCoordinate requested) {
        LOG.debug("resolveRemoteBundle invoked for {}", requested.getCoordinate());

        List<Bundle> candidates = remoteBundlesExtensions.keySet().stream()
                .filter(b -> b.getGroup().equals(requested.getGroup()) && b.getArtifact().equals(requested.getId()))
                .toList();

        if (candidates.isEmpty()) {
            // attempt a refresh if not yet populated
            try {
                discoverExtensions();
            } catch (final Exception e) {
                LOG.warn("Unable to refresh extension registries while resolving bundle {}", requested.getCoordinate(), e);
            }

            candidates = remoteBundlesExtensions.keySet().stream()
                    .filter(b -> b.getGroup().equals(requested.getGroup()) && b.getArtifact().equals(requested.getId()))
                    .toList();

            if (candidates.isEmpty()) {
                LOG.debug("No remote bundles found for {} after refresh", requested.getCoordinate());
                return null;
            }
        }

        LOG.debug("Resolving remote bundle {} against {} candidate(s): {}", requested.getCoordinate(), candidates.size(),
                candidates.stream().map(Bundle::getVersion).toList());

        // exact match wins immediately
        for (Bundle bundle : candidates) {
            if (bundle.getVersion().equals(requested.getVersion())) {
                LOG.info("Resolved remote bundle {} to exact match {}", requested.getCoordinate(), bundle.getVersion());
                return requested;
            }
        }

        Bundle best = null;
        for (Bundle bundle : candidates) {
            if (best == null) {
                best = bundle;
                continue;
            }

            if (compareVersions(bundle.getVersion(), best.getVersion()) > 0) {
                best = bundle;
            }
        }

        if (best != null) {
            LOG.debug("Resolved bundle {} to remote version {}", requested.getCoordinate(), best.getVersion());
            return new BundleCoordinate(best.getGroup(), best.getArtifact(), best.getVersion());
        }

        LOG.info("No remote bundle could be resolved for {}", requested.getCoordinate());
        return null;
    }

    private int compareVersions(final String v1, final String v2) {
        if (v1 == null && v2 == null) {
            return 0;
        } else if (v1 == null) {
            return -1;
        } else if (v2 == null) {
            return 1;
        }

        final String[] parts1 = v1.split("-", 2);
        final String[] parts2 = v2.split("-", 2);

        final String base1 = parts1[0];
        final String base2 = parts2[0];

        final String[] digits1 = base1.split("\\.");
        final String[] digits2 = base2.split("\\.");

        final int maxLen = Math.max(digits1.length, digits2.length);
        for (int i = 0; i < maxLen; i++) {
            final int d1 = i < digits1.length ? parseIntSafe(digits1[i]) : 0;
            final int d2 = i < digits2.length ? parseIntSafe(digits2[i]) : 0;
            if (d1 != d2) {
                return Integer.compare(d1, d2);
            }
        }

        final boolean hasSuffix1 = parts1.length > 1 && parts1[1] != null && !parts1[1].isBlank();
        final boolean hasSuffix2 = parts2.length > 1 && parts2[1] != null && !parts2[1].isBlank();

        if (hasSuffix1 == hasSuffix2) {
            return 0;
        }
        return hasSuffix1 ? -1 : 1; // prefer version without suffix
    }

    private int parseIntSafe(final String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

}
