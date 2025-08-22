package org.phoebus.pv.pvws.client;


import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class HeartbeatHandler {

    private final PVWS_Client client;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> heartbeatTask;
    private volatile long lastPongTime;
    private final long heartbeatInterval;
    private final long heartbeatTimeout;


    public HeartbeatHandler(PVWS_Client client,
                            ScheduledExecutorService scheduler,
                            long heartbeatInterval,
                            long heartbeatTimeout) {
        this.client = client;
        this.scheduler = scheduler;
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatTimeout = heartbeatTimeout;
    }


    public void start()
    {
        if (heartbeatTask != null && !heartbeatTask.isCancelled())
        {
            PVWS_Client.console.log(Level.INFO, "HeartbeatHandler is already started");
           // System.out.println("Heartbeat Task already running❤️, skipping start ...");
            return;
        }
        // Initializing lastpongtime so to avoid a false reconnect
        lastPongTime = System.currentTimeMillis();

        heartbeatTask = scheduler.scheduleAtFixedRate(() ->
        {
            try {
                //System.out.println(" Heartbeat loop running");
                client.sendPing();
                System.out.println("Ping sent");
                scheduler.schedule(() -> {
                    long elapsed = System.currentTimeMillis() - lastPongTime;
                    if (elapsed > heartbeatTimeout)
                    {
                        System.out.println("Heartbeat timeout.( " + elapsed +"ms ). Reconnecting...");
                        client.attemptReconnect();
                    }
                }, 3, TimeUnit.SECONDS);
            } catch (Exception e) {
                // If there is no answer to the ping then reconnect is called
                System.out.println("Heartbeat error: " + e.getMessage());
                client.attemptReconnect();
            }
        }, 0, heartbeatInterval, TimeUnit.MILLISECONDS);

    }

    public void stop(){
        if (heartbeatTask != null)
        {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
    }

    public void setLastPongTime(Long lastPongTime)
    {
        this.lastPongTime = lastPongTime;
    }

    public long getHeartbeatInterval()
    {
        return heartbeatInterval;
    }

    public long getHeartbeatTimeout()
    {
        return heartbeatTimeout;
    }

}