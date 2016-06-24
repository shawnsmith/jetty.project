//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.gcloud.session;

import org.eclipse.jetty.server.session.AbstractServerCrossContextSessionTest;
import org.eclipse.jetty.server.session.AbstractTestServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * ServerCrossContextSessionTest
 *
 *
 */
public class ServerCrossContextSessionTest extends AbstractServerCrossContextSessionTest
{
    @AfterClass
    public static void teardown () throws Exception
    {
        GCloudTestSuite.__testSupport.deleteSessions();
    }

    @Override
    public AbstractTestServer createServer(int port, int maxInactiveMs, int scavengeMs,int evictionPolicy)
    {
       return new GCloudTestServer(port, maxInactiveMs, scavengeMs, evictionPolicy, GCloudTestSuite.__testSupport.getConfiguration());
    }

    @Test
    @Override
    public void testCrossContextDispatch() throws Exception
    {
        super.testCrossContextDispatch();
    }

    
}