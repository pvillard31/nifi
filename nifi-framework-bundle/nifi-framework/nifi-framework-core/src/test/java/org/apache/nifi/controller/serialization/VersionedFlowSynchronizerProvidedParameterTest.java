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
package org.apache.nifi.controller.serialization;

import org.apache.nifi.asset.AssetManager;
import org.apache.nifi.controller.flow.FlowManager;
import org.apache.nifi.encrypt.PropertyEncryptor;
import org.apache.nifi.flow.VersionedParameter;
import org.apache.nifi.flow.VersionedParameterContext;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.parameter.Parameter;
import org.apache.nifi.parameter.StandardParameterValueMapper;
import org.apache.nifi.persistence.FlowConfigurationArchiveManager;
import org.apache.nifi.util.NiFiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class VersionedFlowSynchronizerProvidedParameterTest {

    private static final String FLOW_CONFIGURATION = "flow.json.gz";
    private static final String LOCAL_PC_NAME = "LocalPC";
    private static final String LOCAL_PC_ID = "local-pc";
    private static final String PARAMETER_NAME = "Included Table Regex";
    private static final String PARAMETER_LITERAL_VALUE = "AWS_VALUE";

    private VersionedFlowSynchronizer versionedFlowSynchronizer;

    @BeforeEach
    void setVersionedFlowSynchronizer(@TempDir final Path tempDir) {
        final File flowStorageFile = tempDir.resolve(UUID.randomUUID().toString()).toFile();
        final Path flowConfigurationFile = tempDir.resolve(FLOW_CONFIGURATION);
        final Map<String, String> additionalProperties = Map.of(
                NiFiProperties.FLOW_CONFIGURATION_FILE, flowConfigurationFile.toString()
        );
        final NiFiProperties properties = NiFiProperties.createBasicNiFiProperties(null, additionalProperties);
        final FlowConfigurationArchiveManager archiveManager = new FlowConfigurationArchiveManager(properties);

        versionedFlowSynchronizer = new VersionedFlowSynchronizer(mock(ExtensionManager.class), flowStorageFile, archiveManager);
    }

    /**
     * Reproduces the historical corruption: a Parameter Context with no Parameter Provider that contains a parameter
     * marked {@code provided=true} with a literal value (not the sentinel). The load path must drop the {@code provided}
     * flag and preserve the literal value so the corruption self-heals on the next save.
     */
    @Test
    void testCreateParameterMapNormalizesProvidedTrueWithLiteralValueWhenNoProvider() {
        final VersionedParameterContext context = newLocalContext(newVersionedParameter(PARAMETER_NAME, PARAMETER_LITERAL_VALUE, true));

        final Map<String, Parameter> parameters = versionedFlowSynchronizer.createParameterMap(
                mock(FlowManager.class), context, mock(PropertyEncryptor.class), mock(AssetManager.class));

        assertEquals(1, parameters.size());
        final Parameter parameter = parameters.get(PARAMETER_NAME);
        assertFalse(parameter.isProvided(), "Provided flag must be dropped when the owning context has no provider bound");
        assertEquals(PARAMETER_LITERAL_VALUE, parameter.getValue(), "Literal value must be preserved as a local literal so the corrupt flow self-heals");
    }

    /**
     * A Parameter Context with no Parameter Provider that contains a parameter marked {@code provided=true} with the
     * persistence sentinel. Resolving the literal sentinel through would leak {@code "provided:parameter"} to the
     * runtime; instead the value must be normalized to {@code null} so an inherited provider value can win via the
     * existing override semantics.
     */
    @Test
    void testCreateParameterMapNormalizesProvidedTrueWithSentinelValueWhenNoProvider() {
        final VersionedParameterContext context = newLocalContext(newVersionedParameter(PARAMETER_NAME, StandardParameterValueMapper.PROVIDED_MAPPING, true));

        final Map<String, Parameter> parameters = versionedFlowSynchronizer.createParameterMap(
                mock(FlowManager.class), context, mock(PropertyEncryptor.class), mock(AssetManager.class));

        final Parameter parameter = parameters.get(PARAMETER_NAME);
        assertFalse(parameter.isProvided());
        assertNull(parameter.getValue(), "Sentinel value must be normalized to null so an inherited provider value can win via override");
    }

    /**
     * A Parameter Context with no Parameter Provider that contains a parameter marked {@code provided=true} with a
     * {@code null} value. The load path must drop the {@code provided} flag with a {@code null} value, with no WARN.
     */
    @Test
    void testCreateParameterMapNormalizesProvidedTrueWithNullValueWhenNoProvider() {
        final VersionedParameterContext context = newLocalContext(newVersionedParameter(PARAMETER_NAME, null, true));

        final Map<String, Parameter> parameters = versionedFlowSynchronizer.createParameterMap(
                mock(FlowManager.class), context, mock(PropertyEncryptor.class), mock(AssetManager.class));

        final Parameter parameter = parameters.get(PARAMETER_NAME);
        assertFalse(parameter.isProvided());
        assertNull(parameter.getValue());
    }

    /**
     * Regression: a non-provided parameter on a non-provider context must continue to round-trip its literal value.
     */
    @Test
    void testCreateParameterMapPreservesNonProvidedLiteralValue() {
        final VersionedParameterContext context = newLocalContext(newVersionedParameter(PARAMETER_NAME, "local-value", false));

        final Map<String, Parameter> parameters = versionedFlowSynchronizer.createParameterMap(
                mock(FlowManager.class), context, mock(PropertyEncryptor.class), mock(AssetManager.class));

        final Parameter parameter = parameters.get(PARAMETER_NAME);
        assertFalse(parameter.isProvided());
        assertEquals("local-value", parameter.getValue());
    }

    private static VersionedParameterContext newLocalContext(final VersionedParameter... parameters) {
        final VersionedParameterContext context = new VersionedParameterContext();
        context.setName(LOCAL_PC_NAME);
        context.setIdentifier(LOCAL_PC_ID);
        context.setInstanceIdentifier(LOCAL_PC_ID);

        final Set<VersionedParameter> parameterSet = new HashSet<>();
        for (final VersionedParameter parameter : parameters) {
            parameterSet.add(parameter);
        }
        context.setParameters(parameterSet);
        return context;
    }

    private static VersionedParameter newVersionedParameter(final String name, final String value, final boolean provided) {
        final VersionedParameter parameter = new VersionedParameter();
        parameter.setName(name);
        parameter.setValue(value);
        parameter.setProvided(provided);
        parameter.setSensitive(false);
        return parameter;
    }
}
