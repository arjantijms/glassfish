/*
 * Copyright (c) 2017, 2020 Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.net.*;
import javax.naming.*;
import jakarta.jms.*;
import com.sun.ejte.ccl.reporter.*;
import org.glassfish.test.jms.injection.ejb.*;

/*
 * Unit test for resource injection into servlet filter.
 */
public class WebTest {

    private static final String TEST_NAME = "jms-injection-jsp(TransactionScoped)";
    private static final String EXPECTED_RESPONSE = "JSP Hello World!";

    private static SimpleReporterAdapter stat = new SimpleReporterAdapter("appserv-tests");

    private String host;
    private String port;
    private String contextRoot;

    public WebTest(String[] args) {
        host = args[0];
        port = args[1];
        contextRoot = args[2];
    }
    
    public static void main(String[] args) {

        stat.addDescription("Unit test for resource injection into servlet "
                            + "filter"+"(TransactionScoped)");
        WebTest webTest = new WebTest(args);
        try {
            webTest.doTest();
            stat.addStatus(TEST_NAME, stat.PASS);
        } catch (Exception ex) {
            stat.addStatus(TEST_NAME, stat.FAIL);
            ex.printStackTrace();
        }
        stat.printSummary();
    }

    public void doTest() throws Exception {
     
        URL url = new URL("http://" + host  + ":" + port
                          + contextRoot + "/newJSP.jsp");
        System.out.println("Connecting to: " + url.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.connect();
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) { 
            throw new Exception("Wrong response code. Expected: 200" +
                ", received: " + responseCode);
        }

        Context ctx = new InitialContext();
        MessageReceiverRemote beanRemote = (MessageReceiverRemote) ctx.lookup(MessageReceiverRemote.RemoteJNDIName);
        boolean received = beanRemote.checkMessage("Hello JSP Tag");
        if (!received)
            throw new Exception("Expected 2 JMS messages.");

        InputStream is = null;
        BufferedReader input = null;
        try {
            is = conn.getInputStream();
            input = new BufferedReader(new InputStreamReader(is));
            String line = null;
            StringBuffer sb = new StringBuffer();
            while ((line = input.readLine()) != null) {
                sb.append(line);
            }
            line = sb.toString();
            if (line.indexOf(EXPECTED_RESPONSE) < 0) {
                throw new Exception("Wrong response. Expected: " +
                    EXPECTED_RESPONSE + ", received: " + line);
            }
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException ioe) {
                // ignore
            }
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException ioe) {
                // ignore
            }
        }
    }
}
