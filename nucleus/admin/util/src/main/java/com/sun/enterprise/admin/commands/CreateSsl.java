/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.admin.commands;

import com.sun.enterprise.config.serverbeans.AdminService;
import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.JmxConnector;
import com.sun.enterprise.security.ssl.GlassfishSSLImpl;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.util.SystemPropertyConstants;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.beans.PropertyVetoException;

import org.glassfish.api.ActionReport;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RestEndpoint;
import org.glassfish.api.admin.RestEndpoints;
import org.glassfish.api.admin.RestParam;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.config.support.TargetType;
import org.glassfish.grizzly.config.dom.NetworkConfig;
import org.glassfish.grizzly.config.dom.NetworkListener;
import org.glassfish.grizzly.config.dom.Protocol;
import org.glassfish.grizzly.config.dom.Protocols;
import org.glassfish.grizzly.config.dom.Ssl;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.Target;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.SingleConfigCode;
import org.jvnet.hk2.config.TransactionFailure;

/**
 * Create Ssl Command
 *
 * Usage: create-ssl --type [http-listener|iiop-listener|iiop-service|protocol] --certname cert_name
 * [--ssl2enabled=false] [--ssl2ciphers ssl2ciphers] [--ssl3enabled=true] [--ssl3tlsciphers ssl3tlsciphers]
 * [--tlsenabled=true] [--tlsrollbackenabled=true] [--clientauthenabled=false] [--target target(Default server)]
 * [listener_id|protocol_id]
 *
 * domain.xml element example &lt;ssl cert-nickname="s1as" client-auth-enabled="false" ssl2-enabled="false"
 * ssl3-enabled="true" tls-enabled="true" tls-rollback-enabled="true"/&gt;
 *
 * @author Nandini Ektare
 */
@Service(name = "create-ssl")
@PerLookup
@I18n("create.ssl")
@ExecuteOn({ RuntimeType.DAS, RuntimeType.INSTANCE })
@TargetType({ CommandTarget.DAS, CommandTarget.STANDALONE_INSTANCE, CommandTarget.CLUSTER, CommandTarget.CONFIG })
@RestEndpoints({
        @RestEndpoint(configBean = JmxConnector.class, opType = RestEndpoint.OpType.POST, path = "create-ssl", description = "create-ssl", params = {
                @RestParam(name = "id", value = "$parent"), @RestParam(name = "type", value = "jmx-connector") }),
        @RestEndpoint(configBean = NetworkListener.class, opType = RestEndpoint.OpType.POST, path = "create-ssl", description = "create-ssl", params = {
                @RestParam(name = "id", value = "$parent"), @RestParam(name = "type", value = "http-listener") }),
        @RestEndpoint(configBean = Protocol.class, opType = RestEndpoint.OpType.POST, path = "create-ssl", description = "create-ssl", params = {
                @RestParam(name = "id", value = "$parent"), @RestParam(name = "type", value = "protocol") }) })
public class CreateSsl implements AdminCommand {
    final private static LocalStringManagerImpl localStrings = new LocalStringManagerImpl(CreateSsl.class);

    @Param(name = "certname", alias = "certNickname")
    String certName;

    @Param(name = "type", acceptableValues = "network-listener, http-listener, iiop-listener, iiop-service, jmx-connector, protocol")
    String type;

    @Param(name = "ssl2Enabled", optional = true, defaultValue = Ssl.SSL2_ENABLED + "")
    Boolean ssl2Enabled;

    @Param(name = "ssl2Ciphers", optional = true)
    String ssl2ciphers;

    @Param(name = "ssl3Enabled", optional = true, defaultValue = Ssl.SSL3_ENABLED + "")
    Boolean ssl3Enabled;

    @Param(name = "ssl3TlsCiphers", optional = true)
    String ssl3tlsciphers;

    @Param(name = "tlsEnabled", optional = true, defaultValue = Ssl.TLS_ENABLED + "")
    Boolean tlsenabled;

    @Param(name = "tlsRollbackEnabled", optional = true, defaultValue = Ssl.TLS_ROLLBACK_ENABLED + "")
    Boolean tlsrollbackenabled;

    @Param(name = "clientAuthEnabled", optional = true, defaultValue = Ssl.CLIENT_AUTH_ENABLED + "")
    Boolean clientauthenabled;

    @Param(name = "target", optional = true, defaultValue = SystemPropertyConstants.DAS_SERVER_NAME)
    String target;

    @Param(name = "listener_id", primary = true, optional = true)
    public String listenerId;

    @Inject
    @Named(ServerEnvironment.DEFAULT_INSTANCE_NAME)
    public Config config;

    @Inject
    Domain domain;

    @Inject
    ServiceLocator habitat;

    private static final String GF_SSL_IMPL_NAME = GlassfishSSLImpl.class.getName();

    /**
     * Executes the command with the command parameters passed as Properties where the keys are the paramter names and the
     * values the parameter values
     *
     * @param context information
     */
    @Override
    public void execute(AdminCommandContext context) {
        final ActionReport report = context.getActionReport();
        Target targetUtil = habitat.getService(Target.class);
        Config newConfig = targetUtil.getConfig(target);
        if (newConfig != null) {
            config = newConfig;
        }
        if (!"iiop-service".equals(type)) {
            if (listenerId == null) {
                report.setMessage(localStrings.getLocalString("create.ssl.listenerid.missing", "Listener id needs to be specified"));
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                return;
            }
        }
        SslConfigHandler configHandler = habitat.getService(SslConfigHandler.class, type);
        if (configHandler != null) {
            configHandler.create(this, report);
        } else if ("jmx-connector".equals(type)) {
            addSslToJMXConnector(config, report);
        }
    }

    public void reportError(ActionReport report, TransactionFailure e) {
        report.setMessage(localStrings.getLocalString("create.ssl.fail", "Creation of Ssl in {0} failed", listenerId));
        report.setActionExitCode(ActionReport.ExitCode.FAILURE);
        report.setFailureCause(e);
    }

    public void reportSuccess(ActionReport report) {
        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
    }

    public void populateSslElement(Ssl newSsl) {
        newSsl.setCertNickname(certName);
        newSsl.setClientAuthEnabled(clientauthenabled.toString());
        newSsl.setSsl2Ciphers(ssl2ciphers);
        newSsl.setSsl2Enabled(ssl2Enabled.toString());
        newSsl.setSsl3Enabled(ssl3Enabled.toString());
        newSsl.setSsl3TlsCiphers(ssl3tlsciphers);
        newSsl.setClassname(GF_SSL_IMPL_NAME);
        newSsl.setTlsEnabled(tlsenabled.toString());
        newSsl.setTlsRollbackEnabled(tlsrollbackenabled.toString());
    }

    public Protocol findOrCreateProtocol(final String name, final boolean create) throws TransactionFailure {
        NetworkConfig networkConfig = config.getNetworkConfig();
        Protocol protocol = networkConfig.findProtocol(name);
        if (protocol == null && create) {
            protocol = (Protocol) ConfigSupport.apply(new SingleConfigCode<Protocols>() {
                @Override
                public Object run(Protocols param) throws TransactionFailure {
                    Protocol newProtocol = param.createChild(Protocol.class);
                    newProtocol.setName(name);
                    newProtocol.setSecurityEnabled("true");
                    param.getProtocol().add(newProtocol);
                    return newProtocol;
                }
            }, habitat.<Protocols>getService(Protocols.class));
        }
        return protocol;
    }

    public Protocol findOrCreateProtocol(final String name) throws TransactionFailure {
        return findOrCreateProtocol(name, true);
    }

    private void addSslToJMXConnector(Config config, ActionReport report) {
        AdminService adminService = config.getAdminService();
        // ensure we have the specified listener
        JmxConnector jmxConnector = null;
        for (JmxConnector jmxConn : adminService.getJmxConnector()) {
            if (jmxConn.getName().equals(listenerId)) {
                jmxConnector = jmxConn;
            }
        }
        if (jmxConnector == null) {
            report.setMessage(localStrings.getLocalString("create.ssl.jmx.notfound",
                    "JMX Connector named {0} to which this ssl element is " + "being added does not exist.", listenerId));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }
        if (jmxConnector.getSsl() != null) {
            report.setMessage(localStrings.getLocalString("create.ssl.jmx.alreadyExists",
                    "IIOP Listener named {0} to which this ssl element is " + "being added already has an ssl element.", listenerId));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }
        try {
            ConfigSupport.apply(new SingleConfigCode<JmxConnector>() {
                @Override
                public Object run(JmxConnector param) throws PropertyVetoException, TransactionFailure {
                    Ssl newSsl = param.createChild(Ssl.class);
                    populateSslElement(newSsl);
                    param.setSsl(newSsl);
                    return newSsl;
                }
            }, jmxConnector);

        } catch (TransactionFailure e) {
            reportError(report, e);
        }
        reportSuccess(report);
    }
}
