package org.apache.nifi.snmp.processors.trap;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;

import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.snmp.processors.SNMPUtils;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcher;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.AuthenticationProtocol;
import org.snmp4j.security.Priv3DES;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.PrivacyProtocol;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TcpAddress;
import org.snmp4j.smi.TransportIpAddress;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.transport.AbstractTransportMapping;
import org.snmp4j.transport.DefaultTcpTransportMapping;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.MultiThreadedMessageDispatcher;
import org.snmp4j.util.ThreadPool;

public class TrapReceiver implements CommandResponder, Runnable {

    private BlockingQueue<CommandResponderEvent> pduEvents;
    private ComponentLog logger;

    private TransportIpAddress address;
    private String snmpVersion;
    private String snmpSecurityName;
    private String snmpAuthProtocol;
    private String snmpAuthPassword;
    private String snmpPrivProtocol;
    private String snmpPrivPassword;

    public TrapReceiver(ComponentLog logger, BlockingQueue<CommandResponderEvent> pduEvents, TransportIpAddress address, String snmpVersion,
            String snmpSecurityName, String snmpAuthProtocol, String snmpAuthPassword, String snmpPrivProtocol, String snmpPrivPassword) {
        this.address = address;
        this.logger = logger;
        this.pduEvents = pduEvents;
        this.snmpVersion = snmpVersion;
        this.snmpSecurityName = snmpSecurityName;
        this.snmpAuthProtocol = snmpAuthProtocol;
        this.snmpAuthPassword = snmpAuthPassword;
        this.snmpPrivProtocol = snmpPrivProtocol;
        this.snmpPrivPassword = snmpPrivPassword;
    }

    @Override
    public void run() {
        try {
            listen();
        } catch (IOException e) {
            logger.error("Exception caught while running listener.", e);
        }
    }

    /**
     * Method to listen for traps
     */
    private void listen() throws IOException {
        AbstractTransportMapping transport;
        if (address instanceof TcpAddress) {
            transport = new DefaultTcpTransportMapping((TcpAddress) address);
        } else {
            transport = new DefaultUdpTransportMapping((UdpAddress) address);
        }

        MessageDispatcher mtDispatcher = new MultiThreadedMessageDispatcher(ThreadPool.create("pool", 10), new MessageDispatcherImpl());

        // add message processing models
        switch (snmpVersion) {
            case "SNMPv2c":
                mtDispatcher.addMessageProcessingModel(new MPv2c());
                break;

            case "SNMPv3":
                // initialize security protocols
                SecurityProtocols sp = SecurityProtocols.getInstance();
                sp.addDefaultProtocols();

                // add all security protocols
                if(snmpPrivProtocol != null) {
                    SecurityProtocols.getInstance().addPrivacyProtocol(getPriv(snmpPrivProtocol));
                }

                if(snmpAuthProtocol != null) {
                    SecurityProtocols.getInstance().addAuthenticationProtocol(getAuth(snmpAuthProtocol));
                }

                // initialize USM
                USM usm = new USM(sp, new OctetString(MPv3.createLocalEngineID()), 0);
                usm.setEngineDiscoveryEnabled(true);

                // add security model
                SecurityModels.getInstance().addSecurityModel(usm);

                OctetString aPwd = snmpAuthPassword != null ? new OctetString(snmpAuthPassword) : null;
                OctetString pPwd = snmpPrivPassword != null ? new OctetString(snmpPrivPassword) : null;

                // add user information
                usm.addUser(new OctetString(snmpSecurityName),
                        new UsmUser(new OctetString(snmpSecurityName), SNMPUtils.getAuth(snmpAuthProtocol), aPwd,
                                SNMPUtils.getPriv(snmpPrivProtocol), pPwd));

                mtDispatcher.addMessageProcessingModel(new MPv3(usm));
                break;

            case "SNMPv1":
            default:
                mtDispatcher.addMessageProcessingModel(new MPv1());
                break;
        }

        Snmp snmp = new Snmp(mtDispatcher, transport);
        snmp.addCommandResponder(this);

        transport.listen();
    }

    /**
     * This method is called each time a trap is received
     */
    @Override
    public synchronized void processPdu(CommandResponderEvent cmdRespEvent) {
        if(!this.pduEvents.offer(cmdRespEvent)) {
            logger.warn("No space is currently available in the queue, trap has been dropped");
        }
    }

    /**
     * Method to return the private protocol given the property
     * @param privProtocol property
     * @return protocol
     */
    public static PrivacyProtocol getPriv(String privProtocol) {
        switch (privProtocol) {
        case "DES":
            return new PrivDES();
        case "3DES":
            return new Priv3DES();
        case "AES128":
            return new PrivAES128();
        case "AES192":
            return new PrivAES192();
        case "AES256":
            return new PrivAES256();
        default:
            return null;
        }
    }

    /**
     * Method to return the authentication protocol given the property
     * @param authProtocol property
     * @return protocol
     */
    public static AuthenticationProtocol getAuth(String authProtocol) {
        switch (authProtocol) {
        case "SHA":
            return new AuthSHA();
        case "MD5":
            return new AuthMD5();
        default:
            return null;
        }
    }
}