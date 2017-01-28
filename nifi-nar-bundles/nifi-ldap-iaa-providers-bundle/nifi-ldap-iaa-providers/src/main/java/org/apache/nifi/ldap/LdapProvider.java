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
package org.apache.nifi.ldap;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.net.ssl.SSLContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.authentication.AuthenticationIdentity;
import org.apache.nifi.authentication.AuthenticationResponse;
import org.apache.nifi.authentication.LoginCredentials;
import org.apache.nifi.authentication.LoginIdentityProvider;
import org.apache.nifi.authentication.LoginIdentityProviderConfigurationContext;
import org.apache.nifi.authentication.LoginIdentityProviderInitializationContext;
import org.apache.nifi.authentication.exception.IdentityAccessException;
import org.apache.nifi.authentication.exception.InvalidLoginCredentialsException;
import org.apache.nifi.authentication.exception.ProviderCreationException;
import org.apache.nifi.authentication.exception.ProviderDestructionException;
import org.apache.nifi.security.util.SslContextFactory;
import org.apache.nifi.security.util.SslContextFactory.ClientAuth;
import org.apache.nifi.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.AuthenticationException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.AbstractContextMapper;
import org.springframework.ldap.core.support.AbstractTlsDirContextAuthenticationStrategy;
import org.springframework.ldap.core.support.DefaultTlsDirContextAuthenticationStrategy;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.core.support.SimpleDirContextAuthenticationStrategy;
import org.springframework.ldap.filter.AndFilter;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.HardcodedFilter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.ldap.authentication.AbstractLdapAuthenticationProvider;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.search.LdapUserSearch;
import org.springframework.security.ldap.userdetails.LdapUserDetails;

/**
 * Abstract LDAP based implementation of a login identity provider.
 */
public class LdapProvider implements LoginIdentityProvider {

    private static final Logger logger = LoggerFactory.getLogger(LdapProvider.class);

    private LoginIdentityProviderConfigurationContext configuration;
    private AbstractLdapAuthenticationProvider provider;
    private LdapTemplate ldapTemplate;
    private String issuer;
    private long expiration;
    private IdentityStrategy identityStrategy;

    @Override
    public final void initialize(final LoginIdentityProviderInitializationContext initializationContext) throws ProviderCreationException {
        this.issuer = getClass().getSimpleName();
    }

    @Override
    public final void onConfigured(final LoginIdentityProviderConfigurationContext configurationContext) throws ProviderCreationException {
        final String rawExpiration = configurationContext.getProperty("Authentication Expiration");
        if (StringUtils.isBlank(rawExpiration)) {
            throw new ProviderCreationException("The Authentication Expiration must be specified.");
        }

        try {
            expiration = FormatUtils.getTimeDuration(rawExpiration, TimeUnit.MILLISECONDS);
        } catch (final IllegalArgumentException iae) {
            throw new ProviderCreationException(String.format("The Expiration Duration '%s' is not a valid time duration", rawExpiration));
        }

        final LdapContextSource context = new LdapContextSource();

        final Map<String, Object> baseEnvironment = new HashMap<>();

        // connect/read time out
        setTimeout(configurationContext, baseEnvironment, "Connect Timeout", "com.sun.jndi.ldap.connect.timeout");
        setTimeout(configurationContext, baseEnvironment, "Read Timeout", "com.sun.jndi.ldap.read.timeout");

        // authentication strategy
        final String rawAuthenticationStrategy = configurationContext.getProperty("Authentication Strategy");
        final LdapAuthenticationStrategy authenticationStrategy;
        try {
            authenticationStrategy = LdapAuthenticationStrategy.valueOf(rawAuthenticationStrategy);
        } catch (final IllegalArgumentException iae) {
            throw new ProviderCreationException(String.format("Unrecognized authentication strategy '%s'. Possible values are [%s]",
                    rawAuthenticationStrategy, StringUtils.join(LdapAuthenticationStrategy.values(), ", ")));
        }

        switch (authenticationStrategy) {
            case ANONYMOUS:
                context.setAnonymousReadOnly(true);
                break;
            default:
                final String userDn = configurationContext.getProperty("Manager DN");
                final String password = configurationContext.getProperty("Manager Password");

                context.setUserDn(userDn);
                context.setPassword(password);

                switch (authenticationStrategy) {
                    case SIMPLE:
                        context.setAuthenticationStrategy(new SimpleDirContextAuthenticationStrategy());
                        break;
                    case LDAPS:
                        context.setAuthenticationStrategy(new SimpleDirContextAuthenticationStrategy());

                        // indicate a secure connection
                        baseEnvironment.put(Context.SECURITY_PROTOCOL, "ssl");

                        // get the configured ssl context
                        final SSLContext ldapsSslContext = getConfiguredSslContext(configurationContext);
                        if (ldapsSslContext != null) {
                            // initialize the ldaps socket factory prior to use
                            LdapsSocketFactory.initialize(ldapsSslContext.getSocketFactory());
                            baseEnvironment.put("java.naming.ldap.factory.socket", LdapsSocketFactory.class.getName());
                        }
                        break;
                    case START_TLS:
                        final AbstractTlsDirContextAuthenticationStrategy tlsAuthenticationStrategy = new DefaultTlsDirContextAuthenticationStrategy();

                        // shutdown gracefully
                        final String rawShutdownGracefully = configurationContext.getProperty("TLS - Shutdown Gracefully");
                        if (StringUtils.isNotBlank(rawShutdownGracefully)) {
                            final boolean shutdownGracefully = Boolean.TRUE.toString().equalsIgnoreCase(rawShutdownGracefully);
                            tlsAuthenticationStrategy.setShutdownTlsGracefully(shutdownGracefully);
                        }

                        // get the configured ssl context
                        final SSLContext startTlsSslContext = getConfiguredSslContext(configurationContext);
                        if (startTlsSslContext != null) {
                            tlsAuthenticationStrategy.setSslSocketFactory(startTlsSslContext.getSocketFactory());
                        }

                        // set the authentication strategy
                        context.setAuthenticationStrategy(tlsAuthenticationStrategy);
                        break;
                }
                break;
        }

        // referrals
        final String rawReferralStrategy = configurationContext.getProperty("Referral Strategy");

        final ReferralStrategy referralStrategy;
        try {
            referralStrategy = ReferralStrategy.valueOf(rawReferralStrategy);
        } catch (final IllegalArgumentException iae) {
            throw new ProviderCreationException(String.format("Unrecognized referral strategy '%s'. Possible values are [%s]",
                    rawReferralStrategy, StringUtils.join(ReferralStrategy.values(), ", ")));
        }

        // using the value as this needs to be the lowercase version while the value is configured with the enum constant
        context.setReferral(referralStrategy.getValue());

        // url
        final String urls = configurationContext.getProperty("Url");

        if (StringUtils.isBlank(urls)) {
            throw new ProviderCreationException("LDAP identity provider 'Url' must be specified.");
        }

        // connection
        context.setUrls(StringUtils.split(urls));

        // search criteria
        final String userSearchBase = configurationContext.getProperty("User Search Base");
        final String userSearchFilter = configurationContext.getProperty("User Search Filter");

        if (StringUtils.isBlank(userSearchBase) || StringUtils.isBlank(userSearchFilter)) {
            throw new ProviderCreationException("LDAP identity provider 'User Search Base' and 'User Search Filter' must be specified.");
        }

        final LdapUserSearch userSearch = new FilterBasedLdapUserSearch(userSearchBase, userSearchFilter, context);

        // bind
        final BindAuthenticator authenticator = new BindAuthenticator(context);
        authenticator.setUserSearch(userSearch);

        // identity strategy
        final String rawIdentityStrategy = configurationContext.getProperty("Identity Strategy");

        if (StringUtils.isBlank(rawIdentityStrategy)) {
            logger.info(String.format("Identity Strategy is not configured, defaulting strategy to %s.", IdentityStrategy.USE_DN));

            // if this value is not configured, default to use dn which was the previous implementation
            identityStrategy = IdentityStrategy.USE_DN;
        } else {
            try {
                // attempt to get the configured identity strategy
                identityStrategy = IdentityStrategy.valueOf(rawIdentityStrategy);
            } catch (final IllegalArgumentException iae) {
                throw new ProviderCreationException(String.format("Unrecognized identity strategy '%s'. Possible values are [%s]",
                        rawIdentityStrategy, StringUtils.join(IdentityStrategy.values(), ", ")));
            }
        }

        // set the base environment is necessary
        if (!baseEnvironment.isEmpty()) {
            context.setBaseEnvironmentProperties(baseEnvironment);
        }

        try {
            // handling initializing beans
            context.afterPropertiesSet();
            authenticator.afterPropertiesSet();
        } catch (final Exception e) {
            throw new ProviderCreationException(e.getMessage(), e);
        }

        // create the underlying provider
        provider = new LdapAuthenticationProvider(authenticator);
        ldapTemplate = new LdapTemplate(context);
        configuration = configurationContext;
    }

    private void setTimeout(final LoginIdentityProviderConfigurationContext configurationContext,
            final Map<String, Object> baseEnvironment,
            final String configurationProperty,
            final String environmentKey) {

        final String rawTimeout = configurationContext.getProperty(configurationProperty);
        if (StringUtils.isNotBlank(rawTimeout)) {
            try {
                final Long timeout = FormatUtils.getTimeDuration(rawTimeout, TimeUnit.MILLISECONDS);
                baseEnvironment.put(environmentKey, timeout.toString());
            } catch (final IllegalArgumentException iae) {
                throw new ProviderCreationException(String.format("The %s '%s' is not a valid time duration", configurationProperty, rawTimeout));
            }
        }
    }

    private SSLContext getConfiguredSslContext(final LoginIdentityProviderConfigurationContext configurationContext) {
        final String rawKeystore = configurationContext.getProperty("TLS - Keystore");
        final String rawKeystorePassword = configurationContext.getProperty("TLS - Keystore Password");
        final String rawKeystoreType = configurationContext.getProperty("TLS - Keystore Type");
        final String rawTruststore = configurationContext.getProperty("TLS - Truststore");
        final String rawTruststorePassword = configurationContext.getProperty("TLS - Truststore Password");
        final String rawTruststoreType = configurationContext.getProperty("TLS - Truststore Type");
        final String rawClientAuth = configurationContext.getProperty("TLS - Client Auth");
        final String rawProtocol = configurationContext.getProperty("TLS - Protocol");

        // create the ssl context
        final SSLContext sslContext;
        try {
            if (StringUtils.isBlank(rawKeystore) && StringUtils.isBlank(rawTruststore)) {
                sslContext = null;
            } else {
                // ensure the protocol is specified
                if (StringUtils.isBlank(rawProtocol)) {
                    throw new ProviderCreationException("TLS - Protocol must be specified.");
                }

                if (StringUtils.isBlank(rawKeystore)) {
                    sslContext = SslContextFactory.createTrustSslContext(rawTruststore, rawTruststorePassword.toCharArray(), rawTruststoreType, rawProtocol);
                } else if (StringUtils.isBlank(rawTruststore)) {
                    sslContext = SslContextFactory.createSslContext(rawKeystore, rawKeystorePassword.toCharArray(), rawKeystoreType, rawProtocol);
                } else {
                    // determine the client auth if specified
                    final ClientAuth clientAuth;
                    if (StringUtils.isBlank(rawClientAuth)) {
                        clientAuth = ClientAuth.NONE;
                    } else {
                        try {
                            clientAuth = ClientAuth.valueOf(rawClientAuth);
                        } catch (final IllegalArgumentException iae) {
                            throw new ProviderCreationException(String.format("Unrecognized client auth '%s'. Possible values are [%s]",
                                    rawClientAuth, StringUtils.join(ClientAuth.values(), ", ")));
                        }
                    }

                    sslContext = SslContextFactory.createSslContext(rawKeystore, rawKeystorePassword.toCharArray(), rawKeystoreType,
                            rawTruststore, rawTruststorePassword.toCharArray(), rawTruststoreType, clientAuth, rawProtocol);
                }
            }
        } catch (final KeyStoreException | NoSuchAlgorithmException | CertificateException | UnrecoverableKeyException | KeyManagementException | IOException e) {
            throw new ProviderCreationException(e.getMessage(), e);
        }

        return sslContext;
    }

    @Override
    public final AuthenticationResponse authenticate(final LoginCredentials credentials) throws InvalidLoginCredentialsException, IdentityAccessException {
        if (provider == null) {
            throw new IdentityAccessException("The LDAP authentication provider is not initialized.");
        }

        try {
            // perform the authentication
            final UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(credentials.getUsername(), credentials.getPassword());
            final Authentication authentication = provider.authenticate(token);

            // use dn if configured
            if (IdentityStrategy.USE_DN.equals(identityStrategy)) {
                // attempt to get the ldap user details to get the DN
                if (authentication.getPrincipal() instanceof LdapUserDetails) {
                    final LdapUserDetails userDetails = (LdapUserDetails) authentication.getPrincipal();
                    return new AuthenticationResponse(userDetails.getDn(), credentials.getUsername(), expiration, issuer);
                } else {
                    logger.warn(String.format("Unable to determine user DN for %s, using username.", authentication.getName()));
                    return new AuthenticationResponse(authentication.getName(), credentials.getUsername(), expiration, issuer);
                }
            } else {
                return new AuthenticationResponse(authentication.getName(), credentials.getUsername(), expiration, issuer);
            }
        } catch (final BadCredentialsException | UsernameNotFoundException | AuthenticationException e) {
            throw new InvalidLoginCredentialsException(e.getMessage(), e);
        } catch (final Exception e) {
            // there appears to be a bug that generates a InternalAuthenticationServiceException wrapped around an AuthenticationException. this
            // shouldn't be the case as they the service exception suggestions that something was wrong with the service. while the authentication
            // exception suggests that username and/or credentials were incorrect. checking the cause seems to address this scenario.
            final Throwable cause = e.getCause();
            if (cause instanceof AuthenticationException) {
                throw new InvalidLoginCredentialsException(e.getMessage(), e);
            }

            logger.error(e.getMessage());
            if (logger.isDebugEnabled()) {
                logger.debug(StringUtils.EMPTY, e);
            }
            throw new IdentityAccessException("Unable to validate the supplied credentials. Please contact the system administrator.", e);
        }
    }

    @Override
    public final void preDestruction() throws ProviderDestructionException {
    }

    @Override
    public List<AuthenticationIdentity> listIdentities(String userSearchFilter, String groupSearchFilter) throws IdentityAccessException {

        String userSearchBase = configuration.getProperty("User Search Base");
        String userNameAttribute = configuration.getProperty("User name Attribute");
        String userObjectClass = configuration.getProperty("User Object Class");
        String userGroupNameAttribute = configuration.getProperty("User Group Name Attribute");

        String groupSearchBase = configuration.getProperty("Group Search Base");
        String groupMemberAttribute = configuration.getProperty("Group Member Attribute");
        String groupNameAttribute = configuration.getProperty("Group Name Attribute");
        String groupObjectClass = configuration.getProperty("Group Object Class");

        // we look for users only if no filters are provided (meaning we look for everything), or if a filter is provided for users
        boolean performUsersSearch = (userSearchFilter == null && groupSearchFilter == null) || userSearchFilter != null;

        // we look for groups only if no filters are provided (meaning we look for everything), or if a filter is provided for groups
        boolean performGroupsSearch = (userSearchFilter == null && groupSearchFilter == null) || groupSearchFilter != null;

        // check required parameters
        if(StringUtils.isBlank(userObjectClass) && StringUtils.isBlank(groupObjectClass)) {
            throw new IdentityAccessException("One of User Object Class and Group Object Class is required in login identity provider configuration.");
        }

        // if user set parameters to search users, then...
        if(!StringUtils.isBlank(userObjectClass)) {
            // user name attribute is required
            if(StringUtils.isBlank(userNameAttribute)) {
                throw new IdentityAccessException("User name attribute is required in login identity provider configuration.");
            }
        }

        // if user set parameters to search groups, then...
        if(!StringUtils.isBlank(groupSearchBase)) {
            // group name attribute is required
            if(StringUtils.isBlank(groupObjectClass)) {
                throw new IdentityAccessException("Group object class is required in login identity provider configuration when group search base is set.");
            }
            // group name attribute is required
            if(StringUtils.isBlank(groupNameAttribute)) {
                throw new IdentityAccessException("Group name attribute is required in login identity provider configuration when group search base is set.");
            }
            // if identity strategy is USERNAME, then user name attribute is required
            if(IdentityStrategy.USE_USERNAME.equals(identityStrategy) && StringUtils.isBlank(userNameAttribute)) {
                throw new IdentityAccessException("User name attribute is required in login identity provider configuration.");
            }
        }


        // At this point, we list users based on the configuration parameters
        // as well as using the filter provided by the user
        AndFilter andFilter = new AndFilter();

        // looking for objects matching the user object class
        andFilter.and(new EqualsFilter("objectClass", userObjectClass));

        // if a filter has been provided by the user, we add it to the filter
        if(!StringUtils.isBlank(userSearchFilter)) {
            andFilter.and(new HardcodedFilter(userSearchFilter));
        }

        // we list the users based on the parameters and we return identities (user name + list of groups the user belongs to)
        List<AuthenticationIdentity> users = new ArrayList<AuthenticationIdentity>();

        if(performUsersSearch) {

            try {

                users = ldapTemplate.search(userSearchBase, andFilter.encode(), new AbstractContextMapper<AuthenticationIdentity>() {
                    @Override
                    protected AuthenticationIdentity doMapFromContext(DirContextOperations ctx) {
                        String name;
                        if(identityStrategy.equals(IdentityStrategy.USE_DN)) {
                            name = ctx.getDn().toString();
                        } else {
                            Attribute attributeName = ctx.getAttributes().get(userNameAttribute);
                            if(attributeName == null) {
                                throw new IdentityAccessException("User name attribute [" + userNameAttribute + "] does not exist.");
                            }
                            try {
                                name = (String) attributeName.get();
                            } catch (NamingException e) {
                                throw new IdentityAccessException("Error while retrieving user name attribute [" + userNameAttribute + "].");
                            }
                        }

                        List<String> groups = new ArrayList<String>();
                        if(!StringUtils.isBlank(userGroupNameAttribute)) {
                            Attribute attributeGroups = ctx.getAttributes().get(userGroupNameAttribute);
                            if(attributeGroups == null) {
                                logger.error("User group name attribute [" + userGroupNameAttribute + "] does not exist. Ignoring group membership.");
                            } else {
                                try {
                                    groups = toList((Enumeration<String>) attributeGroups.getAll());
                                } catch (NamingException e) {
                                    throw new IdentityAccessException("Error while retrieving user group name attribute [" + userNameAttribute + "].");
                                }
                            }
                        }

                        return new AuthenticationIdentity(name, groups);
                    }

                    private List<String> toList(Enumeration<String> enumeration) {
                        List<String> result = new ArrayList<String>();
                        while(enumeration.hasMoreElements()) {
                            result.add(enumeration.nextElement());
                        }
                        return result;
                    }
                });

            } catch (Exception e) {
                throw new IdentityAccessException("Error while listing users with provided parameters.", e);
            }

        }

        // At this point, we list groups based on the configuration parameters
        // as well as using the filter provided by the user
        AndFilter groupAndFilter = new AndFilter();

        // looking for objects matching the group object class
        groupAndFilter.and(new EqualsFilter("objectClass", groupObjectClass));

        // if a filter has been provided by the user, we add it to the filter
        if(!StringUtils.isBlank(groupSearchFilter)) {
            groupAndFilter.and(new HardcodedFilter(groupSearchFilter));
        }

        // we list the groups based on the parameters and we return identities (group name + list of users the group contains)
        List<GroupIdentity> groups = new ArrayList<GroupIdentity>();

        if(!StringUtils.isBlank(groupSearchBase) && performGroupsSearch) {

            try {
                groups = ldapTemplate.search(groupSearchBase, groupAndFilter.encode(), new AttributesMapper<GroupIdentity>() {
                    @Override
                    public GroupIdentity mapFromAttributes(Attributes attributes) throws NamingException {
                        Attribute attributeName = attributes.get(groupNameAttribute);
                        if(attributeName == null) {
                            throw new NamingException("Group name attribute [" + groupNameAttribute + "] does not exist.");
                        }

                        List<String> users = new ArrayList<String>();
                        if(!StringUtils.isBlank(groupMemberAttribute)) {
                            Attribute attributeUsers = attributes.get(groupMemberAttribute);
                            if(attributeUsers == null) {
                                logger.error("Group member attribute [" + userGroupNameAttribute + "] does not exist. Ignoring group membership.");
                            } else {
                                users = toList((Enumeration<String>) attributeUsers.getAll());
                            }
                        }

                        // at this point we got DN of users belonging to the group, if we want to resolve user names
                        // we have to go through the list to lookup for names.
                        if(identityStrategy.equals(IdentityStrategy.USE_USERNAME)) {
                            List<String> usernames = new ArrayList<String>();
                            for(String userDn : users) {
                                DirContextAdapter ctx = (DirContextAdapter) ldapTemplate.lookup(userDn);
                                Attribute userNameAtt = ctx.getAttributes().get(userNameAttribute);
                                if(userNameAtt == null) {
                                    throw new IdentityAccessException("User name attribute [" + userNameAttribute + "] does not exist.");
                                }
                                usernames.add((String) userNameAtt.get());
                            }
                            return new GroupIdentity((String) attributeName.get(), usernames);
                        }

                        return new GroupIdentity((String) attributeName.get(), users);
                    }

                    private List<String> toList(Enumeration<String> enumeration) {
                        List<String> result = new ArrayList<String>();
                        while(enumeration.hasMoreElements()) {
                            result.add(enumeration.nextElement());
                        }
                        return result;
                    }
                });
            } catch (Exception e) {
                throw new IdentityAccessException("Error while listing groups with provided parameters.", e);
            }

        }

        // from here we have two lists of different objects and we want to return one single list
        Map<String, List<String>> userIdentityMap = new HashMap<String, List<String>>();
        for(AuthenticationIdentity authId : users) {
            userIdentityMap.put(authId.getUsername(), authId.getGroups());
        }

        for(GroupIdentity groupId : groups) {
            for(String user : groupId.getUsers()) {
                if(userIdentityMap.containsKey(user)) {
                    if(!userIdentityMap.get(user).contains(groupId.getGroupName())) {
                        userIdentityMap.get(user).add(groupId.getGroupName());
                    }
                } else {
                    List<String> initGroupList = new ArrayList<String>();
                    initGroupList.add(groupId.getGroupName());
                    userIdentityMap.put(user, initGroupList);
                }
            }
        }

        // construct the final list with all the users/groups to add
        List<AuthenticationIdentity> result = new ArrayList<AuthenticationIdentity>();
        for(String user : userIdentityMap.keySet()) {
            result.add(new AuthenticationIdentity(user, userIdentityMap.get(user)));
        }

        return result;
    }

    /**
     * Class to handle a group with a list of members
     */
    private class GroupIdentity {

        private final String groupName;
        private final List<String> users;

        public GroupIdentity(final String groupName, final List<String> users) {
            this.groupName = groupName;
            this.users = users;
        }

        public String getGroupName() {
            return groupName;
        }

        public List<String> getUsers() {
            return users;
        }
    }

}
