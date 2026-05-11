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

package org.apache.nifi.registry.flow.mapping;

import org.apache.nifi.attribute.expression.language.VariableImpact;
import org.apache.nifi.bundle.BundleCoordinate;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.connectable.Position;
import org.apache.nifi.controller.BackoffMechanism;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.PropertyConfiguration;
import org.apache.nifi.controller.ScheduledState;
import org.apache.nifi.controller.service.ControllerServiceNode;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.flow.VersionedParameter;
import org.apache.nifi.flow.VersionedParameterContext;
import org.apache.nifi.flow.VersionedProcessor;
import org.apache.nifi.logging.LogLevel;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.parameter.ExpressionLanguageAgnosticParameterParser;
import org.apache.nifi.parameter.Parameter;
import org.apache.nifi.parameter.ParameterContext;
import org.apache.nifi.parameter.ParameterDescriptor;
import org.apache.nifi.parameter.ParameterProvider;
import org.apache.nifi.parameter.ParameterReference;
import org.apache.nifi.parameter.ParameterReferenceManager;
import org.apache.nifi.parameter.ParameterTokenList;
import org.apache.nifi.scheduling.ExecutionNode;
import org.apache.nifi.scheduling.SchedulingStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestVersionedComponentFlowMapper {
    private static final SensitiveValueEncryptor ENCRYPTOR = value -> new StringBuilder(value).reverse().toString();

    @Test
    public void testMappingProcessorWithSensitiveValuesGivesNullValue() {
        final ExtensionManager extensionManager = mock(ExtensionManager.class);
        final FlowMappingOptions mappingOptions = FlowMappingOptions.DEFAULT_OPTIONS;
        final VersionedComponentFlowMapper mapper = new VersionedComponentFlowMapper(extensionManager, mappingOptions);

        final ControllerServiceProvider serviceProvider = mock(ControllerServiceProvider.class);
        final Map<String, String> properties = new HashMap<>();
        properties.put("Property A", "A");
        properties.put("Sensitive Property B", "B");

        final ProcessorNode procNode = createProcessorNode(properties);
        final VersionedProcessor versionedProcessor = mapper.mapProcessor(procNode, serviceProvider, Collections.emptySet(), Collections.emptyMap());
        assertNotNull(versionedProcessor);

        final Map<String, String> versionedProperties = versionedProcessor.getProperties();
        assertEquals("A", versionedProperties.get("Property A"));
        assertNull(versionedProperties.get("Sensitive Property B"));
    }

    @Test
    public void testMappingProcessorWithSensitiveValuesLeavesSensitiveParameterReference() {
        final ExtensionManager extensionManager = mock(ExtensionManager.class);
        final FlowMappingOptions mappingOptions = FlowMappingOptions.DEFAULT_OPTIONS;
        final VersionedComponentFlowMapper mapper = new VersionedComponentFlowMapper(extensionManager, mappingOptions);

        final ControllerServiceProvider serviceProvider = mock(ControllerServiceProvider.class);
        final Map<String, String> properties = new HashMap<>();
        properties.put("Property A", "A");
        properties.put("Sensitive Property B", "#{Param B}");

        final ProcessorNode procNode = createProcessorNode(properties);
        final VersionedProcessor versionedProcessor = mapper.mapProcessor(procNode, serviceProvider, Collections.emptySet(), Collections.emptyMap());
        assertNotNull(versionedProcessor);

        final Map<String, String> versionedProperties = versionedProcessor.getProperties();
        assertEquals("A", versionedProperties.get("Property A"));
        assertEquals("#{Param B}", versionedProperties.get("Sensitive Property B"));
    }

    @Test
    public void testMappingProcessorWithSensitiveValuesProvidesEncryptedValue() {
        final ExtensionManager extensionManager = mock(ExtensionManager.class);
        final FlowMappingOptions mappingOptions = new FlowMappingOptions.Builder()
            .stateLookup(VersionedComponentStateLookup.IDENTITY_LOOKUP)
            .componentIdLookup(ComponentIdLookup.USE_COMPONENT_ID)
            .mapSensitiveConfiguration(true)
            .sensitiveValueEncryptor(ENCRYPTOR)
            .build();

        final VersionedComponentFlowMapper mapper = new VersionedComponentFlowMapper(extensionManager, mappingOptions);

        final ControllerServiceProvider serviceProvider = mock(ControllerServiceProvider.class);
        final Map<String, String> properties = new HashMap<>();
        properties.put("Property A", "A");
        properties.put("Sensitive Property B", "A B C");

        final ProcessorNode procNode = createProcessorNode(properties);
        final VersionedProcessor versionedProcessor = mapper.mapProcessor(procNode, serviceProvider, Collections.emptySet(), Collections.emptyMap());
        assertNotNull(versionedProcessor);

        final Map<String, String> versionedProperties = versionedProcessor.getProperties();
        assertEquals("A", versionedProperties.get("Property A"));
        assertEquals("enc{C B A}", versionedProperties.get("Sensitive Property B"));
    }

    @Test
    public void testControllerServicesMapsToVersionedId() {
        testControllerServicesMapsToProperId(true);
    }

    @Test
    public void testControllerServicesMapsToInstanceId() {
        testControllerServicesMapsToProperId(false);
    }

    private void testControllerServicesMapsToProperId(final boolean useVersionedId) {
        final ExtensionManager extensionManager = mock(ExtensionManager.class);
        final FlowMappingOptions mappingOptions = new FlowMappingOptions.Builder()
            .stateLookup(VersionedComponentStateLookup.IDENTITY_LOOKUP)
            .componentIdLookup(ComponentIdLookup.VERSIONED_OR_GENERATE)
            .mapControllerServiceReferencesToVersionedId(useVersionedId)
            .build();

        final VersionedComponentFlowMapper mapper = new VersionedComponentFlowMapper(extensionManager, mappingOptions);

        final ControllerServiceNode mockServiceNode = mock(ControllerServiceNode.class);
        when(mockServiceNode.getIdentifier()).thenReturn("1234");
        when(mockServiceNode.getVersionedComponentId()).thenReturn(Optional.of("versioned"));

        final ControllerServiceProvider serviceProvider = mock(ControllerServiceProvider.class);
        when(serviceProvider.getControllerServiceNode("1234")).thenReturn(mockServiceNode);
        final Map<String, String> properties = new HashMap<>();
        properties.put("Service A", "1234");

        final ProcessorNode procNode = createProcessorNode(properties);
        final VersionedProcessor versionedProcessor = mapper.mapProcessor(procNode, serviceProvider, Collections.emptySet(), Collections.emptyMap());
        assertNotNull(versionedProcessor);

        final Map<String, String> versionedProperties = versionedProcessor.getProperties();
        final String idReferenced = versionedProperties.get("Service A");
        if (useVersionedId) {
            assertEquals("versioned", idReferenced);
        } else {
            assertEquals("1234", idReferenced);
        }
    }

    @Test
    public void testMapParameterContextForcesProvidedFalseWhenNoProviderBound() {
        final ExtensionManager extensionManager = mock(ExtensionManager.class);
        final FlowMappingOptions mappingOptions = FlowMappingOptions.DEFAULT_OPTIONS;
        final VersionedComponentFlowMapper mapper = new VersionedComponentFlowMapper(extensionManager, mappingOptions);

        final ParameterDescriptor descriptor = new ParameterDescriptor.Builder().name("included-table-regex").build();
        final Parameter corruptParameter = new Parameter.Builder()
                .descriptor(descriptor)
                .value("AWS_VALUE")
                .provided(true)
                .build();

        final ParameterContext parameterContext = mockParameterContext("LocalPC", "local-pc", null, descriptor, corruptParameter);

        final VersionedParameterContext mapped = mapper.mapParameterContext(parameterContext);
        assertNotNull(mapped);
        assertEquals(1, mapped.getParameters().size());

        final VersionedParameter versioned = mapped.getParameters().iterator().next();
        assertEquals("included-table-regex", versioned.getName());
        assertFalse(versioned.isProvided(), "Save-side guard must force provided=false when context has no provider bound");
    }

    @Test
    public void testMapParameterContextPreservesProvidedTrueWhenProviderBound() {
        final ExtensionManager extensionManager = mock(ExtensionManager.class);
        final FlowMappingOptions mappingOptions = FlowMappingOptions.DEFAULT_OPTIONS;
        final VersionedComponentFlowMapper mapper = new VersionedComponentFlowMapper(extensionManager, mappingOptions);

        final ParameterDescriptor descriptor = new ParameterDescriptor.Builder().name("included-table-regex").build();
        final Parameter providedParameter = new Parameter.Builder()
                .descriptor(descriptor)
                .value("AWS_VALUE")
                .provided(true)
                .build();

        final ParameterContext parameterContext = mockParameterContext("ProvidedPC", "provided-pc", mock(ParameterProvider.class), descriptor, providedParameter);

        final VersionedParameterContext mapped = mapper.mapParameterContext(parameterContext);
        assertNotNull(mapped);

        final VersionedParameter versioned = mapped.getParameters().iterator().next();
        assertTrue(versioned.isProvided(), "Provided flag must be preserved when context has a provider bound");
    }

    private static ParameterContext mockParameterContext(final String name, final String identifier, final ParameterProvider provider,
                                                         final ParameterDescriptor descriptor, final Parameter parameter) {
        final ParameterReferenceManager referenceManager = mock(ParameterReferenceManager.class);
        when(referenceManager.getReferencedControllerServiceData(any(ParameterContext.class), any(String.class))).thenReturn(Collections.emptyList());

        final ParameterContext parameterContext = mock(ParameterContext.class);
        when(parameterContext.getName()).thenReturn(name);
        when(parameterContext.getIdentifier()).thenReturn(identifier);
        when(parameterContext.getDescription()).thenReturn(null);
        when(parameterContext.getParameterProvider()).thenReturn(provider);
        when(parameterContext.getParameterProviderConfiguration()).thenReturn(null);
        when(parameterContext.getInheritedParameterContextNames()).thenReturn(Collections.emptyList());
        when(parameterContext.getParameters()).thenReturn(Collections.singletonMap(descriptor, parameter));
        when(parameterContext.getParameterReferenceManager()).thenReturn(referenceManager);
        return parameterContext;
    }

    private ProcessorNode createProcessorNode(final Map<String, String> properties) {
        final ExpressionLanguageAgnosticParameterParser parameterParser = new ExpressionLanguageAgnosticParameterParser();

        final Map<PropertyDescriptor, PropertyConfiguration> propertyMap = new HashMap<>();
        final Map<String, PropertyDescriptor> propertyDescriptors = new HashMap<>();

        for (final Map.Entry<String, String> property : properties.entrySet()) {
            final String propName = property.getKey();
            final String value = property.getValue();

            final PropertyDescriptor descriptor = new PropertyDescriptor.Builder()
                .name(propName)
                .sensitive(propName.toLowerCase().contains("sensitive"))
                .identifiesControllerService(propName.toLowerCase().contains("service ") ? ControllerService.class : null)
                .addValidator(Validator.VALID)
                .build();

            final ParameterTokenList tokenList = parameterParser.parseTokens(value);
            final List<ParameterReference> referenceList = tokenList.toReferenceList();
            final PropertyConfiguration configuration = new PropertyConfiguration(value, tokenList, referenceList, VariableImpact.NEVER_IMPACTED);
            propertyMap.put(descriptor, configuration);
            propertyDescriptors.put(propName, descriptor);
        }

        final ProcessorNode procNode = mock(ProcessorNode.class);
        when(procNode.getProperties()).thenReturn(propertyMap);
        when(procNode.getProperty(any(PropertyDescriptor.class))).thenAnswer((Answer<PropertyConfiguration>) invocation -> {
            final PropertyDescriptor descriptor = invocation.getArgument(0, PropertyDescriptor.class);
            return propertyMap.get(descriptor);
        });

        when(procNode.getRawPropertyValue(any(PropertyDescriptor.class))).thenAnswer((Answer<String>) invocation -> {
            final PropertyDescriptor descriptor = invocation.getArgument(0, PropertyDescriptor.class);
            return properties.get(descriptor.getName());
        });

        final String id = UUID.randomUUID().toString();
        when(procNode.getIdentifier()).thenReturn(id);

        when(procNode.getBulletinLevel()).thenReturn(LogLevel.WARN);
        when(procNode.getExecutionNode()).thenReturn(ExecutionNode.ALL);
        when(procNode.getPropertyDescriptors()).thenReturn(new ArrayList<>(propertyDescriptors.values()));
        when(procNode.getBundleCoordinate()).thenReturn(new BundleCoordinate("group", "id", "version"));
        when(procNode.getPosition()).thenReturn(new Position(0D, 0D));
        when(procNode.getSchedulingStrategy()).thenReturn(SchedulingStrategy.TIMER_DRIVEN);
        when(procNode.getPhysicalScheduledState()).thenReturn(ScheduledState.STOPPED);
        when(procNode.getBackoffMechanism()).thenReturn(BackoffMechanism.YIELD_PROCESSOR);

        return procNode;
    }
}
