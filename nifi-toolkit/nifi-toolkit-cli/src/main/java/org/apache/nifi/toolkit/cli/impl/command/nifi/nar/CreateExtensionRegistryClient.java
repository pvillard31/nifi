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
import org.apache.nifi.toolkit.cli.api.Context;
import org.apache.nifi.toolkit.cli.impl.command.CommandOption;
import org.apache.nifi.toolkit.cli.impl.command.nifi.AbstractNiFiCommand;
import org.apache.nifi.toolkit.cli.impl.result.StringResult;
import org.apache.nifi.toolkit.client.NiFiClient;
import org.apache.nifi.toolkit.client.NiFiClientException;
import org.apache.nifi.web.api.dto.ExtensionRegistryClientDTO;
import org.apache.nifi.web.api.entity.ExtensionRegistryClientEntity;

import java.io.IOException;
import java.util.Properties;

/**
 * Command for creating an extension registry client in NiFi.
 */
public class CreateExtensionRegistryClient extends AbstractNiFiCommand<StringResult> {

    public CreateExtensionRegistryClient() {
        super("create-extension-reg-client", StringResult.class);
    }

    @Override
    public String getDescription() {
        return "Creates an extension registry client using the provided information.";
    }

    @Override
    public void doInitialize(final Context context) {
        addOption(CommandOption.REGISTRY_CLIENT_NAME.createOption());
        addOption(CommandOption.REGISTRY_CLIENT_DESC.createOption());
        addOption(CommandOption.REGISTRY_CLIENT_TYPE.createOption());
    }

    @Override
    public StringResult doExecute(final NiFiClient client, final Properties properties)
            throws NiFiClientException, IOException, MissingOptionException {

        final String name = getRequiredArg(properties, CommandOption.REGISTRY_CLIENT_NAME);
        final String type = getRequiredArg(properties, CommandOption.REGISTRY_CLIENT_TYPE);
        final String desc = getArg(properties, CommandOption.REGISTRY_CLIENT_DESC);

        final ExtensionRegistryClientDTO extensionRegistryClientDTO = new ExtensionRegistryClientDTO();
        extensionRegistryClientDTO.setName(name);
        extensionRegistryClientDTO.setDescription(desc);
        extensionRegistryClientDTO.setType(type);

        final ExtensionRegistryClientEntity clientEntity = new ExtensionRegistryClientEntity();
        clientEntity.setComponent(extensionRegistryClientDTO);
        clientEntity.setRevision(getInitialRevisionDTO());

        final ExtensionRegistryClientEntity createdEntity = client.getControllerClient().createExtensionRegistryClient(clientEntity);
        return new StringResult(createdEntity.getId(), getContext().isInteractive());
    }
}
