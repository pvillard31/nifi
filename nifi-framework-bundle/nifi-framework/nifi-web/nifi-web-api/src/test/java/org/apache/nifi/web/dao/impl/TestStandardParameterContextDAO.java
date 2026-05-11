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

import org.apache.nifi.authorization.AuthorizationRequest;
import org.apache.nifi.authorization.AuthorizationResult;
import org.apache.nifi.authorization.Authorizer;
import org.apache.nifi.authorization.user.NiFiUser;
import org.apache.nifi.authorization.user.NiFiUserDetails;
import org.apache.nifi.authorization.user.StandardNiFiUser;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.ParameterProviderNode;
import org.apache.nifi.controller.flow.FlowManager;
import org.apache.nifi.controller.parameter.ParameterProviderLookup;
import org.apache.nifi.parameter.Parameter;
import org.apache.nifi.parameter.ParameterContext;
import org.apache.nifi.parameter.ParameterProvider;
import org.apache.nifi.parameter.ParameterReferenceManager;
import org.apache.nifi.parameter.StandardParameterContext;
import org.apache.nifi.parameter.StandardParameterContextManager;
import org.apache.nifi.parameter.StandardParameterProviderConfiguration;
import org.apache.nifi.parameter.StandardParameterReferenceManager;
import org.apache.nifi.web.api.dto.ParameterContextDTO;
import org.apache.nifi.web.api.dto.ParameterContextReferenceDTO;
import org.apache.nifi.web.api.dto.ParameterDTO;
import org.apache.nifi.web.api.entity.ParameterContextReferenceEntity;
import org.apache.nifi.web.api.entity.ParameterEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TestStandardParameterContextDAO {

    private StandardParameterContextDAO dao;

    @Mock(answer = RETURNS_DEEP_STUBS)
    private FlowController flowController;

    @Mock
    private Authentication authentication;

    @Mock
    private Authorizer authorizer;

    @BeforeEach
    void setUp() {
        dao = new StandardParameterContextDAO();
        dao.setFlowController(flowController);
        dao.setAuthorizer(authorizer);
        lenient().when(authorizer.authorize(any(AuthorizationRequest.class))).thenReturn(AuthorizationResult.approved());

        final SecurityContext securityContext = SecurityContextHolder.getContext();
        securityContext.setAuthentication(authentication);
        final NiFiUser user = new StandardNiFiUser.Builder().identity("user").build();
        final NiFiUserDetails userDetail = new NiFiUserDetails(user);
        lenient().when(authentication.getPrincipal()).thenReturn(userDetail);

        final ParameterReferenceManager parameterReferenceManager = new StandardParameterReferenceManager(() -> flowController.getFlowManager().getRootGroup());

        final FlowManager flowManager = flowController.getFlowManager();
        final StandardParameterContextManager parameterContextLookup = new StandardParameterContextManager();
        when(flowManager.getParameterContextManager()).thenReturn(parameterContextLookup);
        parameterContextLookup.addParameterContext(new StandardParameterContext.Builder().id("id")
                .name("Context")
                .parameterReferenceManager(parameterReferenceManager)
                .build());

        final String providerId = "provider-id";
        final ParameterProvider parameterProvider = mock(ParameterProvider.class);
        when(parameterProvider.getIdentifier()).thenReturn(providerId);
        final ParameterProviderNode parameterProviderNode = mock(ParameterProviderNode.class);
        when(parameterProviderNode.getParameterProvider()).thenReturn(parameterProvider);
        final ParameterProviderLookup parameterProviderLookup = mock(ParameterProviderLookup.class);
        when(parameterProviderLookup.getParameterProvider(providerId)).thenReturn(parameterProviderNode);

        final ParameterContext inheritedContext = new StandardParameterContext.Builder().id("inherited-id")
                .parameterReferenceManager(parameterReferenceManager)
                .parameterProviderLookup(parameterProviderLookup)
                .parameterProviderConfiguration(new StandardParameterProviderConfiguration(providerId, "group", true))
                .name("Inherited")
                .build();
        final Map<String, Parameter> parameters = new HashMap<>();
        parameters.put("inherited-param", new Parameter.Builder().name("inherited-param").value("value").provided(true).build());
        inheritedContext.setParameters(parameters);
        parameterContextLookup.addParameterContext(inheritedContext);
    }

    @Test
    public void testVerifyUpdateInheritedProvidedParameter() {
        final ParameterContextDTO dto = new ParameterContextDTO();
        dto.setId("id");
        dto.setName("Context");

        final List<ParameterContextReferenceEntity> refs = new ArrayList<>();
        final ParameterContextReferenceEntity ref = new ParameterContextReferenceEntity();
        ref.setId("inherited-id");
        ref.setComponent(new ParameterContextReferenceDTO());
        ref.getComponent().setId("inherited-id");
        ref.getComponent().setName("Inherited");
        refs.add(ref);
        dto.setInheritedParameterContexts(refs);

        // Updating a provided parameter that is inherited is allowed
        dao.verifyUpdate(dto, true);
    }

    @Test
    public void testCreateParameterNormalizesProvidedTrueWhenContextHasNoProvider() {
        final ParameterContextDTO contextDto = new ParameterContextDTO();
        contextDto.setId("id");
        contextDto.setName("Context");

        final ParameterDTO parameterDto = new ParameterDTO();
        parameterDto.setName("Included Table Regex");
        parameterDto.setValue("AWS_VALUE");
        parameterDto.setProvided(true);
        parameterDto.setSensitive(false);
        parameterDto.setInherited(false);

        final ParameterEntity parameterEntity = new ParameterEntity();
        parameterEntity.setParameter(parameterDto);
        parameterEntity.setCanWrite(true);
        contextDto.setParameters(new HashSet<>(List.of(parameterEntity)));

        final ParameterContext context = dao.getParameterContext("id");
        final Map<String, Parameter> parameters = dao.getParameters(contextDto, context);

        final Parameter parameter = parameters.get("Included Table Regex");
        assertFalse(parameter.isProvided(), "Provided flag must be normalized to false because target context has no provider");
        assertEquals("AWS_VALUE", parameter.getValue(), "Literal value must be preserved");
    }

    @Test
    public void testVerifyUpdateNonInheritedProvidedParameter() {
        final ParameterContextDTO dto = new ParameterContextDTO();
        dto.setId("id");
        dto.setName("Context");
        final Set<ParameterEntity> parameters = new HashSet<>();
        final ParameterEntity parameter = new ParameterEntity();
        parameter.setCanWrite(true);
        final ParameterDTO parameterDto = new ParameterDTO();
        parameterDto.setProvided(true);
        parameterDto.setName("param");
        parameterDto.setValue("value");
        parameterDto.setInherited(false);
        parameter.setParameter(parameterDto);
        parameters.add(parameter);
        dto.setParameters(parameters);

        final List<ParameterContextReferenceEntity> refs = new ArrayList<>();
        final ParameterContextReferenceEntity ref = new ParameterContextReferenceEntity();
        ref.setId("inherited-id");
        ref.setComponent(new ParameterContextReferenceDTO());
        ref.getComponent().setId("inherited-id");
        ref.getComponent().setName("Inherited");
        refs.add(ref);
        dto.setInheritedParameterContexts(refs);

        // Updating a provided parameter that is not inherited should fail
        assertThrows(IllegalArgumentException.class, () -> dao.verifyUpdate(dto, true));
    }
}
