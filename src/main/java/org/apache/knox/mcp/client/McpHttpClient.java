package org.apache.knox.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Standard HTTP MCP client implementation - sends requests and receives responses 
 * in the same HTTP connection, compatible with standard MCP HTTP servers.
 */
public class McpHttpClient implements AutoCloseable {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String serverName;
    
    private JsonNode serverCapabilities;
    private volatile boolean closed = false;
    
    public McpHttpClient(String baseUrl) {
        this.httpClient = HttpClients.createDefault();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
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
        // For standard HTTP, no persistent connection setup needed
        // Connection is established per request
    }
    
    private JsonNode sendHttpRequest(String method, JsonNode params) throws Exception {
        if (closed) {
            throw new IllegalStateException("Client is closed");
        }
        
        long id = requestIdCounter.getAndIncrement();
        
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        
        HttpPost httpPost = new HttpPost(baseUrl);
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Accept", "application/json");
        
        String requestJson = objectMapper.writeValueAsString(request);
        httpPost.setEntity(new StringEntity(requestJson, "UTF-8"));
        
        HttpResponse response = httpClient.execute(httpPost);
        int statusCode = response.getStatusLine().getStatusCode();
        
        if (statusCode != 200) {
            String errorBody = "";
            if (response.getEntity() != null) {
                errorBody = EntityUtils.toString(response.getEntity());
            }
            throw new IOException("HTTP request failed with status: " + statusCode + ", body: " + errorBody);
        }
        
        if (response.getEntity() == null) {
            throw new IOException("Empty response body");
        }
        
        String responseBody = EntityUtils.toString(response.getEntity());
        JsonNode responseJson = objectMapper.readTree(responseBody);
        
        // Validate JSON-RPC response
        if (!responseJson.has("jsonrpc") || !"2.0".equals(responseJson.get("jsonrpc").asText())) {
            throw new McpException(-32600, "Invalid JSON-RPC response");
        }
        
        if (responseJson.has("error")) {
            JsonNode error = responseJson.get("error");
            throw new McpException(
                error.get("code").asInt(),
                error.get("message").asText()
            );
        }
        
        if (!responseJson.has("result")) {
            throw new McpException(-32603, "Missing result in JSON-RPC response");
        }
        
        return responseJson.get("result");
    }
    
    public JsonNode initialize(JsonNode clientCapabilities) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", clientCapabilities != null ? clientCapabilities : objectMapper.createObjectNode());
        params.set("clientInfo", createClientInfo());
        
        JsonNode result = sendHttpRequest("initialize", params);
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
        JsonNode result = sendHttpRequest("tools/list", null);
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
        
        return sendHttpRequest("tools/call", params);
    }
    
    public List<McpResource> listResources() throws Exception {
        JsonNode result = sendHttpRequest("resources/list", null);
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
        
        return sendHttpRequest("resources/read", params);
    }
    
    @Override
    public void close() {
        closed = true;
        
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
        return !closed;
    }
}