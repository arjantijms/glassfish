/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
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

package com.sun.enterprise.util;

import java.io.File;
import java.util.Locale;

/**
 * @author bnevins December 8, 2001, 5:48 PM
 */
public class OS {

    private OS() {
    }


    public static boolean isWindows() {
        return File.separatorChar == '\\';
    }


    public static boolean isUNIX() {
        return File.separatorChar == '/';
    }


    public static boolean isUnix() {
        // convenience method...
        return isUNIX();
    }

    public static boolean isLinux() {
        return isName("linux");
    }


    public static boolean isDarwin() {
        return isName("Mac OS X");
    }


    public static boolean isWindowsForSure() {
        return isName("windows") && isWindows();
    }

    public static boolean isAix() {
        return isName("AIX");
    }


    private static boolean isArch(String name) {
        String archname = System.getProperty("os.arch");

        if (archname == null || archname.isEmpty()) {
            return false;
        }

        // case insensitive compare...
        archname = archname.toLowerCase(Locale.getDefault());
        name = name.toLowerCase(Locale.getDefault());

        if (archname.indexOf(name) >= 0) {
            return true;
        }

        return false;
    }


    private static boolean isName(String name) {
        String osname = System.getProperty("os.name");

        if (osname == null || osname.isEmpty()) {
            return false;
        }

        // case insensitive compare...
        osname = osname.toLowerCase(Locale.getDefault());
        name = name.toLowerCase(Locale.getDefault());

        if (osname.contains(name)) {
            return true;
        }

        return false;
    }
}
