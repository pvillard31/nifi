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
package org.apache.nifi.toolkit.cli.impl.result.nifi;

import org.apache.nifi.toolkit.cli.api.ResultType;
import org.apache.nifi.toolkit.cli.impl.result.AbstractWritableResult;
import org.apache.nifi.toolkit.cli.impl.result.writer.DynamicTableWriter;
import org.apache.nifi.toolkit.cli.impl.result.writer.Table;
import org.apache.nifi.toolkit.cli.impl.result.writer.TableWriter;
import org.apache.nifi.web.api.dto.ExtensionRegistryClientDTO;
import org.apache.nifi.web.api.entity.ExtensionRegistryClientEntity;
import org.apache.nifi.web.api.entity.ExtensionRegistryClientsEntity;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Result for a ExtensionRegistryClientsEntity.
 */
public class ExtensionRegistryClientsResult extends AbstractWritableResult<ExtensionRegistryClientsEntity> {

    final ExtensionRegistryClientsEntity registryClients;

    public ExtensionRegistryClientsResult(final ResultType resultType, final ExtensionRegistryClientsEntity registryClients) {
        super(resultType);
        this.registryClients = Objects.requireNonNull(registryClients);
    }

    @Override
    public ExtensionRegistryClientsEntity getResult() {
        return this.registryClients;
    }

    @Override
    protected void writeSimpleResult(final PrintStream output) {
        final Set<ExtensionRegistryClientEntity> clients = registryClients.getRegistries();
        if (clients == null || clients.isEmpty()) {
            return;
        }

        final List<ExtensionRegistryClientDTO> registries = clients.stream()
            .map(ExtensionRegistryClientEntity::getComponent)
            .sorted(Comparator.comparing(ExtensionRegistryClientDTO::getName))
            .toList();

        final Table table = new Table.Builder()
                .column("#", 3, 3, false)
                .column("Name", 20, 36, true)
                .column("Type", 20, 120, true)
                .column("Id", 36, 36, false)
                .column("Properties", 3, Integer.MAX_VALUE, false)
                .build();

        for (int i = 0; i < registries.size(); i++) {
            ExtensionRegistryClientDTO clientDto = registries.get(i);
            final Map<String, String> properties = clientDto.getProperties();
            table.addRow("" + (i + 1), clientDto.getName(), clientDto.getType(), clientDto.getId(), properties == null ? "" : properties.toString());
        }

        final TableWriter tableWriter = new DynamicTableWriter();
        tableWriter.write(table, output);
    }

}
