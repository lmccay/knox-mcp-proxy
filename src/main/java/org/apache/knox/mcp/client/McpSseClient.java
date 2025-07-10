package org.apache.knox.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Standard SSE MCP client implementation - bidirectional communication over 
 * a single Server-Sent Events connection, compatible with standard MCP SSE servers.
 */
public class McpSseClient implements AutoCloseable {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String sseEndpoint;
    private volatile String messageEndpoint;  // Make this volatile and mutable
    private Thread sseReaderThread;
    private HttpURLConnection sseConnection;
    private volatile boolean closed = false;
    
    private String serverName;
    private JsonNode serverCapabilities;
    
    public McpSseClient(String baseUrl) {
        this.httpClient = HttpClients.createDefault();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.sseEndpoint = this.baseUrl + "/sse";
        this.messageEndpoint = null;  // Will be set by the "endpoint" event
        this.serverName = extractServerName(baseUrl);
    }
    
    private String extractServerName(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            int port = uri.getPort();
            return host + (port != -1 ? ":" + port : "");
        } catch (Exception e) {
            return "sse-server";
        }
    }
    
    public void connect() throws IOException {
        establishSseConnection();
        startSseListener();
    }
    
    private void establishSseConnection() throws IOException {
        System.out.println("DEBUG: Establishing SSE connection to: " + sseEndpoint);
        
        URL url = new URL(sseEndpoint);
        sseConnection = (HttpURLConnection) url.openConnection();
        sseConnection.setRequestMethod("GET");
        sseConnection.setRequestProperty("Accept", "text/event-stream");
        sseConnection.setRequestProperty("Cache-Control", "no-cache");
        sseConnection.setDoInput(true);
        // Don't set doOutput for SSE connections
        
        int responseCode = sseConnection.getResponseCode();
        System.out.println("DEBUG: SSE connection response code: " + responseCode);
        
        if (responseCode != 200) {
            throw new IOException("Failed to establish SSE connection: " + responseCode);
        }
        
        System.out.println("DEBUG: SSE connection established successfully to: " + sseEndpoint);
    }
    
    private void startSseListener() throws IOException {
        System.out.println("DEBUG: Starting SSE listener for server: " + serverName);
        
        sseReaderThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(sseConnection.getInputStream(), "UTF-8"))) {
                
                String line;
                StringBuilder eventData = new StringBuilder();
                String eventType = null;
                
                System.out.println("DEBUG: SSE reader thread started for server: " + serverName);
                
                while (!closed && (line = reader.readLine()) != null) {
                    System.out.println("DEBUG: Raw SSE line from " + serverName + ": " + line);
                    
                    if (line.startsWith("event: ")) {
                        eventType = line.substring(7);
                        System.out.println("DEBUG: SSE event type: " + eventType);
                    } else if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        eventData.append(data);
                    } else if (line.isEmpty() && eventData.length() > 0) {
                        // End of event, process the data
                        System.out.println("DEBUG: Complete SSE event from " + serverName + " (type: " + eventType + "): " + eventData.toString());
                        handleSseEvent(eventType, eventData.toString());
                        eventData.setLength(0);
                        eventType = null;
                    }
                }
                System.out.println("DEBUG: SSE reader thread ending for server: " + serverName + " (closed=" + closed + ")");
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("Error in SSE connection to " + serverName + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        
        sseReaderThread.setDaemon(true);
        sseReaderThread.start();
        
        // Give SSE connection time to establish
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        System.out.println("DEBUG: SSE listener started for server: " + serverName);
    }
    
    private void handleSseMessage(String message) {
        System.out.println("DEBUG: Handling SSE message from " + serverName + ": " + message);
        
        try {
            // Check if the message is a URL/path instead of JSON
            if (message.startsWith("/")) {
                System.out.println("DEBUG: Received URL-style message, attempting to fetch: " + message);
                // This might be a reference to fetch the actual message
                fetchMessageFromUrl(message);
                return;
            }
            
            JsonNode response = objectMapper.readTree(message);
            
            if (response.has("id") && !response.get("id").isNull()) {
                // This is a response to a request
                long id = response.get("id").asLong();
                System.out.println("DEBUG: Processing SSE response for ID: " + id);
                
                CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                if (future != null) {
                    if (response.has("error")) {
                        JsonNode error = response.get("error");
                        System.err.println("ERROR: SSE server returned error for ID " + id + ": " + error);
                        future.completeExceptionally(new McpException(
                            error.get("code").asInt(),
                            error.get("message").asText()
                        ));
                    } else {
                        System.out.println("DEBUG: Completing SSE future for ID " + id + " with result");
                        future.complete(response.get("result"));
                    }
                } else {
                    System.err.println("WARNING: No pending request found for SSE ID: " + id);
                }
            } else {
                // This is a notification - ignore for now
                System.out.println("Received notification from " + serverName + ": " + message);
            }
        } catch (Exception e) {
            System.err.println("Error parsing SSE message from " + serverName + ": " + e.getMessage());
            System.err.println("Raw message was: " + message);
            e.printStackTrace();
        }
    }
    
    private void fetchMessageFromUrl(String messagePath) {
        try {
            // The messagePath already includes the full path from root, so don't prepend baseUrl
            // Just use the host and port from baseUrl
            String messageUrl;
            if (messagePath.startsWith("/mcp/")) {
                // Extract just the protocol, host, and port from baseUrl
                URI baseUri = URI.create(baseUrl);
                messageUrl = baseUri.getScheme() + "://" + baseUri.getHost() + 
                           (baseUri.getPort() != -1 ? ":" + baseUri.getPort() : "") + messagePath;
            } else {
                // Fallback to the old method if the path doesn't start with /mcp/
                messageUrl = baseUrl + messagePath;
            }
            
            System.out.println("DEBUG: Fetching message from URL: " + messageUrl);
            
            URL url = new URL(messageUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoInput(true);
            
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                    
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    
                    String jsonResponse = response.toString();
                    System.out.println("DEBUG: Fetched JSON response: " + jsonResponse);
                    
                    // Now process this as a normal JSON response
                    handleSseMessage(jsonResponse);
                }
            } else {
                System.err.println("ERROR: Failed to fetch message from URL " + messageUrl + 
                                 ", response code: " + responseCode);
            }
            
        } catch (Exception e) {
            System.err.println("ERROR: Exception while fetching message from URL: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void handleSseEvent(String eventType, String data) {
        System.out.println("DEBUG: Handling SSE event - type: " + eventType + ", data: " + data);
        
        if ("endpoint".equals(eventType)) {
            // The server is telling us the message endpoint URL for this session
            this.messageEndpoint = data;
            System.out.println("DEBUG: Set message endpoint to: " + messageEndpoint);
        } else if ("message".equals(eventType) || eventType == null) {
            // Regular data event or no event type specified - treat as message
            handleSseMessage(data);
        } else {
            System.out.println("DEBUG: Ignoring unknown SSE event type: " + eventType);
        }
    }
    
    private CompletableFuture<JsonNode> sendSseRequest(String method, JsonNode params) {
        System.out.println("DEBUG: Sending SSE request - method: " + method + ", server: " + serverName);
        
        if (closed) {
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("SSE connection is closed"));
            return future;
        }
        
        long id = requestIdCounter.getAndIncrement();
        
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);
        
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            System.out.println("DEBUG: Sending SSE JSON request: " + requestJson);
            
            // Wait for message endpoint to be set by the "endpoint" event
            if (messageEndpoint == null) {
                System.out.println("DEBUG: Waiting for message endpoint to be set by server...");
                // Give some time for the endpoint event to arrive
                for (int i = 0; i < 50 && messageEndpoint == null; i++) {
                    try {
                        Thread.sleep(100); // Wait up to 5 seconds total
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
                if (messageEndpoint == null) {
                    throw new McpException(-1, "Message endpoint not set by server - no 'endpoint' event received");
                }
            }
            
            // Send request via HTTP POST to message endpoint
            // The messageEndpoint from the "endpoint" event should already be a full URL or a path
            URL url;
            if (messageEndpoint.startsWith("http://") || messageEndpoint.startsWith("https://")) {
                // Full URL
                url = new URL(messageEndpoint);
            } else {
                // Path only - construct full URL using base server info
                URI baseUri = URI.create(baseUrl);
                String fullUrl = baseUri.getScheme() + "://" + baseUri.getHost() + 
                                (baseUri.getPort() != -1 ? ":" + baseUri.getPort() : "") + messageEndpoint;
                url = new URL(fullUrl);
            }
            System.out.println("DEBUG: Posting to message endpoint URL: " + url);
            
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            
            // Write request
            try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), "UTF-8")) {
                writer.write(requestJson);
                writer.flush();
            }
            
            // Check response code
            int responseCode = connection.getResponseCode();
            System.out.println("DEBUG: Message endpoint response code: " + responseCode);
            
            if (responseCode != 200 && responseCode != 202) {
                // Try to read the error response body
                String errorBody = "";
                try {
                    if (connection.getErrorStream() != null) {
                        try (BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(connection.getErrorStream(), "UTF-8"))) {
                            StringBuilder errorResponse = new StringBuilder();
                            String line;
                            while ((line = errorReader.readLine()) != null) {
                                errorResponse.append(line);
                            }
                            errorBody = errorResponse.toString();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("DEBUG: Could not read error response body: " + e.getMessage());
                }
                
                System.err.println("ERROR: HTTP request failed - code: " + responseCode + ", error body: " + errorBody);
                pendingRequests.remove(id);
                future.completeExceptionally(new IOException("HTTP request failed with code: " + responseCode + ", body: " + errorBody));
            } else {
                System.out.println("DEBUG: SSE request sent successfully, waiting for response with ID: " + id);
            }
            // Note: For SSE, we expect the response to come back via the SSE stream, not the HTTP response
            
        } catch (Exception e) {
            System.err.println("ERROR: Failed to send SSE request: " + e.getMessage());
            e.printStackTrace();
            pendingRequests.remove(id);
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    public JsonNode initialize(JsonNode clientCapabilities) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", clientCapabilities != null ? clientCapabilities : objectMapper.createObjectNode());
        params.set("clientInfo", createClientInfo());
        
        JsonNode result = sendSseRequest("initialize", params).get(10, TimeUnit.SECONDS);
        if (result != null && result.has("capabilities")) {
            this.serverCapabilities = result.get("capabilities");
            
            // Send the required "initialized" notification to complete the handshake
            System.out.println("DEBUG: Sending 'initialized' notification to complete handshake");
            sendInitializedNotification();
        }
        return result;
    }
    
    private void sendInitializedNotification() throws Exception {
        // The "initialized" notification has no ID and no response is expected
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        // No "id" field for notifications
        
        try {
            String requestJson = objectMapper.writeValueAsString(notification);
            System.out.println("DEBUG: Sending 'initialized' notification: " + requestJson);
            
            // Ensure message endpoint is still set
            if (messageEndpoint == null) {
                throw new McpException(-1, "Message endpoint not set - cannot send initialized notification");
            }
            
            // Send notification via HTTP POST to message endpoint
            URL url;
            if (messageEndpoint.startsWith("http://") || messageEndpoint.startsWith("https://")) {
                url = new URL(messageEndpoint);
            } else {
                URI baseUri = URI.create(baseUrl);
                String fullUrl = baseUri.getScheme() + "://" + baseUri.getHost() + 
                                (baseUri.getPort() != -1 ? ":" + baseUri.getPort() : "") + messageEndpoint;
                url = new URL(fullUrl);
            }
            System.out.println("DEBUG: Posting 'initialized' notification to: " + url);
            
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            
            // Write notification
            try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), "UTF-8")) {
                writer.write(requestJson);
                writer.flush();
            }
            
            // Check response code
            int responseCode = connection.getResponseCode();
            System.out.println("DEBUG: 'initialized' notification response code: " + responseCode);
            
            if (responseCode != 200 && responseCode != 202) {
                throw new IOException("Failed to send 'initialized' notification, response code: " + responseCode);
            } else {
                System.out.println("DEBUG: 'initialized' notification sent successfully - client is now ready");
            }
            
        } catch (Exception e) {
            System.err.println("ERROR: Failed to send 'initialized' notification: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    private JsonNode createClientInfo() {
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "Knox MCP Proxy");
        clientInfo.put("version", "1.0.0");
        return clientInfo;
    }
    
    public List<McpTool> listTools() throws Exception {
        JsonNode result = sendSseRequest("tools/list", null).get(10, TimeUnit.SECONDS);
        List<McpTool> tools = new ArrayList<>();
        
        System.out.println("DEBUG: tools/list result: " + result);
        
        if (result != null) {
            System.out.println("DEBUG: tools/list result keys: " + result.fieldNames());
            
            if (result.has("tools")) {
                JsonNode toolsArray = result.get("tools");
                System.out.println("DEBUG: Found 'tools' field with " + toolsArray.size() + " tools");
                
                for (JsonNode toolNode : toolsArray) {
                    System.out.println("DEBUG: Processing tool: " + toolNode);
                    tools.add(new McpTool(
                        toolNode.get("name").asText(),
                        toolNode.has("description") ? toolNode.get("description").asText() : "",
                        toolNode.has("inputSchema") ? toolNode.get("inputSchema") : null
                    ));
                }
            } else {
                System.out.println("DEBUG: No 'tools' field found in result");
            }
        } else {
            System.out.println("DEBUG: tools/list result is null");
        }
        
        System.out.println("DEBUG: Returning " + tools.size() + " tools");
        return tools;
    }
    
    public JsonNode callTool(String toolName, Map<String, Object> arguments) throws Exception {
        System.out.println("DEBUG: callTool - toolName: " + toolName + ", arguments: " + arguments);
        
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        if (arguments != null) {
            JsonNode argsNode = objectMapper.valueToTree(arguments);
            System.out.println("DEBUG: callTool - converted arguments to JSON: " + argsNode);
            params.set("arguments", argsNode);
        }
        
        System.out.println("DEBUG: callTool - final params: " + params);
        
        return sendSseRequest("tools/call", params).get(30, TimeUnit.SECONDS);
    }
    
    public List<McpResource> listResources() throws Exception {
        JsonNode result = sendSseRequest("resources/list", null).get(10, TimeUnit.SECONDS);
        List<McpResource> resources = new ArrayList<>();
        
        System.out.println("DEBUG: resources/list result: " + result);
        
        if (result != null) {
            System.out.println("DEBUG: resources/list result keys: " + result.fieldNames());
            
            if (result.has("resources")) {
                JsonNode resourcesArray = result.get("resources");
                System.out.println("DEBUG: Found 'resources' field with " + resourcesArray.size() + " resources");
                
                for (JsonNode resourceNode : resourcesArray) {
                    System.out.println("DEBUG: Processing resource: " + resourceNode);
                    resources.add(new McpResource(
                        resourceNode.get("uri").asText(),
                        resourceNode.has("name") ? resourceNode.get("name").asText() : "",
                        resourceNode.has("description") ? resourceNode.get("description").asText() : "",
                        resourceNode.has("mimeType") ? resourceNode.get("mimeType").asText() : null
                    ));
                }
            } else {
                System.out.println("DEBUG: No 'resources' field found in result");
            }
        } else {
            System.out.println("DEBUG: resources/list result is null");
        }
        
        System.out.println("DEBUG: Returning " + resources.size() + " resources");
        return resources;
    }
    
    public JsonNode readResource(String uri) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("uri", uri);
        
        return sendSseRequest("resources/read", params).get(10, TimeUnit.SECONDS);
    }
    
    @Override
    public void close() {
        closed = true;
        
        // Cancel pending requests
        for (CompletableFuture<JsonNode> future : pendingRequests.values()) {
            future.cancel(true);
        }
        pendingRequests.clear();
        
        // Interrupt SSE reader thread
        if (sseReaderThread != null) {
            sseReaderThread.interrupt();
        }
        
        // Close SSE connection
        if (sseConnection != null) {
            sseConnection.disconnect();
        }
        
        // Close HTTP client if it's a CloseableHttpClient
        try {
            if (httpClient instanceof java.io.Closeable) {
                ((java.io.Closeable) httpClient).close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }
    
    public String getServerName() {
        return serverName;
    }
    
    public JsonNode getServerCapabilities() {
        return serverCapabilities;
    }
    
    public boolean isAlive() {
        return !closed && sseReaderThread != null && sseReaderThread.isAlive();
    }
}