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

import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.toolkit.cli.api.CommandException;
import org.apache.nifi.toolkit.cli.api.Context;
import org.apache.nifi.toolkit.cli.impl.command.CommandOption;
import org.apache.nifi.toolkit.cli.impl.command.nifi.AbstractNiFiCommand;
import org.apache.nifi.toolkit.cli.impl.result.nifi.ExtensionRegistryClientIDResult;
import org.apache.nifi.toolkit.client.NiFiClient;
import org.apache.nifi.toolkit.client.NiFiClientException;
import org.apache.nifi.web.api.dto.ExtensionRegistryClientDTO;
import org.apache.nifi.web.api.entity.ExtensionRegistryClientsEntity;

import java.io.IOException;
import java.util.Properties;

/**
 * Command to get the id of an extension registry client by name.
 */
public class GetExtensionRegistryClientId extends AbstractNiFiCommand<ExtensionRegistryClientIDResult> {

    public GetExtensionRegistryClientId() {
        super("get-extension-reg-client-id", ExtensionRegistryClientIDResult.class);
    }

    @Override
    public String getDescription() {
        return "Returns the id of the first extension registry client found with the given name.";
    }

    @Override
    protected void doInitialize(final Context context) {
        addOption(CommandOption.REGISTRY_CLIENT_NAME.createOption());
    }

    @Override
    public ExtensionRegistryClientIDResult doExecute(final NiFiClient client, final Properties properties)
            throws NiFiClientException, IOException, CommandException, MissingOptionException {
        final String regClientName = getRequiredArg(properties, CommandOption.REGISTRY_CLIENT_NAME);
        final ExtensionRegistryClientsEntity registries = client.getControllerClient().getExtensionRegistryClients();
        ExtensionRegistryClientDTO registry = null;

        if (!StringUtils.isBlank(regClientName)) {
            registry = registries.getRegistries().stream()
                    .map(r -> r.getComponent())
                    .filter(r -> r.getName().equalsIgnoreCase(regClientName.trim()))
                    .findFirst()
                    .orElse(null);
        }

        if (registry == null) {
            throw new NiFiClientException("No extension registry client exists with the name '" + regClientName + "'");
        } else {
            return new ExtensionRegistryClientIDResult(getResultType(properties), registry);
        }
    }

}
