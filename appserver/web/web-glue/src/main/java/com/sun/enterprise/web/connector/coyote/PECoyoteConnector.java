/*
 * Copyright (c) 2024, 2025 Contributors to the Eclipse Foundation.
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

package com.sun.enterprise.web.connector.coyote;

import com.sun.appserv.ProxyHandler;
import com.sun.enterprise.config.serverbeans.ConfigBeansUtilities;
import com.sun.enterprise.config.serverbeans.HttpService;
import com.sun.enterprise.web.WebContainer;
import com.sun.enterprise.web.WebModule;
import com.sun.enterprise.web.connector.MapperListener;
import com.sun.enterprise.web.connector.extension.GrizzlyConfig;
import com.sun.enterprise.web.connector.grizzly.DummyConnectorLauncher;
import com.sun.enterprise.web.pwc.connector.coyote.PwcCoyoteRequest;

import jakarta.servlet.http.HttpServletRequest;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.Notification;

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Request;
import org.apache.catalina.Response;
import org.apache.catalina.connector.Connector;
import org.glassfish.grizzly.config.dom.FileCache;
import org.glassfish.grizzly.config.dom.Http;
import org.glassfish.grizzly.config.dom.NetworkListener;
import org.glassfish.grizzly.config.dom.Ssl;
import org.glassfish.grizzly.config.dom.ThreadPool;
import org.glassfish.grizzly.config.dom.Transport;
import org.glassfish.security.common.CipherInfo;
import org.glassfish.web.LogFacade;
import org.glassfish.web.admin.monitor.RequestProbeProvider;
import org.glassfish.web.util.IntrospectionUtils;

import static com.sun.enterprise.util.SystemPropertyConstants.KEYSTORE_TYPE_DEFAULT;
import static org.glassfish.embeddable.GlassFishVariable.KEYSTORE_FILE;
import static org.glassfish.embeddable.GlassFishVariable.KEYSTORE_TYPE;
import static org.glassfish.embeddable.GlassFishVariable.TRUSTSTORE_FILE;
import static org.glassfish.embeddable.GlassFishVariable.TRUSTSTORE_TYPE;

public class PECoyoteConnector extends Connector {

    private static final String DUMMY_CONNECTOR_LAUNCHER = DummyConnectorLauncher.class.getName();

    protected static final Logger _logger = LogFacade.getLogger();

    protected static final ResourceBundle _rb = _logger.getResourceBundle();

    /**
     * Are we recycling objects
     */
    protected boolean recycleObjects;


     /**
     * The number of acceptor threads.
     */
    protected int maxAcceptWorkerThreads;


    /**
     * The number of reader threads.
     */
    protected int maxReadWorkerThreads;


    /**
     * The request timeout value used by the processor threads.
     */
    protected int processorWorkerThreadsTimeout;


    /**
     * The increment number used by the processor threads.
     *
    protected int minProcessorWorkerThreadsIncrement;
     */


    /**
     * The size of the accept queue.
     */
    protected int minAcceptQueueLength;


    /**
     * The size of the read queue
     */
    protected int minReadQueueLength;


    /**
     * The size of the processor queue.
     */
    protected int minProcessorQueueLength;


    /**
     * Use direct or non direct byte buffer.
     */
    protected boolean useDirectByteBuffer;


    // Are we using the NIO Connector or the CoyoteConnector
    //private boolean coyoteOn = false;

    /*
     * Number of seconds before idle keep-alive connections expire
     */
    private int keepAliveTimeoutInSeconds;

    /*
     * Number of keep-alive threads
     */
    private int keepAliveThreadCount;

    /**
     * Maximum pending connection before refusing requests.
     */
    private int queueSizeInBytes = 4096;

    /**
     * Server socket backlog.
     */
    protected int ssBackLog = 4096;

    /**
     * Set the number of <code>Selector</code> used by Grizzly.
     */
    private int selectorReadThreadsCount = 0;

    /**
     * The monitoring classes used to gather stats.
     */
    protected GrizzlyConfig grizzlyMonitor;

    /**
     * The root folder where application are deployed
     */
    private String rootFolder = "";

    /**
     * Mapper listener.
     */
    protected MapperListener mapperListener;


    // --------------------------------------------- FileCache support --//

    /**
     * Timeout before remove the static resource from the cache.
     */
    private int secondsMaxAge = -1;

    /**
     * The maximum entries in the <code>fileCache</code>
     */
    private int maxCacheEntries = 1024;

    /**
     * The maximum size of a cached resources.
     */
    private long minEntrySize = 2048;

    /**
     * The maximum size of a cached resources.
     */
    private long maxEntrySize = 537600;

    /**
     * The maximum cached bytes
     */
    private long maxLargeFileCacheSize = 10485760;

    /**
     * The maximum cached bytes
     */
    private long maxSmallFileCacheSize = 1048576;

    /**
     * Is the FileCache enabled.
     */
    private boolean fileCacheEnabled = true;

    /**
     * Is the large FileCache enabled.
     */
    private boolean isLargeFileCacheEnabled = true;

    /**
     * Location of the CRL file
     */
    private String crlFile;

    /**
     * The trust management algorithm
     */
    private String trustAlgorithm;

    /**
     * The maximum number of non-self-issued intermediate
     * certificates that may exist in a certification path
     */
    private String trustMaxCertLength;

    private final WebContainer webContainer;

    private final RequestProbeProvider requestProbeProvider;


    /**
     * Constructor
     */
    public PECoyoteConnector(WebContainer webContainer) {
        this.webContainer = webContainer;
        requestProbeProvider = webContainer.getRequestProbeProvider();
        setProtocolHandlerClassName(DUMMY_CONNECTOR_LAUNCHER);
    }


    /**
     * Create (or allocate) and return a Request object suitable for
     * specifying the contents of a Request to the responsible ContractProvider.
     */
    @Override
    public Request createRequest() {

        PwcCoyoteRequest request = new PwcCoyoteRequest();
        request.setConnector(this);
        return request;
    }


    /**
     * Creates and returns Response object.
     *
     * @return Response object
     */
    @Override
    public Response createResponse() {

        PECoyoteResponse response = new PECoyoteResponse();
        response.setConnector(this);
        return response;

    }


    /**
     * Gets the number of seconds before a keep-alive connection that has
     * been idle times out and is closed.
     *
     * @return Keep-alive timeout in number of seconds
     */
    public int getKeepAliveTimeoutInSeconds() {
        return keepAliveTimeoutInSeconds;
    }


    /**
     * Sets the number of seconds before a keep-alive connection that has
     * been idle times out and is closed.
     *
     * @param timeout Keep-alive timeout in number of seconds
     */
    public void setKeepAliveTimeoutInSeconds(int timeout) {
        keepAliveTimeoutInSeconds = timeout;
        setProperty("keepAliveTimeoutInSeconds", String.valueOf(timeout));
    }


    /**
     * Gets the number of keep-alive threads.
     *
     * @return Number of keep-alive threads
     */
    public int getKeepAliveThreadCount() {
        return keepAliveThreadCount;
    }

    /**
     * Sets the number of keep-alive threads
     *
     * @param number Number of keep-alive threads
     */
    public void setKeepAliveThreadCount(int number) {
        keepAliveThreadCount = number;
        setProperty("KeepAliveThreadCount", String.valueOf(number));
    }

    /**
     * Set the maximum pending connection this <code>Connector</code>
     * can handle.
     */
    public void setQueueSizeInBytes(int queueSizeInBytes){
        this.queueSizeInBytes = queueSizeInBytes;
        setProperty("queueSizeInBytes", String.valueOf(queueSizeInBytes));
    }


    /**
     * Return the maximum pending connection.
     */
    public int getQueueSizeInBytes(){
        return queueSizeInBytes;
    }


    /**
     * Set the <code>SocketServer</code> backlog.
     */
    public void setSocketServerBacklog(int ssBackLog){
        this.ssBackLog = ssBackLog;
        setProperty("socketServerBacklog", String.valueOf(ssBackLog));
    }


    /**
     * Return the maximum pending connection.
     */
    public int getSocketServerBacklog(){
        return ssBackLog;
    }


    /**
     * Set the <code>recycle-tasks</code> used by this <code>Selector</code>
     */
    public void setRecycleObjects(boolean recycleObjects){
        this.recycleObjects= recycleObjects;
        setProperty("recycleObjects",
                    String.valueOf(recycleObjects));
    }


    /**
     * Return the <code>recycle-tasks</code> used by this
     * <code>Selector</code>
     */
    public boolean getRecycleObjects(){
        return recycleObjects;
    }


    /**
     * Set the <code>reader-thread</code> from domian.xml.
     */
    public void setMaxReadWorkerThreads(int maxReadWorkerThreads){
        this.maxReadWorkerThreads = maxReadWorkerThreads;
        setProperty("maxReadWorkerThreads",
                    String.valueOf(maxReadWorkerThreads));
    }


    /**
     * Return the <code>read-thread</code> used by this <code>Selector</code>
     */
    public int getMaxReadWorkerThreads(){
        return maxReadWorkerThreads;
    }


    /**
     * Set the <code>reader-thread</code> from domian.xml.
     */
    public void setMaxAcceptWorkerThreads(int maxAcceptWorkerThreads){
        this.maxAcceptWorkerThreads = maxAcceptWorkerThreads;
        setProperty("maxAcceptWorkerThreads",
                    String.valueOf(maxAcceptWorkerThreads));
    }


    /**
     * Return the <code>read-thread</code> used by this <code>Selector</code>
     */
    public int getMaxAcceptWorkerThreads(){
        return maxAcceptWorkerThreads;
    }


    /**
     * Set the <code>acceptor-queue-length</code> value
     * on this <code>Selector</code>
     */
    public void setMinAcceptQueueLength(int minAcceptQueueLength){
        this.minAcceptQueueLength = minAcceptQueueLength;
        setProperty("minAcceptQueueLength",
                    String.valueOf(minAcceptQueueLength));
    }


    /**
     * Return the <code>acceptor-queue-length</code> value
     * on this <code>Selector</code>
     */
    public int getMinAcceptQueueLength(){
        return minAcceptQueueLength;
    }


    /**
     * Set the <code>reader-queue-length</code> value
     * on this <code>Selector</code>
     */
    public void setMinReadQueueLength(int minReadQueueLength){
        this.minReadQueueLength = minReadQueueLength;
        setProperty("minReadQueueLength",
                    String.valueOf(minReadQueueLength));
    }


    /**
     * Return the <code>reader-queue-length</code> value
     * on this <code>Selector</code>
     */
    public int getMinReadQueueLength(){
        return minReadQueueLength;
    }


    /**
     * Set the <code>processor-queue-length</code> value
     * on this <code>Selector</code>
     */
    public void setMinProcessorQueueLength(int minProcessorQueueLength){
        this.minProcessorQueueLength = minProcessorQueueLength;
        setProperty("minProcessorQueueLength",
                    String.valueOf(minProcessorQueueLength));
    }


    /**
     * Return the <code>processor-queue-length</code> value
     * on this <code>Selector</code>
     */
    public int getMinProcessorQueueLength(){
        return minProcessorQueueLength;
    }


    /**
     * Set the <code>use-nio-non-blocking</code> by this <code>Selector</code>
     */
    public void setUseDirectByteBuffer(boolean useDirectByteBuffer){
        this.useDirectByteBuffer = useDirectByteBuffer;
        setProperty("useDirectByteBuffer",
                    String.valueOf(useDirectByteBuffer));
    }


    /**
     * Return the <code>use-nio-non-blocking</code> used by this
     * <code>Selector</code>
     */
    public boolean getUseDirectByteBuffer(){
        return useDirectByteBuffer;
    }

    public void setProcessorWorkerThreadsTimeout(int timeout){
        processorWorkerThreadsTimeout = timeout;
        setProperty("processorWorkerThreadsTimeout",
                    String.valueOf(timeout));
    }

    public int getProcessorWorkerThreadsTimeout(){
        return processorWorkerThreadsTimeout;
    }

    /*public int getMinProcessorWorkerThreadsIncrement(){
        return minProcessorWorkerThreadsIncrement;
    }*/

    public void setSelectorReadThreadsCount(int selectorReadThreadsCount) {
        this.selectorReadThreadsCount = selectorReadThreadsCount;
        setProperty("selectorReadThreadsCount",
                    String.valueOf(selectorReadThreadsCount));
    }

    public int getSelectorReadThreadsCount() {
        return selectorReadThreadsCount;
    }

    @Override
    public void start() throws LifecycleException {
        super.start();

        if( this.domain != null ) {
            if (!"admin-listener".equals(getName())) {
                // See IT 8255
                mapper.removeContext(getDefaultHost(), "");
                mapper.removeHost(getDefaultHost());
            }
            mapperListener.setDomain(domain);
            // BEGIN S1AS 5000999
            mapperListener.setNetworkListenerName(this.getName());
            mapperListener.setDefaultHost(getDefaultHost());
            // END S1AS 5000999
            //mapperListener.setEngine( service.getContainer().getName() );
            mapperListener.setInstanceName(getInstanceName());
            mapperListener.init();
            getService().getBroadcaster().addNotificationListener(mapperListener, mapperListener, null);
            Notification notification =
                    new Notification("chloe", this.getObjectName(), 0);
            getService().getBroadcaster().sendNotification(notification);
        }
        if ( grizzlyMonitor != null ) {
            grizzlyMonitor.initConfig();
            grizzlyMonitor.registerMonitoringLevelEvents();
        }
    }

    @Override
    public void stop() throws LifecycleException {
        super.stop();
        if ( grizzlyMonitor != null ) {
            grizzlyMonitor.destroy();
            grizzlyMonitor=null;
        }
    }

    //------------------------------------------------- FileCache config -----/

    /**
     * The timeout in seconds before remove a <code>FileCacheEntry</code>
     * from the <code>fileCache</code>
     */
    public void setSecondsMaxAge(int sMaxAges) {
        secondsMaxAge = sMaxAges;
        setProperty("secondsMaxAge", String.valueOf(secondsMaxAge));
    }

    public int getSecondsMaxAge() {
        return secondsMaxAge;
    }

    /**
     * Set the maximum entries this cache can contains.
     */
    public void setMaxCacheEntries(int mEntries){
        maxCacheEntries = mEntries;
        setProperty("maxCacheEntries", String.valueOf(maxCacheEntries));
    }

    /**
     * Return the maximum entries this cache can contains.
     */
    public int getMaxCacheEntries(){
        return maxCacheEntries;
    }


    /**
     * Set the maximum size a <code>FileCacheEntry</code> can have.
     */
    public void setMinEntrySize(long mSize){
        minEntrySize = mSize;
        setProperty("minEntrySize", String.valueOf(minEntrySize));
    }


    /**
     * Get the maximum size a <code>FileCacheEntry</code> can have.
     */
    public long getMinEntrySize(){
        return minEntrySize;
    }


    /**
     * Set the maximum size a <code>FileCacheEntry</code> can have.
     */
    public void setMaxEntrySize(long mEntrySize){
        maxEntrySize = mEntrySize;
        setProperty("maxEntrySize", String.valueOf(maxEntrySize));
    }


    /**
     * Get the maximum size a <code>FileCacheEntry</code> can have.
     */
    public long getMaxEntrySize(){
        return maxEntrySize;
    }


    /**
     * Set the maximum cache size
     */
    public void setMaxLargeCacheSize(long mCacheSize){
        maxLargeFileCacheSize = mCacheSize;
        setProperty("maxLargeFileCacheSize",
                String.valueOf(maxLargeFileCacheSize));
    }


    /**
     * Get the maximum cache size
     */
    public long getMaxLargeCacheSize(){
        return maxLargeFileCacheSize;
    }


    /**
     * Set the maximum cache size
     */
    public void setMaxSmallCacheSize(long mCacheSize){
        maxSmallFileCacheSize = mCacheSize;
        setProperty("maxSmallFileCacheSize",
                String.valueOf(maxSmallFileCacheSize));
    }


    /**
     * Get the maximum cache size
     */
    public long getMaxSmallCacheSize(){
        return maxSmallFileCacheSize;
    }


    /**
     * Is the fileCache enabled.
     */
    public boolean isFileCacheEnabled(){
        return fileCacheEnabled;
    }


    /**
     * Is the file caching mechanism enabled.
     */
    public void setFileCacheEnabled(boolean fileCacheEnabled){
        this.fileCacheEnabled = fileCacheEnabled;
        setProperty("fileCacheEnabled",String.valueOf(fileCacheEnabled));
    }


    /**
     * Is the large file cache support enabled.
     */
    public void setLargeFileCacheEnabled(boolean isLargeEnabled){
        isLargeFileCacheEnabled = isLargeEnabled;
        setProperty("largeFileCacheEnabled",
                String.valueOf(isLargeFileCacheEnabled));
    }


    /**
     * Is the large file cache support enabled.
     */
    public boolean getLargeFileCacheEnabled(){
        return isLargeFileCacheEnabled;
    }

    // --------------------------------------------------------------------//


    /**
     * Set the documenr root folder
     */
    public void setWebAppRootPath(String rootFolder){
        this.rootFolder = rootFolder;
        setProperty("webAppRootPath",rootFolder);
    }


    /**
     * Return the folder's root where application are deployed.
     */
    public String getWebAppRootPath(){
        return rootFolder;
    }


    /**
     * Initialize this connector.
     */
    @Override
    public void initialize() throws LifecycleException {
        super.initialize();
        mapperListener = new MapperListener(mapper, webContainer);
        // Set the monitoring.
        grizzlyMonitor = new GrizzlyConfig(webContainer, domain, getPort());
    }


    /**
     * Sets the truststore location of this connector.
     *
     * @param truststore The truststore location
     */
    public void setTruststore(String truststore) {
        setProperty("truststore", truststore);
    }


    /**
     * Gets the truststore location of this connector.
     *
     * @return The truststore location
     */
    public String getTruststore() {
        return getProperty("truststore");
    }


    /**
     * Sets the truststore type of this connector.
     *
     * @param type The truststore type
     */
    public void setTruststoreType(String type) {
        setProperty("truststoreType", type);
    }


    /**
     * Gets the truststore type of this connector.
     *
     * @return The truststore type
     */
    public String getTruststoreType() {
        return getProperty("truststoreType");
    }


    /**
     * Sets the keystore type of this connector.
     *
     * @param type The keystore type
     */
    public void setKeystoreType(String type) {
        setProperty("keystoreType", type);
    }


    /**
     * Gets the keystore type of this connector.
     *
     * @return The keystore type
     */
    public String getKeystoreType() {
        return getProperty("keystoreType");
    }


    /**
     * Gets the location of the CRL file
     *
     * @return The location of the CRL file
     */
    public String getCrlFile() {
         return crlFile;
    }


    /**
     * Sets the location of the CRL file.
     *
     * @param crlFile The location of the CRL file
     */
    public void setCrlFile(String crlFile) {
        this.crlFile = crlFile;
        setProperty("crlFile", crlFile);
    }


    /**
     * Gets the trust management algorithm
     *
     * @return The trust management algorithm
     */
    public String getTrustAlgorithm() {
         return trustAlgorithm;
    }


    /**
     * Sets the trust management algorithm
     *
     * @param trustAlgorithm The trust management algorithm
     */
    public void setTrustAlgorithm(String trustAlgorithm) {
        this.trustAlgorithm = trustAlgorithm;
        setProperty("truststoreAlgorithm", trustAlgorithm);
    }


    /**
     * Gets the maximum number of non-self-issued intermediate
     * certificates that may exist in a certification path
     *
     * @return The maximum number of non-self-issued intermediate
     * certificates that may exist in a certification path
     */
    public String getTrustMaxCertLength() {
         return trustMaxCertLength;
    }


    /**
     * Sets the maximum number of non-self-issued intermediate
     * certificates that may exist in a certification path
     *
     * @param trustMaxCertLength The maximum number of non-self-issued
     * intermediate certificates that may exist in a certification path
     */
    public void setTrustMaxCertLength(String trustMaxCertLength) {
        this.trustMaxCertLength = trustMaxCertLength;
        setProperty("trustMaxCertLength", trustMaxCertLength);
    }

    /**
     * Gets the MapperListener of this connector.
     *
     * @return The MapperListener of this connector
     */
    public MapperListener getMapperListener() {
        return mapperListener;
    }


    /*
     * Configures this connector.
     *
     * @param listener The http-listener that corresponds to the given
     * connector
     * @param isSecure true if the connector is security-enabled, false
     * otherwise
     * @param httpServiceProps The http-service properties
     */
    public void configure(NetworkListener listener, boolean isSecure,
        HttpService httpService) {

        final Transport transport = listener.findTransport();
        try {
            setSocketServerBacklog(
                Integer.parseInt(transport.getMaxConnectionsCount()));
        } catch (NumberFormatException ex) {
            String msg = MessageFormat.format(
                _rb.getString(LogFacade.INVALID_MAX_PENDING_COUNT), transport.getMaxConnectionsCount(),
                Integer.toString(getSocketServerBacklog()));
            _logger.log(Level.WARNING, msg, ex);
        }

        setDomain(webContainer.getServerContext().getDefaultDomainName());

        configureSSL(listener);
        configureThreadPool(listener.findThreadPool());

        final Http http = listener.findHttpProtocol().getHttp();

        configureFileCache(http.getFileCache());
        setMaxHttpHeaderSize(Integer.parseInt(http.getSendBufferSizeBytes()));
        setDefaultHost(http.getDefaultVirtualServer());
        setEnableLookups(ConfigBeansUtilities.toBoolean(http.getDnsLookupEnabled()));

        setXpoweredBy(Boolean.parseBoolean(http.getXpoweredBy()));

        // Application root
        setWebAppRootPath(webContainer.getModulesRoot().getAbsolutePath());

        // server-name (may contain scheme and colon-separated port number)
        String serverName = http.getServerName();
        setProxyAttributesFromServerName(serverName, listener);

        // redirect-port
        String redirectPort = http.getRedirectPort();
        if (redirectPort != null && redirectPort.length() != 0) {
            try {
                setRedirectPort(Integer.parseInt(redirectPort));
            } catch (NumberFormatException nfe) {
                _logger.log(Level.WARNING,
                    LogFacade.INVALID_REDIRECT_PORT,
                    new Object[] {
                        redirectPort,
                        listener.getName(),
                        Integer.toString(getRedirectPort()) });
            }
        } else {
            setRedirectPort(-1);
        }

        // acceptor-threads
        String acceptorThreads = transport.getAcceptorThreads();
        if (acceptorThreads != null) {
            try {
                setSelectorReadThreadsCount(Integer.parseInt(
                    acceptorThreads));
            } catch (NumberFormatException nfe) {
                _logger.log(Level.WARNING,
                    LogFacade.INVALID_ACCEPTOR_THREADS,
                    new Object[] {
                        acceptorThreads,
                        listener.getName(),
                        Integer.toString(getMaxProcessors()) });
            }
        }

        // Configure Connector with keystore password and location
        if (isSecure) {
            configureKeysAndCerts();
        }

        webContainer.configureHttpServiceProperties(httpService, this);

        // Overrided http-service property if defined.
        configureHttpListenerProperties(listener);
    }

    private void setProxyAttributesFromServerName(String serverName, NetworkListener listener) {
        if (serverName != null && serverName.length() > 0) {
            // Split serverName into scheme, serverName, and port number
            String scheme = null;
            if (serverName.startsWith("http://")) {
                scheme = "http";
                serverName = serverName.substring("http://".length());
            } else if (serverName.startsWith("https://")) {
                scheme = "https";
                serverName = serverName.substring("https://".length());
            }
            setProxyScheme(scheme);
            int index = serverName.indexOf(':');
            if (index != -1) {
                setProxyName(serverName.substring(0, index).trim());
                String serverPort = serverName.substring(index+1).trim();
                setProxyPort(serverPort, listener);
            } else {
                setProxyName(serverName);
            }
        }
    }

    private void setProxyPort(String serverPort, NetworkListener listener) {
        if (serverPort.length() > 0) {
            try {
                setProxyPort(Integer.parseInt(serverPort));
            } catch (NumberFormatException nfe) {
                _logger.log(Level.SEVERE,
                        LogFacade.INVALID_PROXY_PORT,
                        new Object[] { serverPort, listener.getName() });
            }
        }
    }


    /*
     * Configures this connector for modjk.
     */
    public void configureJKProperties(final NetworkListener listener) {

        File propertiesFile = null;
        if (listener != null) {
            propertiesFile = new File(listener.getJkConfigurationFile());
        }
        String propertyFile = System.getProperty("com.sun.enterprise.web.connector.enableJK.propertyFile");
        if (propertiesFile!=null && !propertiesFile.exists() && propertyFile!=null) {
            propertiesFile   = new File(propertyFile);
        }
        if (propertiesFile==null) {
            if (_logger.isLoggable(Level.FINEST)) {
                _logger.log(Level.FINEST, LogFacade.JK_PROPERTIES_NOT_DEFINED);
            }
            return;
        }

        if (!propertiesFile.exists()) {
            _logger.log(Level.WARNING,
                    MessageFormat.format(_rb.getString("pewebcontainer.missingJKProperties"),
                            propertiesFile.getAbsolutePath()));
            return;
        }

        if (_logger.isLoggable(Level.FINEST)) {
            _logger.log(Level.FINEST,
                    MessageFormat.format(_rb.getString(LogFacade.LOADING_JK_PROPERTIED),
                            propertiesFile.getAbsolutePath()));
        }

        Properties properties = null;

        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(propertiesFile));
            properties = new Properties();
            properties.load(is);
        } catch (Exception ex) {
            String msg = MessageFormat.format(_rb.getString(LogFacade.UNABLE_TO_CONFIGURE_JK), new Object[]{propertiesFile, getPort()});
            _logger.log(Level.SEVERE, msg, ex);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ioe) {}
            }
        }

        Enumeration enumeration = properties.keys();
        while (enumeration.hasMoreElements()) {
            String propName = (String) enumeration.nextElement();
            String value = properties.getProperty(propName);
            if (value != null) {
                IntrospectionUtils.setProperty(this, propName, value);
            }
        }
    }

    /**
     * Configure the Grizzly FileCache mechanism
     * @param fileCache
     */
    public void configureFileCache(FileCache fileCache) {
        if (fileCache != null) {
            setLargeFileCacheEnabled(ConfigBeansUtilities.toBoolean(
                fileCache.getEnabled()));
            if (fileCache.getMaxAgeSeconds() != null) {
                setSecondsMaxAge(Integer.parseInt(fileCache.getMaxAgeSeconds()));
            }
            if (fileCache.getMaxFilesCount() != null) {
                setMaxCacheEntries(Integer.parseInt(
                    fileCache.getMaxFilesCount()));
            }
            if (fileCache.getMaxCacheSizeBytes() != null) {
                setMaxLargeCacheSize(Integer.parseInt(fileCache.getMaxCacheSizeBytes()));
            }
        }
    }


    /**
     * Configures this connector from the given thread-pool
     * configuration bean.
     *
     * @param pool the thread-pool configuration bean
     */
    public void configureThreadPool(ThreadPool pool){
        if (pool != null) {
            try {
                setMaxProcessors(Integer.parseInt(
                    pool.getMaxThreadPoolSize()));
            } catch (NumberFormatException ex) {
                String msg = MessageFormat.format(_rb.getString(LogFacade.INVALID_THREAD_POOL_ATTRIBUTE), "max-thread-pool-size");
                _logger.log(Level.WARNING, msg, ex);
            }
            try {
                setMinProcessors(Integer.parseInt(
                    pool.getMinThreadPoolSize()));
            } catch (NumberFormatException ex) {
                String msg = MessageFormat.format(_rb.getString(LogFacade.INVALID_THREAD_POOL_ATTRIBUTE), "min-thread-pool-size");
                _logger.log(Level.WARNING, msg, ex);
            }
            try {
                setQueueSizeInBytes(Integer.parseInt(
                    pool.getMaxQueueSize()));
            } catch (NumberFormatException ex) {
                String msg = MessageFormat.format(_rb.getString(LogFacade.INVALID_THREAD_POOL_ATTRIBUTE), "max-queue-size");
                _logger.log(Level.WARNING, msg, ex);
            }
        }
    }

    /**
     * Configure http-listener property.
     * return true if the property exists and has been set.
     */
    public boolean configureHttpListenerProperty(String propName, String propValue)
        throws NumberFormatException {
        if ("proxyHandler".equals(propName)) {
            setProxyHandler(propValue);
            return true;
        }
        return false;
    }

    public void configHttpProperties(Http http, Transport transport, Ssl ssl) {
        setAllowTrace(ConfigBeansUtilities.toBoolean(http.getTraceEnabled()));
        setMaxKeepAliveRequests(Integer.parseInt(http.getMaxConnections()));
        setKeepAliveTimeoutInSeconds(Integer.parseInt(http.getTimeoutSeconds()));
        setAuthPassthroughEnabled(ConfigBeansUtilities.toBoolean(http.getAuthPassThroughEnabled()));
        setMaxPostSize(Integer.parseInt(http.getMaxPostSizeBytes()));
        setMaxSavePostSize(Integer.parseInt(http.getMaxSavePostSizeBytes()));
        setProperty("compression", http.getCompression());
        setProperty("compressableMimeType", http.getCompressableMimeType());
        if (http.getNoCompressionUserAgents() != null) {
            setProperty("noCompressionUserAgents", http.getNoCompressionUserAgents());
        }
        setProperty("compressionMinSize", http.getCompressionMinSizeBytes());
        if (http.getRestrictedUserAgents() != null) {
            setProperty("restrictedUserAgents", http.getRestrictedUserAgents());
        }
        setProperty("cometSupport",
                Boolean.toString(ConfigBeansUtilities.toBoolean(http.getCometSupportEnabled())));
        setProperty("rcmSupport",
                Boolean.toString(ConfigBeansUtilities.toBoolean(http.getRcmSupportEnabled())));
        setConnectionUploadTimeout(Integer.parseInt(http.getConnectionUploadTimeoutMillis()));
        setDisableUploadTimeout(!ConfigBeansUtilities.toBoolean(http.getUploadTimeoutEnabled()));
        setURIEncoding(http.getUriEncoding());
        configSslOptions(ssl);
    }

    private void configSslOptions(final Ssl ssl) {
        if (ssl != null) {
            if (ssl.getCrlFile() != null) {
                setCrlFile(ssl.getCrlFile());
            }
            if (ssl.getTrustAlgorithm() != null) {
                setTrustAlgorithm(ssl.getTrustAlgorithm());
            }
            if (ssl.getTrustMaxCertLength() != null) {
                setTrustMaxCertLength(ssl.getTrustMaxCertLength());
            }
        }
    }

    /*
     * Loads and instantiates the ProxyHandler implementation
     * class with the specified name, and sets the instantiated
     * ProxyHandler on this connector.
     *
     * @param className The ProxyHandler implementation class name
     */
    public void setProxyHandler(String className) {

        Object handler = null;
        try {
            Class handlerClass = webContainer.loadCommonClass(className);
            handler = handlerClass.newInstance();
        } catch (Exception e) {
            String msg = MessageFormat.format(_rb.getString(LogFacade.PROXY_HANDLER_CLASS_LOAD_ERROR), className);
            _logger.log(Level.SEVERE, msg, e);
        }
        if (handler != null) {
            if (!(handler instanceof ProxyHandler)) {
                _logger.log(
                    Level.SEVERE,
                    LogFacade.PROXY_HANDLER_CLASS_INVALID,
                    className);
            } else {
                setProxyHandler((ProxyHandler) handler);
            }
        }
    }


    /*
     * Request/response related probe events
     */

    /**
     * Fires probe event related to the fact that the given request has
     * been entered the web container.
     *
     * @param request the request object
     * @param host the virtual server to which the request was mapped
     * @param context the Context to which the request was mapped
     */
    @Override
    public void requestStartEvent(HttpServletRequest request, Host host,
            Context context) {
        if (requestProbeProvider != null) {
            String appName = null;
            if (context instanceof WebModule) {
                appName = ((WebModule) context).getMonitoringNodeName();
            }
            String hostName = null;
            if (host != null) {
                hostName = host.getName();
            }
            requestProbeProvider.requestStartEvent(
                appName, hostName,
                request.getServerName(), request.getServerPort(),
                request.getContextPath(), request.getServletPath());
        }
    }

    /**
     * Fires probe event related to the fact that the given request is about
     * to exit from the web container.
     *
     * @param request the request object
     * @param host the virtual server to which the request was mapped
     * @param context the Context to which the request was mapped
     * @param statusCode the response status code
     */
    @Override
    public void requestEndEvent(HttpServletRequest request, Host host,
            Context context, int statusCode) {
        if (requestProbeProvider != null) {
            String appName = null;
            if (context instanceof WebModule) {
                appName = ((WebModule) context).getMonitoringNodeName();
            }
            String hostName = null;
            if (host != null) {
                hostName = host.getName();
            }
            requestProbeProvider.requestEndEvent(
                appName, hostName,
                request.getServerName(), request.getServerPort(),
                request.getContextPath(), request.getServletPath(),
                statusCode, request.getMethod(), request.getRequestURI());
        }
    }


    /*
     * Configures the SSL properties on this PECoyoteConnector from the
     * SSL config of the given HTTP listener.
     *
     * @param listener HTTP listener whose SSL config to use
     */
    private void configureSSL(NetworkListener listener) {

        Ssl sslConfig = listener.findHttpProtocol().getSsl();
        if (sslConfig == null) {
            return;
        }

        // client-auth
        if (Boolean.parseBoolean(sslConfig.getClientAuthEnabled())) {
            setClientAuth(true);
        }

        // ssl protocol variants
        StringBuilder sslProtocolsBuf = new StringBuilder();
        boolean needComma = false;
        if (Boolean.parseBoolean(sslConfig.getSsl2Enabled())) {
            sslProtocolsBuf.append("SSLv2");
            needComma = true;
        }
        if (Boolean.parseBoolean(sslConfig.getSsl3Enabled())) {
            if (needComma) {
                sslProtocolsBuf.append(", ");
            } else {
                needComma = true;
            }
            sslProtocolsBuf.append("SSLv3");
        }
        if (Boolean.parseBoolean(sslConfig.getTlsEnabled())) {
            if (needComma) {
                sslProtocolsBuf.append(", ");
            } else {
                needComma = true;
            }
            sslProtocolsBuf.append("TLSv1");
        }
        if (Boolean.parseBoolean(sslConfig.getTls11Enabled())) {
            if (needComma) {
                sslProtocolsBuf.append(", ");
            } else {
                needComma = true;
            }
            sslProtocolsBuf.append("TLSv1.1");
        }
        if (Boolean.parseBoolean(sslConfig.getTls12Enabled())) {
            if (needComma) {
                sslProtocolsBuf.append(", ");
            } else {
                needComma = true;
            }
            sslProtocolsBuf.append("TLSv1.2");
        }
        if (Boolean.parseBoolean(sslConfig.getTls13Enabled())) {
            if (needComma) {
                sslProtocolsBuf.append(", ");
            }
            sslProtocolsBuf.append("TLSv1.3");
        }
        if (Boolean.valueOf(sslConfig.getSsl3Enabled()) ||
                Boolean.valueOf(sslConfig.getTlsEnabled())) {
            sslProtocolsBuf.append(", SSLv2Hello");
        }

        if (sslProtocolsBuf.length() == 0) {
            _logger.log(Level.WARNING, LogFacade.ALL_SSL_PROTOCOLS_DISABLED, listener.getName());
        } else {
            setSslProtocols(sslProtocolsBuf.toString());
        }

        // cert-nickname
        String certNickname = sslConfig.getCertNickname();
        if (certNickname != null && certNickname.length() > 0) {
            setKeyAlias(sslConfig.getCertNickname());
        }

        // ssl3-tls-ciphers
        String ciphers = sslConfig.getSsl3TlsCiphers();
        if (ciphers != null) {
            String jsseCiphers = getJSSECiphers(ciphers);
            if (jsseCiphers == null) {
                if (_logger.isLoggable(Level.FINE)) {
                    _logger.log(Level.FINE, LogFacade.ALL_CIPHERS_DISABLED, listener.getName());
                }
            } else {
                setCiphers(jsseCiphers);
            }
        }
    }


    /*
     * Configures this connector with its keystore and truststore.
     */
    private void configureKeysAndCerts() {
        String prop = System.getProperty(KEYSTORE_FILE.getSystemPropertyName());
        String keyStoreType = System.getProperty(KEYSTORE_TYPE.getSystemPropertyName(), KEYSTORE_TYPE_DEFAULT);
        if (prop != null) {
            setKeystoreFile(prop);
            setKeystoreType(keyStoreType);
        }

        prop = System.getProperty(TRUSTSTORE_FILE.getSystemPropertyName());
        keyStoreType = System.getProperty(TRUSTSTORE_TYPE.getSystemPropertyName(), KEYSTORE_TYPE_DEFAULT);
        if (prop != null) {
            setTruststore(prop);
            setTruststoreType(keyStoreType);
        }
    }


    /**
     * Configure http-listener properties
     */
    private void configureHttpListenerProperties(NetworkListener listener) {
        // Configure Connector with <http-service> properties
        configHttpProperties(listener.findHttpProtocol().getHttp(),
            listener.findTransport(), listener.findHttpProtocol().getSsl());
    }


    /*
     * Parses the given comma-separated string of cipher suite names,
     * converts each cipher suite that is enabled (i.e., not preceded by a
     * '-') to the corresponding JSSE cipher suite name, and returns a string
     * of comma-separated JSSE cipher suite names.
     *
     * @param sslCiphers String of SSL ciphers to parse
     *
     * @return String of comma-separated JSSE cipher suite names, or null if
     * none of the cipher suites in the given string are enabled or can be
     * mapped to corresponding JSSE cipher suite names
     */
    private String getJSSECiphers(String ciphers) {
        String cipher = null;
        StringBuilder enabledCiphers = null;
        boolean first = true;
        int index = ciphers.indexOf(',');
        if (index != -1) {
            int fromIndex = 0;
            while (index != -1) {
                cipher = ciphers.substring(fromIndex, index).trim();
                if (cipher.length() > 0 && !cipher.startsWith("-")) {
                    if (cipher.startsWith("+")) {
                        cipher = cipher.substring(1);
                    }
                    String jsseCipher = getJSSECipher(cipher);
                    if (jsseCipher == null) {
                        _logger.log(Level.WARNING,
                            LogFacade.UNRECOGNIZED_CIPHER, cipher);
                    } else {
                        if (enabledCiphers == null) {
                            enabledCiphers = new StringBuilder();
                        }
                        if (!first) {
                            enabledCiphers.append(", ");
                        } else {
                            first = false;
                        }
                        enabledCiphers.append(jsseCipher);
                    }
                }
                fromIndex = index + 1;
                index = ciphers.indexOf(',', fromIndex);
            }
            cipher = ciphers.substring(fromIndex);
        } else {
            cipher = ciphers;
        }
        if (cipher != null) {
            cipher = cipher.trim();
            if (cipher.length() > 0 && !cipher.startsWith("-")) {
                if (cipher.startsWith("+")) {
                    cipher = cipher.substring(1);
                }
                String jsseCipher = getJSSECipher(cipher);
                if (jsseCipher == null) {
                    _logger.log(Level.WARNING,
                        LogFacade.UNRECOGNIZED_CIPHER, cipher);
                } else {
                    if (enabledCiphers == null) {
                        enabledCiphers = new StringBuilder();
                    }
                    if (!first) {
                        enabledCiphers.append(", ");
                    } else {
                        first = false;
                    }
                    enabledCiphers.append(jsseCipher);
                }
            }
        }
        return enabledCiphers == null ? null : enabledCiphers.toString();
    }


    /*
     * Converts the given cipher suite name to the corresponding JSSE cipher.
     *
     * @param cipher The cipher suite name to convert
     *
     * @return The corresponding JSSE cipher suite name, or null if the given
     * cipher suite name can not be mapped
     */
    private String getJSSECipher(String cipher) {

        String jsseCipher = null;

        CipherInfo ci = CipherInfo.getCipherInfo(cipher);
        if( ci != null ) {
            jsseCipher = ci.getCipherName();
        }

        return jsseCipher;
    }
}

