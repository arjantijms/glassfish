/*
 * Copyright (c) 2022, 2025 Contributors to the Eclipse Foundation
 * Copyright (c) 2010, 2018 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.v3.admin.cluster;

import com.sun.enterprise.config.serverbeans.Node;
import com.sun.enterprise.config.serverbeans.SshAuth;
import com.sun.enterprise.config.serverbeans.SshConnector;
import com.sun.enterprise.universal.glassfish.TokenResolver;
import com.sun.enterprise.universal.process.ProcessManagerException;
import com.sun.enterprise.util.ExceptionUtil;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.util.cluster.RemoteType;
import com.sun.enterprise.util.net.NetUtils;

import java.io.File;
import java.lang.System.Logger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.api.admin.SSHCommandExecutionException;
import org.glassfish.cluster.ssh.connect.NodeRunner;
import org.glassfish.cluster.ssh.launcher.SSHException;
import org.glassfish.cluster.ssh.launcher.SSHLauncher;
import org.glassfish.cluster.ssh.sftp.SFTPPath;
import org.glassfish.common.util.admin.AuthTokenManager;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.RelativePathResolver;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * Utility methods for operating on Nodes
 *
 * @author Joe Di Pol
 * @author Byron Nevins
 */
public class NodeUtils {
    private static final Logger LOG = System.getLogger(NodeUtils.class.getName());

    public static final String NODE_DEFAULT_SSH_PORT = "22";
    public static final String NODE_DEFAULT_REMOTE_USER = "${user.name}";
    static final String NODE_DEFAULT_INSTALLDIR = "${com.sun.aas.productRoot}";
    // Command line option parameter names
    static final String PARAM_NODEHOST = "nodehost";
    static final String PARAM_INSTALLDIR = "installdir";
    static final String PARAM_NODEDIR = "nodedir";
    static final String PARAM_REMOTEPORT = "sshport";
    public static final String PARAM_REMOTEUSER = "sshuser";
    static final String PARAM_SSHKEYFILE = "sshkeyfile";
    static final String PARAM_REMOTEPASSWORD = "sshpassword";
    static final String PARAM_SSHKEYPASSPHRASE = "sshkeypassphrase";
    static final String PARAM_WINDOWSDOMAINNAME = "windowsdomain";
    static final String PARAM_TYPE = "type";
    static final String PARAM_INSTALL = "install";
    public static final String PARAM_WINDOWS_DOMAIN = "windowsdomain";
    static final Path LANDMARK_FILE = Path.of("glassfish", "admin-cli.jar");
    private static final String NL = System.lineSeparator();
    private TokenResolver resolver = null;
    private ServiceLocator locator = null;

    NodeUtils(ServiceLocator locator) {
        this.locator = locator;

        // Create a resolver that can replace system properties in strings
        Map<String, String> systemPropsMap =
                new HashMap<String, String>((Map) (System.getProperties()));
        resolver = new TokenResolver(systemPropsMap);
    }

    static boolean isSSHNode(Node node) {
        if (node == null) {
            return false;
        }
        return node.getType().equals("SSH");
    }

    /**
     * Get the version string from a glassfish installation on the node.
     * @param node
     * @return version string
     */
    String getGlassFishVersionOnNode(Node node, AdminCommandContext context) throws CommandValidationException {
        if (node == null) {
            return "";
        }

        List<String> command = new ArrayList<>();
        command.add("version");
        command.add("--local");
        command.add("--terse");
        NodeRunner nr = new NodeRunner(locator.getService(AuthTokenManager.class));

        StringBuilder output = new StringBuilder();
        try {
            int commandStatus = nr.runAdminCommandOnNode(node, output, command, context);
            if (commandStatus != 0) {
                return "unknown version: " + output.toString();
            }
        } catch (Exception e) {
            throw new CommandValidationException(Strings.get("failed.to.run", command.toString(), node.getNodeHost()),
                e);
        }
        return output.toString().trim();
    }

    void validate(Node node) throws
            CommandValidationException {

        // Put node values into parameter map and validate
        ParameterMap map = new ParameterMap();
        map.add("DEFAULT", node.getName());
        map.add(NodeUtils.PARAM_INSTALLDIR, node.getInstallDir());
        map.add(NodeUtils.PARAM_NODEHOST, node.getNodeHost());
        map.add(NodeUtils.PARAM_NODEDIR, node.getNodeDirAbsolute());

        SshConnector sshc = node.getSshConnector();
        if (sshc != null) {
            map.add(NodeUtils.PARAM_REMOTEPORT, sshc.getSshPort());
            SshAuth ssha = sshc.getSshAuth();
            map.add(NodeUtils.PARAM_REMOTEUSER, ssha.getUserName());
            map.add(NodeUtils.PARAM_SSHKEYFILE, ssha.getKeyfile());
            map.add(NodeUtils.PARAM_REMOTEPASSWORD, ssha.getPassword());
            map.add(NodeUtils.PARAM_SSHKEYPASSPHRASE, ssha.getKeyPassphrase());
            map.add(NodeUtils.PARAM_TYPE, node.getType());
        }

        validate(map);
        return;
    }

    /**
     * Validate all the parameters used to create a remote node
     * @param map   Map with all parameters used to create a remote node.
     *              The map values can contain system property tokens.
     * @throws CommandValidationException
     */
    void validate(ParameterMap map) throws
            CommandValidationException {

        validatePassword(map.getOne(PARAM_REMOTEPASSWORD));
        String nodehost = map.getOne(PARAM_NODEHOST);
        validateHostName(nodehost);
        validateRemote(map, nodehost);
    }

    private void validateRemote(ParameterMap map, String nodehost) throws
            CommandValidationException {

        // guaranteed to either get a valid type -- or a CommandValidationException
        RemoteType type = parseType(map);

        if (type == RemoteType.SSH) {
            validateSsh(map, nodehost);
        }

        // bn: shouldn't this be something more sophisticated than just the standard string?!?
        // i.e. check to see if the hostname is this machine?
        // todo
        if (nodehost.equals("localhost")) {
            return;
        }

        validateRemoteConnection(map);
    }

    /**
     * Validate all the parameters used to create an ssh node
     * @param map   Map with all parameters used to create an ssh node.
     *              The map values can contain system property tokens.
     * @throws CommandValidationException
     */
    private void validateSsh(ParameterMap map, String nodehost) throws
            CommandValidationException {

        String sshkeyfile = map.getOne(PARAM_SSHKEYFILE);
        if (StringUtils.ok(sshkeyfile)) {
            // User specified a key file. Make sure we get use it
            File kfile = new File(resolver.resolve(sshkeyfile));
            if (!kfile.isAbsolute()) {
                throw new CommandValidationException(
                        Strings.get("key.path.not.absolute",
                        kfile.getPath()));
            }
            if (!kfile.exists()) {
                throw new CommandValidationException(
                        Strings.get("key.path.not.found",
                        kfile.getPath()));
            }
            if (!kfile.canRead()) {
                throw new CommandValidationException(
                        Strings.get("key.path.not.readable",
                        kfile.getPath(), System.getProperty("user.name")));
            }
        }
    }

    void validateHostName(String hostName)
            throws CommandValidationException {

        if (!StringUtils.ok(hostName)) {
            throw new CommandValidationException(
                    Strings.get("nodehost.required"));
        }
        try {
            // Check if hostName is valid by looking up it's address
            InetAddress.getByName(hostName);
        }
        catch (UnknownHostException e) {
            throw new CommandValidationException(
                    Strings.get("unknown.host", hostName),
                    e);
        }
    }

    private void validatePassword(String p) throws CommandValidationException {

        String expandedPassword = null;

        // Make sure if a password alias is used we can expand it
        if (StringUtils.ok(p)) {
            try {
                expandedPassword = RelativePathResolver.getRealPasswordFromAlias(p);
            } catch (IllegalArgumentException e) {
                throw new CommandValidationException(Strings.get("no.such.password.alias", p));
            } catch (Exception e) {
                throw new CommandValidationException(Strings.get("no.such.password.alias", p), e);
            }

            if (expandedPassword == null) {
                throw new CommandValidationException(Strings.get("no.such.password.alias", p));
            }
        }
    }

    /**
     * Make sure we can make an SSH connection using an existing node.
     *
     * @param node  Node to connect to
     * @throws CommandValidationException
     */
    void pingRemoteConnection(Node node) throws CommandValidationException {
        RemoteType type = RemoteType.valueOf(node.getType());
        validateHostName(node.getNodeHost());

        switch (type) {
            case SSH:
                pingSSHConnection(node);
                break;
            default:
                throw new CommandValidationException("Internal Error: unknown type");
        }
    }

    /**
     * Make sure we can make an SSH connection using an existing node.
     *
     * @param node  Node to connect to
     * @throws CommandValidationException
     */
    private void pingSSHConnection(Node node) throws CommandValidationException {
        SSHLauncher sshL = new SSHLauncher(node);
        try {
            sshL.pingConnection();
        } catch (SSHException e) {
            String msg = Strings.get("ssh.bad.connect", node.getNodeHost(), "SSH", e.getMessage());
            LOG.log(WARNING, msg, e);
            throw new CommandValidationException(msg, e);
        }
    }

    private void validateRemoteConnection(ParameterMap map) throws
            CommandValidationException {
        // guaranteed to either get a valid type -- or a CommandValidationException
        RemoteType type = parseType(map);

        // just too difficult to refactor now...
        if (type == RemoteType.SSH) {
            validateSSHConnection(map);
        }
    }

    private void validateSSHConnection(ParameterMap map) throws
            CommandValidationException {

        String nodehost = map.getOne(PARAM_NODEHOST);
        String installdir = map.getOne(PARAM_INSTALLDIR);
        String sshport = map.getOne(PARAM_REMOTEPORT);
        String sshuser = map.getOne(PARAM_REMOTEUSER);
        String sshkeyfile = map.getOne(PARAM_SSHKEYFILE);
        String sshpassword = map.getOne(PARAM_REMOTEPASSWORD);
        String sshkeypassphrase = map.getOne(PARAM_SSHKEYPASSPHRASE);
        boolean installFlag = Boolean.parseBoolean(map.getOne(PARAM_INSTALL));

        // We use the resolver to expand any system properties
        if (!NetUtils.isPortStringValid(resolver.resolve(sshport))) {
            throw new CommandValidationException(Strings.get(
                    "ssh.invalid.port", sshport));
        }

        int port = Integer.parseInt(resolver.resolve(sshport));

        // sshpassword and sshkeypassphrase may be password alias.
        // Those aliases are handled by sshLauncher
        SFTPPath resolvedInstallDir = SFTPPath.of(resolver.resolve(installdir));
        String keyFile = resolver.resolve(sshkeyfile);
        String host = resolver.resolve(nodehost);
        SSHLauncher sshLauncher = new SSHLauncher(resolver.resolve(sshuser), resolver.resolve(nodehost), port,
            sshpassword, keyFile == null ? null : new File(keyFile), sshkeypassphrase);
        try {
            SFTPPath pathToCheck = resolvedInstallDir.resolve(LANDMARK_FILE);
            if (!installFlag && !sshLauncher.exists(pathToCheck)) {
                throw new CommandValidationException(
                    "Invalid install directory: could not find " + pathToCheck + " on " + host);
            }
        } catch (SSHException e) {
            String msg = Strings.get("ssh.bad.connect", nodehost, "SSH", e.getMessage());
            LOG.log(WARNING, msg, e);
            throw new CommandValidationException(msg, e);
        }
    }

    /**
     * Takes an action report and updates the message in the report with
     * the message from the root cause of the report.
     *
     * @param report
     */
    static void sanitizeReport(ActionReport report) {
        if (report != null && report.hasFailures()
                && report.getFailureCause() != null) {
            Throwable rootCause = ExceptionUtil.getRootCause(
                    report.getFailureCause());
            if (rootCause != null && StringUtils.ok(rootCause.getMessage())) {
                report.setMessage(rootCause.getMessage());
            }
        }
    }

    /**
     * Run on admin command on a node and handle setting the message in the
     * ActionReport on an error. Note that on success no message is set in
     * the action report
     *
     * @param node  The node to run the command on. Can be local or remote
     * @param command  asadmin command to run. The list must contain all
     *                  parameters to asadmin, but not "asadmin" itself.
     * @param context   The command context. The ActionReport in this
     *                  context will be updated on an error to contain an
     *                  appropriate error message.
     * @param firstErrorMessage The first message to use if an error is
     *                          encountered. Usually something like
     *                          "Could not start instance".
     * @param humanCommand  The command the user should run on the node if
     *                      we failed to run the passed command.
     * @param output        Output from the run command.
     * @param waitForReaderThreads True: wait for the command IO to complete.
     *                      False: don't wait for IO to complete, just for
     *                      process to end.
     *                      Currently this only applies to locally run commands
     *                      and should only be set to false by start-instance
     *                      (see bug 12777).
     */
    void runAdminCommandOnNode(Node node, List<String> command,
            AdminCommandContext context, String firstErrorMessage,
            String humanCommand, StringBuilder output) {

        ActionReport report = context.getActionReport();
        boolean failure = true;
        String msg1 = firstErrorMessage;
        String msg2 = "";
        String msg3 = "";
        String nodeHost = node.getNodeHost();
        String nodeName = node.getName();
        String installDir = node.getInstallDir();

        if (output == null) {
            output = new StringBuilder();
        }

        if (StringUtils.ok(humanCommand)) {
            msg3 = Strings.get("node.remote.tocomplete", nodeHost, installDir, humanCommand);
        }

        NodeRunner nr = new NodeRunner(locator.getService(AuthTokenManager.class));
        try {
            int status = nr.runAdminCommandOnNode(node, output, command, context);
            if (status == 0) {
                failure = false;
                LOG.log(INFO, "Output from the command execution on the node {0}:\n{1}", node.getName(), output);
            } else {
                // Command ran, but didn't succeed. Log full information
                msg2 = Strings.get("node.command.failed", nodeName, nodeHost, output.toString().trim(),
                    nr.getLastCommandRun());
                LOG.log(WARNING, StringUtils.cat(": ", msg1, msg2, msg3));
                // Don't expose command name to user in case it is a hidden command
                msg2 = Strings.get("node.command.failed.short", nodeName, nodeHost, output.toString().trim());
            }
        } catch (SSHCommandExecutionException e) {
            msg2 = Strings.get("node.ssh.bad.connect", nodeName, nodeHost, e.getMessage());
            // Log some extra info
            String msg = Strings.get("node.command.failed.ssh.details", nodeName, nodeHost, e.getCommandRun(),
                e.getMessage(), e.getSSHSettings());
            LOG.log(WARNING, StringUtils.cat(": ", msg1, msg, msg3), e);
        } catch (ProcessManagerException e) {
            msg2 = Strings.get("node.command.failed.local.details", e.getMessage(), nr.getLastCommandRun());
            LOG.log(WARNING, StringUtils.cat(": ", msg1, msg2, msg3), e);
            // User message doesn't have command that was run
            msg2 = Strings.get("node.command.failed.local.exception", e.getMessage());
        } catch (UnsupportedOperationException e) {
            msg2 = Strings.get("node.not.ssh", nodeName, nodeHost);
            LOG.log(WARNING, StringUtils.cat(": ", msg1, msg2, msg3), e);
        } catch (IllegalArgumentException e) {
            msg2 = e.getMessage();
            LOG.log(WARNING, StringUtils.cat(": ", msg1, msg2, msg3), e);
        }

        if (failure) {
            report.setMessage(StringUtils.cat(NL + NL, msg1, msg2, msg3));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
        } else {
            report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
        }
    }

    private RemoteType parseType(ParameterMap map) throws CommandValidationException {

        try {
            return RemoteType.valueOf(map.getOne(PARAM_TYPE));
        }
        catch (Exception e) {
            throw new CommandValidationException(e);
        }
    }
}
