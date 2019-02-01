//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.client;


import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

public class ClientCloseTest
{
    private static final Logger LOG = Log.getLogger(ClientCloseTest.class);

    private static Server server;
    private WebSocketClient client;

    private Session confirmConnection(CloseTrackingSocket clientSocket, Future<Session> clientFuture) throws Exception
    {
        // Wait for client connect on via future
        Session session = clientFuture.get(30, SECONDS);

        // Wait for client connect via client websocket
        assertThat("Client WebSocket is Open", clientSocket.openLatch.await(30, SECONDS), is(true));

        try
        {
            // Send message from client to server
            final String echoMsg = "echo-test";
            Future<Void> testFut = clientSocket.getRemote().sendStringByFuture(echoMsg);

            // Wait for send future
            testFut.get(5, SECONDS);

            // Verify received message
            String recvMsg = clientSocket.messageQueue.poll(5, SECONDS);
            assertThat("Received message", recvMsg, is(echoMsg));

            // Verify that there are no errors
            assertThat("Error events", clientSocket.error.get(), nullValue());
        }
        finally
        {
            clientSocket.clearQueues();
        }

        return session;
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setMaxTextMessageBufferSize(1024);
        client.getPolicy().setMaxTextMessageSize(1024);
        client.start();
    }

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        ServletHolder holder = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.getPolicy().setIdleTimeout(10000);
                factory.getPolicy().setMaxTextMessageSize(1024 * 1024 * 2);
                factory.register(ServerEndpoint.class);
            }
        });
        context.addServlet(holder, "/ws");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());
        server.setHandler(handlers);

        server.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testHalfClose() throws Exception
    {
        // Set client timeout
        final int timeout = 5000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        Session session = null;
        try
        {
            // client confirms connection via echo
            session = confirmConnection(clientSocket, clientConnectFuture);

            // client sends close frame (code 1000, normal)
            final String origCloseReason = "send-more-frames";
            clientSocket.getSession().close(StatusCode.NORMAL, origCloseReason);

            // Verify received messages
            String recvMsg = clientSocket.messageQueue.poll(5, SECONDS);
            assertThat("Received message 1", recvMsg, is("Hello"));
            recvMsg = clientSocket.messageQueue.poll(5, SECONDS);
            assertThat("Received message 2", recvMsg, is("World"));

            // Verify that there are no errors
            assertThat("Error events", clientSocket.error.get(), nullValue());

            // client close event on ws-endpoint
            clientSocket.assertReceivedCloseEvent(timeout, is(StatusCode.NORMAL), containsString(""));
        }
        finally
        {
            if (session != null)
            {
                session.close();
            }
        }
    }

    @Test
    public void testMessageTooLargeException() throws Exception
    {
        // Set client timeout
        final int timeout = 3000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        Session session = null;
        try
        {
            // client confirms connection via echo
            session = confirmConnection(clientSocket, clientConnectFuture);

            session.getRemote().sendString("too-large-message");

            clientSocket.assertReceivedCloseEvent(timeout, is(StatusCode.MESSAGE_TOO_LARGE), containsString("exceeds maximum size"));

            // client should have noticed the error
            assertThat("OnError Latch", clientSocket.errorLatch.await(2, SECONDS), is(true));
            assertThat("OnError", clientSocket.error.get(), instanceOf(MessageTooLargeException.class));
        }
        finally
        {
            if (session != null)
            {
                session.close();
            }
        }

        // client triggers close event on client ws-endpoint
        assertThat("Client Open Sessions", client.getOpenSessions(), empty());
    }

    @Test
    public void testReadEOF() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        Session session = null;
        try
        {
            // client confirms connection via echo
            session = confirmConnection(clientSocket, clientConnectFuture);

            // client sends close frame (triggering server connection abort)
            final String origCloseReason = "abort";
            clientSocket.getSession().close(StatusCode.NORMAL, origCloseReason);

            // client should not have received close message (yet)
            clientSocket.assertNoCloseEvent();

            // client reads -1 (EOF)
            // client triggers close event on client ws-endpoint
            clientSocket.assertReceivedCloseEvent(timeout, is(StatusCode.ABNORMAL),
                    anyOf(
                            containsString("EOF"),
                            containsString("Disconnected")
                    ));
        }
        finally
        {
            if (session != null)
            {
                session.close();
            }
        }
    }

    @Test
    public void testServerNoCloseHandshake() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        Session session = null;
        try
        {
            // client confirms connection via echo
            session = confirmConnection(clientSocket, clientConnectFuture);

            // client sends close frame
            final String origCloseReason = "sleep|5000";
            clientSocket.getSession().close(StatusCode.NORMAL, origCloseReason);

            // client close should occur
            clientSocket.assertReceivedCloseEvent(timeout*2, is(StatusCode.SHUTDOWN),
                    anyOf(
                            containsString("Timeout"),
                            containsString("Disconnected")
                    ));

            // client idle timeout triggers close event on client ws-endpoint
            assertThat("OnError Latch", clientSocket.errorLatch.await(2, SECONDS), is(true));
            assertThat("OnError", clientSocket.error.get(), instanceOf(TimeoutException.class));
        }
        finally
        {
            if (session != null)
            {
                session.close();
            }
        }
    }

    @Test
    public void testStopLifecycle() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        int clientCount = 3;
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        List<CloseTrackingSocket> clientSockets = new ArrayList<>();

        assertTimeoutPreemptively(ofSeconds(5), () -> {
            // Open Multiple Clients
            for (int i = 0; i < clientCount; i++)
            {
                // Client Request Upgrade
                CloseTrackingSocket clientSocket = new CloseTrackingSocket();
                clientSockets.add(clientSocket);
                Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

                // client confirms connection via echo
                confirmConnection(clientSocket, clientConnectFuture);
            }

            // client lifecycle stop (the meat of this test)
            client.stop();

            // clients disconnect
            for (int i = 0; i < clientCount; i++)
            {
                clientSockets.get(i).assertReceivedCloseEvent(timeout, is(StatusCode.SHUTDOWN), containsString("Shutdown"));
            }
        });
    }

    @Test
    public void testWriteException() throws Exception
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);

        // Client connects
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        CloseTrackingSocket clientSocket = new CloseTrackingSocket();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);

        // client confirms connection via echo
        confirmConnection(clientSocket, clientConnectFuture);

        // setup client endpoint for write failure (test only)
        EndPoint endp = clientSocket.getEndPoint();
        endp.shutdownOutput();

        // client enqueue close frame
        // client write failure
        final String origCloseReason = "Normal Close";
        clientSocket.getSession().close(StatusCode.NORMAL, origCloseReason);

        assertThat("OnError Latch", clientSocket.errorLatch.await(2, SECONDS), is(true));
        assertThat("OnError", clientSocket.error.get(), instanceOf(EofException.class));

        // client triggers close event on client ws-endpoint
        // assert - close code==1006 (abnormal)
        // assert - close reason message contains (write failure)
        clientSocket.assertReceivedCloseEvent(timeout,
                anyOf( is(StatusCode.ABNORMAL),
                       is(StatusCode.SHUTDOWN)),
                containsString("EOF"));

        assertThat("Client Open Sessions", client.getOpenSessions(), empty());
    }

    public static class ServerEndpoint implements WebSocketFrameListener, WebSocketListener
    {
        private static final Logger LOG = Log.getLogger(ServerEndpoint.class);
        private Session session;

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len)
        {

        }

        @Override
        public void onWebSocketText(String message)
        {
            try
            {
                if (message.equals("too-large-message"))
                {
                    // send extra large message
                    byte buf[] = new byte[0124 * 1024];
                    Arrays.fill(buf, (byte) 'x');
                    String bigmsg = new String(buf, UTF_8);
                    session.getRemote().sendString(bigmsg);
                }
                else
                {
                    // simple echo
                    session.getRemote().sendString(message);
                }
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
        }

        @Override
        public void onWebSocketConnect(Session session)
        {
            this.session = session;
        }

        @Override
        public void onWebSocketError(Throwable cause)
        {
            LOG.warn(cause);
        }

        @Override
        public void onWebSocketFrame(Frame frame)
        {
            if (frame.getOpCode() == OpCode.CLOSE)
            {
                CloseInfo closeInfo = new CloseInfo(frame);
                String reason = closeInfo.getReason();

                if (reason.equals("send-more-frames"))
                {
                    try
                    {
                        session.getRemote().sendString("Hello");
                        session.getRemote().sendString("World");
                    }
                    catch (IOException e)
                    {
                        LOG.warn(e);
                    }
                }
                else if (reason.equals("abort"))
                {
                    try
                    {
                        LOG.info("Server aborting session abruptly");
                        session.disconnect();
                    }
                    catch (IOException ignore)
                    {
                        LOG.ignore(ignore);
                    }
                }
                else if (reason.startsWith("sleep|"))
                {
                    int idx = reason.indexOf('|');
                    int timeMs = Integer.parseInt(reason.substring(idx + 1));
                    try
                    {
                        LOG.info("Server Sleeping for {} ms", timeMs);
                        TimeUnit.MILLISECONDS.sleep(timeMs);
                    }
                    catch (InterruptedException ignore)
                    {
                        LOG.ignore(ignore);
                    }
                }
            }
        }
    }
}