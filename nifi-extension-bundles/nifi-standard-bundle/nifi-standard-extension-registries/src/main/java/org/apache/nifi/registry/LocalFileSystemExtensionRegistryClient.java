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
package org.apache.nifi.registry;

import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyDescriptor.Builder;
import org.apache.nifi.extension.manifest.ExtensionManifest;
import org.apache.nifi.extension.manifest.parser.ExtensionManifestParser;
import org.apache.nifi.extension.manifest.parser.jaxb.JAXBExtensionManifestParser;
import org.apache.nifi.flow.Bundle;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.registry.extension.AbstractExtensionRegistryClient;
import org.apache.nifi.registry.extension.ExtensionBundleMetadata;
import org.apache.nifi.registry.extension.ExtensionRegistryClient;
import org.apache.nifi.registry.extension.ExtensionRegistryClientConfigurationContext;
import org.apache.nifi.registry.extension.ExtensionRegistryException;
import org.apache.nifi.registry.extension.VerifiableExtensionRegistryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Expectation is to have a file system structured as:
 *
 * ./org/apache/nifi/nifi-aws-nar/2.0.0/nifi-aws-nar-2.0.0.nar
 * ./org/apache/nifi/nifi-aws-nar/2.0.0/extension-manifest.xml
 */
public class LocalFileSystemExtensionRegistryClient extends AbstractExtensionRegistryClient implements ExtensionRegistryClient, VerifiableExtensionRegistryClient {

    private static final Logger LOG = LoggerFactory.getLogger(LocalFileSystemExtensionRegistryClient.class);
    private static final String MANIFEST_ENTRY = "META-INF/docs/extension-manifest.xml";

    public static final PropertyDescriptor DIRECTORY = new Builder()
            .name("Directory")
            .description("The input directory from which NAR files should be retrieved")
            .required(true)
            .addValidator(StandardValidators.createDirectoryExistsValidator(true, false))
            .build();

    private static final List<PropertyDescriptor> PROPERTIES = List.of(DIRECTORY);
    private final Map<Bundle, Path> bundlePaths = new HashMap<>();
    private final Map<Bundle, StandardExtensionBundleMetadata> bundleMetadata = new HashMap<>();
    private final ExtensionManifestParser manifestParser = new JAXBExtensionManifestParser();

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    public List<ConfigVerificationResult> verify(ExtensionRegistryClientConfigurationContext context, ComponentLog verificationLogger, Map<String, String> variables) {
        return super.verify(context, verificationLogger, variables);
    }

    @Override
    public InputStream getNARFile(ExtensionRegistryClientConfigurationContext context, Bundle bundle) throws ExtensionRegistryException, IOException {
        Path narFile = bundlePaths.get(bundle);
        if (narFile != null && Files.exists(narFile) && Files.isReadable(narFile)) {
            return Files.newInputStream(narFile);
        } else {
            throw new ExtensionRegistryException("NAR file not found for bundle: " + bundle);
        }
    }

    @Override
    public InputStream getManifestFile(ExtensionRegistryClientConfigurationContext context, Bundle bundle) throws ExtensionRegistryException, IOException {
        final ExtensionBundleMetadata metadata = getBundleMetadata(context, bundle);
        if (metadata == null || metadata.getExtensionManifest() == null) {
            throw new ExtensionRegistryException("Manifest file not found inside NAR: " + bundle);
        }
        return metadata.getExtensionManifest();
    }

    @Override
    public Set<Bundle> listBundles(ExtensionRegistryClientConfigurationContext context) throws ExtensionRegistryException, IOException {
        final String directoryValue = context.getProperty(DIRECTORY).getValue();
        if (directoryValue == null || directoryValue.isBlank()) {
            throw new ExtensionRegistryException("Directory must be specified for LocalFileSystemExtensionRegistryClient.");
        }

        bundlePaths.clear();
        bundleMetadata.clear();

        Path rootDir = Paths.get(directoryValue);
        try (Stream<Path> stream = Files.walk(rootDir)) {
            List<Path> narFiles = stream.filter(f -> Files.exists(f) && Files.isReadable(f) && f.toString().endsWith(".nar")).toList();

            for (Path narFile : narFiles) {
                final StandardExtensionBundleMetadata metadata = buildMetadata(narFile);
                if (metadata != null) {
                    final Bundle bundle = metadata.getBundle();
                    bundlePaths.put(bundle, narFile);
                    bundleMetadata.put(bundle, metadata);
                }
            }
        }
        return bundlePaths.keySet();
    }

    @Override
    public ExtensionBundleMetadata getBundleMetadata(ExtensionRegistryClientConfigurationContext context, Bundle bundle) throws ExtensionRegistryException, IOException {
        if (bundleMetadata.containsKey(bundle)) {
            return bundleMetadata.get(bundle);
        }

        final Path narFile = bundlePaths.get(bundle);
        if (narFile == null) {
            throw new ExtensionRegistryException("NAR file not found for bundle: " + bundle);
        }

        final StandardExtensionBundleMetadata metadata = buildMetadata(narFile);
        if (metadata != null) {
            bundleMetadata.put(bundle, metadata);
        }
        return metadata;
    }

    private StandardExtensionBundleMetadata buildMetadata(final Path narFile) {
        try (ZipFile narZip = new ZipFile(narFile.toFile())) {
            final ZipEntry manifestEntry = narZip.getEntry(MANIFEST_ENTRY);
            if (manifestEntry == null) {
                LOG.warn("Manifest file not found inside NAR file: {}", narFile);
                return null;
            }

            byte[] manifestBytes;
            try (InputStream manifestStream = narZip.getInputStream(manifestEntry)) {
                manifestBytes = manifestStream.readAllBytes();
            }

            final ExtensionManifest manifest = manifestParser.parse(new ByteArrayInputStream(manifestBytes));
            if (manifest == null) {
                LOG.warn("Manifest inside NAR {} could not be parsed; skipping", narFile);
                return null;
            }

            final String group = manifest.getGroupId();
            final String artifact = manifest.getArtifactId();
            final String version = manifest.getVersion();

            if (group == null || artifact == null || version == null) {
                LOG.warn("Manifest inside NAR {} missing bundle coordinates; skipping", narFile);
                return null;
            }

            final Bundle bundle = new Bundle(group, artifact, version, true);
            bundle.setSystemApiVersion(manifest.getSystemApiVersion());
            if (manifest.getParentNar() != null) {
                bundle.setDependencyGroup(manifest.getParentNar().getGroupId());
                bundle.setDependencyArtifact(manifest.getParentNar().getArtifactId());
                bundle.setDependencyVersion(manifest.getParentNar().getVersion());
            }

            final ZipEntry mfEntry = narZip.getEntry("META-INF/MANIFEST.MF");
            if (mfEntry != null) {
                try (InputStream mfStream = narZip.getInputStream(mfEntry)) {
                    final java.util.jar.Manifest jarManifest = new java.util.jar.Manifest(mfStream);
                    final java.util.jar.Attributes attrs = jarManifest.getMainAttributes();
                    final String depGroup = attrs.getValue("Nar-Dependency-Group");
                    final String depId = attrs.getValue("Nar-Dependency-Id");
                    final String depVersion = attrs.getValue("Nar-Dependency-Version");
                    if (depGroup != null && depId != null && depVersion != null) {
                        bundle.setDependencyGroup(depGroup);
                        bundle.setDependencyArtifact(depId);
                        bundle.setDependencyVersion(depVersion);
                    }
                }
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Discovered bundle {}:{}:{} (dependency={}): {}", group, artifact, version,
                        bundle.getDependencyGroup() == null ? "<none>" :
                                bundle.getDependencyGroup() + ":" + bundle.getDependencyArtifact() + ":" + bundle.getDependencyVersion(),
                        narFile);
            }
            return new StandardExtensionBundleMetadata(bundle, manifestBytes);
        } catch (Exception e) {
            LOG.warn("Failed to read manifest from NAR file {}: {}", narFile, e.getMessage());
            return null;
        }
    }

    private static class StandardExtensionBundleMetadata implements ExtensionBundleMetadata {
        private final Bundle bundle;
        private final byte[] manifestBytes;

        StandardExtensionBundleMetadata(final Bundle bundle, final byte[] manifestBytes) {
            this.bundle = bundle;
            this.manifestBytes = manifestBytes;
        }

        @Override
        public Bundle getBundle() {
            return bundle;
        }

        @Override
        public InputStream getExtensionManifest() {
            return manifestBytes == null ? null : new ByteArrayInputStream(manifestBytes);
        }
    }
}
