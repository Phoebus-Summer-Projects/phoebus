package org.phoebus.pv.pvws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.phoebus.pv.pvws.client.PVWS_Client;
import org.phoebus.pv.pvws.client.HeartbeatHandler;
import org.phoebus.pv.pvws.client.ReconnectHandler;
import org.phoebus.pv.pvws.models.temp.SubscribeMessage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

public class PVWS_Context {

    // Singleton instance of PVWS_Context
    private static volatile PVWS_Context instance;

    private final PVWS_Client client;

    //Track active subscriptions
    private final Set<String> subscriptions = new ConcurrentSkipListSet<>();

    private PVWS_Context() throws Exception {
        PVWS_Preferences.getInstance().installPreferences();
        URI serverUri = new URI(System.getProperty("PVWS_ADDRESS"));
        this.client = initializeClient(serverUri);
        // Auto-unsubscribe if Phoebus exits
        installShutdownHook();
    }

    // Thread-safe singleton getter with lazy initialization
    public static PVWS_Context getInstance() throws Exception {
        if (instance == null) {
            synchronized (PVWS_Context.class) {
                if (instance == null) {
                    instance = new PVWS_Context();
                }
            }
        }
        return instance;
    }

    // Public access to the client
    public PVWS_Client getClient() {
        return client;
    }

    // Initialization logic
    private PVWS_Client initializeClient(URI serverUri) throws URISyntaxException, InterruptedException, JsonProcessingException {
        CountDownLatch latch = new CountDownLatch(1);
        ObjectMapper mapper = new ObjectMapper();
        PVWS_Client client = new PVWS_Client(serverUri,latch, mapper);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        HeartbeatHandler heartbeatHandler = initializeHeartbeatHandler(client, scheduler);
        client.setHeartbeatHandler(heartbeatHandler);

        ReconnectHandler reconnectHandler = initializeReconnectHandler(client, scheduler);
        client.setReconnectHandler(reconnectHandler);

        Thread connectThread = new Thread(()->
        {
            boolean connected = false;
            while (!connected) {
                try{
                    client.connectBlocking();
                    connected = true;
                }
                catch (Exception e){
                    System.out.println("Server not available, retrying in 5 seconds...");
                    try{
                        Thread.sleep(5000);
                    }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        },"PVWS-Connect-Retry");
        connectThread.setDaemon(true);
        connectThread.start();
        return client;
    }

    // Initialize heartbeat and reconnect handlers
    private static HeartbeatHandler initializeHeartbeatHandler(PVWS_Client client, ScheduledExecutorService scheduler) {
        final long HEARTBEAT_INTERVAL = 10000;  // 10 sec
        final long HEARTBEAT_TIMEOUT = 15000;   // 15 sec
        return new HeartbeatHandler(client, scheduler, HEARTBEAT_INTERVAL, HEARTBEAT_TIMEOUT);
    }

    private static ReconnectHandler initializeReconnectHandler(PVWS_Client client, ScheduledExecutorService scheduler) {
        return new ReconnectHandler(client, scheduler);
    }

    public void clientSubscribe(String base_name) throws JsonProcessingException {

        SubscribeMessage message = new SubscribeMessage();
        message.setType("subscribe");

        List<String> pv = new ArrayList<>(List.of(base_name));
        message.setPvs(pv);
        String json = getClient().mapper.writeValueAsString(message);
        getClient().send(json);

        subscriptions.addAll(pv); //remember subscription
    }
    // helper for targeted unsubscribe
    public void clientUnsubscribe(List<String> pvs) throws JsonProcessingException {
        if (pvs == null || pvs.isEmpty()) return;
        SubscribeMessage message = new SubscribeMessage();
        message.setType("clear");
        message.setPvs(pvs);
        String json = getClient().mapper.writeValueAsString(message);
        getClient().send(json);
        subscriptions.removeAll(pvs);
    }
    // Called after reconnect to add back subscriptions
    public void restoreSubscriptions() {
        if (subscriptions.isEmpty()) return;
        try {
            SubscribeMessage message = new SubscribeMessage();
            message.setType("subscribe");
            message.setPvs(new ArrayList<>(subscriptions));
            String json = client.mapper.writeValueAsString(message);
            client.send(json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Unsubscribed used during shutdown
    private void unsubscribeAllQuietly() {
        try {
            if (!subscriptions.isEmpty() && client != null && client.isOpen()) {
                SubscribeMessage message = new SubscribeMessage();
                message.setType("clear");
                message.setPvs(new ArrayList<>(subscriptions));
                String json = client.mapper.writeValueAsString(message);
                client.send(json);
            }
        } catch (Exception ignore) {
        } finally {
            subscriptions.clear();
        }
    }

    // Shutdown method (optional)
    public void shutdown() {
        if (client != null) {
            client.closeClient();
        }
    }
    //Shutdown hook to catch app exit
    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                unsubscribeAllQuietly();
                if (client != null && client.isOpen()) {
                    client.closeClient();
                }
            } catch (Exception ignored) {
            }
        }, "pvws-auto-unsubscribe"));
    }
}
