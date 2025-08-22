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

import org.phoebus.pv.pvws.PVWS_PV;

import org.phoebus.pv.pvws.models.pv.PvwsData;
import org.phoebus.pv.pvws.models.pv.PvwsMetadata;
import org.phoebus.pv.pvws.models.temp.SubscribeMessage;
import org.phoebus.pv.pvws.utils.pv.VArrDecoder;
import org.phoebus.pv.pvws.utils.pv.toVType;

import java.io.IOException;
import java.net.URI;
import java.util.*;
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

        private volatile boolean dropped = false; // detects disconnects

        public PVWS_Client(URI serverUri, CountDownLatch latch, ObjectMapper mapper) {
            super(serverUri);
            this.latch = latch;
            this.mapper = mapper;
            // Java-WebSocket detect dead connections & fires onClose/onError is no pong is received
            setConnectionLostTimeout(20); // 20 seconds
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
    }


    @Override
    public void onMessage(String message) {
        console.log(Level.INFO, "Received: " + message);
        try {
            JsonNode node = mapper.readTree(message);
            mapMetadata(node);

            String type = node.get("type").asText();
            switch (type) {
                case "update":
                    PvwsData pvObj = mapper.treeToValue(node, PvwsData.class);

                    // First message does not contain useful data, so resubscribe is needed.
                    if (isAckMessage(node)) // first message may be for ACK/NACK but im unsure.
                    {
                        sendSubscription("subscribe", pvObj.getPv());
                        return;
                    }

                        /* TODO: ADD REFETCH FUNCTIONALITY
                        if (!MetadataHandler.pvMetaMap.containsKey(pvObj.getPv())) {

                            final int MAX_SUBSCRIBE_ATTEMPTS = 5;
                            MetadataHandler.refetch(MAX_SUBSCRIBE_ATTEMPTS, pvObj, this);
                            return;
                        }*/
                    //subscribeAttempts.remove(pvObj.getPv()); // reset retry count if we got the meta data

                    if (pvObj.getPv().endsWith(".RTYP"))
                        return; //these messages contain metadata that won't affect functionality.


                    VArrDecoder.decodeArrValue(node, pvObj);

                    // TODO: NEEDS separate class/map to handle this specific severity data
                    // Curently status is not sent so severity being in the same map is fine.
                    updateSeverity(node, pvObj);

                    mergeMetadata(pvObj);

                    VType vVal = toVType.convert(pvObj);
                    applyUpdate(pvObj, vVal);


                    break;
                default:
                    console.log(Level.WARNING, "Unknown message type: " + type);

            }
        } catch (Exception e) {
            console.log(Level.SEVERE, "Error parsing or processing message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean containsPv(PV target) {
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

    private void applyUpdate(PvwsData pvObj, VType vVal) throws JsonProcessingException {

        if (PVWS_Context.contextMap.get(pvObj.getPv()) == null || !containsPv(PVWS_Context.contextMap.get(pvObj.getPv()))) // IF CURRENT PV UPDATE NOT IN PHOEBUS'S PVPOOL THEN UNSUBSCRIBE
        {
            sendSubscription("clear", pvObj.getPv());
            PVWS_Context.contextMap.get(pvObj.getPv()).disconnectPV();
        } else {
            PVWS_Context.contextMap.get(pvObj.getPv()).updatePV(vVal);
        }
    }


    private void sendSubscription(String type, String pv) throws JsonProcessingException {

        SubscribeMessage msg = new SubscribeMessage();

        msg.setType(type);
        List<String> pvs = new ArrayList<>(List.of(pv));
        msg.setPvs(pvs);
        String json = this.mapper.writeValueAsString(msg);
        this.send(json);


    }


    private void updateSeverity(JsonNode node, PvwsData pvData) {
        if (node.has("severity"))// if severity changes set it in cached value
        {
            String currPV = pvData.getPv();
            String currSeverity = pvData.getSeverity();
            MetadataHandler.pvMetaMap.get(currPV).setSeverity(currSeverity);
        }
    }

    private boolean isAckMessage(JsonNode node) {

        return node.get("value") == null
                && node.get("b64flt") == null
                && node.get("b64dbl") == null
                && node.get("b64srt") == null
                && node.get("b64byt") == null;

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
      
    }

    private void mergeMetadata(PvwsData pvData) throws IOException {
        JsonNode nodeMerge = mapper.valueToTree(MetadataHandler.pvMetaMap.get(pvData.getPv()));
        mapper.readerForUpdating(pvData).readValue(nodeMerge);
    }




    //allow callers to check if the last close was a drop
    public boolean wasDropped() {
        return dropped;
    }

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

        @Override
        public void onWebsocketPong(WebSocket conn, Framedata f) {
            console.log(Level.INFO, "Received Pong frame"); // you could also comment this out to test the heartbeat timeout just for visual clarity
            heartbeatHandler.setLastPongTime(System.currentTimeMillis());
            super.onWebsocketPong(conn, f);
        }

    }



