package org.phoebus.pv.pvws;

import org.junit.jupiter.api.*;
import org.phoebus.pv.pvws.client.HeartbeatHandler;
import org.phoebus.pv.pvws.client.PVWS_Client;

import java.util.concurrent.*;

import static org.mockito.Mockito.*;

class HeartbeatHandlerTest {

    PVWS_Client client;
    ScheduledExecutorService scheduler;
    HeartbeatHandler handler;

    @BeforeEach
    void setup() {
        client = mock(PVWS_Client.class);

        // Use a real scheduled executor, but small thread pool
        scheduler = Executors.newSingleThreadScheduledExecutor();

        // 100ms heartbeat interval, 500ms timeout
        handler = new HeartbeatHandler(client, scheduler, 100, 500);
    }

    @AfterEach
    void teardown() {
        handler.stop();
        scheduler.shutdownNow();
    }

    @Test
    void sendsPingOnStart() throws InterruptedException {
        handler.start();

        // Wait long enough for at least 2 heartbeats
        Thread.sleep(250);

        verify(client, atLeast(2)).sendPing();
    }

    @Test
    void reconnectsOnTimeout() throws InterruptedException {
        handler.start();

        // Set pong time far in the past so timeout triggers
        handler.setLastPongTime(System.currentTimeMillis() - 1000);

        // Wait for timeout check to run
        Thread.sleep(3500); // > 3s so the inner scheduled timeout runs

        verify(client, atLeastOnce()).attemptReconnect();
    }

    @Test
    void noReconnectIfPongIsFresh() throws InterruptedException {
        handler.start();

        // Schedule periodic updates to lastPongTime to simulate fresh pongs
        ScheduledExecutorService updater = Executors.newSingleThreadScheduledExecutor();
        updater.scheduleAtFixedRate(() -> {
            handler.setLastPongTime(System.currentTimeMillis());
        }, 0, 100, TimeUnit.MILLISECONDS);

        // Wait 3.5 seconds during which lastPongTime is refreshed repeatedly
        Thread.sleep(3500);

        // Shutdown the updater
        updater.shutdownNow();

        // Verify reconnect was never called because pong stayed fresh
        verify(client, never()).attemptReconnect();
    }


    @Test
    void stopCancelsHeartbeat() throws InterruptedException {
        handler.start();
        handler.stop();

        int beforeCount = mockingDetails(client).getInvocations().size();

        // Wait longer than interval — no more pings should happen
        Thread.sleep(200);

        int afterCount = mockingDetails(client).getInvocations().size();
        Assertions.assertEquals(beforeCount, afterCount, "Heartbeat still running after stop()");
    }
}

