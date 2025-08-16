package org.apache.knox.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages an individual SSE session for MCP communication
 */
public class McpSseSession {
    
    private final String sessionId;
    private final AsyncContext asyncContext;
    private final PrintWriter writer;
    private final ObjectMapper objectMapper;
    private final McpProxyResource proxyResource;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, String> sessionData = new ConcurrentHashMap<>();
    
    private boolean initialized = false;
    
    public McpSseSession(String sessionId, AsyncContext asyncContext, McpProxyResource proxyResource, HttpServletRequest servletRequest) throws IOException {
        this.sessionId = sessionId;
        this.asyncContext = asyncContext;
        this.proxyResource = proxyResource;
        this.objectMapper = new ObjectMapper();
        
        // Set up SSE response headers
        HttpServletResponse response = (HttpServletResponse) asyncContext.getResponse();
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Headers", "Content-Type");
        
        this.writer = response.getWriter();
        
        System.out.println("DEBUG: Created SSE session: " + sessionId);
        
        // Send initial connection event
        sendEvent("connected", sessionId);
        
        // Build the correct endpoint URL based on the current request context
        String messageEndpoint = buildMessageEndpoint(servletRequest, sessionId);
        sendEvent("endpoint", messageEndpoint);
        
        writer.flush();
    }
    
    private String buildMessageEndpoint(HttpServletRequest request, String sessionId) {
        // Get the current request URI and build the correct endpoint path
        String requestURI = request.getRequestURI();
        String contextPath = request.getContextPath();
        String servletPath = request.getServletPath();
        
        System.out.println("DEBUG: Building endpoint - requestURI: " + requestURI + 
                          ", contextPath: " + contextPath + 
                          ", servletPath: " + servletPath);
        
        // The request URI for SSE could be:
        // - /gateway/mcpservers/mcp/v1/sse (legacy)
        // - /gateway/mcpservers/mcp/v1/api (new unified)
        // We want to change it to: /gateway/mcpservers/mcp/v1/message?session={sessionId}
        String baseEndpoint;
        if (requestURI.endsWith("/sse")) {
            // Legacy SSE endpoint: replace /sse with /message
            baseEndpoint = requestURI.substring(0, requestURI.lastIndexOf("/sse")) + "/message";
        } else if (requestURI.endsWith("/api")) {
            // New unified endpoint: replace /api with /message
            baseEndpoint = requestURI.substring(0, requestURI.lastIndexOf("/api")) + "/message";
        } else {
            // Fallback: construct from known parts
            baseEndpoint = contextPath + servletPath + "/message";
        }
        
        String endpointUrl = baseEndpoint + "?session=" + sessionId;
        System.out.println("DEBUG: Built message endpoint: " + endpointUrl);
        
        return endpointUrl;
    }
    
    public void sendEvent(String eventType, String data) throws IOException {
        if (closed.get()) {
            return;
        }
        
        try {
            if (eventType != null) {
                writer.println("event: " + eventType);
            }
            writer.println("data: " + data);
            writer.println(); // Empty line to end the event
            writer.flush();
            
            System.out.println("DEBUG: Sent SSE event to " + sessionId + " - type: " + eventType + ", data: " + data);
        } catch (Exception e) {
            System.err.println("ERROR: Failed to send SSE event to session " + sessionId + ": " + e.getMessage());
            close();
            throw e;
        }
    }
    
    public void sendJsonRpcResponse(JsonNode id, Object result) throws IOException {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        response.set("result", objectMapper.valueToTree(result));
        
        String jsonResponse = objectMapper.writeValueAsString(response);
        sendEvent("message", jsonResponse);
    }
    
    public void sendJsonRpcError(JsonNode id, int code, String message, String data) throws IOException {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.put("data", data);
        }
        response.set("error", error);
        
        String jsonResponse = objectMapper.writeValueAsString(response);
        sendEvent("message", jsonResponse);
    }
    
    public void sendJsonRpcNotification(String method, JsonNode params) throws IOException {
        ObjectNode notification = objectMapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        // No "id" field for notifications
        
        String jsonNotification = objectMapper.writeValueAsString(notification);
        sendEvent("message", jsonNotification);
    }
    
    public void handleJsonRpcRequest(JsonNode request) {
        try {
            JsonNode id = request.has("id") ? request.get("id") : null;
            String method = request.get("method").asText();
            JsonNode params = request.has("params") ? request.get("params") : null;
            
            System.out.println("DEBUG: SSE session " + sessionId + " handling method: " + method);
            
            // Handle MCP methods
            switch (method) {
                case "initialize":
                    handleInitialize(id, params);
                    break;
                case "tools/list":
                    handleToolsList(id);
                    break;
                case "tools/call":
                    handleToolCall(id, params);
                    break;
                case "resources/list":
                    handleResourcesList(id);
                    break;
                case "resources/read":
                    handleResourceRead(id, params);
                    break;
                case "notifications/initialized":
                    handleInitializedNotification();
                    break;
                default:
                    sendJsonRpcError(id, -32601, "Method not found", "Unknown method: " + method);
            }
            
        } catch (Exception e) {
            System.err.println("ERROR: Failed to handle JSON-RPC request in session " + sessionId + ": " + e.getMessage());
            e.printStackTrace();
            
            try {
                JsonNode id = request.has("id") ? request.get("id") : null;
                sendJsonRpcError(id, -32603, "Internal error", e.getMessage());
            } catch (IOException ioError) {
                System.err.println("ERROR: Failed to send error response: " + ioError.getMessage());
                close();
            }
        }
    }
    
    private void handleInitialize(JsonNode id, JsonNode params) throws Exception {
        System.out.println("DEBUG: SSE session " + sessionId + " handling initialize");
        
        // Validate initialize parameters
        if (params == null || !params.has("protocolVersion")) {
            sendJsonRpcError(id, -32602, "Invalid params", "Missing protocolVersion");
            return;
        }
        
        String protocolVersion = params.get("protocolVersion").asText();
        System.out.println("DEBUG: Client protocol version: " + protocolVersion);
        
        // Create initialization response
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        
        // Server capabilities
        ObjectNode capabilities = objectMapper.createObjectNode();
        
        // Tools capability
        ObjectNode tools = objectMapper.createObjectNode();
        tools.put("listChanged", false); // We don't support dynamic tool list changes
        capabilities.set("tools", tools);
        
        // Resources capability  
        ObjectNode resources = objectMapper.createObjectNode();
        resources.put("subscribe", false); // We don't support resource subscriptions
        resources.put("listChanged", false); // We don't support dynamic resource list changes
        capabilities.set("resources", resources);
        
        result.set("capabilities", capabilities);
        
        // Server info
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", "Knox MCP Proxy");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);
        
        sendJsonRpcResponse(id, result);
        
        // Mark session as initialized
        initialized = true;
        System.out.println("DEBUG: SSE session " + sessionId + " initialized successfully");
    }
    
    private void handleInitializedNotification() {
        System.out.println("DEBUG: SSE session " + sessionId + " received 'initialized' notification - handshake complete");
        // No response needed for notifications
    }
    
    private void handleToolsList(JsonNode id) throws Exception {
        System.out.println("DEBUG: SSE session " + sessionId + " handling tools/list");
        
        if (!initialized) {
            sendJsonRpcError(id, -32002, "Server not initialized", "Call initialize first");
            return;
        }
        
        // Get tools from proxy resource
        Object result = proxyResource.listAllToolsForMcp();
        sendJsonRpcResponse(id, result);
    }
    
    private void handleToolCall(JsonNode id, JsonNode params) throws Exception {
        System.out.println("DEBUG: SSE session " + sessionId + " handling tools/call");
        
        if (!initialized) {
            sendJsonRpcError(id, -32002, "Server not initialized", "Call initialize first");
            return;
        }
        
        Object result = proxyResource.handleToolCallForMcp(params);
        sendJsonRpcResponse(id, result);
    }
    
    private void handleResourcesList(JsonNode id) throws Exception {
        System.out.println("DEBUG: SSE session " + sessionId + " handling resources/list");
        
        if (!initialized) {
            sendJsonRpcError(id, -32002, "Server not initialized", "Call initialize first");
            return;
        }
        
        Object result = proxyResource.listAllResourcesForMcp();
        sendJsonRpcResponse(id, result);
    }
    
    private void handleResourceRead(JsonNode id, JsonNode params) throws Exception {
        System.out.println("DEBUG: SSE session " + sessionId + " handling resources/read");
        
        if (!initialized) {
            sendJsonRpcError(id, -32002, "Server not initialized", "Call initialize first");
            return;
        }
        
        Object result = proxyResource.handleResourceReadForMcp(params);
        sendJsonRpcResponse(id, result);
    }
    
    public void close() {
        if (closed.compareAndSet(false, true)) {
            System.out.println("DEBUG: Closing SSE session: " + sessionId);
            
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (Exception e) {
                System.err.println("DEBUG: Error closing writer for session " + sessionId + ": " + e.getMessage());
            }
            
            try {
                if (asyncContext != null) {
                    asyncContext.complete();
                }
            } catch (Exception e) {
                System.err.println("DEBUG: Error completing async context for session " + sessionId + ": " + e.getMessage());
            }
        }
    }
    
    public boolean isClosed() {
        return closed.get();
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public boolean isInitialized() {
        return initialized;
    }
}