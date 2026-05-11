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
package org.apache.nifi.parameter;

import org.apache.nifi.flow.VersionedParameter;
import org.apache.nifi.flow.VersionedParameterContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestProvidedParameterValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestProvidedParameterValidator.class);

    @Test
    public void testNormalizeForContextNullParameterReturnsNull() {
        assertNull(ProvidedParameterValidator.normalizeForContext(null, mock(ParameterContext.class), LOGGER, "unit-test"));
    }

    @Test
    public void testNormalizeForContextNonProvidedParameterIsUnchanged() {
        final Parameter parameter = new Parameter.Builder().name("regex").value("v").provided(false).build();
        final ParameterContext context = mock(ParameterContext.class);
        when(context.getParameterProvider()).thenReturn(null);

        final Parameter normalized = ProvidedParameterValidator.normalizeForContext(parameter, context, LOGGER, "unit-test");
        assertSame(parameter, normalized);
    }

    @Test
    public void testNormalizeForContextProvidedWithProviderIsUnchanged() {
        final Parameter parameter = new Parameter.Builder().name("regex").value("v").provided(true).build();
        final ParameterContext context = mock(ParameterContext.class);
        when(context.getParameterProvider()).thenReturn(mock(ParameterProvider.class));

        final Parameter normalized = ProvidedParameterValidator.normalizeForContext(parameter, context, LOGGER, "unit-test");
        assertSame(parameter, normalized);
    }

    @Test
    public void testNormalizeForContextProvidedWithoutProviderIsForcedFalse() {
        final Parameter parameter = new Parameter.Builder().name("regex").value("AWS_VALUE").provided(true).build();
        final ParameterContext context = mock(ParameterContext.class);
        when(context.getName()).thenReturn("LocalPC");
        when(context.getParameterProvider()).thenReturn(null);

        final Parameter normalized = ProvidedParameterValidator.normalizeForContext(parameter, context, LOGGER, "unit-test");
        assertFalse(normalized.isProvided());
        assertSame("AWS_VALUE", normalized.getValue());
        assertSame("regex", normalized.getDescriptor().getName());
    }

    @Test
    public void testNormalizeForContextProvidedWithNullContextIsForcedFalse() {
        final Parameter parameter = new Parameter.Builder().name("regex").value("v").provided(true).build();

        final Parameter normalized = ProvidedParameterValidator.normalizeForContext(parameter, null, LOGGER, "unit-test");
        assertFalse(normalized.isProvided());
    }

    @Test
    public void testNormalizeForVersionedNullParameterReturnsNull() {
        assertNull(ProvidedParameterValidator.normalizeForVersioned(null, new VersionedParameterContext(), LOGGER));
    }

    @Test
    public void testNormalizeForVersionedNonProvidedParameterIsUnchanged() {
        final VersionedParameter versioned = new VersionedParameter();
        versioned.setName("regex");
        versioned.setValue("v");
        versioned.setProvided(false);

        final VersionedParameter normalized = ProvidedParameterValidator.normalizeForVersioned(versioned, new VersionedParameterContext(), LOGGER);
        assertSame(versioned, normalized);
    }

    @Test
    public void testNormalizeForVersionedProvidedWithProviderIsUnchanged() {
        final VersionedParameter versioned = new VersionedParameter();
        versioned.setName("regex");
        versioned.setValue("v");
        versioned.setProvided(true);

        final VersionedParameterContext owner = new VersionedParameterContext();
        owner.setName("ProvidedPC");
        owner.setParameterProvider("provider-id");

        final VersionedParameter normalized = ProvidedParameterValidator.normalizeForVersioned(versioned, owner, LOGGER);
        assertSame(versioned, normalized);
    }

    @Test
    public void testNormalizeForVersionedProvidedWithoutProviderIsForcedFalse() {
        final VersionedParameter versioned = new VersionedParameter();
        versioned.setName("regex");
        versioned.setValue("AWS_VALUE");
        versioned.setProvided(true);

        final VersionedParameterContext owner = new VersionedParameterContext();
        owner.setName("LocalPC");

        final VersionedParameter normalized = ProvidedParameterValidator.normalizeForVersioned(versioned, owner, LOGGER);
        assertFalse(normalized.isProvided());
        assertEquals("AWS_VALUE", normalized.getValue());
        assertEquals("regex", normalized.getName());
    }

    @Test
    public void testNormalizeForVersionedProvidedWithNullOwnerIsForcedFalse() {
        final VersionedParameter versioned = new VersionedParameter();
        versioned.setName("regex");
        versioned.setValue("v");
        versioned.setProvided(true);

        final VersionedParameter normalized = ProvidedParameterValidator.normalizeForVersioned(versioned, null, LOGGER);
        assertFalse(normalized.isProvided());
    }
}
