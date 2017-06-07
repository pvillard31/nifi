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
package org.apache.nifi.web.security.spring;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.authentication.GroupIdentityProvider;
import org.apache.nifi.authentication.GroupIdentityProviderConfigurationContext;
import org.apache.nifi.authentication.GroupIdentityProviderInitializationContext;
import org.apache.nifi.authentication.GroupIdentityProviderLookup;
import org.apache.nifi.authentication.LoginCredentials;
import org.apache.nifi.authentication.annotation.GroupIdentityProviderContext;
import org.apache.nifi.authentication.exception.ProviderCreationException;
import org.apache.nifi.authentication.exception.ProviderDestructionException;
import org.apache.nifi.authentication.generated.GroupIdentityProviders;
import org.apache.nifi.authentication.generated.LoginIdentityProviders;
import org.apache.nifi.authentication.generated.Property;
import org.apache.nifi.authentication.generated.Provider;
import org.apache.nifi.bundle.Bundle;
import org.apache.nifi.nar.ExtensionManager;
import org.apache.nifi.nar.NarCloseable;
import org.apache.nifi.properties.AESSensitivePropertyProviderFactory;
import org.apache.nifi.properties.NiFiPropertiesLoader;
import org.apache.nifi.properties.SensitivePropertyProtectionException;
import org.apache.nifi.properties.SensitivePropertyProvider;
import org.apache.nifi.properties.SensitivePropertyProviderFactory;
import org.apache.nifi.util.NiFiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.xml.sax.SAXException;

/**
 *
 */
public class GroupIdentityProviderFactoryBean implements FactoryBean, DisposableBean, GroupIdentityProviderLookup {

    private static final Logger logger = LoggerFactory.getLogger(GroupIdentityProviderFactoryBean.class);
    private static final String IDENTITY_PROVIDERS_XSD = "/login-identity-providers.xsd";
    private static final String JAXB_GENERATED_PATH = "org.apache.nifi.authentication.generated";
    private static final JAXBContext JAXB_CONTEXT = initializeJaxbContext();

    private static SensitivePropertyProviderFactory SENSITIVE_PROPERTY_PROVIDER_FACTORY;
    private static SensitivePropertyProvider SENSITIVE_PROPERTY_PROVIDER;

    /**
     * Load the JAXBContext.
     */
    private static JAXBContext initializeJaxbContext() {
        try {
            return JAXBContext.newInstance(JAXB_GENERATED_PATH, GroupIdentityProviderFactoryBean.class.getClassLoader());
        } catch (JAXBException e) {
            throw new RuntimeException("Unable to create JAXBContext.");
        }
    }

    private NiFiProperties properties;
    private GroupIdentityProvider groupIdentityProvider;
    private final Map<String, GroupIdentityProvider> groupIdentityProviders = new HashMap<>();

    @Override
    public GroupIdentityProvider getGroupIdentityProvider(String identifier) {
        return groupIdentityProviders.get(identifier);
    }

    @Override
    public Object getObject() throws Exception {
        if (groupIdentityProvider == null) {
            // look up the group identity provider to use
            final String groupIdentityProviderIdentifier = properties.getProperty(NiFiProperties.SECURITY_USER_GROUP_IDENTITY_PROVIDER);

            // ensure the group identity provider class name was specified
            if (StringUtils.isNotBlank(groupIdentityProviderIdentifier)) {
                final GroupIdentityProviders groupIdentityProviderConfiguration = loadGroupIdentityProvidersConfiguration();

                // create each group identity provider
                for (final Provider provider : groupIdentityProviderConfiguration.getProvider()) {
                    groupIdentityProviders.put(provider.getIdentifier(), createGroupIdentityProvider(provider.getIdentifier(), provider.getClazz()));
                }

                // configure each group identity provider
                for (final Provider provider : groupIdentityProviderConfiguration.getProvider()) {
                    final GroupIdentityProvider instance = groupIdentityProviders.get(provider.getIdentifier());
                    instance.onConfigured(loadGroupIdentityProviderConfiguration(provider));
                }

                // get the group identity provider instance
                groupIdentityProvider = getGroupIdentityProvider(groupIdentityProviderIdentifier);

                // ensure it was found
                if (groupIdentityProvider == null) {
                    throw new Exception(String.format("The specified group identity provider '%s' could not be found.", groupIdentityProviderIdentifier));
                }
            }
        }

        return groupIdentityProvider;
    }

    private GroupIdentityProviders loadGroupIdentityProvidersConfiguration() throws Exception {
        final File groupIdentityProvidersConfigurationFile = properties.getGroupIdentityProviderConfigurationFile();

        // load the group providers from the specified file
        if (groupIdentityProvidersConfigurationFile.exists()) {
            try {
                // find the schema
                final SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                final Schema schema = schemaFactory.newSchema(LoginIdentityProviders.class.getResource(IDENTITY_PROVIDERS_XSD));

                // attempt to unmarshal
                final Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
                unmarshaller.setSchema(schema);
                final JAXBElement<GroupIdentityProviders> element = unmarshaller.unmarshal(new StreamSource(groupIdentityProvidersConfigurationFile), GroupIdentityProviders.class);
                return element.getValue();
            } catch (SAXException | JAXBException e) {
                throw new Exception("Unable to load the group identity provider configuration file at: " + groupIdentityProvidersConfigurationFile.getAbsolutePath());
            }
        } else {
            throw new Exception("Unable to find the group identity provider configuration file at " + groupIdentityProvidersConfigurationFile.getAbsolutePath());
        }
    }

    private GroupIdentityProvider createGroupIdentityProvider(final String identifier, final String groupIdentityProviderClassName) throws Exception {
        // get the classloader for the specified group identity provider
        final List<Bundle> groupIdentityProviderBundles = ExtensionManager.getBundles(groupIdentityProviderClassName);

        if (groupIdentityProviderBundles.size() == 0) {
            throw new Exception(String.format("The specified group identity provider class '%s' is not known to this nifi.", groupIdentityProviderClassName));
        }

        if (groupIdentityProviderBundles.size() > 1) {
            throw new Exception(String.format("Multiple bundles found for the specified group identity provider class '%s', only one is allowed.", groupIdentityProviderClassName));
        }

        final Bundle groupIdentityProviderBundle = groupIdentityProviderBundles.get(0);
        final ClassLoader groupIdentityProviderClassLoader = groupIdentityProviderBundle.getClassLoader();

        // get the current context classloader
        final ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader();

        final GroupIdentityProvider instance;
        try {
            // set the appropriate class loader
            Thread.currentThread().setContextClassLoader(groupIdentityProviderClassLoader);

            // attempt to load the class
            Class<?> rawGroupIdentityProviderClass = Class.forName(groupIdentityProviderClassName, true, groupIdentityProviderClassLoader);
            Class<? extends GroupIdentityProvider> groupIdentityProviderClass = rawGroupIdentityProviderClass.asSubclass(GroupIdentityProvider.class);

            // otherwise create a new instance
            Constructor constructor = groupIdentityProviderClass.getConstructor();
            instance = (GroupIdentityProvider) constructor.newInstance();

            // method injection
            performMethodInjection(instance, groupIdentityProviderClass);

            // field injection
            performFieldInjection(instance, groupIdentityProviderClass);

            // call post construction lifecycle event
            instance.initialize(new StandardGroupIdentityProviderInitializationContext(identifier, this));
        } finally {
            if (currentClassLoader != null) {
                Thread.currentThread().setContextClassLoader(currentClassLoader);
            }
        }

        return withNarLoader(instance);
    }

    private GroupIdentityProviderConfigurationContext loadGroupIdentityProviderConfiguration(final Provider provider) {
        final Map<String, String> providerProperties = new HashMap<>();

        for (final Property property : provider.getProperty()) {
            if (!StringUtils.isBlank(property.getEncryption())) {
                String decryptedValue = decryptValue(property.getValue(), property.getEncryption());
                providerProperties.put(property.getName(), decryptedValue);
            } else {
                providerProperties.put(property.getName(), property.getValue());
            }
        }

        return new StandardGroupIdentityProviderConfigurationContext(provider.getIdentifier(), providerProperties);
    }

    private String decryptValue(String cipherText, String encryptionScheme) throws SensitivePropertyProtectionException {
            initializeSensitivePropertyProvider(encryptionScheme);
        return SENSITIVE_PROPERTY_PROVIDER.unprotect(cipherText);
    }

    private static void initializeSensitivePropertyProvider(String encryptionScheme) throws SensitivePropertyProtectionException {
        if (SENSITIVE_PROPERTY_PROVIDER == null || !SENSITIVE_PROPERTY_PROVIDER.getIdentifierKey().equalsIgnoreCase(encryptionScheme)) {
            try {
                String keyHex = getMasterKey();
                SENSITIVE_PROPERTY_PROVIDER_FACTORY = new AESSensitivePropertyProviderFactory(keyHex);
                SENSITIVE_PROPERTY_PROVIDER = SENSITIVE_PROPERTY_PROVIDER_FACTORY.getProvider();
            } catch (IOException e) {
                logger.error("Error extracting master key from bootstrap.conf for group identity provider decryption", e);
                throw new SensitivePropertyProtectionException("Could not read master key from bootstrap.conf");
            }
        }
    }

    private static String getMasterKey() throws IOException {
        return NiFiPropertiesLoader.extractKeyFromBootstrapFile();
    }

    private void performMethodInjection(final GroupIdentityProvider instance, final Class groupIdentityProviderClass)
            throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {

        for (final Method method : groupIdentityProviderClass.getMethods()) {
            if (method.isAnnotationPresent(GroupIdentityProviderContext.class)) {
                // make the method accessible
                final boolean isAccessible = method.isAccessible();
                method.setAccessible(true);

                try {
                    final Class<?>[] argumentTypes = method.getParameterTypes();

                    // look for setters (single argument)
                    if (argumentTypes.length == 1) {
                        final Class<?> argumentType = argumentTypes[0];

                        // look for well known types
                        if (NiFiProperties.class.isAssignableFrom(argumentType)) {
                            // nifi properties injection
                            method.invoke(instance, properties);
                        }
                    }
                } finally {
                    method.setAccessible(isAccessible);
                }
            }
        }

        final Class parentClass = groupIdentityProviderClass.getSuperclass();
        if (parentClass != null && GroupIdentityProvider.class.isAssignableFrom(parentClass)) {
            performMethodInjection(instance, parentClass);
        }
    }

    private void performFieldInjection(final GroupIdentityProvider instance, final Class groupIdentityProviderClass) throws IllegalArgumentException, IllegalAccessException {
        for (final Field field : groupIdentityProviderClass.getDeclaredFields()) {
            if (field.isAnnotationPresent(GroupIdentityProviderContext.class)) {
                // make the method accessible
                final boolean isAccessible = field.isAccessible();
                field.setAccessible(true);

                try {
                    // get the type
                    final Class<?> fieldType = field.getType();

                    // only consider this field if it isn't set yet
                    if (field.get(instance) == null) {
                        // look for well known types
                        if (NiFiProperties.class.isAssignableFrom(fieldType)) {
                            // nifi properties injection
                            field.set(instance, properties);
                        }
                    }

                } finally {
                    field.setAccessible(isAccessible);
                }
            }
        }

        final Class parentClass = groupIdentityProviderClass.getSuperclass();
        if (parentClass != null && GroupIdentityProvider.class.isAssignableFrom(parentClass)) {
            performFieldInjection(instance, parentClass);
        }
    }

    private GroupIdentityProvider withNarLoader(final GroupIdentityProvider instance) {
        return new GroupIdentityProvider() {
            @Override
            public void preDestruction() throws ProviderDestructionException {
                try (final NarCloseable narCloseable = NarCloseable.withNarLoader()) {
                    instance.preDestruction();
                }
            }

            @Override
            public List<String> getGroups(LoginCredentials credentials) {
                try (final NarCloseable narCloseable = NarCloseable.withNarLoader()) {
                    return instance.getGroups(credentials);
                }
            }

            @Override
            public void initialize(GroupIdentityProviderInitializationContext initializationContext) throws ProviderCreationException {
                try (final NarCloseable narCloseable = NarCloseable.withNarLoader()) {
                    instance.initialize(initializationContext);
                }
            }

            @Override
            public void onConfigured(GroupIdentityProviderConfigurationContext configurationContext) throws ProviderCreationException {
                try (final NarCloseable narCloseable = NarCloseable.withNarLoader()) {
                    instance.onConfigured(configurationContext);
                }
            }
        };
    }

    @Override
    public Class getObjectType() {
        return GroupIdentityProvider.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public void destroy() throws Exception {
        if (groupIdentityProvider != null) {
            groupIdentityProvider.preDestruction();
        }
    }

    public void setProperties(NiFiProperties properties) {
        this.properties = properties;
    }
}
