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

import java.util.List;

/**
 * Authentication identity.
 */
public class AuthenticationIdentity {

    private final String username;
    private final List<String> groups;

    /**
     * Creates an authentication identity made of a valid user name and the list of groups
     * the user belongs to.
     *
     * @param username The user name
     * @param groups List of groups the user belongs to
     */
    public AuthenticationIdentity(final String username, final List<String> groups) {
        this.username = username;
        this.groups = groups;
    }

    public String getUsername() {
        return username;
    }

    public List<String> getGroups() {
        return groups;
    }

}
