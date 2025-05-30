/*
 * Copyright (c) 2021, 2024 Contributors to the Eclipse Foundation.
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

package com.sun.enterprise.v3.server;

import com.sun.enterprise.config.serverbeans.AppTenant;
import com.sun.enterprise.config.serverbeans.AppTenants;
import com.sun.enterprise.config.serverbeans.Application;
import com.sun.enterprise.config.serverbeans.ApplicationConfig;
import com.sun.enterprise.config.serverbeans.ApplicationRef;
import com.sun.enterprise.config.serverbeans.Applications;
import com.sun.enterprise.config.serverbeans.Cluster;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Engine;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.config.serverbeans.ServerTags;
import com.sun.enterprise.deploy.shared.ArchiveFactory;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.util.io.FileUtils;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.beans.PropertyVetoException;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.glassfish.api.ActionReport;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.api.admin.config.ApplicationName;
import org.glassfish.api.container.Container;
import org.glassfish.api.container.Sniffer;
import org.glassfish.api.deployment.ApplicationMetaDataProvider;
import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.api.deployment.Deployer;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.api.deployment.MetaData;
import org.glassfish.api.deployment.OpsParams;
import org.glassfish.api.deployment.UndeployCommandParameters;
import org.glassfish.api.deployment.archive.ArchiveDetector;
import org.glassfish.api.deployment.archive.ArchiveHandler;
import org.glassfish.api.deployment.archive.CompositeHandler;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.api.deployment.archive.WritableArchive;
import org.glassfish.api.event.EventListener.Event;
import org.glassfish.api.event.Events;
import org.glassfish.api.virtualization.VirtualizationEnv;
import org.glassfish.common.util.admin.ParameterMapExtractor;
import org.glassfish.deployment.common.ApplicationConfigInfo;
import org.glassfish.deployment.common.ClientJarWriter;
import org.glassfish.deployment.common.DeploymentContextImpl;
import org.glassfish.deployment.common.DeploymentProperties;
import org.glassfish.deployment.common.DeploymentUtils;
import org.glassfish.deployment.monitor.DeploymentLifecycleProbeProvider;
import org.glassfish.deployment.versioning.VersioningSyntaxException;
import org.glassfish.deployment.versioning.VersioningUtils;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.hk2.api.PreDestroy;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.classmodel.reflect.Parser;
import org.glassfish.hk2.classmodel.reflect.ParsingContext;
import org.glassfish.hk2.classmodel.reflect.Types;
import org.glassfish.hk2.classmodel.reflect.util.CommonModelRegistry;
import org.glassfish.hk2.classmodel.reflect.util.ResourceLocator;
import org.glassfish.internal.api.ClassLoaderHierarchy;
import org.glassfish.internal.data.ApplicationInfo;
import org.glassfish.internal.data.ApplicationRegistry;
import org.glassfish.internal.data.ContainerRegistry;
import org.glassfish.internal.data.EngineInfo;
import org.glassfish.internal.data.EngineRef;
import org.glassfish.internal.data.ModuleInfo;
import org.glassfish.internal.data.ProgressTracker;
import org.glassfish.internal.deployment.ApplicationLifecycleInterceptor;
import org.glassfish.internal.deployment.Deployment;
import org.glassfish.internal.deployment.DeploymentTracing;
import org.glassfish.internal.deployment.ExtendedDeploymentContext;
import org.glassfish.kernel.KernelLoggerInfo;
import org.glassfish.server.ServerEnvironmentImpl;
import org.jvnet.hk2.annotations.Optional;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.ConfigBean;
import org.jvnet.hk2.config.ConfigBeanProxy;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.Dom;
import org.jvnet.hk2.config.RetryableException;
import org.jvnet.hk2.config.SingleConfigCode;
import org.jvnet.hk2.config.Transaction;
import org.jvnet.hk2.config.TransactionFailure;
import org.jvnet.hk2.config.types.Property;

import static com.sun.enterprise.config.serverbeans.ServerTags.IS_COMPOSITE;
import static com.sun.enterprise.util.Utility.isEmpty;
import static java.util.Collections.emptyList;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static org.glassfish.api.admin.ServerEnvironment.DEFAULT_INSTANCE_NAME;
import static org.glassfish.deployment.common.DeploymentProperties.ALT_DD;
import static org.glassfish.deployment.common.DeploymentProperties.RUNTIME_ALT_DD;
import static org.glassfish.deployment.common.DeploymentProperties.SKIP_SCAN_EXTERNAL_LIB;
import static org.glassfish.deployment.common.DeploymentUtils.getVirtualServers;
import static org.glassfish.kernel.KernelLoggerInfo.inconsistentLifecycleState;

/**
 * Application Loader is providing useful methods to load applications
 *
 * @author Jerome Dochez, Sanjeeb Sahoo
 */
@Service
@Singleton
public class ApplicationLifecycle implements Deployment, PostConstruct {

    private static final String[] UPLOADED_GENERATED_DIRS = new String[]{"policy", "xml", "ejb", "jsp"};
    private static final LocalStringManagerImpl localStrings = new LocalStringManagerImpl(ApplicationLifecycle.class);
    private static final Logger LOG = KernelLoggerInfo.getLogger();

    @Inject
    private SnifferManagerImpl snifferManager;

    @Inject
    private ServiceLocator serviceLocator;

    @Inject
    private ArchiveFactory archiveFactory;

    @Inject
    private ContainerRegistry containerRegistry;

    @Inject
    private ApplicationRegistry appRegistry;

    @Inject
    private Applications applications;

    @Inject
    @Named(DEFAULT_INSTANCE_NAME)
    private Server server;

    @Inject
    private Domain domain;

    @Inject
    private ServerEnvironmentImpl env;

    @Inject
    @Optional
    private VirtualizationEnv virtEnv;

    @Inject
    private Events events;

    private DeploymentLifecycleProbeProvider deploymentLifecycleProbeProvider;
    private ExecutorService executorService;
    private Collection<ApplicationLifecycleInterceptor> alcInterceptors = emptyList();

    protected Deployer<?, ?> getDeployer(EngineInfo engineInfo) {
        return engineInfo.getDeployer();
    }

    @Override
    public void postConstruct() {
        executorService = createExecutorService();
        deploymentLifecycleProbeProvider = new DeploymentLifecycleProbeProvider();
        alcInterceptors = serviceLocator.getAllServices(ApplicationLifecycleInterceptor.class);
    }

    /**
     * Returns the ArchiveHandler for the passed archive abstraction or null if there are none.
     *
     * @param archive the archive to find the handler for
     * @return the archive handler or null if not found.
     * @throws IOException when an error occur
     */
    @Override
    public ArchiveHandler getArchiveHandler(ReadableArchive archive) throws IOException {
        return getArchiveHandler(archive, null);
    }

    /**
     * Returns the ArchiveHandler for the passed archive abstraction or null if there are none.
     *
     * @param archive the archive to find the handler for
     * @param type the type of the archive
     * @return the archive handler or null if not found.
     * @throws IOException when an error occur
     */
    @Override
    public ArchiveHandler getArchiveHandler(ReadableArchive archive, String type) throws IOException {
        if (type != null) {
            return serviceLocator.<ArchiveDetector>getService(ArchiveDetector.class, type).getArchiveHandler();
        }

        List<ArchiveDetector> detectors = new ArrayList<>(serviceLocator.<ArchiveDetector>getAllServices(ArchiveDetector.class));
        Collections.sort(detectors, new Comparator<ArchiveDetector>() {
            // rank 2 is considered lower than rank 1, let's sort them in inceasing order
            @Override
            public int compare(ArchiveDetector o1, ArchiveDetector o2) {
                return o1.rank() - o2.rank();
            }
        });

        for (ArchiveDetector detector : detectors) {
            if (detector.handles(archive)) {
                return detector.getArchiveHandler();
            }
        }

        return null;
    }

    @Override
    public ApplicationInfo deploy(final ExtendedDeploymentContext context) {
        return deploy(null, context);
    }

    @Override
    public ApplicationInfo deploy(Collection<? extends Sniffer> sniffers, final ExtendedDeploymentContext context) {
        long operationStartTime = Calendar.getInstance().getTimeInMillis();

        events.send(new Event<>(Deployment.DEPLOYMENT_START, context), false);
        final ActionReport report = context.getActionReport();

        final DeployCommandParameters commandParams = context.getCommandParameters(DeployCommandParameters.class);

        final String appName = commandParams.name();
        if (commandParams.origin == OpsParams.Origin.deploy && appRegistry.get(appName) != null) {
            report.setMessage(localStrings.getLocalString("appnamenotunique",
                    "Application name {0} is already in use. Please pick a different name.", appName));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return null;
        }

        // If the virtualservers param is not defined, set it to all
        // defined virtual servers minus __asadmin on that target
        if (commandParams.virtualservers == null) {
            commandParams.virtualservers = getVirtualServers(commandParams.target, env, domain);
        }

        if (commandParams.enabled == null) {
            commandParams.enabled = true;
        }

        if (commandParams.altdd != null) {
            context.getSource().addArchiveMetaData(ALT_DD, commandParams.altdd);
        }

        if (commandParams.runtimealtdd != null) {
            context.getSource().addArchiveMetaData(RUNTIME_ALT_DD, commandParams.runtimealtdd);
        }

        ProgressTracker tracker = new ProgressTracker() {
            @Override
            public void actOn(Logger logger) {
                // loaded is used instead of started to include more modules to
                // stop. In some modules, the setup and cleanup steps are not
                // fully symmetric, and to ensure thorough cleanup, we need to
                // call module.stop() for started modules, and modules that are
                // loaded but may not be started. Issue 18263
                for (EngineRef module : get("loaded", EngineRef.class)) {
                    try {
                        module.stop(context);
                    } catch (Exception e) {
                        // ignore
                    }
                }
                try {
                    PreDestroy.class.cast(context).preDestroy();
                } catch (Exception e) {
                    // ignore
                }
                for (EngineRef module : get("loaded", EngineRef.class)) {
                    try {
                        module.unload(context);
                    } catch (Exception e) {
                        // ignore
                    }
                }
                try {
                    ApplicationInfo appInfo = appRegistry.get(appName);
                    if (appInfo != null) {
                        // unload the remaining app resources and trigger all remaining events
                        unload(appInfo, context);
                    }
                } catch (Exception e) {
                    // ignore
                }
                for (EngineRef module : get("prepared", EngineRef.class)) {
                    try {
                        module.clean(context);
                    } catch (Exception e) {
                        // ignore
                    }
                }

                if (!commandParams.keepfailedstubs) {
                    try {
                        context.clean();
                    } catch (Exception e) {
                        // ignore
                    }
                }

                appRegistry.remove(appName);

            }
        };

        context.addTransientAppMetaData(ExtendedDeploymentContext.TRACKER, tracker);
        context.setPhase(DeploymentContextImpl.Phase.PREPARE);
        ApplicationInfo appInfo = null;

        try {
            ArchiveHandler handler = context.getArchiveHandler();
            if (handler == null) {
                handler = getArchiveHandler(context.getSource(), commandParams.type);
                context.setArchiveHandler(handler);
            }

            if (handler == null) {
                report.setMessage(localStrings.getLocalString("unknownarchivetype", "Archive type of {0} was not recognized",
                        context.getSourceDir()));
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                return null;
            }

            DeploymentTracing tracing = context.getModuleMetaData(DeploymentTracing.class);

            if (tracing != null) {
                tracing.addMark(DeploymentTracing.Mark.ARCHIVE_HANDLER_OBTAINED);
            }

            if (handler.requiresAnnotationScanning(context.getSource())) {
                getDeployableTypes(context);
            }

            if (tracing != null) {
                tracing.addMark(DeploymentTracing.Mark.PARSING_DONE);
            }

            // containers that are started are not stopped even if
            // the deployment fail, the main reason
            // is that some container do not support to be restarted.
            if (sniffers != null && LOG.isLoggable(FINE)) {
                LOG.log(Level.FINE, "Before Sorting: "
                        + sniffers.stream().map(Sniffer::getModuleType).collect(Collectors.joining(", ")));
            }

            sniffers = getSniffers(handler, sniffers, context);

            ClassLoaderHierarchy classLoaderHierarchy = serviceLocator.getService(ClassLoaderHierarchy.class);
            if (tracing != null) {
                tracing.addMark(DeploymentTracing.Mark.CLASS_LOADER_HIERARCHY);
            }

            context.createDeploymentClassLoader(classLoaderHierarchy, handler);
            events.send(new Event<>(AFTER_DEPLOYMENT_CLASSLOADER_CREATION, context), false);

            if (tracing != null) {
                tracing.addMark(DeploymentTracing.Mark.CLASS_LOADER_CREATED);
            }

            final ClassLoader cloader = context.getClassLoader();
            final ClassLoader currentCL = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(cloader);

                List<EngineInfo<?, ?>> sortedEngineInfos = setupContainerInfos(handler, sniffers, context);
                if (tracing != null) {
                    tracing.addMark(DeploymentTracing.Mark.CONTAINERS_SETUP_DONE);
                }

                if (LOG.isLoggable(FINE)) {
                    LOG.log(Level.FINE, "After Sorting: " + sortedEngineInfos.stream()
                            .map(i -> i.getSniffer().getModuleType()).collect(Collectors.joining(", ")));
                }

                if (isEmpty(sortedEngineInfos)) {
                    report.failure(LOG,
                            localStrings.getLocalString("unknowncontainertype",
                                    "There is no installed container capable of handling this application {0}",
                                    context.getSource().getName()));
                    tracker.actOn(LOG);
                    return null;
                }

                // create a temporary application info to hold metadata
                // so the metadata could be accessed at classloader
                // construction time through ApplicationInfo
                ApplicationInfo tempAppInfo = new ApplicationInfo(events, context.getSource(), appName);
                for (Object m : context.getModuleMetadata()) {
                    tempAppInfo.addMetaData(m);
                }

                tempAppInfo.detectIfJakartaEEApp(sortedEngineInfos);
                context.getSource().setExtraData(Boolean.class, tempAppInfo.isJakartaEEApp());
                appRegistry.add(appName, tempAppInfo);

                try {
                    notifyLifecycleInterceptorsBefore(ExtendedDeploymentContext.Phase.PREPARE, context);
                } catch (Throwable interceptorException) {
                    report.failure(LOG, "Exception while invoking the lifecycle interceptor", null);
                    report.setFailureCause(interceptorException);
                    LOG.log(SEVERE, KernelLoggerInfo.lifecycleException, interceptorException);
                    tracker.actOn(LOG);
                    return null;
                }

                events.send(new Event<>(Deployment.DEPLOYMENT_BEFORE_CLASSLOADER_CREATION, context), false);

                context.createApplicationClassLoader(classLoaderHierarchy, handler);

                events.send(new Event<>(Deployment.AFTER_APPLICATION_CLASSLOADER_CREATION, context), false);

                if (tracing != null) {
                    tracing.addMark(DeploymentTracing.Mark.CLASS_LOADER_CREATED);
                }

                // this is a first time deployment as opposed as load following an unload event,
                // we need to create the application info
                // todo : we should come up with a general Composite API solution
                ModuleInfo moduleInfo = null;
                try {
                    moduleInfo = prepareModule(sortedEngineInfos, appName, context, tracker);
                    // Now that the prepare phase is done, any artifacts
                    // should be available. Go ahead and create the
                    // downloadable client JAR. We want to do this now, or
                    // at least before the load and start phases, because
                    // (for example) the app client deployer start phase
                    // needs to find all generated files when it runs.
                    final ClientJarWriter cjw = new ClientJarWriter(context);
                    cjw.run();
                } catch (Throwable prepareException) {
                    prepareException.printStackTrace();
                    report.failure(LOG, "Exception while preparing the app", null);
                    report.setFailureCause(prepareException);
                    LOG.log(SEVERE, KernelLoggerInfo.lifecycleException, prepareException);
                    tracker.actOn(LOG);
                    return null;
                }

                // the deployer did not take care of populating the application info, this
                // is not a composite module.
                appInfo = context.getModuleMetaData(ApplicationInfo.class);
                if (appInfo == null) {
                    appInfo = new ApplicationInfo(events, context.getSource(), appName);
                    appInfo.addModule(moduleInfo);

                    for (Object m : context.getModuleMetadata()) {
                        moduleInfo.addMetaData(m);
                        appInfo.addMetaData(m);
                    }
                } else {
                    for (EngineRef ref : moduleInfo.getEngineRefs()) {
                        appInfo.add(ref);
                    }
                }

                // remove the temp application info from the registry
                // first, then register the real one
                appRegistry.remove(appName);
                appInfo.detectIfJakartaEEApp(sortedEngineInfos);
                appRegistry.add(appName, appInfo);

                notifyLifecycleInterceptorsAfter(ExtendedDeploymentContext.Phase.PREPARE, context);

                if (tracing != null) {
                    tracing.addMark(DeploymentTracing.Mark.PREPARED);
                }

                // send the APPLICATION_PREPARED event
                // set the phase and thread context classloader properly
                // before sending the event
                context.setPhase(DeploymentContextImpl.Phase.PREPARED);
                Thread.currentThread().setContextClassLoader(context.getClassLoader());
                appInfo.setAppClassLoader(context.getClassLoader());
                events.send(new Event<>(Deployment.APPLICATION_PREPARED, context), false);

                // now were falling back into the mainstream loading/starting sequence, at this
                // time the containers are set up, all the modules have been prepared in their
                // associated engines and the application info is created and registered
                if (loadOnCurrentInstance(context)) {
                    appInfo.setLibraries(commandParams.libraries());
                    try {
                        notifyLifecycleInterceptorsBefore(ExtendedDeploymentContext.Phase.LOAD, context);
                        appInfo.load(context, tracker);
                        notifyLifecycleInterceptorsAfter(ExtendedDeploymentContext.Phase.LOAD, context);

                        notifyLifecycleInterceptorsBefore(ExtendedDeploymentContext.Phase.START, context);
                        appInfo.start(context, tracker);
                        notifyLifecycleInterceptorsAfter(ExtendedDeploymentContext.Phase.START, context);
                    } catch (Throwable loadException) {
                        LOG.log(SEVERE, KernelLoggerInfo.lifecycleException, loadException);
                        report.failure(LOG, "Exception while loading the app", null);
                        report.setFailureCause(loadException);
                        tracker.actOn(LOG);
                        return null;
                    }
                }

                return appInfo;
            } finally {
                context.postDeployClean(false /* not final clean-up yet */);
                Thread.currentThread().setContextClassLoader(currentCL);
            }

        } catch (Throwable e) {
            report.failure(LOG, localStrings.getLocalString("error.deploying.app", "Exception while deploying the app [{0}]", appName),
                    null);
            report.setFailureCause(e);
            LOG.log(SEVERE, KernelLoggerInfo.lifecycleException, e);
            tracker.actOn(LOG);
            return null;
        } finally {
            if (report.getActionExitCode() == ActionReport.ExitCode.SUCCESS) {
                events.send(new Event<>(Deployment.DEPLOYMENT_SUCCESS, appInfo));
                long operationTime = Calendar.getInstance().getTimeInMillis() - operationStartTime;
                if (appInfo != null) {
                    deploymentLifecycleProbeProvider.applicationDeployedEvent(appName, getApplicationType(appInfo),
                            String.valueOf(operationTime));
                }
            } else {
                events.send(new Event<>(Deployment.DEPLOYMENT_FAILURE, context));
            }
        }
    }

    @Override
    public Types getDeployableTypes(DeploymentContext context) throws IOException {
        synchronized (context) {
            Types types = context.getTransientAppMetaData(Types.class.getName(), Types.class);
            if (types != null) {
                return types;
            }

            try {
                // Scan the jar and store the result in the deployment context.
                Parser parser = new Parser(
                        new ParsingContext.Builder()
                                .logger(context.getLogger())
                                .executorService(executorService)
                                .locator(getResourceLocator())
                                .build());

                try (ReadableArchiveScannerAdapter scannerAdapter = new ReadableArchiveScannerAdapter(parser, context.getSource())) {
                    parser.parse(scannerAdapter, null);

                    List<ReadableArchive> externalLibraries = getExternalLibraries(context);

                    for (ReadableArchive externalLibrary : externalLibraries) {
                        parser.parse(new ReadableArchiveScannerAdapter(parser, externalLibrary), null);
                    }

                    parser.awaitTermination();

                    for (ReadableArchive externalLibrary : externalLibraries) {
                        externalLibrary.close();
                    }
                }

                context.addTransientAppMetaData(Types.class.getName(), parser.getContext().getTypes());
                context.addTransientAppMetaData(Parser.class.getName(), parser);

                return parser.getContext().getTypes();
            } catch (InterruptedException | URISyntaxException e) {
                throw new IOException(e);
            }
        }
    }

    private ResourceLocator getResourceLocator() {
        if (CommonModelRegistry.getInstance().canLoadResources()) {
            return null;
        }

        ClassLoader classLoader = serviceLocator.getService(ClassLoaderHierarchy.class).getCommonClassLoader();

        return new ResourceLocator() {
            private boolean excluded(String name) {
                return name.startsWith("java/") || name.startsWith("sun/") || name.startsWith("com/sun/");
            }

            @Override
            public InputStream openResourceStream(String name) throws IOException {
                return excluded(name) ? null : classLoader.getResourceAsStream(name);
            }

            @Override
            public URL getResource(String name) {
                return excluded(name) ? null : classLoader.getResource(name);
            }
        };
    }

    private void notifyLifecycleInterceptorsBefore(final ExtendedDeploymentContext.Phase phase, final ExtendedDeploymentContext dc) {
        for (ApplicationLifecycleInterceptor interceptor : alcInterceptors) {
            interceptor.before(phase, dc);
        }
    }

    private void notifyLifecycleInterceptorsAfter(final ExtendedDeploymentContext.Phase phase, final ExtendedDeploymentContext dc) {
        for (ApplicationLifecycleInterceptor interceptor : alcInterceptors) {
            interceptor.after(phase, dc);
        }
    }

    private List<ReadableArchive> getExternalLibraries(DeploymentContext context) throws IOException, URISyntaxException {
        List<ReadableArchive> externalLibArchives = new ArrayList<>();

        String skipScanExternalLibProp = context.getAppProps().getProperty(SKIP_SCAN_EXTERNAL_LIB);

        if (Boolean.parseBoolean(skipScanExternalLibProp)) {
            // If we skip scanning external libraries, we should just
            // return an empty list here
            return emptyList();
        }

        // Get the libraries referenced in the manifest class-path
        for (URI externalLib : DeploymentUtils.getExternalLibraries(context.getSource())) {
            externalLibArchives.add(archiveFactory.openArchive(new File(externalLib.getPath())));
        }

        // Get the libraries referenced in the manifest extension-list
        for (URI externalLib : context.getAppLibs()) {
            externalLibArchives.add(archiveFactory.openArchive(new File(externalLib.getPath())));
        }

        return externalLibArchives;
    }

    /**
     * Suspends this application.
     *
     * @param appName the registration application ID
     * @return true if suspending was successful, false otherwise.
     */
    public boolean suspend(String appName) {
        boolean isSuccess = true;

        ApplicationInfo appInfo = appRegistry.get(appName);
        if (appInfo != null) {
            isSuccess = appInfo.suspend(LOG);
        }

        return isSuccess;
    }

    /**
     * Resumes this application.
     *
     * @param appName the registration application ID
     * @return true if resumption was successful, false otherwise.
     */
    public boolean resume(String appName) {
        boolean isSuccess = true;

        ApplicationInfo appInfo = appRegistry.get(appName);
        if (appInfo != null) {
            isSuccess = appInfo.resume(LOG);
        }

        return isSuccess;
    }

    @Override
    public List<EngineInfo<?, ?>> setupContainerInfos(DeploymentContext context) throws Exception {
        return setupContainerInfos(context.getArchiveHandler(), getSniffers(context.getArchiveHandler(), null, context), context);
    }

    @Override
    public Collection<? extends Sniffer> getSniffers(final ArchiveHandler handler, Collection<? extends Sniffer> sniffers, DeploymentContext context) {
        if (handler == null) {
            return emptyList();
        }

        if (sniffers == null) {
            if (handler instanceof CompositeHandler) {
                ((CompositeHandler) handler).initCompositeMetaData(context);
                context.getAppProps().setProperty(IS_COMPOSITE, "true");
            }
            sniffers = snifferManager.getSniffers(context);
        }
        context.addTransientAppMetaData(DeploymentProperties.SNIFFERS, sniffers);
        snifferManager.validateSniffers(sniffers, context);

        return sniffers;
    }

    // set up containers and prepare the sorted ModuleInfos
    @Override
    public List<EngineInfo<?, ?>> setupContainerInfos(final ArchiveHandler handler,
            Collection<? extends Sniffer> sniffers, DeploymentContext context) throws Exception {
        final ActionReport report = context.getActionReport();

        DeploymentTracing tracing = context.getModuleMetaData(DeploymentTracing.class);

        Map<Deployer, EngineInfo> containerInfosByDeployers = new LinkedHashMap<>();

        for (Sniffer sniffer : sniffers) {
            if (sniffer.getContainersNames() == null || sniffer.getContainersNames().length == 0) {
                report.failure(LOG, "no container associated with application of type : " + sniffer.getModuleType(), null);
                return null;
            }

            final String[] containerNames = sniffer.getContainersNames();
            traceContainers(tracing, containerNames, DeploymentTracing.ContainerMark.SNIFFER_DONE);

            // Start all the containers associated with sniffers.
            if (areSomeContainersNotStarted(containerNames)) {
                // Need to synchronize on the registry to not end up starting the same container from
                // different threads.
                Collection<EngineInfo<?, ?>> containersInfo = null;
                synchronized (containerRegistry) {
                    if (areSomeContainersNotStarted(containerNames)) {
                        traceContainers(tracing, containerNames, DeploymentTracing.ContainerMark.BEFORE_CONTAINER_SETUP);
                        containersInfo = setupContainer(sniffer, LOG, context);

                        traceContainers(tracing, containerNames, DeploymentTracing.ContainerMark.AFTER_CONTAINER_SETUP);

                        if (isEmpty(containersInfo)) {
                            String msg = "Cannot start container(s) associated to application of type : " + sniffer.getModuleType();
                            report.failure(LOG, msg, null);
                            throw new Exception(msg);
                        }
                    }
                }

                // Now start all containers, by now, they should be all setup...
                if (containersInfo != null && !startContainers(containersInfo, LOG, context)) {
                    final String msg = "Aborting, Failed to start containers for sniffer " + sniffer.getModuleType();
                    report.failure(LOG, msg, null);
                    throw new Exception(msg);
                }
            }

            for (String containerName : containerNames) {
                EngineInfo<?,?> engineInfo = containerRegistry.getContainer(containerName);
                traceContainer(tracing, containerName, DeploymentTracing.ContainerMark.GOT_CONTAINER);

                if (engineInfo == null) {
                    final String msg = "Aborting, Failed to start container " + containerName + " for sinffer " + sniffer.getModuleType();
                        report.failure(LOG, msg, null);
                        throw new Exception(msg);
                }

                Deployer<?, ?> deployer = getDeployer(engineInfo);
                if (deployer == null) {
                    if (!startContainers(Collections.singleton(engineInfo), LOG, context)) {
                        final String msg = "Aborting, Failed to start container " + containerName + " for sniffer " + sniffer.getModuleType();
                        report.failure(LOG, msg, null);
                        throw new Exception(msg);
                    }
                    deployer = getDeployer(engineInfo);

                    if (deployer == null) {
                        final String msg = "Got a null deployer out of the " + engineInfo.getContainer().getClass()
                                + " container, is it annotated with @Service ?";
                        report.failure(LOG, msg);
                        throw new Exception(msg);
                    }
                }
                traceContainer(tracing, containerName, DeploymentTracing.ContainerMark.GOT_DEPLOYER);

                containerInfosByDeployers.put(deployer, engineInfo);
            }
        }

        // All containers that have recognized parts of the application being deployed
        // have now been successfully started. Start the deployment process.
        List<EngineInfo<?, ?>> sortedEngineInfos = new ArrayList<>();

        Map<Class<?>, ApplicationMetaDataProvider<?>> typeByProvider = new HashMap<>();
        for (ApplicationMetaDataProvider<?> provider : serviceLocator.getAllServices(ApplicationMetaDataProvider.class)) {
            if (provider.getMetaData() != null) {
                for (Class<?> provided : provider.getMetaData().provides()) {
                    typeByProvider.put(provided, provider);
                }
            }
        }

        // Check if everything is provided.
        for (ApplicationMetaDataProvider<?> provider : serviceLocator.getAllServices(ApplicationMetaDataProvider.class)) {
            if (provider.getMetaData() != null) {
                for (Class<?> dependency : provider.getMetaData().requires()) {
                    if (!typeByProvider.containsKey(dependency)) {
                        // at this point, we only log problems, because it maybe that what I am deploying now
                        // will not require this application metadata.
                        LOG.log(WARNING, KernelLoggerInfo.applicationMetaDataProvider, new Object[]{provider, dependency});
                    }
                }
            }
        }

        Map<Class<?>, Deployer<?, ?>> typeByDeployer = new HashMap<>();
        for (Deployer<?, ?> deployer : containerInfosByDeployers.keySet()) {
            if (deployer.getMetaData() != null) {
                for (Class<?> provided : deployer.getMetaData().provides()) {
                    typeByDeployer.put(provided, deployer);
                }
            }
        }

        for (Deployer<?, ?> deployer : containerInfosByDeployers.keySet()) {
            if (deployer.getMetaData() != null) {
                for (Class<?> dependency : deployer.getMetaData().requires()) {
                    if (!typeByDeployer.containsKey(dependency) && !typeByProvider.containsKey(dependency)) {

                        Service s = deployer.getClass().getAnnotation(Service.class);
                        String serviceName;
                        if (s != null && s.name() != null && s.name().length() > 0) {
                            serviceName = s.name();
                        } else {
                            serviceName = deployer.getClass().getSimpleName();
                        }

                        report.failure(LOG, serviceName + " deployer requires " + dependency + " but no other deployer provides it",
                                null);

                        return null;
                    }
                }
            }
        }

        // ok everything is satisfied, just a matter of running things in order
        List<Deployer<?, ?>> orderedDeployers = new ArrayList<>();
        for (Deployer<?, ?> deployer : containerInfosByDeployers.keySet()) {
            LOG.log(Level.FINE, "Keyed Deployer {0}", deployer.getClass());
            loadDeployer(orderedDeployers, deployer, typeByDeployer, typeByProvider, context);
        }

        // Now load metadata from deployers.
        for (Deployer<?, ?> deployer : orderedDeployers) {
            LOG.log(Level.FINE, "Ordered Deployer {0}", deployer);

            final MetaData metadata = deployer.getMetaData();
            try {
                if (metadata == null) {
                    deployer.loadMetaData(null, context);
                } else {
                    Class<?>[] provides = metadata.provides();
                    if (provides == null || provides.length == 0) {
                        deployer.loadMetaData(null, context);
                    } else {
                        for (Class<?> provide : provides) {
                            Object contextMetaData = context.getModuleMetaData(provide);
                            if (contextMetaData == null) {
                                context.addModuleMetaData(deployer.loadMetaData(provide, context));
                            } else {
                                deployer.loadMetaData(null, context);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                report.failure(LOG, "Exception while invoking " + deployer.getClass() + " prepare method", e);
                throw e;
            }

            sortedEngineInfos.add(containerInfosByDeployers.get(deployer));
        }

        return sortedEngineInfos;
    }

    private boolean areSomeContainersNotStarted(final String[] containerNames) {
        for (String containerName : containerNames) {
            if (null == containerRegistry.getContainer(containerName)) {
                return true;
            }
        }
        return false;
    }

    private void loadDeployer(List<Deployer<?, ?>> results, Deployer<?, ?> deployer,
            Map<Class<?>, Deployer<?, ?>> typeByDeployer, Map<Class<?>, ApplicationMetaDataProvider<?>> typeByProvider,
            DeploymentContext dc) throws IOException {
        if (results.contains(deployer)) {
            return;
        }
        results.add(deployer);
        if (deployer.getMetaData() != null) {
            for (Class<?> required : deployer.getMetaData().requires()) {
                if (dc.getModuleMetaData(required) != null) {
                    continue;
                }
                if (typeByDeployer.containsKey(required)) {
                    loadDeployer(results, typeByDeployer.get(required), typeByDeployer, typeByProvider, dc);
                } else {
                    ApplicationMetaDataProvider<?> provider = typeByProvider.get(required);
                    if (provider == null) {
                        LOG.log(SEVERE, inconsistentLifecycleState, required);
                    } else {
                        LinkedList<ApplicationMetaDataProvider<?>> providers = new LinkedList<>();

                        addRecursively(providers, typeByProvider, provider);
                        for (ApplicationMetaDataProvider<?> p : providers) {
                            dc.addModuleMetaData(p.load(dc));
                        }
                    }
                }
            }
        }
    }

    private void addRecursively(LinkedList<ApplicationMetaDataProvider<?>> results,
            Map<Class<?>, ApplicationMetaDataProvider<?>> providers, ApplicationMetaDataProvider<?> provider) {
        results.addFirst(provider);

        for (Class<?> type : provider.getMetaData().requires()) {
            if (providers.containsKey(type)) {
                addRecursively(results, providers, providers.get(type));
            }
        }
    }

    @Override
    public ModuleInfo prepareModule(List<EngineInfo<?, ?>> sortedEngineInfos, String moduleName,
            DeploymentContext context, ProgressTracker tracker) throws Exception {
        ActionReport report = context.getActionReport();
        List<EngineRef> addedEngines = new ArrayList<>();

        DeploymentTracing tracing = context.getModuleMetaData(DeploymentTracing.class);

        if (tracing != null) {
            tracing.addModuleMark(DeploymentTracing.ModuleMark.PREPARE, moduleName);
        }

        for (EngineInfo<?, ?> engineInfo : sortedEngineInfos) {

            // Get the deployer
            Deployer<?, ?> deployer = engineInfo.getDeployer();

            try {
                traceContainer(tracing, engineInfo.getSniffer().getModuleType(), DeploymentTracing.ContainerMark.PREPARE);
                deployer.prepare(context);

                traceContainer(tracing, engineInfo.getSniffer().getModuleType(), DeploymentTracing.ContainerMark.PREPARED);

                // Construct an incomplete EngineRef which will be later
                // filled in at loading time
                EngineRef engineRef = new EngineRef(engineInfo, null);
                addedEngines.add(engineRef);
                tracker.add("prepared", EngineRef.class, engineRef);

                tracker.add(Deployer.class, deployer);
            } catch (Exception e) {
                report.failure(LOG, "Exception while invoking " + deployer.getClass() + " prepare method", e);
                throw e;
            }
        }
        if (tracing != null) {
            tracing.addModuleMark(DeploymentTracing.ModuleMark.PREPARE_EVENTS, moduleName);
        }

        if (events != null) {
            events.send(new Event<>(Deployment.MODULE_PREPARED, context), false);
        }

        if (tracing != null) {
            tracing.addModuleMark(DeploymentTracing.ModuleMark.PREPARED, moduleName);
        }

        // Need to create the application info here from the context, or something like this.
        // and return the application info from this method for automatic registration in the caller.
        // set isComposite property on module props so we know whether to persist
        // module level properties inside ModuleInfo
        String isComposite = context.getAppProps().getProperty(IS_COMPOSITE);
        if (isComposite != null) {
            context.getModuleProps().setProperty(IS_COMPOSITE, isComposite);
        }

        ModuleInfo mi = new ModuleInfo(events, moduleName, addedEngines, context.getModuleProps());

        /*
         * Save the application config that is potentially attached to each engine in the corresponding EngineRefs that have
         * already created.
         *
         * Later, in registerAppInDomainXML, the appInfo is saved, which in turn saves the moduleInfo children and their
         * engineRef children. Saving the engineRef assigns the application config to the Engine which corresponds directly to
         * the <engine> element in the XML. A long way to get this done.
         */
        ApplicationConfigInfo savedAppConfig = new ApplicationConfigInfo(context.getAppProps());
        for (EngineRef er : mi.getEngineRefs()) {
            ApplicationConfig c = savedAppConfig.get(mi.getName(), er.getContainerInfo().getSniffer().getModuleType());
            if (c != null) {
                er.setApplicationConfig(c);
            }
        }

        return mi;
    }

    protected Collection<EngineInfo<?, ?>> setupContainer(Sniffer sniffer, Logger logger, DeploymentContext context) {
        ActionReport report = context.getActionReport();
        ContainerStarter starter = serviceLocator.getService(ContainerStarter.class);
        Collection<EngineInfo<?, ?>> containersInfo = starter.startContainer(sniffer);
        if (isEmpty(containersInfo)) {
            report.failure(logger, "Cannot start container(s) associated to application of type : " + sniffer.getModuleType(), null);
            return null;
        }

        return containersInfo;
    }

    protected boolean startContainers(Collection<EngineInfo<?, ?>> containersInfo, Logger logger, DeploymentContext context) {
        ActionReport report = context.getActionReport();
        for (EngineInfo<?, ?> engineInfo : containersInfo) {
            Container container;
            try {
                container = engineInfo.getContainer();
            } catch (Exception e) {
                LogRecord log = new LogRecord(SEVERE, KernelLoggerInfo.cantStartContainer);
                log.setParameters(new Object[]{engineInfo.getSniffer().getModuleType()});
                log.setThrown(e);
                LOG.log(log);
                return false;
            }

            Class<?> deployerClass = container.getDeployer();
            Deployer<?, ?> deployer;
            try {
                deployer = (Deployer<?, ?>) serviceLocator.getService(deployerClass);
                engineInfo.setDeployer((Deployer) deployer);
            } catch (MultiException e) {
                report.failure(logger, "Cannot instantiate or inject " + deployerClass, e);
                engineInfo.stop(logger);
                return false;
            } catch (ClassCastException e) {
                engineInfo.stop(logger);
                report.failure(logger, deployerClass + " does not implement "
                        + " the org.jvnet.glassfish.api.deployment.Deployer interface", e);
                return false;
            }
        }
        return true;
    }

    protected void stopContainers(EngineInfo<?, ?>[] ctrInfos, Logger logger) {
        for (EngineInfo<?, ?> ctrInfo : ctrInfos) {
            try {
                ctrInfo.stop(logger);
            } catch (Exception e) {
                // this is not a failure per se but we need to document it.
                logger.log(INFO, KernelLoggerInfo.cantReleaseContainer, new Object[]{ctrInfo.getSniffer().getModuleType(), e});
            }
        }
    }

    @Override
    public ApplicationInfo unload(ApplicationInfo info, ExtendedDeploymentContext context) {
        ActionReport report = context.getActionReport();
        if (info == null) {
            report.failure(context.getLogger(), "Application not registered", null);
            return null;
        }

        notifyLifecycleInterceptorsBefore(ExtendedDeploymentContext.Phase.STOP, context);

        if (info.isLoaded()) {
            info.stop(context, context.getLogger());
            notifyLifecycleInterceptorsAfter(ExtendedDeploymentContext.Phase.STOP, context);

            notifyLifecycleInterceptorsBefore(ExtendedDeploymentContext.Phase.UNLOAD, context);
            info.unload(context);
            notifyLifecycleInterceptorsAfter(ExtendedDeploymentContext.Phase.UNLOAD, context);
        }

        events.send(new Event<>(Deployment.APPLICATION_DISABLED, info), false);

        try {
            notifyLifecycleInterceptorsBefore(ExtendedDeploymentContext.Phase.CLEAN, context);
            info.clean(context);
            notifyLifecycleInterceptorsAfter(ExtendedDeploymentContext.Phase.CLEAN, context);
        } catch (Exception e) {
            report.failure(context.getLogger(), "Exception while cleaning", e);
            return info;
        }

        return info;
    }

    @Override
    public void undeploy(String appName, ExtendedDeploymentContext context) {

        ActionReport report = context.getActionReport();
        UndeployCommandParameters params = context.getCommandParameters(UndeployCommandParameters.class);

        ApplicationInfo info = appRegistry.get(appName);
        if (info == null) {
            report.failure(context.getLogger(), "Application " + appName + " not registered", null);
            events.send(new Event<>(Deployment.UNDEPLOYMENT_FAILURE, context));
            return;

        }

        events.send(new Event<>(Deployment.UNDEPLOYMENT_START, info));

        // for DAS target, the undeploy should unload the application
        // as well
        if (DeploymentUtils.isDASTarget(params.target)) {
            unload(info, context);
        }

        if (report.getActionExitCode().equals(ActionReport.ExitCode.SUCCESS)) {
            events.send(new Event<>(Deployment.UNDEPLOYMENT_SUCCESS, context));
            deploymentLifecycleProbeProvider.applicationUndeployedEvent(appName, getApplicationType(info));
        } else {
            events.send(new Event<>(Deployment.UNDEPLOYMENT_FAILURE, context));
        }

        appRegistry.remove(appName);
    }

    // prepare application config change for later registering
    // in the domain.xml
    @Override
    public Transaction prepareAppConfigChanges(final DeploymentContext context) throws TransactionFailure {
        final Properties appProps = context.getAppProps();
        final DeployCommandParameters deployParams = context.getCommandParameters(DeployCommandParameters.class);
        Transaction t = new Transaction();

        try {
            // prepare the application element
            ConfigBean newBean = ((ConfigBean) Dom.unwrap(applications)).allocate(Application.class);
            Application app = newBean.createProxy();
            Application app_w = t.enroll(app);
            setInitialAppAttributes(app_w, deployParams, appProps, context);
            context.addTransientAppMetaData(ServerTags.APPLICATION, app_w);
        } catch (TransactionFailure e) {
            t.rollback();
            throw e;
        } catch (Exception e) {
            t.rollback();
            throw new TransactionFailure(e.getMessage(), e);
        }

        return t;
    }

    // register application information in domain.xml
    @Override
    public void registerAppInDomainXML(final ApplicationInfo applicationInfo, final DeploymentContext context, Transaction t)
            throws TransactionFailure {
        registerAppInDomainXML(applicationInfo, context, t, false);
    }

    // register application information in domain.xml
    @Override
    public void registerAppInDomainXML(final ApplicationInfo applicationInfo, final DeploymentContext context, Transaction t,
            boolean appRefOnly) throws TransactionFailure {
        final Properties appProps = context.getAppProps();
        final DeployCommandParameters deployParams = context.getCommandParameters(DeployCommandParameters.class);
        if (t != null) {
            try {
                if (!appRefOnly) {
                    Application app_w = context.getTransientAppMetaData(ServerTags.APPLICATION, Application.class);
                    // adding the application element
                    setRestAppAttributes(app_w, appProps);
                    Applications apps_w = t.enroll(applications);
                    apps_w.getModules().add(app_w);
                    if (applicationInfo != null) {
                        applicationInfo.save(app_w);
                    }
                }

                List<String> targets = new ArrayList<>();
                if (!DeploymentUtils.isDomainTarget(deployParams.target)) {
                    targets.add(deployParams.target);
                } else {
                    List<String> previousTargets = context
                            .getTransientAppMetaData(DeploymentProperties.PREVIOUS_TARGETS, List.class);
                    if (previousTargets == null) {
                        previousTargets = domain.getAllReferencedTargetsForApplication(deployParams.name);
                    }
                    targets = previousTargets;
                }

                String origVS = deployParams.virtualservers;
                Boolean origEnabled = deployParams.enabled;
                Properties previousVirtualServers = context
                        .getTransientAppMetaData(DeploymentProperties.PREVIOUS_VIRTUAL_SERVERS, Properties.class);
                Properties previousEnabledAttributes = context
                        .getTransientAppMetaData(DeploymentProperties.PREVIOUS_ENABLED_ATTRIBUTES, Properties.class);
                for (String target : targets) {
                    // first reset the virtualservers, enabled attribute
                    deployParams.virtualservers = origVS;
                    deployParams.enabled = origEnabled;
                    // now if the target is domain target,
                    // restore the previous attributes if
                    // applicable
                    if (DeploymentUtils.isDomainTarget(deployParams.target)) {
                        String vs = previousVirtualServers.getProperty(target);
                        if (vs != null) {
                            deployParams.virtualservers = vs;
                        }
                        String enabledAttr = previousEnabledAttributes.getProperty(target);
                        if (enabledAttr != null) {
                            deployParams.enabled = Boolean.valueOf(enabledAttr);
                        }
                    }
                    if (deployParams.enabled == null) {
                        deployParams.enabled = Boolean.TRUE;
                    }
                    Server servr = domain.getServerNamed(target);
                    if (servr != null) {
                        // adding the application-ref element to the standalone
                        // server instance
                        ConfigBeanProxy servr_w = t.enroll(servr);
                        // adding the application-ref element to the standalone
                        // server instance
                        ApplicationRef appRef = servr_w.createChild(ApplicationRef.class);
                        setAppRefAttributes(appRef, deployParams);
                        ((Server) servr_w).getApplicationRef().add(appRef);
                    }

                    Cluster cluster = domain.getClusterNamed(target);
                    if (cluster != null) {
                        // adding the application-ref element to the cluster
                        // and instances
                        ConfigBeanProxy cluster_w = t.enroll(cluster);
                        ApplicationRef appRef = cluster_w.createChild(ApplicationRef.class);
                        setAppRefAttributes(appRef, deployParams);
                        ((Cluster) cluster_w).getApplicationRef().add(appRef);

                        for (Server svr : cluster.getInstances()) {
                            ConfigBeanProxy svr_w = t.enroll(svr);
                            ApplicationRef appRef2 = svr_w.createChild(ApplicationRef.class);
                            setAppRefAttributes(appRef2, deployParams);
                            ((Server) svr_w).getApplicationRef().add(appRef2);
                        }
                    }
                }
            } catch (TransactionFailure e) {
                t.rollback();
                throw e;
            } catch (Exception e) {
                t.rollback();
                throw new TransactionFailure(e.getMessage(), e);
            }

            try {
                t.commit();
            } catch (RetryableException e) {
                LOG.log(Level.INFO, "Rollbacking the transaction. Retryable...");
                LOG.log(Level.FINEST, "Rollbacking the transaction.", e);
                t.rollback();
            } catch (TransactionFailure e) {
                t.rollback();
                throw e;
            }
        }
    }

    @Override
    public void registerTenantWithAppInDomainXML(final String appName, final ExtendedDeploymentContext context) throws TransactionFailure {

        final Transaction t = new Transaction();
        try {
            final AppTenant appTenant_w = writeableTenantForApp(appName, t);
            appTenant_w.setContextRoot(context.getAppProps().getProperty(ServerTags.CONTEXT_ROOT));
            appTenant_w.setTenant(context.getTenant());

            t.commit();
        } catch (TransactionFailure ex) {
            t.rollback();
            throw ex;
        } catch (Throwable ex) {
            t.rollback();
            throw new TransactionFailure(ex.getLocalizedMessage(), ex);
        }
    }

    @Override
    public void unregisterTenantWithAppInDomainXML(final String appName, final String tenantName)
            throws TransactionFailure, RetryableException {
        final com.sun.enterprise.config.serverbeans.Application app = applications.getApplication(appName);
        if (app == null) {
            throw new IllegalArgumentException("Application " + appName + " not found");
        }
        final AppTenants appTenants = app.getAppTenants();
        final AppTenant appTenant = appTenants.getAppTenant(tenantName);
        if (appTenant == null) {
            throw new IllegalArgumentException("Tenant " + tenantName + " not provisioned for application " + appName);
        }
        Transaction t = new Transaction();
        final AppTenants appTenants_w = t.enroll(appTenants);
        appTenants_w.getAppTenant().remove(appTenant);
        t.commit();
    }

    private AppTenant writeableTenantForApp(final String appName, final Transaction t) throws TransactionFailure, PropertyVetoException {
        final com.sun.enterprise.config.serverbeans.Application app = applications.getApplication(appName);
        if (app == null) {
            throw new IllegalArgumentException("Application " + appName + " not found");
        }

        /*
         * The app-tenants subelement might or might not already be there.
         */
        AppTenants appTenants = app.getAppTenants();
        AppTenants appTenants_w;
        if (appTenants == null) {
            com.sun.enterprise.config.serverbeans.Application app_w = t.enroll(app);
            appTenants_w = app_w.createChild(AppTenants.class);
            app_w.setAppTenants(appTenants_w);
        } else {
            appTenants_w = t.enroll(appTenants);
        }

        final List<AppTenant> appTenantList = appTenants_w.getAppTenant();
        AppTenant appTenant_w = appTenants_w.createChild(AppTenant.class);
        appTenantList.add(appTenant_w);
        return appTenant_w;
    }

    // application attributes that are set in the beginning of the deployment
    // that will not be changed in the course of the deployment
    private void setInitialAppAttributes(Application app, DeployCommandParameters deployParams, Properties appProps,
            DeploymentContext context) throws PropertyVetoException {
        Properties previousEnabledAttributes = context.getTransientAppMetaData(DeploymentProperties.PREVIOUS_ENABLED_ATTRIBUTES,
                Properties.class);
        // various attributes
        app.setName(deployParams.name);
        if (deployParams.libraries != null) {
            app.setLibraries(deployParams.libraries);
        }
        if (deployParams.description != null) {
            app.setDescription(deployParams.description);
        }
        if (deployParams.deploymentorder != null) {
            app.setDeploymentOrder(deployParams.deploymentorder.toString());
        }

        app.setEnabled(String.valueOf(true));
        if (appProps.getProperty(ServerTags.LOCATION) != null) {
            app.setLocation(appProps.getProperty(ServerTags.LOCATION));
            // when redeploy to domain we preserve the enable
            // attribute
            if (DeploymentUtils.isDomainTarget(deployParams.target)) {
                if (previousEnabledAttributes != null) {
                    String enabledAttr = previousEnabledAttributes.getProperty(DeploymentUtils.DOMAIN_TARGET_NAME);
                    if (enabledAttr != null) {
                        app.setEnabled(enabledAttr);
                    }
                }
            }
            app.setAvailabilityEnabled(deployParams.availabilityenabled.toString());
            app.setAsyncReplication(deployParams.asyncreplication.toString());
        }
        if (appProps.getProperty(ServerTags.OBJECT_TYPE) != null) {
            app.setObjectType(appProps.getProperty(ServerTags.OBJECT_TYPE));
        }
        if (appProps.getProperty(ServerTags.DIRECTORY_DEPLOYED) != null) {
            app.setDirectoryDeployed(appProps.getProperty(ServerTags.DIRECTORY_DEPLOYED));
        }
    }

    // set the rest of the application attributes at the end of the
    // deployment
    private void setRestAppAttributes(Application app, Properties appProps) throws PropertyVetoException, TransactionFailure {
        // context-root element
        if (appProps.getProperty(ServerTags.CONTEXT_ROOT) != null) {
            app.setContextRoot(appProps.getProperty(ServerTags.CONTEXT_ROOT));
        }
        // property element
        // trim the properties that have been written as attributes
        // the rest properties will be written as property element
        for (Object element : appProps.keySet()) {
            String propName = (String) element;
            if (!propName.equals(ServerTags.LOCATION) && !propName.equals(ServerTags.CONTEXT_ROOT)
                    && !propName.equals(ServerTags.OBJECT_TYPE) && !propName.equals(ServerTags.DIRECTORY_DEPLOYED)
                    && !propName.startsWith(DeploymentProperties.APP_CONFIG)) {
                if (appProps.getProperty(propName) != null) {
                    Property prop = app.createChild(Property.class);
                    app.getProperty().add(prop);
                    prop.setName(propName);
                    prop.setValue(appProps.getProperty(propName));
                }
            }
        }
    }

    @Override
    public void unregisterAppFromDomainXML(final String appName, final String target) throws TransactionFailure {
        unregisterAppFromDomainXML(appName, target, false);
    }

    @Override
    public void unregisterAppFromDomainXML(final String appName, final String tgt, final boolean appRefOnly) throws TransactionFailure {
        ConfigSupport.apply(new SingleConfigCode() {
            @Override
            public Object run(ConfigBeanProxy param) throws PropertyVetoException, TransactionFailure {
                // get the transaction
                Transaction t = Transaction.getTransaction(param);
                if (t != null) {
                    List<String> targets = new ArrayList<>();
                    if (!DeploymentUtils.isDomainTarget(tgt)) {
                        targets.add(tgt);
                    } else {
                        targets = domain.getAllReferencedTargetsForApplication(appName);
                    }

                    Domain dmn;
                    if (param instanceof Domain) {
                        dmn = (Domain) param;
                    } else {
                        return Boolean.FALSE;
                    }

                    for (String target : targets) {
                        Server servr = dmn.getServerNamed(target);
                        if (servr != null) {
                            // remove the application-ref from standalone
                            // server instance
                            ConfigBeanProxy servr_w = t.enroll(servr);
                            for (ApplicationRef appRef : servr.getApplicationRef()) {
                                if (appRef.getRef().equals(appName)) {
                                    ((Server) servr_w).getApplicationRef().remove(appRef);
                                    break;
                                }
                            }
                        }

                        Cluster cluster = dmn.getClusterNamed(target);
                        if (cluster != null) {
                            // remove the application-ref from cluster
                            ConfigBeanProxy cluster_w = t.enroll(cluster);
                            for (ApplicationRef appRef : cluster.getApplicationRef()) {
                                if (appRef.getRef().equals(appName)) {
                                    ((Cluster) cluster_w).getApplicationRef().remove(appRef);
                                    break;
                                }
                            }

                            // remove the application-ref from cluster instances
                            for (Server svr : cluster.getInstances()) {
                                ConfigBeanProxy svr_w = t.enroll(svr);
                                for (ApplicationRef appRef : svr.getApplicationRef()) {
                                    if (appRef.getRef().equals(appName)) {
                                        ((Server) svr_w).getApplicationRef().remove(appRef);
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (!appRefOnly) {
                        // remove application element
                        Applications apps = dmn.getApplications();
                        ConfigBeanProxy apps_w = t.enroll(apps);
                        for (ApplicationName module : apps.getModules()) {
                            if (module.getName().equals(appName)) {
                                ((Applications) apps_w).getModules().remove(module);
                                break;
                            }
                        }
                    }
                }
                return Boolean.TRUE;
            }
        }, domain);
    }

    @Override
    public void updateAppEnabledAttributeInDomainXML(final String appName, final String target, final boolean enabled)
            throws TransactionFailure {
        ConfigSupport.apply(new SingleConfigCode() {
            @Override
            public Object run(ConfigBeanProxy param) throws PropertyVetoException, TransactionFailure {
                // get the transaction
                Transaction t = Transaction.getTransaction(param);
                if (t != null) {
                    Domain dmn;
                    if (param instanceof Domain) {
                        dmn = (Domain) param;
                    } else {
                        return Boolean.FALSE;
                    }

                    if (enabled || DeploymentUtils.isDomainTarget(target)) {
                        Application app = dmn.getApplications().getApplication(appName);
                        ConfigBeanProxy app_w = t.enroll(app);
                        ((Application) app_w).setEnabled(String.valueOf(enabled));

                    }

                    List<String> targets = new ArrayList<>();
                    if (!DeploymentUtils.isDomainTarget(target)) {
                        targets.add(target);
                    } else {
                        targets = domain.getAllReferencedTargetsForApplication(appName);
                    }

                    for (String target : targets) {
                        Server servr = dmn.getServerNamed(target);
                        if (servr != null) {
                            // update the application-ref from standalone
                            // server instance
                            for (ApplicationRef appRef : servr.getApplicationRef()) {
                                if (appRef.getRef().equals(appName)) {
                                    ConfigBeanProxy appRef_w = t.enroll(appRef);
                                    ((ApplicationRef) appRef_w).setEnabled(String.valueOf(enabled));
                                    break;
                                }
                            }
                            updateClusterAppRefWithInstanceUpdate(t, servr, appName, enabled);
                        }
                        Cluster cluster = dmn.getClusterNamed(target);
                        if (cluster != null) {
                            // update the application-ref from cluster
                            for (ApplicationRef appRef : cluster.getApplicationRef()) {
                                if (appRef.getRef().equals(appName)) {
                                    ConfigBeanProxy appRef_w = t.enroll(appRef);
                                    ((ApplicationRef) appRef_w).setEnabled(String.valueOf(enabled));
                                    break;
                                }
                            }

                            // update the application-ref from cluster instances
                            for (Server svr : cluster.getInstances()) {
                                for (ApplicationRef appRef : svr.getApplicationRef()) {
                                    if (appRef.getRef().equals(appName)) {
                                        ConfigBeanProxy appRef_w = t.enroll(appRef);
                                        ((ApplicationRef) appRef_w).setEnabled(String.valueOf(enabled));
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                return Boolean.TRUE;
            }
        }, domain);
    }

    // check if the application is registered in domain.xml
    @Override
    public boolean isRegistered(String appName) {
        return applications.getApplication(appName) != null;
    }

    @Override
    public ApplicationInfo get(String appName) {
        return appRegistry.get(appName);
    }

    private boolean isPaaSEnabled(Boolean isClassicStyle) {
        if (isClassicStyle) {
            return false;
        }

        if (virtEnv != null && virtEnv.isPaasEnabled()) {
            return true;
        }

        return false;
    }

    // gets the default target when no target is specified for non-paas case
    @Override
    public String getDefaultTarget(Boolean isClassicStyle) {
        if (!isPaaSEnabled(isClassicStyle)) {
            return DeploymentUtils.DAS_TARGET_NAME;
        }
        return null;
    }

    // gets the default target when no target is specified
    @Override
    public String getDefaultTarget(String appName, OpsParams.Origin origin, Boolean isClassicStyle) {
        if (!isPaaSEnabled(isClassicStyle)) {
            return DeploymentUtils.DAS_TARGET_NAME;
        } else {
            // for deploy case, OE will set the deploy target later
            if (origin == OpsParams.Origin.deploy) {
                return null;
            }
            // for other cases, we try to derive it from domain.xml
            List<String> targets = domain.getAllReferencedTargetsForApplication(appName);
            if (targets.size() == 0) {
                throw new IllegalArgumentException("Application not registered");
            }
            if (targets.size() > 1) {
                throw new IllegalArgumentException(
                        "Cannot determine the default target. Please specify an explicit target for the operation.");
            }
            return targets.get(0);
        }
    }

    private void traceContainers(DeploymentTracing tracing, String[] containerNames, DeploymentTracing.ContainerMark containerMark) {
        if (tracing != null) {
            for (String containerName : containerNames) {
                tracing.addContainerMark(containerMark, containerName);
            }
        }
    }

    private void traceContainer(DeploymentTracing tracing, String containerName, DeploymentTracing.ContainerMark containerMark) {
        if (tracing != null) {
            tracing.addContainerMark(containerMark, containerName);
        }
    }

    public class DeploymentContextBuidlerImpl implements DeploymentContextBuilder {
        private final Logger logger;
        private final ActionReport report;
        private final OpsParams params;
        private File sFile;
        private ReadableArchive sArchive;
        private ArchiveHandler handler;

        public DeploymentContextBuidlerImpl(Logger logger, OpsParams params, ActionReport report) {
            this.logger = logger;
            this.report = report;
            this.params = params;
        }

        public DeploymentContextBuidlerImpl(DeploymentContextBuilder b) throws IOException {
            this.logger = b.logger();
            this.report = b.report();
            this.params = b.params();
            ReadableArchive archive = getArchive(b);
            source(archive);
            handler = b.archiveHandler();
        }

        @Override
        public DeploymentContextBuilder source(File source) {
            this.sFile = source;
            return this;
        }

        @Override
        public File sourceAsFile() {
            return sFile;
        }

        @Override
        public ReadableArchive sourceAsArchive() {
            return sArchive;
        }

        @Override
        public ArchiveHandler archiveHandler() {
            return handler;
        }

        @Override
        public DeploymentContextBuilder source(ReadableArchive archive) {
            this.sArchive = archive;
            return this;
        }

        @Override
        public DeploymentContextBuilder archiveHandler(ArchiveHandler handler) {
            this.handler = handler;
            return this;
        }

        @Override
        public ExtendedDeploymentContext build() throws IOException {
            return build(null);
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public ActionReport report() {
            return report;
        }

        @Override
        public OpsParams params() {
            return params;
        }

        @Override
        public ExtendedDeploymentContext build(ExtendedDeploymentContext initialContext) throws IOException {
            return ApplicationLifecycle.this.getContext(initialContext, this);
        }
    }

    @Override
    public DeploymentContextBuilder getBuilder(Logger logger, OpsParams params, ActionReport report) {
        return new DeploymentContextBuidlerImpl(logger, params, report);
    }

    /**
     * Updates the "enabled" setting of the cluster's app ref for the given app if a change to the "enabled" setting of the
     * app ref on one of the cluster's instances implies a cluster-level change.
     * <p>
     * If the app is enabled on any single instance in a cluster then the cluster state needs to be enabled. If the app is
     * disabled on all instances in the cluster then the cluster state should be disabled. This method makes sure the
     * cluster-level app ref enabled state is correct, given the current values of the app refs on the cluster's instances
     * combined with the new value for the specified instance.
     *
     * @param t current config Transaction in progress
     * @param servr the Server for which the app ref has been enabled or disabled
     * @param appName the name of the app whose app ref has been enabled or disabled
     * @param isNewInstanceAppRefStateEnabled whether the new instance app ref state is enabled (false if disabled)
     */
    private void updateClusterAppRefWithInstanceUpdate(final Transaction t, final Server servr, final String appName,
            final boolean isNewInstanceAppRefStateEnabled) throws TransactionFailure, PropertyVetoException {
        final Cluster clusterContainingInstance = servr.getCluster();
        if (clusterContainingInstance != null) {
            /*
             * Update the cluster state also if needed.
             */
            boolean isAppRefEnabledOnAnyClusterInstance = false;
            for (Server inst : clusterContainingInstance.getInstances()) {
                /*
                 * The app ref for the server just changed above still has its old state when fetched using
                 * inst.getApplicationRef(appName). So when we encounter the same server in the list of cluster instances, use the
                 * "enabled" value -- which we just used above to update the app ref for the targeted instance -- below when we need to
                 * consider the "enabled" value for the just-changed instance.
                 */
                isAppRefEnabledOnAnyClusterInstance |= (servr.getName().equals(inst.getName()) ? isNewInstanceAppRefStateEnabled
                        : Boolean.parseBoolean(inst.getApplicationRef(appName).getEnabled()));
            }
            final ApplicationRef clusterAppRef = clusterContainingInstance.getApplicationRef(appName);
            if (Boolean.parseBoolean(clusterAppRef.getEnabled()) != isAppRefEnabledOnAnyClusterInstance) {
                t.enroll(clusterAppRef).setEnabled(String.valueOf(isAppRefEnabledOnAnyClusterInstance));
            }
        }
    }

    // cannot put it on the builder itself since the builder is an official API.
    private ReadableArchive getArchive(DeploymentContextBuilder builder) throws IOException {
        ReadableArchive archive = builder.sourceAsArchive();
        if (archive == null && builder.sourceAsFile() == null) {
            throw new IOException("Source archive or file not provided to builder");
        }
        if (archive == null && builder.sourceAsFile() != null) {
            archive = serviceLocator.<ArchiveFactory>getService(ArchiveFactory.class).openArchive(builder.sourceAsFile());
            if (archive == null) {
                throw new IOException("Invalid archive type : " + builder.sourceAsFile().getAbsolutePath());
            }
        }
        return archive;
    }

    private ExtendedDeploymentContext getContext(ExtendedDeploymentContext initial, DeploymentContextBuilder builder) throws IOException {

        DeploymentContextBuilder copy = new DeploymentContextBuidlerImpl(builder);

        final ReadableArchive archive = getArchive(copy);
        copy.source(archive);

        if (initial == null) {
            initial = new DeploymentContextImpl(copy, env);
        }

        ArchiveHandler archiveHandler = copy.archiveHandler();
        if (archiveHandler == null) {
            String type = null;
            OpsParams params = builder.params();
            if (params != null) {
                if (params instanceof DeployCommandParameters) {
                    type = ((DeployCommandParameters) params).type;
                } else if (params instanceof UndeployCommandParameters) {
                    type = ((UndeployCommandParameters) params)._type;
                }
            }
            archiveHandler = getArchiveHandler(archive, type);
        }

        // this is needed for autoundeploy to find the application
        // with the archive name
        File sourceFile = new File(archive.getURI().getSchemeSpecificPart());
        initial.getAppProps().put(ServerTags.DEFAULT_APP_NAME, DeploymentUtils.getDefaultEEName(sourceFile.getName()));

        if (!(sourceFile.isDirectory())) {

            String repositoryBitName = copy.params().name();
            try {
                repositoryBitName = VersioningUtils.getRepositoryName(repositoryBitName);
            } catch (VersioningSyntaxException e) {
                ActionReport report = copy.report();
                report.setMessage(e.getMessage());
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            }

            // create a temporary deployment context
            File expansionDir = new File(domain.getApplicationRoot(), repositoryBitName);
            if (!expansionDir.mkdirs()) {
                /*
                 * On Windows especially a previous directory might have remainded after an earlier undeployment, for example if a JAR
                 * file in the earlier deployment had been locked. Warn but do not fail in such a case.
                 */
                LOG.fine(localStrings.getLocalString("deploy.cannotcreateexpansiondir",
                        "Error while creating directory for jar expansion: {0}", expansionDir));
            }
            try {
                Long start = System.currentTimeMillis();
                try (WritableArchive expandedArchive = archiveFactory.createArchive(expansionDir)) {
                    archiveHandler.expand(archive, expandedArchive, initial);
                    LOG.fine(() -> "Deployment expansion took " + (System.currentTimeMillis() - start) + " ms");

                    // Close the JAR archive before losing the reference to it or else the JAR remains locked.
                    try {
                        // FIXME: There is no guarantee that the archive will be closed.
                        archive.close();
                    } catch (IOException e) {
                        LOG.log(SEVERE, KernelLoggerInfo.errorClosingArtifact, new Object[]{archive.getURI().getSchemeSpecificPart(), e});
                        throw e;
                    }
                    initial.setSource((ReadableArchive) expandedArchive);
                }
            } catch (IOException e) {
                LOG.log(SEVERE, KernelLoggerInfo.errorExpandingFile, e);
                throw e;
            }
        }
        initial.setArchiveHandler(archiveHandler);
        return initial;
    }

    private void setAppRefAttributes(ApplicationRef appRef, DeployCommandParameters deployParams) throws PropertyVetoException {
        appRef.setRef(deployParams.name);
        if (deployParams.virtualservers != null) {
            appRef.setVirtualServers(deployParams.virtualservers);
        } else {
            // deploy to all virtual-servers, we need to get the list.
            appRef.setVirtualServers(DeploymentUtils.getVirtualServers(deployParams.target, env, domain));
        }
        if (deployParams.lbenabled != null) {
            appRef.setLbEnabled(deployParams.lbenabled);
        } else {
            // check if system property exists and use that
            String lbEnabledDefault = System.getProperty(Server.lbEnabledSystemProperty);
            if (lbEnabledDefault != null) {
                appRef.setLbEnabled(lbEnabledDefault);
            }
        }
        appRef.setEnabled(deployParams.enabled.toString());
    }

    @Override
    public ParameterMap prepareInstanceDeployParamMap(DeploymentContext dc) throws Exception {
        final DeployCommandParameters params = dc.getCommandParameters(DeployCommandParameters.class);
        final Collection<String> excludedParams = new ArrayList<>();
        excludedParams.add(DeploymentProperties.PATH);
        excludedParams.add(DeploymentProperties.DEPLOYMENT_PLAN);
        excludedParams.add(DeploymentProperties.ALT_DD);
        excludedParams.add(DeploymentProperties.RUNTIME_ALT_DD);
        excludedParams.add(DeploymentProperties.UPLOAD); // We'll force it to true ourselves.

        final ParameterMap paramMap;
        final ParameterMapExtractor extractor = new ParameterMapExtractor(params);
        paramMap = extractor.extract(excludedParams);

        prepareGeneratedContent(dc, paramMap);

        // set the path and plan params
        // get the location properties from the application so the token
        // will be resolved
        Application application = applications.getApplication(params.name);
        Properties appProperties = application.getDeployProperties();
        String archiveLocation = appProperties.getProperty(Application.APP_LOCATION_PROP_NAME);
        final File archiveFile = new File(new URI(archiveLocation));
        paramMap.set("DEFAULT", archiveFile.getAbsolutePath());

        String planLocation = appProperties.getProperty(Application.DEPLOYMENT_PLAN_LOCATION_PROP_NAME);
        if (planLocation != null) {
            final File actualPlan = new File(new URI(planLocation));
            paramMap.set(DeployCommandParameters.ParameterNames.DEPLOYMENT_PLAN, actualPlan.getAbsolutePath());
        }

        String altDDLocation = appProperties.getProperty(Application.ALT_DD_LOCATION_PROP_NAME);
        if (altDDLocation != null) {
            final File altDD = new File(new URI(altDDLocation));
            paramMap.set(DeployCommandParameters.ParameterNames.ALT_DD, altDD.getAbsolutePath());
        }

        String runtimeAltDDLocation = appProperties.getProperty(Application.RUNTIME_ALT_DD_LOCATION_PROP_NAME);
        if (runtimeAltDDLocation != null) {
            final File runtimeAltDD = new File(new URI(runtimeAltDDLocation));
            paramMap.set(DeployCommandParameters.ParameterNames.RUNTIME_ALT_DD, runtimeAltDD.getAbsolutePath());
        }

        // always upload the archives to the instance side
        // but not directories. Note that we prepare a zip file containing
        // the generated directories and pass that as a single parameter so it
        // will be uploaded even though a deployment directory is not.
        paramMap.set(DeploymentProperties.UPLOAD, "true");

        // pass the params we restored from the previous deployment in case of
        // redeployment
        if (params.previousContextRoot != null) {
            paramMap.set(DeploymentProperties.PRESERVED_CONTEXT_ROOT, params.previousContextRoot);
        }

        // pass the app props so we have the information to persist in the
        // domain.xml
        Properties appProps = dc.getAppProps();
        appProps.remove(DeploymentProperties.APP_CONFIG);
        paramMap.set(DeploymentProperties.APP_PROPS, extractor.propertiesValue(appProps, ':'));

        Properties previousVirtualServers = dc.getTransientAppMetaData(DeploymentProperties.PREVIOUS_VIRTUAL_SERVERS, Properties.class);
        if (previousVirtualServers != null) {
            paramMap.set(DeploymentProperties.PREVIOUS_VIRTUAL_SERVERS, extractor.propertiesValue(previousVirtualServers, ':'));
        }

        Properties previousEnabledAttributes = dc.getTransientAppMetaData(DeploymentProperties.PREVIOUS_ENABLED_ATTRIBUTES,
                Properties.class);
        if (previousEnabledAttributes != null) {
            paramMap.set(DeploymentProperties.PREVIOUS_ENABLED_ATTRIBUTES, extractor.propertiesValue(previousEnabledAttributes, ':'));
        }

        return paramMap;
    }

    private void prepareGeneratedContent(final DeploymentContext dc, final ParameterMap paramMap) throws IOException {

        /*
         * Create a single ZIP file containing the various generated directories for this app.
         *
         * Note that some deployments - such as of OSGI modules - might not create any generated content.
         */
        final File generatedContentZip = createGeneratedContentZip();


        /*
         * We want the ZIP file to contain xml/(appname), ejb/(appname), etc. directories, even if those directories don't
         * contain anything. Then the instance deploy command can expand the uploaded zip file based at the instance's
         * generated/ directory and the files - including empty directories if appropriate - will be stored in the right places.
         */
        final File baseDir = dc.getScratchDir("xml").getParentFile().getParentFile();

        ZipOutputStream zipOS = null;
        try {
            for (String scratchType : UPLOADED_GENERATED_DIRS) {
                final File genDir = dc.getScratchDir(scratchType);
                if (genDir.isDirectory()) {
                    if (zipOS == null) {
                        zipOS = new ZipOutputStream(
                                new BufferedOutputStream(new FileOutputStream(generatedContentZip)));
                    }
                    addFileToZip(zipOS, baseDir, genDir);
                }
            }
        } finally {
            if (zipOS != null) {
                zipOS.close();
            }
        }

        if (zipOS != null) {
            // Because we did zip up some generated content, add the just-generated zip file as a parameter to the param map.
            // set the generated content param
            paramMap.set("generatedcontent", generatedContentZip.getAbsolutePath());
        }
    }

    private File createGeneratedContentZip() throws IOException {
        final File tempFile = File.createTempFile("gendContent", ".zip");
        tempFile.deleteOnExit();
        return tempFile;
    }

    private void addFileToZip(final ZipOutputStream zipOS, final File baseDir, final File f) throws IOException {
        final String entryName = baseDir.toURI().relativize(f.toURI()).getPath();
        final ZipEntry entry = new ZipEntry(entryName);
        zipOS.putNextEntry(entry);
        if (f.isDirectory()) {
            // A directory entry has no content itself.
            zipOS.closeEntry();
            for (File subFile : f.listFiles()) {
                addFileToZip(zipOS, baseDir, subFile);
            }
        } else {
            FileUtils.copy(f, zipOS);
            zipOS.closeEntry();
        }
    }

    @Override
    public void validateDeploymentTarget(String target, String name, boolean isRedeploy) {
        List<String> referencedTargets = domain.getAllReferencedTargetsForApplication(name);
        if (referencedTargets.isEmpty()) {
            if (isRegistered(name)) {
                if (!isRedeploy && DeploymentUtils.isDomainTarget(target)) {
                    throw new IllegalArgumentException(localStrings.getLocalString("application.alreadyreg.redeploy",
                            "Application with name {0} is already registered. Either specify that redeployment must be forced, or redeploy the application. Or if this is a new deployment, pick a different name.",
                            name));
                } else {
                    if (!DeploymentUtils.isDomainTarget(target)) {
                        throw new IllegalArgumentException(localStrings.getLocalString("use.create_app_ref_2",
                                "Application {0} is already deployed in this domain. Please use create application ref to create application reference on target {1}.",
                                name, target));
                    }
                }
            }
            return;
        }
        if (!isRedeploy) {
            if (DeploymentUtils.isDomainTarget(target)) {
                throw new IllegalArgumentException(localStrings.getLocalString("application.deploy_domain",
                        "Application with name {0} is already referenced by other target(s). Please specify force option to redeploy to domain.",
                        name));
            }
            if (referencedTargets.size() == 1 && referencedTargets.contains(target)) {
                throw new IllegalArgumentException(localStrings.getLocalString("application.alreadyreg.redeploy",
                        "Application with name {0} is already registered. Either specify that redeployment must be forced, or redeploy the application. Or if this is a new deployment, pick a different name.",
                        name));
            } else {
                throw new IllegalArgumentException(localStrings.getLocalString("use.create_app_ref",
                        "Application {0} is already referenced by other target(s). Please use create application ref to create application reference on target {1}.",
                        name, target));
            }
        } else {
            if (referencedTargets.size() == 1 && referencedTargets.contains(target)) {
                return;
            } else {
                if (!DeploymentUtils.isDomainTarget(target)) {
                    throw new IllegalArgumentException(localStrings.getLocalString("redeploy_on_multiple_targets",
                            "Application {0} is referenced by more than one targets. Please remove other references or specify all targets (or domain target if using asadmin command line) before attempting redeploy operation.",
                            name));
                }
            }
        }
    }

    @Override
    public void validateUndeploymentTarget(String target, String name) {
        List<String> referencedTargets = domain.getAllReferencedTargetsForApplication(name);
        if (referencedTargets.size() > 1) {
            Application app = applications.getApplication(name);
            if (!DeploymentUtils.isDomainTarget(target)) {
                if (app.isLifecycleModule()) {
                    throw new IllegalArgumentException(localStrings.getLocalString("delete_lifecycle_on_multiple_targets",
                            "Lifecycle module {0} is referenced by more than one targets. Please remove other references before attempting delete operation.",
                            name));
                } else {
                    throw new IllegalArgumentException(localStrings.getLocalString("undeploy_on_multiple_targets",
                            "Application {0} is referenced by more than one targets. Please remove other references or specify all targets (or domain target if using asadmin command line) before attempting undeploy operation.",
                            name));
                }
            }
        }
    }

    @Override
    public void validateSpecifiedTarget(String target) {
        if (env.isDas()) {
            if (target == null) {
                // we only validate the specified target
                return;
            }
            Cluster cluster = domain.getClusterNamed(target);
            if (cluster != null) {
                if (cluster.isVirtual()) {
                    throw new IllegalArgumentException(localStrings.getLocalString("cannot_specify_managed_target",
                            "Cannot specify target {0} for the operation. Target {0} is a managed target.", target));
                }
            }
        }
    }

    @Override
    public boolean isAppEnabled(Application app) {
        if (Boolean.parseBoolean(app.getEnabled())) {
            ApplicationRef appRef = server.getApplicationRef(app.getName());
            if (appRef != null && Boolean.valueOf(appRef.getEnabled())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ExtendedDeploymentContext disable(UndeployCommandParameters commandParams, Application app, ApplicationInfo appInfo,
            ActionReport report, Logger logger) throws Exception {
        // if it's not on DAS and the application is not loaded, do not unload
        // when it's on DAS, there is some necessary clean up we need to do
        if ((appInfo == null) || (!env.isDas() && !appInfo.isLoaded())) {
            return null;
        }

        if (app != null) {
            commandParams._type = app.archiveType();
        }

        final ExtendedDeploymentContext deploymentContext = getBuilder(logger, commandParams, report).source(appInfo.getSource()).build();

        if (app != null) {
            deploymentContext.getAppProps().putAll(app.getDeployProperties());
            deploymentContext.setModulePropsMap(app.getModulePropertiesMap());
        }

        if (commandParams.properties != null) {
            deploymentContext.getAppProps().putAll(commandParams.properties);
        }

        unload(appInfo, deploymentContext);
        return deploymentContext;
    }

    @Override
    public ExtendedDeploymentContext enable(String target, Application app, ApplicationRef appRef, ActionReport report, Logger logger)
            throws Exception {
        ReadableArchive archive = null;
        try {
            DeployCommandParameters commandParams = app.getDeployParameters(appRef);
            // if the application is already loaded, do not load again
            ApplicationInfo appInfo = appRegistry.get(commandParams.name);
            if (appInfo != null && appInfo.isLoaded()) {
                return null;
            }
            commandParams.origin = DeployCommandParameters.Origin.load;
            commandParams.command = DeployCommandParameters.Command.enable;
            commandParams.target = target;
            commandParams.enabled = Boolean.TRUE;
            Properties contextProps = app.getDeployProperties();
            Map<String, Properties> modulePropsMap = app.getModulePropertiesMap();
            ApplicationConfigInfo savedAppConfig = new ApplicationConfigInfo(app);
            URI uri = new URI(app.getLocation());
            File file = new File(uri);

            if (!file.exists()) {
                throw new Exception(localStrings.getLocalString("fnf", "File not found {0}", file.getAbsolutePath()));
            }

            archive = archiveFactory.openArchive(file);

            final ExtendedDeploymentContext deploymentContext = getBuilder(logger, commandParams, report).source(archive).build();

            Properties appProps = deploymentContext.getAppProps();
            appProps.putAll(contextProps);
            savedAppConfig.store(appProps);

            if (modulePropsMap != null) {
                deploymentContext.setModulePropsMap(modulePropsMap);
            }

            deploy(getSniffersFromApp(app), deploymentContext);
            return deploymentContext;
        } finally {
            try {
                if (archive != null) {
                    archive.close();
                }
            } catch (IOException ioe) {
                // ignore
            }
        }
    }

    private boolean loadOnCurrentInstance(DeploymentContext context) {
        final DeployCommandParameters commandParams = context.getCommandParameters(DeployCommandParameters.class);
        final Properties appProps = context.getAppProps();
        if (commandParams.enabled) {
            // if the current instance match with the target
            if (domain.isCurrentInstanceMatchingTarget(commandParams.target, commandParams.name(), server.getName(),
                    context.getTransientAppMetaData(DeploymentProperties.PREVIOUS_TARGETS, List.class))) {
                return true;
            }
            if (server.isDas()) {
                String objectType = appProps.getProperty(ServerTags.OBJECT_TYPE);
                if (objectType != null) {
                    // if it's a system application needs to be loaded on DAS
                    if (objectType.equals(DeploymentProperties.SYSTEM_ADMIN) || objectType.equals(DeploymentProperties.SYSTEM_ALL)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String getApplicationType(ApplicationInfo appInfo) {
        StringBuffer sb = new StringBuffer();
        if (appInfo.getSniffers().size() > 0) {
            for (Sniffer sniffer : appInfo.getSniffers()) {
                if (sniffer.isUserVisible()) {
                    sb.append(sniffer.getModuleType() + ", ");
                }
            }
        }
        if (sb.length() > 2) {
            return sb.substring(0, sb.length() - 2);
        }
        return sb.toString();
    }

    @Override
    public List<Sniffer> getSniffersFromApp(Application app) {
        List<String> snifferTypes = new ArrayList<>();
        for (com.sun.enterprise.config.serverbeans.Module module : app.getModule()) {
            for (Engine engine : module.getEngines()) {
                snifferTypes.add(engine.getSniffer());
            }
        }

        if (snifferTypes.isEmpty()) {
            // for the upgrade scenario, we cannot get the sniffers from the
            // domain.xml, so we need to re-process it during deployment
            return null;
        }

        List<Sniffer> sniffers = new ArrayList<>();
        if (app.isStandaloneModule()) {
            for (String snifferType : snifferTypes) {
                Sniffer sniffer = snifferManager.getSniffer(snifferType);
                if (sniffer != null) {
                    sniffers.add(sniffer);
                } else {
                    LOG.log(SEVERE, KernelLoggerInfo.cantFindSniffer, snifferType);
                }
            }
            if (sniffers.isEmpty()) {
                LOG.log(SEVERE, KernelLoggerInfo.cantFindSnifferForApp, app.getName());
                return null;
            }
        } else {
            // todo, this is a cludge to force the reload and reparsing of the
            // composite application.
            return null;
        }

        return sniffers;
    }

    private ExecutorService createExecutorService() {
        Runtime runtime = Runtime.getRuntime();
        int nrOfProcessors = runtime.availableProcessors();
        return Executors.newFixedThreadPool(nrOfProcessors, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("deployment-jar-scanner");
                t.setContextClassLoader(getClass().getClassLoader());
                t.setDaemon(true);
                return t;
            }
        });
    }

}
