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
package org.apache.nifi.web.api.dto;

import javax.xml.bind.annotation.XmlType;

import com.wordnik.swagger.annotations.ApiModelProperty;

/**
 *  A query to sync users and groups in NiFi.
 */
@XmlType(name = "syncUsersGroups")
public class SyncUsersGroupsDTO extends TenantDTO {

    private String usersSearchFilter;
    private String groupsSearchFilter;

    /**
     * @return The search filter to apply on query to get users.
     */
    @ApiModelProperty(value = "The search filter to apply on query to get users.")
    public String getUsersSearchFilter() {
        return usersSearchFilter;
    }

    public void setUsersSearchFilter(String usersSearchFilter) {
        this.usersSearchFilter = usersSearchFilter;
    }

    /**
     * @return The search filter to apply on query to get groups.
     */
    @ApiModelProperty(value = "The search filter to apply on query to get groups.")
    public String getGroupsSearchFilter() {
        return groupsSearchFilter;
    }

    public void setGroupsSearchFilter(String groupsSearchFilter) {
        this.groupsSearchFilter = groupsSearchFilter;
    }
}
