package testin.client;

import org.junit.jupiter.api.Test;
import org.phoebus.pv.pvws.client.HeartbeatHandler;
import org.phoebus.pv.pvws.client.PVWS_Client;
import org.phoebus.pv.pvws.client.ReconnectHandler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.Mockito.*;

public class Reconnect {

    @Test
    public void testReconnectSuccess() throws Exception {
        // Mock client
        PVWS_Client mockClient = mock(PVWS_Client.class);
        HeartbeatHandler mockHeartbeat = mock(HeartbeatHandler.class);
        when(mockClient.getHeartbeatHandler()).thenReturn(mockHeartbeat);
        when(mockClient.reconnectBlocking()).thenReturn(true);
        when(mockClient.isOpen()).thenReturn(true);

        // Use a direct executor to avoid scheduling delays
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        ReconnectHandler handler = new ReconnectHandler(mockClient, executor);

        // Attempt reconnect
        handler.attemptReconnect();

        // Give a tiny delay for runnable to execute
        Thread.sleep(100);

        // Verify heartbeat restarted and reconnecting flag cleared
        verify(mockHeartbeat, times(1)).start();
        assert(!handler.isReconnecting());
    }

    @Test
    public void testReconnectAlreadyInProgress() {
        PVWS_Client mockClient = mock(PVWS_Client.class);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        ReconnectHandler handler = new ReconnectHandler(mockClient, executor);

        // Manually set reconnecting to true
        handler.attemptReconnect();
        handler.attemptReconnect(); // second attempt should not run

        // No exception thrown and flag remains true
        assert(handler.isReconnecting());
    }
}
