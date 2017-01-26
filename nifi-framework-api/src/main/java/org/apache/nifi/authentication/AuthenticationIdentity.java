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
package org.apache.nifi.authentication;

import java.util.Set;

/**
 * Authentication identity.
 */
public class AuthenticationIdentity {

    private final String identity;
    private final String username;
    private final Set<String> groups;
    private final String issuer;

    /**
     * Creates an authentication response. The username and how long the authentication is valid in milliseconds
     *
     * @param identity The user identity
     * @param username The username
     * @param issuer The issuer of the token
     */
    public AuthenticationIdentity(final String identity, final String username, final String issuer) {
        this.identity = identity;
        this.username = username;
        this.issuer = issuer;
    }

    public String getIdentity() {
        return identity;
    }

    public String getUsername() {
        return username;
    }

    public String getIssuer() {
        return issuer;
    }

}
