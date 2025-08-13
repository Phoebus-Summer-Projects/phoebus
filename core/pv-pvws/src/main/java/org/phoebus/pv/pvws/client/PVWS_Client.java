package org.phoebus.pv.pvws.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
//import jdk.swing.interop.LightweightFrameWrapper;
import org.epics.vtype.VType;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.phoebus.pv.PVPool;
import org.phoebus.pv.pvws.PVWS_PV;
import org.phoebus.pv.pvws.models.pv.PvwsData;
import org.phoebus.pv.pvws.models.pv.PvwsMetadata;
import org.phoebus.pv.pvws.models.temp.SubscribeMessage;
import org.phoebus.pv.pvws.utils.pv.VArrDecoder;
import org.phoebus.pv.pvws.utils.pv.toVType;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.phoebus.pv.pvws.utils.pv.MetadataHandler;

public class PVWS_Client extends WebSocketClient {
    private static final Logger console = Logger.getLogger(PVWS_Client.class.getName());
        public final ObjectMapper mapper;
        private final CountDownLatch latch;
        /*
        private SubscriptionHandler subHandler;
        private MetadataHandler metadataHandler;
         */

        private HeartbeatHandler heartbeatHandler;
        private ReconnectHandler reconnectHandler;


        // List of PVS so that if the server disconnects then the Pvs are saved(Might change this bit of code into another class)
        private List<String> subscribedPVs = new ArrayList<>();

        public void subscribe(String pvName)
        {
            if(!subscribedPVs.contains(pvName))
            {
                subscribedPVs.add(pvName);
            }
            sendSubscribeMessage(List.of(pvName));
        }

        private void sendSubscribeMessage(List<String> pvs)
        {
            try
            {
                SubscribeMessage msg = new SubscribeMessage();
                msg.setType("subscribe");
                msg.setPvs(new ArrayList<>(pvs));
                String json = mapper.writeValueAsString(msg);
                console.log(Level.INFO,"[PVWS_Client] Sending subscribe message: " + json);
                send(json);
            }
            catch(Exception e)
            {
                console.log(Level.SEVERE, "Failed to send subscribe message", e.getMessage());
            }

        }




    public PVWS_Client(URI serverUri, CountDownLatch latch, ObjectMapper mapper) {
            super(serverUri);
            this.latch = latch;
            this.mapper = mapper;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            //try {
                console.log(Level.INFO, "Connected to server");

                // Adding reconnect and heartbeat on open
                if(reconnectHandler != null)
                {
                    reconnectHandler.resetStatus();
                    console.log(Level.INFO, "[PVWS_Client] ReconnectHandler status reset.");
                }

                if(heartbeatHandler != null)
                {
                    heartbeatHandler.start();
                    console.log(Level.INFO, "[PVWS_Client] Heartbeat started.");
                }

                // Resubscribes after reconnect
            if (heartbeatHandler != null) {
                heartbeatHandler.start();
                console.log(Level.INFO, "[PVWS_Client] Heartbeat started.");
            }

            if (!subscribedPVs.isEmpty()) {
                console.log(Level.INFO, "[PVWS_Client] Resubscribing to PVs after reconnect: " + subscribedPVs);

                try {
                    // 1. Send batch unsubscribe
                    sendUnsubscribeMessageBatch(subscribedPVs);

                    // 2. Wait briefly for server to process unsubscribes
                    Thread.sleep(200);  // adjust if needed for your server

                    // 3. Send batch subscribe to force updates
                    sendSubscribeMessage(subscribedPVs);

                } catch (InterruptedException e) {
                    console.log(Level.WARNING, "Interrupted during resubscribe: " + e.getMessage());
                    Thread.currentThread().interrupt(); // restore interrupt status
                } catch (Exception e) {
                    console.log(Level.SEVERE, "Unexpected error during resubscribe: " + e.getMessage());
                }
            }


            latch.countDown();


           /*}
            catch (Exception e)
            {
                console.log(Level.SEVERE, "Exception in onOpen: " + e.getMessage(), e);
            }

            */
        }


    private void sendUnsubscribeMessageBatch(List<String> pvs) {
        try {
            SubscribeMessage msg = new SubscribeMessage();
            msg.setType("unsubscribe");
            msg.setPvs(new ArrayList<>(pvs));
            String json = mapper.writeValueAsString(msg);
            console.log(Level.INFO, "[PVWS_Client] Sending batch unsubscribe message: " + json);
            send(json);
        } catch (Exception e) {
            console.log(Level.SEVERE, "Failed to send batch unsubscribe message", e);
        }
    }


    private void sendUnsubscribeMessage(String pvName) {
        try {
            SubscribeMessage msg = new SubscribeMessage();
            msg.setType("unsubscribe");
            msg.setPvs(List.of(pvName));
            String json = mapper.writeValueAsString(msg);
            console.log(Level.INFO, "[PVWS_Client] Sending unsubscribe message: " + json);
            send(json);
        } catch (Exception e) {
            console.log(Level.SEVERE, "Failed to send unsubscribe message", e);
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

                        /* TODO: ADD REFETCH FUNCTIONALITY
                        if (!MetadataHandler.pvMetaMap.containsKey(pvObj.getPv())) {

                            final int MAX_SUBSCRIBE_ATTEMPTS = 5;
                            MetadataHandler.refetch(MAX_SUBSCRIBE_ATTEMPTS, pvObj, this);
                            return;

                        }*/


                        if (pvObj.getPv().endsWith(".RTYP")) {
                            return;
                        }
                        VArrDecoder.decodeArrValue(node, pvObj);


                        //subscribeAttempts.remove(pvObj.getPv()); // reset retry count if we got the meta data

                        // TODO: NEEDS separate class to handle this specific severity data and probably status too
                        updateSeverity(node, pvObj);

                        //merges class PV and json node of metadata together


                        //toVType.convert(pvObj);

                        mergeMetadata(pvObj);

                        VType vVal = toVType.convert(pvObj);

                        String pvname = ("pvws://" + pvObj.getPv());



                            PVPool.getPV(pvname).update(vVal);




                        break;
                    default:
                        console.log(Level.WARNING, "Unknown message type: " + type);

                }
            } catch (Exception e) {
                console.log(Level.SEVERE,"Error parsing or processing message: " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void mapMetadata(JsonNode node) throws JsonProcessingException {

            PvwsMetadata pvMeta = mapper.treeToValue(node, PvwsMetadata.class);

            if (pvMeta.getVtype() != null)
                MetadataHandler.setData(pvMeta); // comment this line out to test missing

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
            console.log(Level.WARNING, "Disconnected. Reason: " + reason);
            // Ensuring heartbeat stops and reconnect is called

            if(heartbeatHandler != null)
            {
                heartbeatHandler.stop();
            }

            if(reconnectHandler != null)
            {
                reconnectHandler.attemptReconnect();
            }

            latch.countDown();
        }

        @Override
        public void onError(Exception ex) {
            console.log(Level.SEVERE,"WebSocket Error: " + ex.getMessage());
            // If disconnected stop heartbeat and call reconnect

            if(heartbeatHandler != null)
            {
                heartbeatHandler.stop();
            }

            if(reconnectHandler != null)
            {
                reconnectHandler.attemptReconnect();
            }

        }


        public void closeClient() {
            this.close();
        }




        /* TODO: ADD HANDLERS
        public void setSubscriptionHandler(SubscriptionHandler subHandler) {
            this.subHandler = subHandler;
        }

        public void setMetadataHandler(MetadataHandler metadataHandler) {
            this.metadataHandler = metadataHandler;
        }

        public void subscribeClient(String[] pvs) throws JsonProcessingException {
            subHandler.subscribe(pvs);
        }

        public void unSubscribeClient(String[] pvs) throws JsonProcessingException {
            subHandler.unSubscribe(pvs);
        }

         */

        public void attemptReconnect() {
            this.reconnectHandler.attemptReconnect();
        }

        public void setHeartbeatHandler(HeartbeatHandler heartbeatHandler) {
        this.heartbeatHandler = heartbeatHandler;
        }

        public void setReconnectHandler(ReconnectHandler reconnectHandler) {
        this.reconnectHandler = reconnectHandler;
        }


        /* TODO: NEEDS HEARTBEAT HANDLER AND IDEALLY REFACTOR THESE 2 INTO THE HEARTBEAT CLASS
        @Override
        public void onWebsocketPing(WebSocket conn, Framedata f) {
            console.log(Level.INFO, "Received Ping frame");
            super.onWebsocketPing(conn, f);
        }
         */

        @Override
        public void onWebsocketPong(WebSocket conn, Framedata f)
        {
            console.log(Level.INFO, "Received Pong frame"); // you could also comment this out to test the heartbeat timeout just for visual clarity
            super.onWebsocketPong(conn, f);

           if(heartbeatHandler != null)
           {
               heartbeatHandler.setLastPongTime(System.currentTimeMillis());
           }
        }

        // Send Ping Method
        public void sendPing()
        {
            try
            {
                super.sendPing();
            }
            catch(Exception e)
            {
                console.log(Level.WARNING,"Failed to send ping: " + e.getMessage(),e);
            }
        }



    }




