/*
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

package org.glassfish.uberjar.osgimain;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;

/**
 * @author bhavanishankar@dev.java.net
 */

public class ModuleExtractor {

    private static final Logger logger = Logger.getLogger("embedded-glassfish");

    private static String MODULES_DIR_PREFIX = "modules";
    private static String MODULES_DIR_SUFFIX = "_jar/";
    private static final char SLASH = '/';
    private static final String JARFILE_URL_PREFIX = "jar:file:";
    private static final String JARENTRY_PREFIX = "!/";
    private static final int BYTEBUFFER_SIZE = 10240;

    /**
     * Extracts the OSGI Modules from the Jar file.
     *
     * @param modulesJarFile Jar file containing the modules.
     * @return Iterable list of OSGIModule
     */
    public static Iterable<OSGIModule> extractModules(File modulesJarFile) {

        final JarFile modulesJar;
        final Enumeration<JarEntry> entries;
        try {
            modulesJar = new JarFile(modulesJarFile);
            entries = modulesJar.entries();
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex.getMessage(), ex);
            return null;
        }

        return new Iterable<OSGIModule>() {

            public Iterator<OSGIModule> iterator() {

                return new Iterator<OSGIModule>() {

                    JarEntry nextEntry = getNextEntry();

                    public boolean hasNext() {
                        return nextEntry != null;
                    }

                    public OSGIModule next() {
                        OSGIModule b = null;

                        try {
                            b = getModule(nextEntry.getName(), modulesJar);
                        } catch (IOException ex) {

                        }
                        nextEntry = getNextEntry();
                        return b;
                    }

                    public void remove() {
                        throw new UnsupportedOperationException(
                                "Removal via iterator is not supported");
                    }

                    private JarEntry getNextEntry() {
                        JarEntry nextEntry = null;

                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();

                            if (!entry.isDirectory()) {
                                continue;
                            }

//                            if (entry.getName().split("/").length != 2) {
//                                continue;
//                            }

                            if (!entry.getName().startsWith(MODULES_DIR_PREFIX) ||
                                    !entry.getName().endsWith(MODULES_DIR_SUFFIX)) {
                                continue;
                            }
                            nextEntry = entry;
                            break;
                        }
                        return nextEntry;
                    }
                };
            }
        };
    }

    /**
     * Extracts a specified module from the modulesJar
     *
     * @param modulePath Path of the module to be extracted eg., modules/module_1/
     * @param modulesJar Jar file containing the modules.
     * @return Extracted OSGIModule.
     * @throws IOException if an I/O error has occurred
     */
    public static OSGIModule getModule(final String modulePath,
                                       final JarFile modulesJar) throws IOException {

        final PipedOutputStream pos = new PipedOutputStream();
        final PipedInputStream pis = new PipedInputStream(pos);

        final OSGIModule b = new OSGIModule();
        b.setContentStream(pis);
        b.setLocation(JARFILE_URL_PREFIX + modulesJar.getName() +
                JARENTRY_PREFIX + modulePath);

        new Thread() {

            @Override
            public void run() {

                try {
                    ZipEntry manifestEntry = modulesJar.getEntry(
                            modulePath + JarFile.MANIFEST_NAME);
                    Manifest m;
                    InputStream in = modulesJar.getInputStream(manifestEntry);


                    try {
                        m = new Manifest(in);
                        if(m != null) {
                            Attributes attrs = m.getMainAttributes();
                            if(attrs != null) {
                                b.setBundleSymbolicName(attrs.getValue("Bundle-SymbolicName"));
                            }
                        }
                    } finally {
                        in.close();
                    }

                    final JarOutputStream jos = new JarOutputStream(pos, m);
                    jos.setLevel(Deflater.NO_COMPRESSION);
                    final ByteBuffer buf = ByteBuffer.allocate(BYTEBUFFER_SIZE);

                    Enumeration<JarEntry> entries = modulesJar.entries();

                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();

/*
                        if (entry.isDirectory()) {
                            continue;
                        }
*/
                        if (!entry.getName().startsWith(modulePath)) {
                            continue;
                        }
                        if (entry.getName().indexOf(JarFile.MANIFEST_NAME) != -1) {
                            continue;
                        }

                        String entryName = entry.getName();
                        entryName = entryName.substring(modulePath.length());
                        jos.putNextEntry(new JarEntry(entryName));

                        in = modulesJar.getInputStream(entry);
                        try {
                            copy(in, jos, buf);
                        } finally {
                            in.close();
                        }
                        jos.closeEntry();
                    }

                    jos.close();
                    pos.close();

                } catch (IOException ex) {
                    b.getExceptionHandler().handle(ex);
                }
            }
        }.start();

        return b;
    }

    /**
     * Copies input to output. To avoid unnecessary allocation of byte buffers,
     * this method takes a byte buffer as argument. It clears the byte buffer
     * at the end of the operation.
     *
     * @param in
     * @param out
     * @param byteBuffer
     */
    private static void copy(InputStream in, OutputStream out, ByteBuffer byteBuffer)
            throws IOException {
        try {
            ReadableByteChannel inChannel = Channels.newChannel(in);
            WritableByteChannel outChannel = Channels.newChannel(out);

            int read;
            do {
                read = inChannel.read(byteBuffer);
                if (read > 0) {
                    byteBuffer.limit(byteBuffer.position());
                    byteBuffer.rewind();
                    int written = 0;
                    while ((written += outChannel.write(byteBuffer)) < read) {
                        // sometimes channel.write may write partial data,
                        // so ensure that the data is written fully.
                    }
                    if (logger.isLoggable(Level.FINER)) {
                        if (logger.isLoggable(Level.FINER)) {
                            logger.logp(Level.FINE, "JarHelper", "write",
                                    "Copied {0} bytes", new Object[]{read});
                        }
                    }
                    byteBuffer.clear();
                }
            } while (read != -1);
        } finally {
            byteBuffer.clear();
        }
    }

    // Utility method to test the JarHelper.

    public static void main(String[] args) throws Exception {

        int bundleCount = 0;
        for (OSGIModule b : ModuleExtractor.extractModules(new File(args[0]))) {

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] array = new byte[BYTEBUFFER_SIZE];
            int count;

            while ((count = b.getContentStream().read(array)) != -1) {
                out.write(array, 0, count);
            }

            ++bundleCount;
            logger.info("b.name = " + b.getLocation() + ", b.streamSize = " + out.size());
        }

        logger.info("Total number of bundles = " + bundleCount);
    }


}
