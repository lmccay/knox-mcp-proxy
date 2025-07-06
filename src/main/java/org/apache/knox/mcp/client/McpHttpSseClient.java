package org.apache.knox.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * MCP client implementation using HTTP with Server-Sent Events
 */
public class McpHttpSseClient implements AutoCloseable {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String sseEndpoint;
    private Thread sseReaderThread;
    private volatile boolean closed = false;
    
    private String serverName;
    private JsonNode serverCapabilities;
    
    public McpHttpSseClient(String baseUrl) {
        this.httpClient = HttpClients.createDefault();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.sseEndpoint = this.baseUrl + "/sse";
        this.serverName = extractServerName(baseUrl);
    }
    
    private String extractServerName(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            int port = uri.getPort();
            return host + (port != -1 ? ":" + port : "");
        } catch (Exception e) {
            return "http-server";
        }
    }
    
    public void connect() throws IOException {
        startSseListener();
    }
    
    private void startSseListener() throws IOException {
        sseReaderThread = new Thread(() -> {
            try {
                HttpGet sseRequest = new HttpGet(sseEndpoint);
                sseRequest.setHeader("Accept", "text/event-stream");
                sseRequest.setHeader("Cache-Control", "no-cache");
                
                HttpResponse response = httpClient.execute(sseRequest);
                
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new IOException("Failed to connect to SSE endpoint: " + 
                        response.getStatusLine().getStatusCode());
                }
                
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getEntity().getContent()))) {
                    
                    String line;
                    StringBuilder eventData = new StringBuilder();
                    
                    while (!closed && (line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            eventData.append(data);
                        } else if (line.isEmpty() && eventData.length() > 0) {
                            // End of event, process the data
                            handleSseMessage(eventData.toString());
                            eventData.setLength(0);
                        }
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("Error in SSE connection to " + serverName + ": " + e.getMessage());
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
    }
    
    private void handleSseMessage(String message) {
        try {
            JsonNode response = objectMapper.readTree(message);
            
            if (response.has("id") && !response.get("id").isNull()) {
                // This is a response to a request
                long id = response.get("id").asLong();
                CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                if (future != null) {
                    if (response.has("error")) {
                        JsonNode error = response.get("error");
                        future.completeExceptionally(new McpException(
                            error.get("code").asInt(),
                            error.get("message").asText()
                        ));
                    } else {
                        future.complete(response.get("result"));
                    }
                }
            } else {
                // This is a notification - ignore for now
                System.out.println("Received notification from " + serverName + ": " + message);
            }
        } catch (Exception e) {
            System.err.println("Error parsing SSE message from " + serverName + ": " + e.getMessage());
        }
    }
    
    private CompletableFuture<JsonNode> sendHttpRequest(String method, JsonNode params) {
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
        
        // Send request via HTTP POST in background
        CompletableFuture.runAsync(() -> {
            try {
                HttpPost httpPost = new HttpPost(baseUrl + "/message");
                httpPost.setHeader("Content-Type", "application/json");
                
                String requestJson = objectMapper.writeValueAsString(request);
                httpPost.setEntity(new StringEntity(requestJson));
                
                HttpResponse response = httpClient.execute(httpPost);
                int statusCode = response.getStatusLine().getStatusCode();
                
                if (statusCode != 200 && statusCode != 202) {
                    pendingRequests.remove(id);
                    future.completeExceptionally(new IOException(
                        "HTTP request failed with status: " + statusCode));
                }
                
                // For HTTP/SSE, we don't expect immediate response body
                // Response will come via SSE channel
                EntityUtils.consume(response.getEntity());
                
            } catch (Exception e) {
                pendingRequests.remove(id);
                future.completeExceptionally(e);
            }
        });
        
        return future;
    }
    
    public JsonNode initialize(JsonNode clientCapabilities) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", clientCapabilities != null ? clientCapabilities : objectMapper.createObjectNode());
        params.set("clientInfo", createClientInfo());
        
        JsonNode result = sendHttpRequest("initialize", params).get(10, TimeUnit.SECONDS);
        if (result != null && result.has("capabilities")) {
            this.serverCapabilities = result.get("capabilities");
        }
        return result;
    }
    
    private JsonNode createClientInfo() {
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "Knox MCP Proxy");
        clientInfo.put("version", "1.0.0");
        return clientInfo;
    }
    
    public List<McpTool> listTools() throws Exception {
        JsonNode result = sendHttpRequest("tools/list", null).get(10, TimeUnit.SECONDS);
        List<McpTool> tools = new ArrayList<>();
        
        if (result != null && result.has("tools")) {
            for (JsonNode toolNode : result.get("tools")) {
                tools.add(new McpTool(
                    toolNode.get("name").asText(),
                    toolNode.has("description") ? toolNode.get("description").asText() : "",
                    toolNode.has("inputSchema") ? toolNode.get("inputSchema") : null
                ));
            }
        }
        
        return tools;
    }
    
    public JsonNode callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        if (arguments != null) {
            params.set("arguments", objectMapper.valueToTree(arguments));
        }
        
        return sendHttpRequest("tools/call", params).get(30, TimeUnit.SECONDS);
    }
    
    public List<McpResource> listResources() throws Exception {
        JsonNode result = sendHttpRequest("resources/list", null).get(10, TimeUnit.SECONDS);
        List<McpResource> resources = new ArrayList<>();
        
        if (result != null && result.has("resources")) {
            for (JsonNode resourceNode : result.get("resources")) {
                resources.add(new McpResource(
                    resourceNode.get("uri").asText(),
                    resourceNode.has("name") ? resourceNode.get("name").asText() : "",
                    resourceNode.has("description") ? resourceNode.get("description").asText() : "",
                    resourceNode.has("mimeType") ? resourceNode.get("mimeType").asText() : null
                ));
            }
        }
        
        return resources;
    }
    
    public JsonNode readResource(String uri) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("uri", uri);
        
        return sendHttpRequest("resources/read", params).get(10, TimeUnit.SECONDS);
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