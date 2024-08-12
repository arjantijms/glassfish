/*
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

package com.sun.jdo.api.persistence.enhancer.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.StringTokenizer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * ClassFileSource provides a mechanism for associating a class
 * with the source of that class.  The source is currently either
 * an ordinary .class file or a zip file 1 or more class files.
 */
//^olsen: cosmetics (indentation, control flow ...)
public class ClassFileSource
extends Support {

    /* The original expected name of the class */
    private String theOriginalExpectedClassName = null;

    /* The expected name of the class */
    private String theExpectedClassName = null;

    /* The file containing the class file */
    private File classFile = null;

    /* The zip file containing the class file */
    private ZipFile zipFile = null;

    /* The byte-code input stream from a class file */
    private InputStream byteCodeStream = null;

    /* The ClassPathElement through which this was located */
    private ClassPathElement sourceElement;

    /* The cached modification date */
    private long cachedModDate;
    //  private ArchiveEntry archiveEntry; //@yury: added ability to get the class bytecode from the Forte4J archive entry

    //@yury archive entry getter
    /**
     * @return The ArchiveEntry
     */
    /*
    public ArchiveEntry getArchiveEntry() {
    return archiveEntry;
    }
     */

    /* public access */

    /**
     * Does the other class file source refer to the same source location?
     */
    public boolean sameAs(ClassFileSource other) {

        /*
      //@yury: added ArchiveEntry processing [[[[
      if ((null == getArchiveEntry()) ^ (null == getArchiveEntry())) {
      return false;
      }

      if (null != getArchiveEntry() ) {
      return getArchiveEntry().getName().equals(other.getArchiveEntry().getName());
      }
      //@yury: added ArchiveEntry processing ]]]]]
         */

        //^olsen: simplify control flow
        if (isZipped())
            return (other.isZipped() &&
                other.zipFile.getName().equals(zipFile.getName()));
        else if (other.isZipped())
            return false;
        else if (other.classFile != null && classFile != null)
            return other.classFile.getPath().equals(classFile.getPath());
        //@olsen: added test
        else if (byteCodeStream != null)
            return byteCodeStream.equals(other.byteCodeStream);
        return false;
    }

    /**
     * Does this class originate in a zip file?
     */
    public boolean isZipped() {
        return zipFile != null;
    }

    /**
     * Does this class originate in a zip file?
     */
    //@olsen: added method
    public boolean isStreamed() {
        return byteCodeStream != null;
    }

    /**
     * The expected name of the class contained in the class file.
     * Returns null if the class name can not be intuited from the file name.
     */
    public String expectedClassName() {
        return theExpectedClassName;
    }

    /**
     * Set the name of the class contained in the class file.
     */
    public void setExpectedClassName(String name) {
        theExpectedClassName = name;
    }

    /**
     * Get the path of the File containing the class
     */
    public String containingFilePath() {
        if (isZipped())
            return zipFile.getName();
        else if (classFile != null)
            return classFile.getPath();
        else
            return null;
    }

    /**
     * Constructor
     * @param className The expected name of the class
     * @param classFile The file containing the class.  This file should
     *   exist and be readable.
     */
    public ClassFileSource(String className, File classFile) {
        //@olsen: added println() for debugging
        //System.out.println("ClassFileSource(): new class = " + className);
        theExpectedClassName = className;
        theOriginalExpectedClassName = className;
        this.classFile = classFile;
    }

    //@yury: added archive entry-based constructor
    /**
     * Constructs the ClassFileSource form the ArchiveEntry
     *
     * @param className The class name
     * @param entry The archive entry
     */
    /*
    public ClassFileSource(String className, ArchiveEntry entry) {
    archiveEntry = entry;
    theExpectedClassName = className;
    theOriginalExpectedClassName = className;
    }
     */

    /**
     * Constructor
     * @param className The expected name of the class
     * @param zipFile The zip file containing the class.  This file should
     *   exist and be readable.
     */
    public ClassFileSource(String className, ZipFile zipFile) {
        //@olsen: added println() for debugging
        //System.out.println("ClassFileSource(): new class = " + className);
        theExpectedClassName = className;
        theOriginalExpectedClassName = className;
        this.zipFile = zipFile;
    }

    /**
     * Constructor
     * @param className The expected name of the class
     * @param byteCodeStream containing the class file.
     *
     */
    //@olsen: added constructor
    public ClassFileSource(String className, InputStream byteCodeStream) {
        //@olsen: added println() for debugging
        //System.out.println("ClassFileSource(): new class = " + className);
        theExpectedClassName = className;
        theOriginalExpectedClassName = className;
        this.byteCodeStream = byteCodeStream;
    }

    /**
     * Attempt to find the next possible source of the class
     */
    public ClassFileSource nextSource(String className) {
        if (sourceElement != null && sourceElement.next() != null)
            return ClassPath.findClass(className, sourceElement.next());
        return null;
    }

    /**
     * Build a "friend" source file specification for the class of
     * the given name.  That is, the new class file source should be
     * in the same zip file if zipped or else the same directory.
     *
     * Restriction: containingFilePath() must be non-null.
     */
    public ClassFileSource friendSource(String className) {
        /*
      //@yury: ArchiveEntry case sanity check
      if ( null != archiveEntry) {
      throw new IllegalArgumentException("----- Not implemented yet");
      }
         */

        if (isZipped())
            return new ClassFileSource(className, zipFile);
        else {
            String fullPath = FilePath.getAbsolutePath(classFile);
            File dir = new File(fullPath.substring(
                0, fullPath.lastIndexOf(File.separatorChar)+1));
            File f = new File(dir, unpackagedName(className) + ".class");//NOI18N
            return new ClassFileSource(className, f);
        }
    }

    /**
     * Get a DataInputStream containing the class file.
     *
     * Restriction: containingFilePath() must be non-null.
     */
    public DataInputStream classFileContents()
        throws IOException, FileNotFoundException {
        /*
      //@yury: ArchiveEntry case sanity check
      if ( null != archiveEntry) {
      return new DataInputStream(new BufferedInputStream(archiveEntry.createInputStream()));
      }
         */
        //@olsen: cosmetics
        if (isZipped()) {
            ZipEntry entry =
                zipFile.getEntry(ClassPath.zipFileNameOf(theExpectedClassName));
            if (entry == null)
                throw new FileNotFoundException(
                    "The zip file member " + theExpectedClassName + " was not found.");
            return new DataInputStream(zipFile.getInputStream(entry));
        }
        //@olsen: added case
        if (isStreamed()) {
            return new DataInputStream(byteCodeStream);
        }
        return new DataInputStream(
            new BufferedInputStream(
                new FileInputStream(classFile)));
    }

    /**
     * Get the modification date of the class file.  The date format is
     * that used by java.util.Date.
     *
     * Restriction: containingFilePath() must be non-null.
     */
    public long modificationDate() throws FileNotFoundException {
        if (cachedModDate == 0) {
            /*
    //@yury: ArchiveEntry case sanity check
    if ( null != archiveEntry) {
        throw new IllegalArgumentException("----- Not implemented yet");
    }
             */

            if (isZipped()) {
                ZipEntry entry =
                    zipFile.getEntry(ClassPath.zipFileNameOf(theOriginalExpectedClassName));
                if (entry == null)
                    throw new FileNotFoundException("The zip file member was not found.");
                cachedModDate = entry.getTime();
            }
            else if (classFile != null)
                cachedModDate = classFile.lastModified();
        }

        return cachedModDate;
    }

    /**
     * Set the cached modification date of the class file.
     * This doesn't actually update the file.
     */
    public void setModificationDate(long date) {
        cachedModDate = date;
    }

    /**
     * Set the ClassPathElement through which this was located
     */
    void setSourceElement(ClassPathElement cpathElement) {
        sourceElement = cpathElement;
    }

    /**
     * Compute the destination directory for the class.
     * rootDestDir must be non-null - use that as a destination
     * location root, ensuring its existence.
     */
    private File computeDestinationDir(File rootDestDir)  throws IOException, FileNotFoundException {

        StringBuffer buf = new StringBuffer(rootDestDir.getPath());
        String prevToken = null;
        StringTokenizer parser
        = new StringTokenizer(theExpectedClassName, "/", false);//NOI18N
        while (parser.hasMoreTokens()) {
            if (prevToken != null) {
                buf.append(File.separatorChar);
                buf.append(prevToken);

                File currDir = new File(buf.toString());
                if (!currDir.isDirectory()) {
                    if (!currDir.mkdir())
                        //@olsen: support for I18N
                        throw new UserException(
                            getI18N("enhancer.unable_to_create_dir",//NOI18N
                                currDir.getPath()));
                }
            }
            prevToken = parser.nextToken();
        }
        return new File(buf.toString());
    }

    /**
     * Compute the destination file for the class.
     * If destDir is non-null, use that as a destination location
     *
     * Restriction: if containingFilePath() is null, null is returned.
     */
    public File computeDestination(File destDir)
        throws IOException, FileNotFoundException {
        if (destDir != null) {
            File finalDestDir = computeDestinationDir(destDir);
            String theFinalClassComponent = "";//NOI18N
            StringTokenizer parser =
                new StringTokenizer(theExpectedClassName, "/", false);//NOI18N
            while (parser.hasMoreTokens())
                theFinalClassComponent = parser.nextToken();
            return new File(finalDestDir, theFinalClassComponent + ".class");//NOI18N
        }
        else
            /* Note: this is wrong when repackaging occurs but we currently
       require a destination directory to be specified, so it doesn't
       matter. */
            return classFile;
    }

    /**
     * Get a DataOutputStream to which a class file should be written.
     * The caller must close the output stream when complete.
     */
    public DataOutputStream getOutputStream(File dest) throws IOException, FileNotFoundException {
        /*
      //@yury: ArchiveEntry case sanity check
      if ( null != archiveEntry) {
      throw new IllegalArgumentException("----- Must never call here");
      }
         */

        /* If this were a zipped file we would need to do some fancy footwork */
        return new DataOutputStream(new BufferedOutputStream(new FileOutputStream(dest)));
    }

    /**
     * Return the name of the class, ignoring any leading package specification.
     */
    private String unpackagedName(String className) {
        int idx = className.lastIndexOf((int) '/');
        if (idx < 0)
            return className;
        return className.substring(idx+1);
    }
}
