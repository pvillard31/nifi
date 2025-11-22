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
package org.apache.nifi.toolkit.cli.impl.command.nifi.nar;

import org.apache.nifi.toolkit.cli.impl.command.nifi.AbstractNiFiCommand;
import org.apache.nifi.toolkit.cli.impl.result.nifi.ExtensionRegistryClientsResult;
import org.apache.nifi.toolkit.client.NiFiClient;
import org.apache.nifi.toolkit.client.NiFiClientException;
import org.apache.nifi.web.api.entity.ExtensionRegistryClientsEntity;

import java.io.IOException;
import java.util.Properties;

/**
 * Lists the extension registry clients defined in the given NiFi instance.
 */
public class ListExtensionRegistryClients extends AbstractNiFiCommand<ExtensionRegistryClientsResult> {

    public ListExtensionRegistryClients() {
        super("list-extension-reg-clients", ExtensionRegistryClientsResult.class);
    }

    @Override
    public String getDescription() {
        return "Returns the extension registry clients defined in the given NiFi instance.";
    }

    @Override
    public ExtensionRegistryClientsResult doExecute(final NiFiClient client, final Properties properties)
            throws NiFiClientException, IOException {
        final ExtensionRegistryClientsEntity registries = client.getControllerClient().getExtensionRegistryClients();
        return new ExtensionRegistryClientsResult(getResultType(properties), registries);
    }

}
