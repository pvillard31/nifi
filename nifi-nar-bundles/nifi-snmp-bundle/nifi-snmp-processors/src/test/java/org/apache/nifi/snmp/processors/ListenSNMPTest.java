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
package org.apache.nifi.snmp.processors;

import static org.junit.Assert.assertNotNull;

import org.apache.nifi.snmp.processors.trap.ListenSNMP;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Test;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.IpAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;

public class ListenSNMPTest {

    private static final String community = "public";
    private static final String trapOid = ".1.3.6.1.2.1.1.6";
    private static final String ipAddress = "127.0.0.1";
    private static final int port = 17891;

    // sendSnmpV1V2Trap(SnmpConstants.version1);
    // sendSnmpV1V2Trap(SnmpConstants.version2c);
    // sendSnmpV3Trap();

    @Test
    public void testListenTrapV1() throws Exception {
        ListenSNMP pubProc = new ListenSNMP();
        TestRunner runner = TestRunners.newTestRunner(pubProc);

        runner.setProperty(ListenSNMP.PORT, String.valueOf(port));

        runner.assertValid();

        runner.run(2, true, true, 1000);
        sendSnmpV1V2Trap(SnmpConstants.version1);

        final MockFlowFile successFF = runner.getFlowFilesForRelationship(ListenSNMP.REL_SUCCESS).get(0);
        assertNotNull(successFF);
        // assertEquals("test", successFF.getAttributes().get(SNMPUtils.SNMP_PROP_PREFIX + sysDescr.toString() + SNMPUtils.SNMP_PROP_DELIMITER + "4"));

    }

    /**
     * This method is used to both send traps in SNMPv1
     * and SNMP v2.
     * @param version version to use
     */
    private void sendSnmpV1V2Trap(int version) throws Exception {
        // create v1/v2 PDU
        PDU snmpPDU = createPdu(version);

        // Create Transport Mapping
        TransportMapping transport = new DefaultUdpTransportMapping();
        transport.listen();

        // Create Target
        CommunityTarget comtarget = new CommunityTarget();
        comtarget.setCommunity(new OctetString(community));
        comtarget.setVersion(version);
        comtarget.setAddress(new UdpAddress(ipAddress + "/" + port));
        comtarget.setRetries(2);
        comtarget.setTimeout(5000);

        // Send the PDU
        Snmp snmp = new Snmp(transport);
        snmp.send(snmpPDU, comtarget);
        snmp.close();
    }

    /** method to generate PDUs */
    private PDU createPdu(int snmpVersion) {
        PDU pdu = DefaultPDUFactory.createPDU(snmpVersion);

        if (snmpVersion == SnmpConstants.version1) {
            pdu.setType(PDU.V1TRAP);
        } else {
            pdu.setType(PDU.TRAP);
        }

        pdu.add(new VariableBinding(SnmpConstants.sysUpTime));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, new OID(trapOid)));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapAddress, new IpAddress(ipAddress)));
        pdu.add(new VariableBinding(new OID(trapOid), new OctetString("my test value")));

        return pdu;
    }

    /**
     * This method is used to send trap using SNMPv3
     */
    private void sendSnmpV3Trap() throws Exception {
        Address targetAddress = GenericAddress.parse("udp:" + ipAddress + "/" + port);
        TransportMapping transport = new DefaultUdpTransportMapping();
        Snmp snmp = new Snmp(transport);
        USM usm = new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0);
        SecurityModels.getInstance().addSecurityModel(usm);
        transport.listen();

        snmp.getUSM().addUser(new OctetString("MD5DES"), new UsmUser(new OctetString("MD5DES"), null, null, null, null));

        // Create Target
        UserTarget target = new UserTarget();
        target.setAddress(targetAddress);
        target.setRetries(1);
        target.setTimeout(11500);
        target.setVersion(SnmpConstants.version3);
        target.setSecurityLevel(SecurityLevel.NOAUTH_NOPRIV);
        target.setSecurityName(new OctetString("MD5DES"));

        // Create PDU for V3
        ScopedPDU pdu = new ScopedPDU();
        pdu.setType(ScopedPDU.NOTIFICATION);
        pdu.add(new VariableBinding(SnmpConstants.sysUpTime));
        pdu.add(new VariableBinding(SnmpConstants.snmpTrapOID, SnmpConstants.linkDown));
        pdu.add(new VariableBinding(new OID(trapOid), new OctetString("my test value")));

        // Send the PDU
        snmp.send(pdu, target);
        snmp.close();
    }

}
