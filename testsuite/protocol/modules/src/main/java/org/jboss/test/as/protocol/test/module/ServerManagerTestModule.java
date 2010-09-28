/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.test.as.protocol.test.module;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;

import junit.framework.Assert;

import org.jboss.as.model.ServerModel;
import org.jboss.as.process.ProcessManagerMaster;
import org.jboss.as.server.manager.Server;
import org.jboss.as.server.manager.ServerManager;
import org.jboss.as.server.manager.ServerManagerProtocolCommand;
import org.jboss.as.server.manager.ServerManagerProtocolUtils;
import org.jboss.as.server.manager.ServerState;
import org.jboss.test.as.protocol.support.process.TestProcessHandler;
import org.jboss.test.as.protocol.support.process.TestProcessHandlerFactory;
import org.jboss.test.as.protocol.support.process.TestProcessManager;
import org.jboss.test.as.protocol.support.server.MockServerProcess;
import org.jboss.test.as.protocol.support.server.manager.TestServerManagerProcess;
import org.jboss.test.as.protocol.test.base.ServerManagerTest;

/**
 * Tests that the server manager part works in isolation with
 * the process manager and server processes mocked up
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @version $Revision: 1.1 $
 */
public class ServerManagerTestModule extends AbstractProtocolTestModule implements ServerManagerTest {

    @Override
    public void testStartServerManagerNoConfig() throws Exception {
        TestProcessHandlerFactory processHandlerFactory = new TestProcessHandlerFactory(true, false);
        TestProcessManager pm = TestProcessManager.create(processHandlerFactory, InetAddress.getLocalHost(), 0, false);
        try {
            TestServerManagerProcess.createServerManager(pm);
            Assert.fail("Expected failed start");
        } catch (Exception expected) {
        }
        pm.shutdown();
    }

    @Override
    public void testStartStopServerManager() throws Exception {
        setDomainConfigDir("standard");
        TestProcessHandlerFactory processHandlerFactory = new TestProcessHandlerFactory(true, false);
        final TestProcessManager pm = TestProcessManager.create(processHandlerFactory, InetAddress.getLocalHost(), 0);

        pm.pollAddedProcess(3); //SM + 2 servers
        pm.pollStartedProcess(3);

        TestServerManagerProcess sm = assertGetServerManager(processHandlerFactory);
        MockServerProcess svr1 = assertGetServer(processHandlerFactory, "Server:server-one");
        MockServerProcess svr2 = assertGetServer(processHandlerFactory, "Server:server-two");
        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_AVAILABLE, svr1, svr2);

        Assert.assertEquals("server-one", assertReadStartCommand(svr1).getServerName());
        Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());

        managerAlive(svr1.getSmAddress(), svr1.getSmPort());
        managerAlive(svr1.getPmAddress(), svr1.getPmPort());

        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_STARTED, svr1, svr2);

        shutdownProcessManagerNoWait(pm);

        /* On shutdown the pm will:
         * 1)send SHUTDOWN_SERVERS to SM. SM then sends SHUTDOWN_SERVER to the servers and waits for SERVER_STOPPED confirmation back
         * 2)wait for the SERVERS_SHUTDOWN message from SM
         * 3)send SHUTDOWN to SM
         */

        //Check SM received SHUTDOWN_SERVERS
        sm.waitForShutdownServers();

        //Check servers received STOP_SERVER from SM
        assertReadCommand(svr1, ServerManagerProtocolCommand.STOP_SERVER);
        assertReadCommand(svr2, ServerManagerProtocolCommand.STOP_SERVER);

        //Send SERVER_STOPPED from servers to SM
        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_STOPPED, svr1, svr2);

        //Check PM has stopped and removed the processes
        pm.pollStoppedProcess(2);  //Only server-one
        pm.pollRemovedProcess(2);

        //Wait for SERVERS_SHUTDOWN from SM
        pm.waitForServersShutdown();

        //Check SM received SHUTDOWN
        sm.waitForShutdown();

        //Check PM and SM sockets are no longer listening
        waitForManagerToStop(svr1.getSmAddress(), svr1.getSmPort(), 5000);
        waitForManagerToStop(svr1.getPmAddress(), svr1.getPmPort(), 5000);

    }

    @Override
    public void testServerStartFailedAndGetsRespawned() throws Exception {
        setDomainConfigDir("standard");
        TestProcessHandlerFactory processHandlerFactory = new TestProcessHandlerFactory(true, false);
        final TestProcessManager pm = TestProcessManager.create(processHandlerFactory, InetAddress.getLocalHost(), 0);

        pm.pollAddedProcess(3); //SM + 2 servers
        pm.pollStartedProcess(3);

        MockServerProcess svr1 = assertGetServer(processHandlerFactory, "Server:server-one");
        MockServerProcess svr2 = assertGetServer(processHandlerFactory, "Server:server-two");
        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_AVAILABLE, svr1, svr2);

        Assert.assertEquals("server-one", assertReadStartCommand(svr1).getServerName());
        Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());

        managerAlive(svr1.getSmAddress(), svr1.getSmPort());
        managerAlive(svr1.getPmAddress(), svr1.getPmPort());

        svr1.sendMessageToManager(ServerManagerProtocolCommand.SERVER_STARTED);
        final int addCount = pm.getAddCount();
        final int removeCount = pm.getRemoveCount();
        int stopCount = pm.getStopCount();
        int startCount = pm.getStartCount();
        for (int i = 0 ; i <= 4 ; i++ ) {
            if (i < 4) {
                svr2.sendMessageToManager(ServerManagerProtocolCommand.SERVER_START_FAILED);
                pm.pollStoppedProcess(1);
                pm.pollStartedProcess(1);
                Assert.assertEquals(++stopCount, pm.getStopCount());
                Assert.assertEquals(++startCount, pm.getStartCount());
                svr2 = assertGetServer(processHandlerFactory, "Server:server-two");
            } else {
                svr2 = assertGetServer(processHandlerFactory, "Server:server-two");
                sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_AVAILABLE, svr2);
                Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());
                svr2.sendMessageToManager(ServerManagerProtocolCommand.SERVER_STARTED);
            }
        }
        Assert.assertEquals(addCount, pm.getAddCount());
        Assert.assertEquals(removeCount, pm.getRemoveCount());

        shutdownProcessManagerNoWait(pm);
    }

    @Override
    public void testServerStartFailedAndRespawnExpires() throws Exception {
        setDomainConfigDir("standard");
        TestProcessHandlerFactory processHandlerFactory = new TestProcessHandlerFactory(true, false);
        final TestProcessManager pm = TestProcessManager.create(processHandlerFactory, InetAddress.getLocalHost(), 0);

        pm.pollAddedProcess(3); //SM + 2 servers
        pm.pollStartedProcess(3);

        MockServerProcess svr1 = assertGetServer(processHandlerFactory, "Server:server-one");
        MockServerProcess svr2 = assertGetServer(processHandlerFactory, "Server:server-two");
        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_AVAILABLE, svr1, svr2);

        Assert.assertEquals("server-one", assertReadStartCommand(svr1).getServerName());
        Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());

        managerAlive(svr1.getSmAddress(), svr1.getSmPort());
        managerAlive(svr1.getPmAddress(), svr1.getPmPort());

        svr2.sendMessageToManager(ServerManagerProtocolCommand.SERVER_STARTED);
        final int addCount = pm.getAddCount();
        final int removeCount = pm.getRemoveCount();
        int stopCount = pm.getStopCount();
        int startCount = pm.getStartCount();
        //TODO JBAS-8390 once respawn policies are configurable we can make this a bit less time-consuming
        for (int i = 0 ; i <= 14 ; i++ ) {
            svr1.sendMessageToManager(ServerManagerProtocolCommand.SERVER_START_FAILED);
            pm.pollStoppedProcess(1);
            Assert.assertEquals(++stopCount, pm.getStopCount());

            if (i < 14) {
                pm.pollStartedProcess(1);
                Assert.assertEquals(++startCount, pm.getStartCount());
                svr1 = assertGetServer(processHandlerFactory, "Server:server-one");
                svr1.sendMessageToManager(ServerManagerProtocolCommand.SERVER_AVAILABLE);
                Assert.assertEquals("server-one", assertReadStartCommand(svr1).getServerName());
                Assert.assertEquals(addCount, pm.getAddCount());
                Assert.assertEquals(removeCount, pm.getRemoveCount());
            }
        }
        pm.pollRemovedProcess(1);
        Assert.assertEquals(removeCount + 1, pm.getRemoveCount());

        shutdownProcessManagerNoWait(pm);
    }

    @Override
    public void testServerCrashedAfterStartGetsRespawned() throws Exception {
        setDomainConfigDir("standard");
        TestProcessHandlerFactory processHandlerFactory = new TestProcessHandlerFactory(true, false);
        final TestProcessManager pm = TestProcessManager.create(processHandlerFactory, InetAddress.getLocalHost(), 0);

        pm.pollAddedProcess(3); //SM + 2 servers
        pm.pollStartedProcess(3);

        TestServerManagerProcess sm = assertGetServerManager(processHandlerFactory);
        MockServerProcess svr1 = assertGetServer(processHandlerFactory, "Server:server-one");
        MockServerProcess svr2 = assertGetServer(processHandlerFactory, "Server:server-two");
        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_AVAILABLE, svr1, svr2);

        Assert.assertEquals("server-one", assertReadStartCommand(svr1).getServerName());
        Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());

        Assert.assertTrue(managerAlive(svr1.getSmAddress(), svr1.getSmPort()));
        Assert.assertTrue(managerAlive(svr1.getPmAddress(), svr1.getPmPort()));

        svr1.sendMessageToManager(ServerManagerProtocolCommand.SERVER_STARTED);
        final int addCount = pm.getAddCount();
        final int removeCount = pm.getRemoveCount();
        int stopCount = pm.getStopCount();
        int startCount = pm.getStartCount();

        for (int i = 0 ; i <= 4 ; i++) {
            svr2.sendMessageToManager(ServerManagerProtocolCommand.SERVER_STARTED);
            if (i < 4) {
                final MockServerProcess proc = svr2;
                sm.resetDownLatch();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        proc.crashServer(1);
                    }
                }).start();
                sm.waitForDown();

                pm.pollStoppedProcess(1);
                pm.pollStartedProcess(1);
                Assert.assertEquals(++stopCount, pm.getStopCount());
                Assert.assertEquals(++startCount, pm.getStartCount());
                svr2 = assertGetServer(processHandlerFactory, "Server:server-two");
                svr2.sendMessageToManager(ServerManagerProtocolCommand.SERVER_AVAILABLE);
                Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());
            }
        }
        Assert.assertEquals(addCount, pm.getAddCount());
        Assert.assertEquals(removeCount, pm.getRemoveCount());

        shutdownProcessManagerNoWait(pm);
    }

    public void testServersGetReconnectMessageFollowingRestartedServerManager_StartingDoesNotGetStarted() throws Exception {
        testServersGetReconnectMessageFollowingRestartedServerManager(ServerState.STARTING, false);
    }

    public void testServersGetReconnectMessageFollowingRestartedServerManager_StartedDoesNotGetStarted() throws Exception {
        testServersGetReconnectMessageFollowingRestartedServerManager(ServerState.STARTED, false);
    }

    public void testServersGetReconnectMessageFollowingRestartedServerManager_StoppingDoesNotGetStarted() throws Exception {
        testServersGetReconnectMessageFollowingRestartedServerManager(ServerState.STOPPING, false);
    }

    public void testServersGetReconnectMessageFollowingRestartedServerManager_StoppedDoesNotGetStarted() throws Exception {
        testServersGetReconnectMessageFollowingRestartedServerManager(ServerState.STARTED, false);
    }

    public void testServersGetReconnectMessageFollowingRestartedServerManager_BootingGetsStarted() throws Exception {
        testServersGetReconnectMessageFollowingRestartedServerManager(ServerState.BOOTING, true);
    }

    public void testServersGetReconnectMessageFollowingRestartedServerManager_AvailableGetsStarted() throws Exception {
        testServersGetReconnectMessageFollowingRestartedServerManager(ServerState.AVAILABLE, true);
    }

    public void testServersGetReconnectMessageFollowingRestartedServerManager_FailedGetsStarted() throws Exception {
        testServersGetReconnectMessageFollowingRestartedServerManager(ServerState.FAILED, true);
    }

    private void testServersGetReconnectMessageFollowingRestartedServerManager(ServerState state, boolean receiveConfig) throws Exception {
        setDomainConfigDir("standard");
        TestProcessHandlerFactory processHandlerFactory = new TestProcessHandlerFactory(true, false);
        final TestProcessManager pm = TestProcessManager.create(processHandlerFactory, InetAddress.getLocalHost(), 0);

        pm.pollAddedProcess(3); //SM + 2 servers
        pm.pollStartedProcess(3);

        TestServerManagerProcess sm = assertGetServerManager(processHandlerFactory);
        MockServerProcess svr1 = assertGetServer(processHandlerFactory, "Server:server-one");
        MockServerProcess svr2 = assertGetServer(processHandlerFactory, "Server:server-two");
        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_AVAILABLE, svr1, svr2);

        Assert.assertEquals("server-one", assertReadStartCommand(svr1).getServerName());
        Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());

        Assert.assertTrue(managerAlive(svr1.getSmAddress(), svr1.getSmPort()));

        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_STARTED, svr1, svr2);

        sm.stop();

        final TestServerManagerProcess proc = sm;
        new Thread(new Runnable() {
            @Override
            public void run() {
                proc.crashServerManager(1);
            }
        }).start();

        String newSmAddrAndPort = pm.waitForReconnectServers();
        Assert.assertEquals(newSmAddrAndPort, svr1.waitForReconnectServer());
        Assert.assertEquals(newSmAddrAndPort, svr2.waitForReconnectServer());
        int newSmPort = parsePort(newSmAddrAndPort);
        sm = assertGetServerManager(processHandlerFactory);

        svr1.reconnnectToServerManagerAndSendReconnectStatus(InetAddress.getLocalHost(), newSmPort, ServerState.STARTED);
        svr2.reconnnectToServerManagerAndSendReconnectStatus(InetAddress.getLocalHost(), newSmPort, state);

        Map<String, Server> servers = checkServerManagerServers(sm, 5000, new ServerManagerCheck("Server:server-one", ServerState.STARTED));
        Server two = servers.get("Server:server-two");
        Assert.assertNotNull(two);


        if (!receiveConfig) {
            try {
                svr2.awaitAndReadMessage(500);
                Assert.fail("Should not have received a command");
            } catch (RuntimeException expected) {
            }
            checkServerManagerServers(sm, 5000, new ServerManagerCheck("Server:server-two", state));

        } else {

            Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());
        }

        shutdownProcessManagerNoWait(pm);
    }


    @Override
    public void testServerGetsReconnectedFollowingBrokenConnection() throws Exception {
        setDomainConfigDir("standard");
        TestProcessHandlerFactory processHandlerFactory = new TestProcessHandlerFactory(true, false);
        final TestProcessManager pm = TestProcessManager.create(processHandlerFactory, InetAddress.getLocalHost(), 0);

        pm.pollAddedProcess(3); //SM + 2 servers
        pm.pollStartedProcess(3);

        TestServerManagerProcess sm = assertGetServerManager(processHandlerFactory);
        MockServerProcess svr1 = assertGetServer(processHandlerFactory, "Server:server-one");
        MockServerProcess svr2 = assertGetServer(processHandlerFactory, "Server:server-two");
        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_AVAILABLE, svr1, svr2);

        Assert.assertEquals("server-one", assertReadStartCommand(svr1).getServerName());
        Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());

        managerAlive(svr1.getSmAddress(), svr1.getSmPort());
        managerAlive(svr1.getPmAddress(), svr1.getPmPort());

        sendMessageToServerManager(ServerManagerProtocolCommand.SERVER_STARTED, svr1, svr2);

        svr2.closeServerManagerConnection();

        int newSmPort = parsePort("Server:server-two", pm.waitForReconnectServers());
        Assert.assertEquals(newSmPort, parsePort(svr2.waitForReconnectServer()));
        Assert.assertSame(sm, assertGetServerManager(processHandlerFactory));
        svr2.reconnnectToServerManagerAndSendReconnectStatus(InetAddress.getLocalHost(), newSmPort, ServerState.AVAILABLE);
        Assert.assertEquals("server-two", assertReadStartCommand(svr2).getServerName());

        try {
            svr2.awaitAndReadMessage(500);
            Assert.fail("Should not have any messages for server one");
        } catch (Exception expected) {
        }

        shutdownProcessManagerNoWait(pm);
    }

    private void shutdownProcessManagerNoWait(final TestProcessManager pm) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                pm.shutdown();
            }
        }).start();
    }

    private TestServerManagerProcess assertGetServerManager(TestProcessHandlerFactory processHandlerFactory) {
        TestProcessHandler handler = processHandlerFactory.getProcessHandler(ProcessManagerMaster.SERVER_MANAGER_PROCESS_NAME);
        Assert.assertNotNull(handler);
        TestServerManagerProcess mgr = handler.getServerManager();
        Assert.assertNotNull(mgr);
        return mgr;
    }

    private MockServerProcess assertGetServer(TestProcessHandlerFactory processHandlerFactory, String serverName) {
        TestProcessHandler handler = processHandlerFactory.getProcessHandler(serverName);
        Assert.assertNotNull(handler);
        MockServerProcess svr = handler.getMockServerProcess();
        Assert.assertNotNull(svr);
        return svr;
    }

    private void sendMessageToServerManager(ServerManagerProtocolCommand cmd, MockServerProcess...processes) throws IOException {
        for (MockServerProcess proc : processes) {
            proc.sendMessageToManager(cmd);
        }
    }

    private ServerManagerProtocolCommand.Command assertReadCommand(MockServerProcess svr, ServerManagerProtocolCommand expected) throws Exception {
        byte[] received = svr.awaitAndReadMessage();
        Assert.assertNotNull(received);
        ServerManagerProtocolCommand.Command cmd = ServerManagerProtocolCommand.readCommand(received);
        Assert.assertEquals(expected, cmd.getCommand());
        return cmd;
    }

    private ServerModel assertReadStartCommand(MockServerProcess svr) throws Exception {
        ServerManagerProtocolCommand.Command cmd = assertReadCommand(svr, ServerManagerProtocolCommand.START_SERVER);
        ServerModel cfg = ServerManagerProtocolUtils.unmarshallCommandData(ServerModel.class, cmd);
        Assert.assertNotNull(cfg);
        return cfg;
    }

    private int parsePort(String newAddressAndPort) throws Exception {
        Assert.assertNotNull(newAddressAndPort);
        String[] parts = newAddressAndPort.split(":");
        Assert.assertEquals(2, parts.length);
        Assert.assertEquals(InetAddress.getLocalHost().getHostAddress(), parts[0]);
        return Integer.parseInt(parts[1]);
    }

    private int parsePort(String expectedServerName, String processNameAddressAndPort) throws Exception {
        Assert.assertNotNull(processNameAddressAndPort);
        String[] parts = processNameAddressAndPort.split(TestProcessManager.SERVER_NAME_AND_CONNECTION_SEPARATOR);
        Assert.assertEquals(2, parts.length);
        Assert.assertEquals(expectedServerName, parts[0]);
        return parsePort(parts[1]);
    }

    private Map<String, Server> checkServerManagerServers(ServerManager sm, int timeoutMs, ServerManagerCheck...checks) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            Map<String, Server> servers = sm.getServers();

            boolean allMatched = true;
            for (ServerManagerCheck check : checks) {
                Server server = servers.get(check.getServerName());
                if (server == null || server.getState() != check.getState()) {
                    allMatched = false;
                    break;
                }
            }
            if (allMatched)
                break;
        }

        Map<String, Server> servers = sm.getServers();
        for (ServerManagerCheck check : checks) {
            Server server = servers.get(check.getServerName());
            Assert.assertNotNull(server);
            Assert.assertSame("Actual state of " + check.getServerName() + " was: " + server.getState(), check.getState(), server.getState());
        }
        return servers;
    }

    private static class ServerManagerCheck {
        String serverName;
        ServerState state;

        public ServerManagerCheck(String serverName, ServerState state) {
            super();
            this.serverName = serverName;
            this.state = state;
        }

        public String getServerName() {
            return serverName;
        }

        public ServerState getState() {
            return state;
        }
    }
}
