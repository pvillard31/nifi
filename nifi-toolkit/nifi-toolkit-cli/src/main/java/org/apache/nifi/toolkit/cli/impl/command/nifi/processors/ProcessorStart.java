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
package org.apache.nifi.toolkit.cli.impl.command.nifi.processors;

import org.apache.commons.cli.MissingOptionException;
import org.apache.nifi.toolkit.cli.api.CommandException;
import org.apache.nifi.toolkit.cli.api.Context;
import org.apache.nifi.toolkit.cli.impl.command.CommandOption;
import org.apache.nifi.toolkit.cli.impl.command.nifi.AbstractNiFiCommand;
import org.apache.nifi.toolkit.cli.impl.result.nifi.ProcessorResult;
import org.apache.nifi.toolkit.client.NiFiClient;
import org.apache.nifi.toolkit.client.NiFiClientException;
import org.apache.nifi.toolkit.client.ProcessorClient;
import org.apache.nifi.web.api.entity.ProcessorEntity;

import java.io.IOException;
import java.util.Properties;

/**
 * Command to start a processor
 */
public class ProcessorStart extends AbstractNiFiCommand<ProcessorResult> {

    public ProcessorStart() {
        super("processor-start", ProcessorResult.class);
    }

    @Override
    public String getDescription() {
        return "Starts a processor.";
    }

    @Override
    protected void doInitialize(Context context) {
        addOption(CommandOption.PROC_ID.createOption());
    }

    @Override
    public ProcessorResult doExecute(NiFiClient client, Properties properties)
            throws NiFiClientException, IOException, MissingOptionException, CommandException {

        final String procId = getRequiredArg(properties, CommandOption.PROC_ID);
        final ProcessorClient processorClient = client.getProcessorClient();
        final ProcessorEntity processor = processorClient.getProcessor(procId);
        final ProcessorEntity result = processorClient.startProcessor(processor);

        return new ProcessorResult(getResultType(properties), result);
    }

}
