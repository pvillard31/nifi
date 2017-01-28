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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.nifi.authentication.AuthenticationIdentity;
import org.apache.nifi.authentication.AuthenticationResponse;
import org.apache.nifi.authentication.LoginCredentials;
import org.apache.nifi.authentication.LoginIdentityProviderConfigurationContext;
import org.apache.nifi.authentication.exception.IdentityAccessException;
import org.apache.nifi.authentication.exception.InvalidLoginCredentialsException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test for LDAP provider, requires some configuration to work
 */
public class ITLdapProvider {

    private final LdapProvider ldapProvider = new LdapProvider();

    @Before
    public void init() {
        ldapProvider.initialize(null);

        Map<String, String> properties = new HashMap<String, String>();
        properties.put("Authentication Strategy", "SIMPLE");

        properties.put("Manager DN", "uid=admin,ou=system");
        properties.put("Manager Password", "secret");

        properties.put("Referral Strategy", "FOLLOW");
        properties.put("Connect Timeout", "10 secs");
        properties.put("Read Timeout", "10 secs");

        properties.put("Url", "ldap://localhost:10389");
        properties.put("User Search Base", "ou=people,dc=nifi,dc=com");
        properties.put("User Search Filter", "cn={0}");

        // parameters for sync
        properties.put("User Object Class", "person");
        properties.put("User name Attribute", "cn");
        properties.put("User Group Name Attribute", "memberOf");

        properties.put("Group Search Base", "ou=groups,dc=nifi,dc=com");
        properties.put("Group Object Class", "groupOfUniqueNames");
        properties.put("Group Name Attribute", "cn");
        properties.put("Group Member Attribute", "uniqueMember");

        properties.put("Identity Strategy", "USE_USERNAME");
        properties.put("Authentication Expiration", "12 hours");

        LoginIdentityProviderConfigurationContext configurationContext = new StandardLoginIdentityProviderConfigurationContext("ldap-provider", properties);
        ldapProvider.onConfigured(configurationContext);
    }

    @Test
    public void testAuthentication() {
        LoginCredentials credentials = new LoginCredentials("test", "password");
        try {
            AuthenticationResponse response = ldapProvider.authenticate(credentials);
            Assert.assertTrue(response.getUsername().equals("test"));
        } catch (InvalidLoginCredentialsException | IdentityAccessException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void listUsersAndGroupsWithNoFilter() {
        List<AuthenticationIdentity> list = ldapProvider.listIdentities(null, null);

        // expected result:
        // two users "test" and "test2" belonging to group "users"
        // one user "admin" belonging to group "users" and to group "admins"

        Assert.assertEquals(list.size(), 3); // 3 users

        for (AuthenticationIdentity authenticationIdentity : list) {
            if(authenticationIdentity.getUsername().equals("test")) {
                Assert.assertEquals(authenticationIdentity.getGroups().size(), 1);
                Assert.assertEquals(authenticationIdentity.getGroups().get(0), "users");
                continue;
            }
            if(authenticationIdentity.getUsername().equals("test2")) {
                Assert.assertEquals(authenticationIdentity.getGroups().size(), 1);
                Assert.assertEquals(authenticationIdentity.getGroups().get(0), "users");
                continue;
            }
            if(authenticationIdentity.getUsername().equals("admin")) {
                Assert.assertEquals(authenticationIdentity.getGroups().size(), 2);
                Assert.assertTrue(authenticationIdentity.getGroups().contains("users"));
                Assert.assertTrue(authenticationIdentity.getGroups().contains("admins"));
                continue;
            }
            Assert.fail();
        }
    }

    @Test
    public void listUsersAndGroupsWithUsersFilter() {
        List<AuthenticationIdentity> list = ldapProvider.listIdentities("(cn=test)", null);
        Assert.assertEquals(list.size(), 1); // 1 user
        Assert.assertTrue(list.get(0).getUsername().equals("test"));
    }

    @Test
    public void listUsersAndGroupsWithGroupsFilter() {
        List<AuthenticationIdentity> list = ldapProvider.listIdentities(null, "(cn=admins)");
        Assert.assertEquals(list.size(), 1); // 1 user
        Assert.assertTrue(list.get(0).getUsername().equals("admin"));
    }

    @Test
    public void listUsersAndGroupsWithBadFilter() {
        List<AuthenticationIdentity> list = ldapProvider.listIdentities(null, "(cn=notagroup)");
        Assert.assertEquals(list.size(), 0);
    }

    @Test
    public void listUsersAndGroupsWithBothFilters() {
        List<AuthenticationIdentity> list = ldapProvider.listIdentities("(cn=admin)", "(cn=users)");
        // we get all users because filters are used independently
        Assert.assertEquals(list.size(), 3);
    }

    private class StandardLoginIdentityProviderConfigurationContext implements LoginIdentityProviderConfigurationContext {
        private final String identifier;
        private final Map<String, String> properties;

        public StandardLoginIdentityProviderConfigurationContext(String identifier, Map<String, String> properties) {
            this.identifier = identifier;
            this.properties = properties;
        }

        @Override
        public String getIdentifier() {
            return identifier;
        }

        @Override
        public Map<String, String> getProperties() {
            return Collections.unmodifiableMap(properties);
        }

        @Override
        public String getProperty(String property) {
            return properties.get(property);
        }
    }

}
