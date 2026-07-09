package com.telephony.event.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.freeswitch.esl.client.outbound.AbstractOutboundClientHandler;
import org.freeswitch.esl.client.outbound.AbstractOutboundPipelineFactory;
import org.freeswitch.esl.client.outbound.SocketClient;
import org.freeswitch.esl.client.transport.event.EslEvent;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Outbound ESL server that receives call events from FreeSWITCH
 * and forwards them to the lead-service via REST API.
 */
@Component
@Slf4j
public class EslOutboundServer {

    private final String host;
    private final int port;
    private final RestTemplate restTemplate;
    private final String leadServiceUrl;
    private final int maxRetries;
    private final long retryDelayMs;
    private final String spoolDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SocketClient socketClient;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(10, r -> {
        Thread t = new Thread(r);
        t.setName("esl-rest-publisher-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    public EslOutboundServer(
            @Value("${telephony.esl.outbound-host:0.0.0.0}") String host,
            @Value("${telephony.esl.outbound-port:8084}") int port,
            RestTemplate restTemplate,
            @Value("${telephony.lead-service.url:http://lead-service:8080}") String leadServiceUrl,
            @Value("${telephony.lead-service.max-retries:3}") int maxRetries,
            @Value("${telephony.lead-service.retry-delay-ms:1000}") long retryDelayMs,
            @Value("${telephony.esl.spool-dir:spool}") String spoolDir) {
        this.host = host;
        this.port = port;
        this.restTemplate = restTemplate;
        this.leadServiceUrl = leadServiceUrl;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.spoolDir = spoolDir;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("Starting Outbound ESL Server on {}:{}", host, port);
        try {
            socketClient = new SocketClient(
                    port,
                    new AbstractOutboundPipelineFactory() {
                        @Override
                        protected AbstractOutboundClientHandler makeHandler() {
                            return new CallHandler();
                        }
                    }
            );
            socketClient.start();
            log.info("Outbound ESL Server started successfully on port {}", port);
        } catch (Exception e) {
            log.error("Failed to start Outbound ESL Server", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (socketClient != null) {
            log.info("Stopping Outbound ESL Server");
            socketClient.stop();
        }
        log.info("Shutting down event publisher thread pool");
        executor.shutdown();
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${telephony.esl.spool-retry-interval-ms:30000}")
    public void retrySpooledEvents() {
        java.io.File dir = new java.io.File(spoolDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return;
        }

        log.info("Found {} spooled events to retry", files.length);
        String url = leadServiceUrl + "/api/v1/call-events";
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);

        for (java.io.File file : files) {
            try {
                String json = java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                HttpEntity<String> request = new HttpEntity<>(json, httpHeaders);
                restTemplate.postForEntity(url, request, String.class);
                log.info("Successfully sent spooled event {} to lead-service. Deleting file.", file.getName());
                java.nio.file.Files.delete(file.toPath());
            } catch (Exception e) {
                log.warn("Failed to resend spooled event {}: {}", file.getName(), e.getMessage());
            }
        }
    }

    private void spoolEvent(String json) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(spoolDir);
            if (!java.nio.file.Files.exists(path)) {
                java.nio.file.Files.createDirectories(path);
            }
            String filename = "event-" + java.util.UUID.randomUUID() + ".json";
            java.nio.file.Path filePath = path.resolve(filename);
            java.nio.file.Files.writeString(filePath, json, java.nio.charset.StandardCharsets.UTF_8);
            log.info("Successfully spooled failed event to {}", filePath);
        } catch (Exception e) {
            log.error("Critical: Failed to spool event to disk: {}", e.getMessage(), e);
        }
    }

    private class CallHandler extends AbstractOutboundClientHandler {

        private String getCallerId(Map<String, String> headers) {
            String orig = headers.get("variable_origination_caller_id_number");
            if (orig != null && !orig.isBlank() && !"public".equalsIgnoreCase(orig)) {
                return orig;
            }
            String chan = headers.get("Channel-Caller-ID-Number");
            if (chan != null && !chan.isBlank() && !"public".equalsIgnoreCase(chan)) {
                return chan;
            }
            return headers.get("Caller-Caller-ID-Number");
        }

        @Override
        protected void handleConnectResponse(ChannelHandlerContext ctx, EslEvent event) {
            Map<String, String> headers = event.getEventHeaders();
            String channelUniqueId = headers.get("Unique-ID");
            String callerIdNumber = getCallerId(headers);
            String destinationNumber = headers.get("Caller-Destination-Number");
            String contextName = headers.get("Caller-Context");

            log.info("Received outbound ESL connection from FreeSWITCH. Unique-ID: {}, Caller: {}, Destination: {}, Context: {}",
                    channelUniqueId, callerIdNumber, destinationNumber, contextName);

            // Send CALL_START event to lead-service
            publishCallEvent("CALL_START", channelUniqueId, callerIdNumber, destinationNumber, contextName, headers);

            // Subscribe to events for this channel
            sendSyncSingleLineCommand(ctx.getChannel(), "myevents");

            // Send execute hangup command to FreeSWITCH to terminate the channel after greeting playback.
            java.util.List<String> hangupMsg = new java.util.ArrayList<>();
            hangupMsg.add("sendmsg");
            hangupMsg.add("call-command: execute");
            hangupMsg.add("execute-app-name: hangup");
            hangupMsg.add("");
            sendSyncMultiLineCommand(ctx.getChannel(), hangupMsg);
        }

        @Override
        protected void handleEslEvent(ChannelHandlerContext ctx, EslEvent event) {
            String eventName = event.getEventName();
            Map<String, String> headers = event.getEventHeaders();
            String channelUniqueId = headers.get("Unique-ID");
            String callerIdNumber = getCallerId(headers);
            String destinationNumber = headers.get("Caller-Destination-Number");
            String contextName = headers.get("Caller-Context");

            log.debug("Received ESL event: {} for channel: {}", eventName, channelUniqueId);

            if ("CHANNEL_ANSWER".equals(eventName)) {
                log.info("Call answered. Unique-ID: {}", channelUniqueId);
                publishCallEvent("CALL_ANSWER", channelUniqueId, callerIdNumber, destinationNumber, contextName, headers);
            } else if ("CHANNEL_HANGUP".equals(eventName)) {
                log.info("Call hung up. Unique-ID: {}", channelUniqueId);
                publishCallEvent("CALL_HANGUP", channelUniqueId, callerIdNumber, destinationNumber, contextName, headers);
            }
        }

        @Override
        protected void handleAuthRequest(ChannelHandlerContext ctx) {
            // Outbound connection authentication is usually not required from our end,
            // but the abstract method requires an implementation.
            log.debug("Auth request received in outbound ESL handler (no auth needed).");
        }

        @Override
        protected void handleDisconnectionNotice() {
            log.info("Outbound ESL channel socket disconnected");
        }

        /**
         * Publishes a call event to lead-service via REST POST.
         * Uses fire-and-forget with configurable retry on failure.
         */
        private void publishCallEvent(String eventType, String uniqueId, String caller,
                                       String destination, String context, Map<String, String> headers) {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("eventType", eventType);
                payload.put("uniqueId", uniqueId);
                payload.put("callerNumber", caller);
                payload.put("calledNumber", destination);
                payload.put("context", context);
                payload.put("timestamp", Instant.now().toString());

                // Extract IVR results set by FreeSWITCH dialplan (empty on non-IVR branches)
                String ivrSelection = headers.get("variable_ivr_result");
                String ivrLanguage = headers.get("variable_ivr_lang");
                payload.put("ivrSelection", ivrSelection != null ? ivrSelection : "");
                payload.put("ivrLanguage", ivrLanguage != null ? ivrLanguage : "");

                payload.put("rawHeaders", headers);

                log.info("Publishing event {} to lead-service for Call: {}. ivrSelection: {}, ivrLanguage: {}",
                        eventType, uniqueId, ivrSelection, ivrLanguage);

                String json = objectMapper.writeValueAsString(payload);
                executor.submit(() -> postWithRetry(json));

            } catch (Exception e) {
                log.error("Failed to publish call event to lead-service", e);
            }
        }

        /**
         * Posts the event JSON to lead-service with retry logic.
         * Fire-and-forget: logs errors and moves on after max retries.
         */
        private void postWithRetry(String json) {
            String url = leadServiceUrl + "/api/v1/call-events";
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(json, httpHeaders);

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    restTemplate.postForEntity(url, request, String.class);
                    log.debug("Successfully posted call event to lead-service (attempt {})", attempt);
                    return;
                } catch (Exception e) {
                    log.warn("Failed to post call event to lead-service (attempt {}/{}): {}",
                            attempt, maxRetries, e.getMessage());
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(retryDelayMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            log.error("Exhausted {} retries posting call event to lead-service. Spooling to disk.", maxRetries);
            spoolEvent(json);
        }
    }
}
