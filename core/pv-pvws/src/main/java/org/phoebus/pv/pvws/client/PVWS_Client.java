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
import org.phoebus.pv.pvws.utils.pv.MetadataHandler;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import java.util.logging.Level;

public class PVWS_Client extends WebSocketClient {
    public static final Logger console = Logger.getLogger(PVWS_Client.class.getName());

    public final ObjectMapper mapper;
    private final CountDownLatch latch;
    private HeartbeatHandler heartbeatHandler;
    private ReconnectHandler reconnectHandler;
    private volatile boolean dropped = false; // detects disconnects

    public PVWS_Client(URI serverUri, ObjectMapper mapper, CountDownLatch latch) {
        super(serverUri);
        this.mapper = mapper;
        this.latch = latch;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        try {
            console.log(Level.INFO, "Connected to server");
            if (!reconnectHandler.isReconnecting()) {
                latch.countDown();
            }
            dropped = false; // reset dropped flag
            reconnectHandler.resetStatus();
            heartbeatHandler.start(); // start heartbeat
            PVWS_Context.getInstance().restoreSubscriptions(); // re-add subscriptions
        } catch (Exception e) {
            console.log(Level.SEVERE, "Exception in onOpen: " + e.getMessage(), e);
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

                    // first message may be ack/nack → resubscribe
                    if (isAckMessage(node)) {
                        sendSubscription("subscribe", pvObj.getPv());
                        return;
                    }

                    if (pvObj.getPv().endsWith(".RTYP"))
                        return; // ignore RTYP metadata messages

                    VArrDecoder.decodeArrValue(node, pvObj);
                    updateSeverity(node, pvObj);
                    mergeMetadata(pvObj);

                    VType vVal = toVType.convert(pvObj);
                    applyUpdate(pvObj, vVal);
                    break;

                default:
                    console.log(Level.WARNING, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            console.log(Level.SEVERE, "Error parsing or processing message: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (remote || code != 1000) {
            dropped = true;
        }
        console.log(Level.WARNING, "Disconnected. code=" + code + " remote=" + remote + " reason=" + reason);

        if (heartbeatHandler != null) {
            heartbeatHandler.stop();
        }
        attemptReconnect(); // probably does not need this line.
    }

    @Override
    public void onError(Exception ex) {
        dropped = true;
        console.log(Level.SEVERE, "WebSocket Error: " + ex.getMessage());

        if (heartbeatHandler != null) {
            heartbeatHandler.stop();
        }
        attemptReconnect();
    }

    @Override
    public void onWebsocketPong(WebSocket conn, Framedata f) {
        console.log(Level.INFO, "Received Pong frame");
        if (heartbeatHandler != null) {
            heartbeatHandler.setLastPongTime(System.currentTimeMillis());
        }
        super.onWebsocketPong(conn, f);
    }

    // ===== helper methods =====

    private boolean containsPv(PV target) {
        Collection<RefCountMap.ReferencedEntry<PV>> entries = PVPool.getPVReferences();
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
            MetadataHandler.setData(pvMeta);
    }

    private void applyUpdate(PvwsData pvObj, VType vVal) throws JsonProcessingException {
        if (PVWS_Context.contextMap.get(pvObj.getPv()) == null ||
                !containsPv(PVWS_Context.contextMap.get(pvObj.getPv()))) {
            sendSubscription("clear", pvObj.getPv());
            PVWS_Context.contextMap.get(pvObj.getPv()).disconnectPV();
        } else {
            PVWS_Context.contextMap.get(pvObj.getPv()).updatePV(vVal);
        }
    }

    private void sendSubscription(String type, String pv) throws JsonProcessingException {
        SubscribeMessage msg = new SubscribeMessage();
        msg.setType(type);
        msg.setPvs(new ArrayList<>(List.of(pv)));
        String json = this.mapper.writeValueAsString(msg);
        this.send(json);
    }

    private void updateSeverity(JsonNode node, PvwsData pvData) {
        if (node.has("severity")) {
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
    }

    private void mergeMetadata(PvwsData pvData) throws IOException {
        JsonNode nodeMerge = mapper.valueToTree(MetadataHandler.pvMetaMap.get(pvData.getPv()));
        mapper.readerForUpdating(pvData).readValue(nodeMerge);
    }

    public boolean wasDropped() {
        return dropped;
    }

    public void closeClient() {
        this.close();
    }

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
}
