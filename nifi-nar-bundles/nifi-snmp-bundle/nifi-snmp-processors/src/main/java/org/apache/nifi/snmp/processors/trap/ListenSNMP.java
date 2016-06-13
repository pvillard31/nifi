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
package org.apache.nifi.snmp.processors.trap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnUnscheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.snmp.processors.SNMPUtils;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.smi.TcpAddress;
import org.snmp4j.smi.TransportIpAddress;
import org.snmp4j.smi.UdpAddress;

/**
 * This processor will listen on a given port to receive
 * SNMP traps. For each receiver trap, a flow file will
 * be created with attributes containing trap data. The
 * flow files will be routed to success relationship.
 */
@Tags({ "snmp", "listen", "oid", "trap" })
@InputRequirement(Requirement.INPUT_FORBIDDEN)
@CapabilityDescription("This processors listens for SNMP traps and creates a flow file for each received trap")
public class ListenSNMP extends AbstractProcessor {

    /** property to define listening port */
    public static final PropertyDescriptor PORT = new PropertyDescriptor.Builder()
            .name("snmp-port")
            .displayName("Port")
            .description("Numeric value identifying the processor listening port (e.g., 162)")
            .required(true)
            .defaultValue("162")
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .build();

    /** property to define expected protocol */
    public static final PropertyDescriptor PROTOCOL = new PropertyDescriptor.Builder()
            .name("snmp-protocol")
            .displayName("Protocol")
            .description("Numeric value identifying Port of SNMP Agent (e.g., 161)")
            .required(true)
            .defaultValue("UDP")
            .allowableValues("UDP", "TCP")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /** property to define SNMP version to use */
    public static final PropertyDescriptor SNMP_VERSION = new PropertyDescriptor.Builder()
            .name("snmp-version")
            .displayName("SNMP Version")
            .description("SNMP Version to use")
            .required(true)
            .allowableValues("SNMPv1", "SNMPv2c", "SNMPv3")
            .defaultValue("SNMPv1")
            .build();

    /** property to define SNMP security name to use */
    public static final PropertyDescriptor SNMP_SECURITY_NAME = new PropertyDescriptor.Builder()
            .name("snmp-security-name")
            .displayName("SNMP Security name / user name")
            .description("Security name used for SNMP exchanges")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    /** property to define SNMP authentication protocol to use */
    public static final PropertyDescriptor SNMP_AUTH_PROTOCOL = new PropertyDescriptor.Builder()
            .name("snmp-authentication-protocol")
            .displayName("SNMP Authentication Protocol")
            .description("SNMP Authentication Protocol to use")
            .required(true)
            .allowableValues("MD5", "SHA", "")
            .defaultValue("")
            .build();

    /** property to define SNMP authentication password to use */
    public static final PropertyDescriptor SNMP_AUTH_PASSWORD = new PropertyDescriptor.Builder()
            .name("snmp-authentication-passphrase")
            .displayName("SNMP Authentication pass phrase")
            .description("Pass phrase used for SNMP authentication protocol")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();

    /** property to define SNMP private protocol to use */
    public static final PropertyDescriptor SNMP_PRIV_PROTOCOL = new PropertyDescriptor.Builder()
            .name("snmp-private-protocol")
            .displayName("SNMP Private Protocol")
            .description("SNMP Private Protocol to use")
            .required(true)
            .allowableValues("DES", "3DES", "AES128", "AES192", "AES256", "")
            .defaultValue("")
            .build();

    /** property to define SNMP private password to use */
    public static final PropertyDescriptor SNMP_PRIV_PASSWORD = new PropertyDescriptor.Builder()
            .name("snmp-private-protocol-passphrase")
            .displayName("SNMP Private protocol pass phrase")
            .description("Pass phrase used for SNMP private protocol")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .sensitive(true)
            .build();

    public static final PropertyDescriptor MAX_MESSAGE_QUEUE_SIZE = new PropertyDescriptor.Builder()
            .name("Max Size of Message Queue")
            .description("The maximum size of the internal queue used to buffer messages being transferred from the underlying channel to the processor. " +
                    "Setting this value higher allows more messages to be buffered in memory during surges of incoming messages, but increases the total " +
                    "memory used by the processor.")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .defaultValue("10000")
            .required(true)
            .build();

    /** relationship for success */
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("All SNMP traps successfully received are routed to this relationship")
            .build();

    /** list of properties descriptors */
    private final static List<PropertyDescriptor> propertyDescriptors;

    /** list of relationships */
    private final static Set<Relationship> relationships;

    /** queue to store events */
    private volatile BlockingQueue<CommandResponderEvent> trapEvents;
    private volatile TrapReceiver receiver;

    /*
     * Will ensure that the list of property descriptors is build only once.
     * Will also create a Set of relationships
     */
    static {
        List<PropertyDescriptor> _propertyDescriptors = new ArrayList<>();
        _propertyDescriptors.add(PORT);
        _propertyDescriptors.add(PROTOCOL);
        _propertyDescriptors.add(SNMP_VERSION);
        _propertyDescriptors.add(SNMP_SECURITY_NAME);
        _propertyDescriptors.add(SNMP_AUTH_PROTOCOL);
        _propertyDescriptors.add(SNMP_AUTH_PASSWORD);
        _propertyDescriptors.add(SNMP_PRIV_PROTOCOL);
        _propertyDescriptors.add(SNMP_PRIV_PASSWORD);
        _propertyDescriptors.add(MAX_MESSAGE_QUEUE_SIZE);
        propertyDescriptors = Collections.unmodifiableList(_propertyDescriptors);

        Set<Relationship> _relationships = new HashSet<>();
        _relationships.add(REL_SUCCESS);
        relationships = Collections.unmodifiableSet(_relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) throws IOException {
        final String protocol = context.getProperty(PROTOCOL).getValue();
        final int port = context.getProperty(PORT).asInteger();
        final int size = context.getProperty(MAX_MESSAGE_QUEUE_SIZE).asInteger();
        final String snmpVersion = context.getProperty(SNMP_VERSION).getValue();
        final String snmpSecurityName = context.getProperty(SNMP_SECURITY_NAME).getValue();
        final String snmpAuthProtocol = context.getProperty(SNMP_AUTH_PROTOCOL).getValue();
        final String snmpAuthPassword = context.getProperty(SNMP_AUTH_PASSWORD).getValue();
        final String snmpPrivProtocol = context.getProperty(SNMP_PRIV_PROTOCOL).getValue();
        final String snmpPrivPassword = context.getProperty(SNMP_PRIV_PASSWORD).getValue();

        trapEvents = new LinkedBlockingQueue<CommandResponderEvent>(size);

        TransportIpAddress address = null;
        if("UDP".equals(protocol)) {
            address = new UdpAddress(port);
        } else {
            address = new TcpAddress(port);
        }

        receiver = new TrapReceiver(getLogger(), trapEvents, address, snmpVersion,
                snmpSecurityName, snmpAuthProtocol, snmpAuthPassword, snmpPrivProtocol, snmpPrivPassword);

        final Thread listenerThread = new Thread(receiver);
        listenerThread.setName("ListenSNMP [" + getIdentifier() + "]");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    @OnUnscheduled
    public void stop() {
        if(receiver != null) {
            receiver.notify();
        }
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        CommandResponderEvent cre;

        // poll the queue to retrieve traps
        while((cre = trapEvents.poll()) != null) {
            // create a new flow file
            FlowFile flowFile = session.create();

            // set attributes of the flow file
            flowFile = SNMPUtils.updateFlowFileAttributesWithPduProperties(cre.getPDU(), flowFile, session);

            // send event
            session.getProvenanceReporter().receive(flowFile, cre.getPeerAddress().toString());

            // transfer to success relationship
            session.transfer(flowFile, REL_SUCCESS);
        }
    }

}
