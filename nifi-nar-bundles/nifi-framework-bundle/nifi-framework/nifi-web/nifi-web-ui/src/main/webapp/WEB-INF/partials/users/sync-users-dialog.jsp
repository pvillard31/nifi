<%--
 Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
--%>
<%@ page contentType="text/html" pageEncoding="UTF-8" session="false" %>
<div id="sync-users-dialog" class="hidden">
    <div class="dialog-content">
        <div class="setting">
            <div class="setting-name">User search filter</div>
            <div class="setting-field">
                <span id="user-id-edit-dialog" class="hidden"></span>
                <input type="text" id="user-filter-edit-dialog"/>
            </div>
            <div class="clear"></div>
            <div class="setting-name">Group search filter</div>
            <div class="setting-field">
                <input type="text" id="group-filter-edit-dialog"/>
            </div>
            <div class="clear"></div>
        </div>
        <div class="setting" class="setting hidden">
            <div class="setting-field">
                <input id="sync-individual-radio-button" type="radio" name="userOrGroup" value="individual" checked="checked"/> Individual
                <input id="sync-group-radio-button" type="radio" name="userOrGroup" value="group" style="margin-left: 20px;"/> Group
            </div>
            <div class="clear"></div>
        </div>
        <div id="user-groups" class="setting hidden">
            <div class="setting-name">Users to add/update</div>
            <div class="setting-field">
                <ul id="sync-groups" class="usersGroupsList"></ul>
            </div>
            <div class="clear"></div>
        </div>
        <div id="group-members" class="setting hidden">
            <div class="setting-name">Groups to add/update</div>
            <div class="setting-field">
                <ul id="sync-users" class="usersGroupsList"></ul>
            </div>
            <div class="clear"></div>
        </div>
    </div>
</div>