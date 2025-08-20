package org.phoebus.pv.pvws.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.epics.vtype.VType;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.phoebus.pv.PV;
import org.phoebus.pv.PVPool;
import org.phoebus.pv.RefCountMap;
import org.phoebus.pv.pvws.PVWS_Context;
import org.phoebus.pv.pvws.models.pv.PvwsData;
import org.phoebus.pv.pvws.models.pv.PvwsMetadata;
import org.phoebus.pv.pvws.models.temp.SubscribeMessage;
import org.phoebus.pv.pvws.utils.pv.VArrDecoder;
import org.phoebus.pv.pvws.utils.pv.toVType;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.phoebus.pv.pvws.utils.pv.MetadataHandler;

public class PVWS_Client extends WebSocketClient {
    private static final Logger console = Logger.getLogger(PVWS_Client.class.getName());
        public final ObjectMapper mapper;
        private final CountDownLatch latch;
        private HeartbeatHandler heartbeatHandler;
        private ReconnectHandler reconnectHandler;
        private final List<PVListener> listeners = new CopyOnWriteArrayList<>();


        public void addPVListener(PVListener listener) {
            listeners.add(listener);
        }

        public void removePVListener(PVListener listener) {
            listeners.remove(listener);
        }

        private volatile boolean dropped = false; // detects disconnects



    public PVWS_Client(URI serverUri, CountDownLatch latch, ObjectMapper mapper) {
            super(serverUri);
            this.latch = latch;
            this.mapper = mapper;
            // Java-WebSocket detect dead connections & fires onClose/onError is no pong is received
            setConnectionLostTimeout(20); // 20 seconds

            addPVListener((pvName, value) -> {
                try {
                    PV pv = PVPool.getPV(pvName);
                    try {
                        pv.update(value);
                    } finally {
                        PVPool.releasePV(pv);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace(); // or use Logger
                }
            });
        }


        @Override
        public void onOpen(ServerHandshake handshakedata) {
            try {
                console.log(Level.INFO, "Connected to server");
               if (!reconnectHandler.isReconnecting())
               {
                   latch.countDown();
               }
               // Reset Dropped Flag
               dropped = false;
               reconnectHandler.resetStatus();
               // Start Heartbeat
               heartbeatHandler.start();
               // Re add the subscriptions
                PVWS_Context.getInstance().restoreSubscriptions();
            } catch (Exception e) {
                console.log(Level.SEVERE, "Exception in onOpen: " + e.getMessage(), e);
            }
        }


    @Override
    public void onMessage(String message) {
        System.out.println("MESSAGE ACQUIRED 💪💪💪💪💪💪💪💪💪: " + message);
        console.log(Level.INFO, "Received: " + message);

        try {
            JsonNode node = mapper.readTree(message);
            mapMetadata(node);

            String type = node.get("type").asText();
            switch (type) {
                case "update":
                    PvwsData pvObj = mapper.treeToValue(node, PvwsData.class);

                    // Ignore RTYP messages if not needed
                    if (pvObj.getPv().endsWith(".RTYP")) {
                        return;
                    }

                    VArrDecoder.decodeArrValue(node, pvObj);

                    updateSeverity(node, pvObj);
                    mergeMetadata(pvObj);

                    // First message may be incomplete -> re-subscribe
                    if (node.get("value") == null
                            && node.get("b64flt") == null
                            && node.get("b64dbl") == null
                            && node.get("b64srt") == null
                            && node.get("b64byt") == null) {
                        sendSubscription("subscribe", pvObj.getPv());
                        return;
                    }

                    // Convert incoming data to VType
                    VType vVal = toVType.convert(pvObj);
                    String pvName = "pvws://" + pvObj.getPv();

                    // 🔹 Notify all listeners instead of directly touching PVPool
                    for (PVListener listener : listeners) {
                        listener.onPVUpdate(pvName, vVal);
                    }

                    // 🔹 For containsPv check, we still need a PV object
                    PV pv = PVPool.getPV(pvName);
                    try {
                        if (!containsPv(pv)) {
                            System.out.println("PV CURRENTLY NOT IN PHOEBUS😤😤 sending unsubscribe message for: " + pvObj.getPv());
                            sendSubscription("clear", pvObj.getPv());
                        }
                    } finally {
                        PVPool.releasePV(pv); // always release to avoid leaks
                    }

                    break;

                default:
                    console.log(Level.WARNING, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            console.log(Level.SEVERE, "Error parsing or processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static boolean containsPv(PV target) {
        // Get all PV entries in the pool
        Collection<RefCountMap.ReferencedEntry<PV>> entries = PVPool.getPVReferences();

        // Check if any entry wraps the target PV
        for (RefCountMap.ReferencedEntry<PV> entry : entries) {
            if (entry.getEntry().equals(target)) {
                return true;
            }
        }
        return false;
    }

        private void mapMetadata(JsonNode node) throws JsonProcessingException {

            PvwsMetadata pvMeta = mapper.treeToValue(node, PvwsMetadata.class);

            if (pvMeta.getVtype() != null)
                MetadataHandler.setData(pvMeta); // comment this line out to test missing

        }

        private void sendSubscription(String type, String pv) throws JsonProcessingException {

            SubscribeMessage msg = new SubscribeMessage();
            msg.setType(type);
            List<String> pvs = new ArrayList<>(List.of(pv));
            msg.setPvs(pvs);
            String json = this.mapper.writeValueAsString(msg);
            this.send(json);
        }

        private void updateSeverity(JsonNode node, PvwsData pvData)
        {
            if (node.has("severity"))// if severity changes set it in cached value
            {
                String currPV = pvData.getPv();
                String currSeverity = pvData.getSeverity();
                MetadataHandler.pvMetaMap.get(currPV).setSeverity(currSeverity);
            }
        }

        private void mergeMetadata(PvwsData pvData) throws IOException {
            JsonNode nodeMerge = mapper.valueToTree(MetadataHandler.pvMetaMap.get(pvData.getPv()));
            mapper.readerForUpdating(pvData).readValue(nodeMerge);
        }


        @Override
        public void onClose(int code, String reason, boolean remote) {
            // Mark as dropped for anything that's not a normal, local close
            // 1000 = normal closure. If remote==true or code!=1000 treat as drop.
            if (remote || code != 1000) {
                dropped = true;
            }
            console.log(Level.WARNING, "Disconnected. code=" + code + " remote=" + remote + " reason=" + reason);
            if(heartbeatHandler != null)
            {
                heartbeatHandler.stop();
            }

            attemptReconnect();
        }

        @Override
        public void onError(Exception ex) {
            // If there is an error
            dropped = true;
            console.log(Level.SEVERE, "WebSocket Error: " + ex.getMessage());
            // Stops the heartbeat
            if(heartbeatHandler != null)
            {
                heartbeatHandler.stop();
            }
            // Calls the reconnect
            attemptReconnect();

        }
        //allow callers to check if the last close was a drop
        // Not used might delete idk....
        public boolean wasDropped() {
            return dropped;
        }
            /* TODO: HEARTBEAT AND RECONN HANDLER
            heartbeatHandler.stop();
            attemptReconnect();

             */
            //this.close();



        public void closeClient() {
            this.close();
        }

    // Attempt reconnect method (might move into actual reconnect class)
    public void attemptReconnect() {
        if (reconnectHandler != null) {
            reconnectHandler.attemptReconnect();
        } else {
            console.log(Level.WARNING, "ReconnectHandler is not set. Cannot reconnect.");
        }
    }


    public void setHeartbeatHandler(HeartbeatHandler heartbeatHandler) {
            this.heartbeatHandler = heartbeatHandler;
        }

        public void setReconnectHandler(ReconnectHandler reconnectHandler) {
            this.reconnectHandler = reconnectHandler;
        }

        public HeartbeatHandler getHeartbeatHandler() {
            return heartbeatHandler;
        }

        public ReconnectHandler getReconnectHandler() {
            return reconnectHandler;
        } // Also not used might need to be removed



        @Override
        public void onWebsocketPong(WebSocket conn, Framedata f) {
            console.log(Level.INFO, "Received Pong frame"); // you could also comment this out to test the heartbeat timeout just for visual clarity
            heartbeatHandler.setLastPongTime(System.currentTimeMillis());
            super.onWebsocketPong(conn, f);
        }

    }


