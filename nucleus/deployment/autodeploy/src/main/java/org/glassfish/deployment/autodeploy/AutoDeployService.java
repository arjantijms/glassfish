/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation
 * Copyright (c) 2008, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.deployment.autodeploy;

import com.sun.enterprise.config.serverbeans.DasConfig;

import jakarta.inject.Inject;

import java.beans.PropertyChangeEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.hk2.api.PreDestroy;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.runlevel.RunLevel;
import org.glassfish.internal.api.PostStartupRunLevel;
import org.glassfish.logging.annotation.LogMessageInfo;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.ConfigListener;
import org.jvnet.hk2.config.UnprocessedChangeEvent;
import org.jvnet.hk2.config.UnprocessedChangeEvents;

/**
 * A service wrapper around the autodeployer.
 * <p>
 * The module system will start this service during GlassFish start-up.  In turn
 * it will start the actual autodeployer to run periodically.
 * <p>
 * Note that some values used by the service are known when the class is first
 * started.  Others can be configured dynamically.  The first type are initialized
 * during postConstruct.  The others will trigger the delivery of config change
 * events to which we respond and, as needed, stop or reschedule the timer task.
 *
 * @author tjquinn
 */
@Service
@RunLevel(PostStartupRunLevel.VAL)
public class AutoDeployService implements PostConstruct, PreDestroy, ConfigListener {

    @Inject
    DasConfig activeDasConfig;

    @Inject
    ServiceLocator habitat;

    @Inject
    ServerEnvironment env;

    private AutoDeployer autoDeployer = null;

    private Timer autoDeployerTimer;

    private TimerTask autoDeployerTimerTask;

    private String target;

    private static final String DAS_TARGET = "server";

    private static final List<String> configPropertyNames = Arrays.asList(
            "autodeploy-enabled", "autodeploy-polling-interval-in-seconds",
            "autodeploy-verifier-enabled", "autodeploy-jsp-precompilation-enabled"
            );


    public static Logger deplLogger;

    @LogMessageInfo(message = "Error parsing configured polling-interval-in-seconds {0} as an integer; {1} {2}", level="WARNING")
    private static final String PARSING_POLLING_INTERVAL_ERROR = "NCLS-DEPLOYMENT-02028";

    @LogMessageInfo(message = "Exception caught:  {0}", cause="An exception was caught when the application was autodeployed.", action="See the exception to determine how to fix the error", level="SEVERE")
    private static final String EXCEPTION_CAUGHT = "NCLS-DEPLOYMENT-02029";

    @LogMessageInfo(message = "Error processing configuration change of {0} from {1} to {2}; {3} {4}", level="WARNING")
    private static final String CONFIGURATION_CHANGE_ERROR = "NCLS-DEPLOYMENT-02030";

    private static final String DEFAULT_POLLING_INTERVAL_IN_SECONDS = "2";
    private static final String DEFAULT_AUTO_DEPLOY_ENABLED = "true";

    public void postConstruct() {
        deplLogger = org.glassfish.deployment.autodeploy.AutoDeployer.deplLogger;
        /*
         * Do not start the autodeployer if this is not the DAS.
         */
        if ( ! env.isDas()) {
            return;
        }

        /*
         * Always create the autoDeployer, even if autodeployment is not enabled.
         * Just don't start it if it's not enabled.
         */
        String directory = activeDasConfig.getAutodeployDir();
        target = getTarget();
        try {
            autoDeployer = new AutoDeployer(
                    target,
                    directory,
                    getDefaultVirtualServer(),
                    Boolean.parseBoolean(activeDasConfig.getAutodeployJspPrecompilationEnabled()),
                    Boolean.parseBoolean(activeDasConfig.getAutodeployVerifierEnabled()),
                    true /* renameOnSuccess */,
                    true /* force deployment */,
                    true /* enabled when autodeployed */,
                    habitat
                    );
            boolean isEnabled = isAutoDeployEnabled();
            int pollingIntervalInSeconds = Integer.parseInt(DEFAULT_POLLING_INTERVAL_IN_SECONDS);
            try {
                pollingIntervalInSeconds = getPollingIntervalInSeconds();
            } catch (NumberFormatException ex) {
                deplLogger.log(Level.WARNING,
                               PARSING_POLLING_INTERVAL_ERROR,
                               new Object[] {
                                 activeDasConfig.getAutodeployPollingIntervalInSeconds(),
                                 ex.getClass().getName(),
                                 ex.getLocalizedMessage()
                               });
            }
            if (isEnabled) {
                startAutoDeployer(pollingIntervalInSeconds);
            }
        } catch (AutoDeploymentException e) {
            LogRecord lr = new LogRecord(Level.SEVERE, EXCEPTION_CAUGHT);
            Object args[] = { e.getMessage() };
            lr.setParameters(args);
            lr.setThrown(e);
            deplLogger.log(lr);
        }

    }

    public void preDestroy() {
        stopAutoDeployer();
    }

    static String getValue(String value, String defaultValue) {
        return (value == null || value.equals("")) ? defaultValue : value;
    }

    private void logConfig(String title,
            boolean isEnabled,
            int pollingIntervalInSeconds,
            String directory) {
        if (deplLogger.isLoggable(Level.FINE)) {
            deplLogger.fine("[AutoDeploy] " + title + ", enabled=" + isEnabled +
                    ", polling interval(seconds)=" + pollingIntervalInSeconds +
                    ", directory=" + directory);
        }
    }

    private String getTarget() {
        // XXX should this also be configurable ?
        return DAS_TARGET;
    }

    private String getDefaultVirtualServer() {
        // XXX write this? Or should DeployCommand take care of it on behalf of all code that uses DeployCommand?
        return null;
    }

    private boolean isAutoDeployEnabled() {
        return Boolean.parseBoolean(
                getValue(activeDasConfig.getAutodeployEnabled(),
                DEFAULT_AUTO_DEPLOY_ENABLED));
    }

    private int getPollingIntervalInSeconds() throws NumberFormatException {
        return Integer.parseInt(
                getValue(activeDasConfig.getAutodeployPollingIntervalInSeconds(),
                DEFAULT_POLLING_INTERVAL_IN_SECONDS));
    }

    private void startAutoDeployer(int pollingIntervalInSeconds) {
        long pollingInterval = pollingIntervalInSeconds * 1000L;
        autoDeployer.init();
        autoDeployerTimer = new Timer("AutoDeployer", true);
        autoDeployerTimer.schedule(
                autoDeployerTimerTask = new TimerTask() {
                    @Override
                    public void run() {
                        try {
                            autoDeployer.run();
                        } catch (Exception ex) {
                            // shoule have been already logged
                            AutoDeployer.deplLogger.log(Level.FINE, ex.getMessage(), ex);
                        }
                    }
                },
                0,
                pollingInterval);
        logConfig(
                "Started",
                isAutoDeployEnabled(),
                pollingIntervalInSeconds,
                activeDasConfig.getAutodeployDir());
    }

    private void stopAutoDeployer() {
        /*
         * Tell the running autodeployer to stop, then cancel the timer task
         * and the timer.
         */
        deplLogger.fine("[AutoDeploy] Stopping");
        if (autoDeployer!=null)
            autoDeployer.cancel(true);
        if (autoDeployerTimerTask!=null)
            autoDeployerTimerTask.cancel();
        if (autoDeployerTimer != null) {
            autoDeployerTimer.cancel();
        }
    }

    /**
     * Reschedules the autodeployer because a configuration change has altered
     * the frequency.
     */
    private void rescheduleAutoDeployer(int pollingIntervalInSeconds) {
        deplLogger.fine("[AutoDeploy] Restarting...");
        stopAutoDeployer();
        try {
            autoDeployer.waitUntilIdle();
        } catch (InterruptedException e) {
            // XXX OK to glide through here?
        }
        startAutoDeployer(pollingIntervalInSeconds);
    }

    public UnprocessedChangeEvents changed(PropertyChangeEvent[] events) {
        /*
         * Deal with any changes to the DasConfig that might affect whether
         * the autodeployer should be stopped or started or rescheduled with a
         * different frequency.  Those change are handled here, by this
         * class.
         */

        if (autoDeployer == null) {
            return null;
        }

       /* Record any events we tried to process but could not. */
        List<UnprocessedChangeEvent> unprocessedEvents = new ArrayList<UnprocessedChangeEvent>();

        Boolean newEnabled = null;
        Integer newPollingIntervalInSeconds = null;

        for (PropertyChangeEvent event : events) {
            if (event.getSource() instanceof DasConfig) {
                String propName = event.getPropertyName();
                if (configPropertyNames.contains(propName) && event.getOldValue().equals(event.getNewValue())) {
                    deplLogger.fine("[AutoDeploy] Ignoring reconfig of " + propName +
                            " from " + event.getOldValue() + " to " + event.getNewValue());
                    continue;
                }
                /*
                 * Substitute any placeholders in the new and old values.
                 */
                final String oldValue = replaceTokens((String) event.getOldValue(), System.getProperties());
                final String newValue = replaceTokens((String) event.getNewValue(), System.getProperties());
                if (propName.equals("autodeploy-enabled")) {
                    /*
                     * Either start the currently stopped autodeployer or stop the
                     * currently running one.
                     */
                    newEnabled = Boolean.valueOf(newValue);
                    deplLogger.fine("[AutoDeploy] Reconfig - enabled changed to " + newEnabled);
                } else if (propName.equals("autodeploy-polling-interval-in-seconds")) {
                    try {
                        newPollingIntervalInSeconds = new Integer(newValue);
                        deplLogger.fine("[AutoDeploy] Reconfig - polling interval (seconds) changed from "
                                + oldValue + " to " +
                                newPollingIntervalInSeconds);
                    } catch (NumberFormatException ex) {
                        deplLogger.log(Level.WARNING,
                                       CONFIGURATION_CHANGE_ERROR,
                                       new Object[] {
                                         propName,
                                         oldValue,
                                         newValue,
                                         ex.getClass().getName(),
                                         ex.getLocalizedMessage()} );
                    }
                } else if (propName.equals("autodeploy-dir")) {
                    String newDir = newValue;
                    try {
                        autoDeployer.setDirectory(newDir);
                        deplLogger.fine("[AutoDeploy] Reconfig - directory changed from " +
                                oldValue + " to " +
                                newDir);
                    } catch (AutoDeploymentException ex) {
                        deplLogger.log(Level.WARNING,
                                       CONFIGURATION_CHANGE_ERROR,
                                       new Object[] {
                                         propName,
                                         oldValue,
                                         newValue,
                                         ex.getClass().getName(),
                                         ex.getLocalizedMessage()} );
                    }
                } else if (propName.equals("autodeploy-verifier-enabled")) {
                    boolean newVerifierEnabled = Boolean.parseBoolean(newValue);
                    autoDeployer.setVerifierEnabled(newVerifierEnabled);
                    deplLogger.fine("[AutoDeploy] Reconfig - verifierEnabled changed from " +
                            Boolean.parseBoolean(oldValue) +
                            " to " + newVerifierEnabled);
                } else if (propName.equals("autodeploy-jsp-precompilation-enabled")) {
                    boolean newJspPrecompiled = Boolean.parseBoolean(newValue);
                    autoDeployer.setJspPrecompilationEnabled(newJspPrecompiled);
                    deplLogger.fine("[AutoDeploy] Reconfig - jspPrecompilationEnabled changed from " +
                            Boolean.parseBoolean(oldValue) +
                            " to " + newJspPrecompiled);
                }
            }
        }
        if (newEnabled != null) {
            if (newEnabled) {
                startAutoDeployer(newPollingIntervalInSeconds == null ?
                    getPollingIntervalInSeconds() : newPollingIntervalInSeconds);
            } else {
                stopAutoDeployer();
            }
        } else {
            if ((newPollingIntervalInSeconds != null) && isAutoDeployEnabled()) {
                /*
                 * There is no change in whether the autodeployer should be running, only
                 * in how often it should run.  If it is not running now don't
                 * start it.  If it is running now, restart it to use the new
                 * polling interval.
                 */
                rescheduleAutoDeployer(newPollingIntervalInSeconds.intValue());
            }
        }
        return (unprocessedEvents.size() > 0) ? new UnprocessedChangeEvents(unprocessedEvents) : null;
    }


    /**
     * pattern is: "${" followed by all chars excluding "}" followed by "}",
     * capturing into group 1 all chars between the "${" and the "}"
     */
    private static final Pattern TOKEN_SUBSTITUTION = Pattern.compile("\\$\\{([^\\}]*)\\}");
    private static final String SLASH_REPLACEMENT = Matcher.quoteReplacement("\\\\");
    private static final String DOLLAR_REPLACEMENT = Matcher.quoteReplacement("\\$");


    /**
     * Searches for placeholders of the form ${token-name} in the input String, retrieves
     * the property with name token-name from the Properties object, and (if
     * found) replaces the token in the input string with the property value.
     * @param s String possibly containing tokens
     * @param values Properties object containing name/value pairs for substitution
     * @return the original string with tokens substituted using their values
     * from the Properties object
     */
    private static String replaceTokens(final String s, final Properties values) {
        final Matcher m = TOKEN_SUBSTITUTION.matcher(s);

        final StringBuffer sb = new StringBuffer();
        /*
         * For each match, retrieve group 1 - the token - and use its value from
         * the Properties object (if found there) to replace the token with the
         * value.
         */
        while (m.find()) {
            final String propertyName = m.group(1);
            final String propertyValue = values.getProperty(propertyName);

            /*
             * Substitute only if the properties object contained a setting
             * for the placeholder we found.
             */
            if (propertyValue != null) {
                /*
                 * The next line quotes any $ signs and backslashes in the replacement string
                 * so they are not interpreted as meta-characters by the regular expression
                 * processor's appendReplacement.
                 */
                final String adjustedPropertyValue =
                        propertyValue.replaceAll("\\\\",SLASH_REPLACEMENT).
                            replaceAll("\\$", DOLLAR_REPLACEMENT);
                final String x = s.substring(m.start(),m.end());
                try {
                    m.appendReplacement(sb, adjustedPropertyValue);
                } catch (IllegalArgumentException iae) {
                    System.err.println("**** appendReplacement failed: segment is " + x + "; original replacement was " + propertyValue + " and adj. replacement is " + adjustedPropertyValue + "; exc follows");
                    throw iae;
                }
            }
        }
        /*
         * There are no more matches, so append whatever remains of the matcher's input
         * string to the output.
         */
        m.appendTail(sb);

        return sb.toString();
    }

}
