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

package org.apache.nifi.web.dao.impl;

import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.logging.LogRepository;
import org.apache.nifi.logging.StandardLoggingContext;
import org.apache.nifi.logging.repository.NopLogRepository;
import org.apache.nifi.processor.SimpleProcessLogger;
import org.apache.nifi.registry.extension.ExtensionRegistryClientNode;
import org.apache.nifi.registry.extension.ExtensionRegistryClientUserContext;
import org.apache.nifi.util.BundleUtils;
import org.apache.nifi.web.ResourceNotFoundException;
import org.apache.nifi.components.validation.ValidationStatus;
import org.apache.nifi.web.api.dto.ConfigVerificationResultDTO;
import org.apache.nifi.web.api.dto.ExtensionRegistryClientDTO;
import org.apache.nifi.web.dao.ExtensionRegistryDAO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class StandardExtensionRegistryDAO extends ComponentDAO implements ExtensionRegistryDAO {
    private FlowController flowController;

    @Override
    public ExtensionRegistryClientNode createExtensionRegistryClient(ExtensionRegistryClientDTO extensionRegistryClientDto) {
        if (extensionRegistryClientDto.getType() == null) {
            throw new IllegalArgumentException("The extension registry client type must be specified.");
        }

        verifyCreate(flowController.getExtensionManager(), extensionRegistryClientDto.getType(), extensionRegistryClientDto.getBundle());

        final BundleCoordinate bundleCoordinate = BundleUtils.getBundle(flowController.getExtensionManager(), extensionRegistryClientDto.getType(), extensionRegistryClientDto.getBundle());
        final ExtensionRegistryClientNode extensionRegistryClient = flowController.getFlowManager()
                .createExtensionRegistryClient(
                        extensionRegistryClientDto.getType(), extensionRegistryClientDto.getId(), bundleCoordinate, Collections.emptySet(), true, true, null);

        configureExtensionRegistry(extensionRegistryClient, extensionRegistryClientDto);

        if (extensionRegistryClient.getValidationStatus() == ValidationStatus.VALID) {
            flowController.getExtensionRegistriesManager().discoverExtensions(true);
        }

        return extensionRegistryClient;
    }

    @Override
    public ExtensionRegistryClientNode updateExtensionRegistryClient(ExtensionRegistryClientDTO extensionRegistryClientDto) {
        final ExtensionRegistryClientNode client = getExtensionRegistryClient(extensionRegistryClientDto.getId());

        // ensure we can perform the update
        verifyUpdate(client, extensionRegistryClientDto);

        // perform the update
        configureExtensionRegistry(client, extensionRegistryClientDto);

        return client;
    }

    @Override
    public ExtensionRegistryClientNode getExtensionRegistryClient(String extensionRegistryId) {
        final ExtensionRegistryClientNode registry = flowController.getFlowManager().getExtensionRegistryClient(extensionRegistryId);

        if (registry == null) {
            throw new ResourceNotFoundException("Unable to find Extension Registry Client with id '" + extensionRegistryId + "'");
        }

        return registry;
    }

    @Override
    public Set<ExtensionRegistryClientNode> getExtensionRegistryClients() {
        return flowController.getFlowManager().getAllExtensionRegistryClients();
    }

    @Override
    public Set<ExtensionRegistryClientNode> getExtensionRegistryClientsForUser(ExtensionRegistryClientUserContext context) {
        return getExtensionRegistryClients();
    }

    @Override
    public ExtensionRegistryClientNode removeExtensionRegistryClient(String extensionRegistryId) {
        final ExtensionRegistryClientNode extensionRegistry = flowController.getFlowManager().getExtensionRegistryClient(extensionRegistryId);
        if (extensionRegistry == null) {
            throw new IllegalArgumentException("The specified extension registry id is unknown to this NiFi.");
        }

        flowController.getFlowManager().removeExtensionRegistryClient(extensionRegistry);

        return extensionRegistry;
    }

    @Override
    public void verifyDeleteExtensionRegistry(String registryClientId) {
        locateExtensionRegistryClient(registryClientId);
    }

    @Override
    public void verifyConfigVerification(final String registryId) {
        getExtensionRegistryClient(registryId);
    }

    @Override
    public List<ConfigVerificationResultDTO> verifyConfiguration(final String registryId, final Map<String, String> properties, final Map<String, String> variables) {
        final ExtensionRegistryClientNode registry = getExtensionRegistryClient(registryId);

        final LogRepository logRepository = new NopLogRepository();
        final ComponentLog configVerificationLog = new SimpleProcessLogger(registry, logRepository, new StandardLoggingContext());
        final Map<String, String> effectiveProperties = properties == null ? Collections.emptyMap() : properties;
        final Map<String, String> effectiveVariables = variables == null ? Collections.emptyMap() : variables;

        final List<ConfigVerificationResult> verificationResults =
                registry.verifyConfiguration(effectiveProperties, effectiveVariables, configVerificationLog, flowController.getExtensionManager());

        return verificationResults.stream()
                .map(this::createConfigVerificationResultDto)
                .collect(Collectors.toList());
    }

    private ConfigVerificationResultDTO createConfigVerificationResultDto(final ConfigVerificationResult result) {
        final ConfigVerificationResultDTO dto = new ConfigVerificationResultDTO();
        dto.setVerificationStepName(result.getVerificationStepName());
        dto.setOutcome(result.getOutcome().name());
        dto.setExplanation(result.getExplanation());
        return dto;
    }

    @Autowired
    public void setFlowController(final FlowController flowController) {
        this.flowController = flowController;
    }

    private ExtensionRegistryClientNode locateExtensionRegistryClient(final String extensionRegistryClientId) {
        final ExtensionRegistryClientNode extensionRegistry = flowController.getFlowManager().getExtensionRegistryClient(extensionRegistryClientId);

        if (extensionRegistry == null) {
            throw new ResourceNotFoundException(String.format("Unable to locate extension registry client with id '%s'.", extensionRegistryClientId));
        }

        return extensionRegistry;
    }

    private void verifyUpdate(final ExtensionRegistryClientNode client, final ExtensionRegistryClientDTO extensionRegistryClientDto) {
        final boolean duplicateName = getExtensionRegistryClients().stream()
                .anyMatch(reg -> reg.getName().equals(extensionRegistryClientDto.getName()) && !reg.getIdentifier().equals(extensionRegistryClientDto.getId()));

        if (duplicateName) {
            throw new IllegalStateException("Cannot update Extension Registry Client because an Extension Registry Client already exists with the name " + extensionRegistryClientDto.getName());
        }
    }

    private void configureExtensionRegistry(final ExtensionRegistryClientNode node, final ExtensionRegistryClientDTO dto) {
        final String name = dto.getName();
        final String description = dto.getDescription();
        final Map<String, String> properties = dto.getProperties();

        node.pauseValidationTrigger();

        try {
            if (isNotNull(name)) {
                node.setName(name);
            }

            if (isNotNull(description)) {
                node.setDescription(description);
            }

            if (isNotNull(properties)) {
                final Set<String> sensitiveDynamicPropertyNames = Optional.ofNullable(dto.getSensitiveDynamicPropertyNames()).orElse(Collections.emptySet());
                node.setProperties(properties, false, sensitiveDynamicPropertyNames);
            }
        } finally {
            node.resumeValidationTrigger();
        }
    }
}
