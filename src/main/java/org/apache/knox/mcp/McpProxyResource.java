package org.apache.knox.mcp;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.ServletContext;
import javax.servlet.AsyncContext;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.knox.mcp.util.McpLogger;

@Singleton
@Path("/mcp/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpProxyResource {

    private static final McpLogger logger = McpLogger.getLogger(McpProxyResource.class);

    @Context
    private HttpServletRequest request;

    private final Map<String, McpServerConnection> serverConnections = new ConcurrentHashMap<>();
    private final Map<String, Object> aggregatedTools = new ConcurrentHashMap<>();
    private final Map<String, Object> aggregatedResources = new ConcurrentHashMap<>();
    private final Map<String, String> toolNameMapping = new ConcurrentHashMap<>(); // sanitized -> original
    private final Map<String, String> serverMapping = new ConcurrentHashMap<>(); // sanitized -> serverName
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> sessionStorage = new ConcurrentHashMap<>(); // sessionId -> client info
    private boolean initialized = false;

    @PostConstruct
    public void init() {
        if (!initialized) {
            try {
                initializeConnections();
                initialized = true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize MCP connections", e);
            }
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            for (McpServerConnection connection : serverConnections.values()) {
                connection.disconnect();
            }
            serverConnections.clear();
            aggregatedTools.clear();
            aggregatedResources.clear();
            toolNameMapping.clear();
            serverMapping.clear();
            // Shutdown SSE session manager
            McpSseSessionManager.getInstance().shutdown();
        } catch (Exception e) {
            // Log error but don't fail shutdown
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }

    private void initializeConnections() throws Exception {
        // Get topology configuration from servlet context
        String serversConfig = getConfigParameter("mcp.servers");
        java.util.Set<String> allowedStdioCommands = parseAllowedStdioCommands();
        
        if (serversConfig != null) {
            String[] servers = serversConfig.split(",");
            for (String serverConfig : servers) {
                String trimmedConfig = serverConfig.trim();
                // Split only on the first colon to separate name from URL
                int firstColonIndex = trimmedConfig.indexOf(':');
                if (firstColonIndex > 0 && firstColonIndex < trimmedConfig.length() - 1) {
                    String name = trimmedConfig.substring(0, firstColonIndex).trim();
                    String endpoint = trimmedConfig.substring(firstColonIndex + 1).trim();
                    
                    McpServerConnection connection = new McpServerConnection(name, endpoint, allowedStdioCommands);
                    serverConnections.put(name, connection);
                    
                    // Connect and aggregate tools/resources
                    connection.connect();
                    aggregateToolsAndResources(connection);
                }
            }
        }
    }
    
    private java.util.Set<String> parseAllowedStdioCommands() {
        String allowedCommandsConfig = getConfigParameter("mcp.stdio.allowed.commands");
        java.util.Set<String> allowedCommands = new java.util.HashSet<>();
        
        if (allowedCommandsConfig != null && !allowedCommandsConfig.trim().isEmpty()) {
            String[] commands = allowedCommandsConfig.split(",");
            for (String command : commands) {
                String trimmedCommand = command.trim();
                if (!trimmedCommand.isEmpty()) {
                    allowedCommands.add(trimmedCommand);
                }
            }
            logger.info("Configured stdio allowed commands: " + allowedCommands);
        } else {
            logger.warn("No stdio command allowlist configured (mcp.stdio.allowed.commands). All stdio commands will be allowed.");
        }
        
        return allowedCommands.isEmpty() ? null : allowedCommands;
    }

    private String getConfigParameter(String key) {
        // Get configuration from servlet context or system properties
        ServletContext context = request.getServletContext();
        String value = context.getInitParameter(key);
        if (value == null) {
            value = System.getProperty(key);
        }
        return value;
    }

    private void aggregateToolsAndResources(McpServerConnection connection) {
        try {
            Map<String, Object> tools = connection.getTools();
            Map<String, Object> resources = connection.getResources();
            
            // Prefix tools and resources with server name to avoid conflicts
            String serverName = connection.getName();
            tools.forEach((key, value) -> {
                String originalToolName = serverName + "_" + key;
                String sanitizedToolName = sanitizeToolName(originalToolName);
                
                aggregatedTools.put(sanitizedToolName, value);
                toolNameMapping.put(sanitizedToolName, key); // Map back to original tool name
                serverMapping.put(sanitizedToolName, serverName); // Map to server name
            });
            resources.forEach((key, value) -> aggregatedResources.put(serverName + "." + key, value));
            
        } catch (Exception e) {
            System.err.println("Failed to aggregate tools/resources from server: " + connection.getName());
        }
    }
    
    /**
     * Sanitize tool names to ensure consistent naming across aggregated MCP servers.
     * Follows pattern: ^[a-zA-Z0-9_-]+$ for maximum compatibility and consistency.
     */
    private String sanitizeToolName(String toolName) {
        if (toolName == null || toolName.trim().isEmpty()) {
            return "unknown_tool";
        }
        
        // Replace invalid characters with underscores and ensure it starts with a letter/underscore
        String sanitized = toolName.replaceAll("[^a-zA-Z0-9_-]", "_");
        
        // Ensure it starts with a letter or underscore
        if (!sanitized.matches("^[a-zA-Z_].*")) {
            sanitized = "_" + sanitized;
        }
        
        // Ensure it's not empty after sanitization
        if (sanitized.trim().isEmpty() || sanitized.equals("_")) {
            sanitized = "unknown_tool";
        }
        
        logger.debug("Sanitized tool name '" + toolName + "' -> '" + sanitized + "'");
        return sanitized;
    }

    private Object callToolInternal(String toolName, Map<String, Object> parameters) throws Exception {
        // First, check if this is a sanitized tool name in our direct mapping
        if (toolNameMapping.containsKey(toolName) && serverMapping.containsKey(toolName)) {
            String originalToolName = toolNameMapping.get(toolName);
            String serverName = serverMapping.get(toolName);
            
            McpServerConnection connection = serverConnections.get(serverName);
            if (connection != null) {
                logger.debug("Calling tool '" + originalToolName + "' on server '" + serverName + 
                             "' (sanitized name: '" + toolName + "')");
                return connection.callTool(originalToolName, parameters);
            }
        }
        
        // Legacy support: try the tool name as provided (might be serverName.toolName format)
        if (toolName.contains(".")) {
            String[] parts = toolName.split("\\.", 2);
            String serverName = parts[0];
            String actualToolName = parts[1];
            
            McpServerConnection connection = serverConnections.get(serverName);
            if (connection != null) {
                return connection.callTool(actualToolName, parameters);
            }
        }
        
        // Legacy support: search through all aggregated tools for a match
        for (String aggregatedToolName : aggregatedTools.keySet()) {
            if (aggregatedToolName.endsWith("." + toolName)) {
                String[] parts = aggregatedToolName.split("\\.", 2);
                String serverName = parts[0];
                String actualToolName = parts[1];
                
                McpServerConnection connection = serverConnections.get(serverName);
                if (connection != null) {
                    return connection.callTool(actualToolName, parameters);
                }
            }
        }
        
        // If still not found, try each server connection directly
        for (McpServerConnection connection : serverConnections.values()) {
            try {
                return connection.callTool(toolName, parameters);
            } catch (IllegalArgumentException e) {
                // Tool not found on this server, try the next one
                continue;
            }
        }
        
        throw new IllegalArgumentException("Tool not found: " + toolName);
    }

    private Object getResource(String resourceName) throws Exception {
        // Route resource requests to appropriate server
        if (resourceName.contains(".")) {
            String[] parts = resourceName.split("\\.", 2);
            String serverName = parts[0];
            String actualResourceName = parts[1];
            
            McpServerConnection connection = serverConnections.get(serverName);
            if (connection != null) {
                return connection.getResource(actualResourceName);
            }
        }
        throw new IllegalArgumentException("Resource not found: " + resourceName);
    }

    // =================================================================
    // MCP Streamable HTTP - Unified Endpoint (Specification Compliant)
    // =================================================================
    
    /**
     * MCP GET endpoint - Establishes SSE connections or returns service info
     * according to the MCP Streamable HTTP specification.
     */
    @GET
    @Path("/mcp")
    @Produces({MediaType.APPLICATION_JSON, "text/event-stream"})
    public Response handleMcpGetRequest(@Context HttpServletRequest request, 
                                      @Context HttpHeaders headers) {
        try {
            init(); // Ensure initialized
            
            String acceptHeader = headers.getHeaderString("Accept");
            String mcpVersion = headers.getHeaderString("mcp-version");
            String sessionId = headers.getHeaderString("Mcp-Session-Id");
            
            logger.debug("MCP GET endpoint - Accept: " + acceptHeader + ", MCP-Version: " + mcpVersion + ", Session-Id: " + sessionId);
            
            // GET requests establish SSE connections when Accept header requests SSE
            // Check if the client specifically prefers SSE over JSON
            if (acceptHeader != null && isEventStreamPreferred(acceptHeader)) {
                logger.debug("Establishing SSE connection via unified GET endpoint");
                return handleSseConnectionUnified(request);
            } else {
                // GET without SSE preference - return service info for JSON Accept headers
                logger.debug("GET request with JSON Accept header - returning service info");
                String serviceInfo = createMcpInfoResponse();
                return Response.ok(serviceInfo, MediaType.APPLICATION_JSON)
                        .header("mcp-version", mcpVersion != null ? mcpVersion : "2024-11-05")
                        .build();
            }
            
        } catch (Exception e) {
            logger.error("Error in MCP GET endpoint: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Internal server error: " + e.getMessage())
                    .build();
        }
    }
    
    /**
     * MCP POST endpoint - Handles JSON-RPC messages with optional streaming
     * according to the MCP Streamable HTTP specification.
     */
    @POST
    @Path("/mcp")
    @Produces({MediaType.APPLICATION_JSON, "text/event-stream"})
    @Consumes(MediaType.APPLICATION_JSON)
    public Response handleMcpPostRequest(@Context HttpServletRequest request,
                                       @Context HttpHeaders headers,
                                       String requestBody) {
        System.out.println("=== MANUAL DEBUG: POST /api called ===");
        System.out.println("Content-Type: " + request.getContentType());
        System.out.println("Accept: " + headers.getHeaderString("Accept"));
        System.out.println("User-Agent: " + headers.getHeaderString("User-Agent"));
        System.out.println("Request body length: " + (requestBody != null ? requestBody.length() : "null"));
        System.out.println("Request body content: " + requestBody);
        System.out.println("=====================================");
        
        try {
            init(); // Ensure initialized
            
            String acceptHeader = headers.getHeaderString("Accept");
            String mcpVersion = headers.getHeaderString("mcp-version");
            String sessionId = headers.getHeaderString("Mcp-Session-Id");
            
            logger.debug("MCP POST endpoint - Accept: " + acceptHeader + ", MCP-Version: " + mcpVersion + ", Session-Id: " + sessionId);
            
            // Set MCP protocol version header in response
            Response.ResponseBuilder responseBuilder = Response.ok();
            if (mcpVersion != null) {
                responseBuilder.header("mcp-version", mcpVersion);
            } else {
                responseBuilder.header("mcp-version", "2024-11-05");
            }
            
            // Include session ID in response if provided
            if (sessionId != null) {
                responseBuilder.header("Mcp-Session-Id", sessionId);
            }
            
            // POST requests handle JSON-RPC messages
            if (acceptHeader != null && isEventStreamPreferred(acceptHeader)) {
                // POST with SSE Accept header - streaming response
                logger.debug("Handling POST with SSE response via unified endpoint");
                return handleStreamingJsonRpcRequest(request, requestBody, sessionId);
            } else {
                // POST with JSON Accept header - standard request/response
                logger.debug("Handling standard JSON-RPC request via unified endpoint");
                return handleStandardJsonRpcRequest(requestBody, request, responseBuilder, sessionId);
            }
            
        } catch (Exception e) {
            logger.error("Error in MCP POST endpoint: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Internal server error: " + e.getMessage())
                    .build();
        }
    }
    
    private String createMcpInfoResponse() throws JsonProcessingException {
        ObjectNode info = objectMapper.createObjectNode();
        info.put("service", "Knox MCP Proxy");
        info.put("version", "1.0.0");
        info.put("protocol", "streamable-http");
        info.put("mcpVersion", "2024-11-05");
        info.put("capabilities", "tools,resources,sse");
        info.put("servers", serverConnections.size());
        info.put("tools", aggregatedTools.size());
        info.put("resources", aggregatedResources.size());
        
        ArrayNode endpoints = objectMapper.createArrayNode();
        endpoints.add("GET /mcp (Accept: text/event-stream) - Establish SSE connection");
        endpoints.add("POST /mcp (Accept: application/json) - JSON-RPC request/response");
        endpoints.add("POST /mcp (Accept: text/event-stream) - JSON-RPC with streaming response");
        info.set("endpoints", endpoints);
        
        return objectMapper.writeValueAsString(info);
    }
    
    private Response handleSseConnectionUnified(HttpServletRequest request) {
        logger.debug("SSE connection request received via unified endpoint");
        
        try {
            // Enable async processing for SSE
            AsyncContext asyncContext = request.startAsync();
            asyncContext.setTimeout(0); // No timeout for SSE connections
            
            // Create SSE session
            McpSseSession session = McpSseSessionManager.getInstance().createSession(asyncContext, this, request);
            
            logger.debug("SSE session created via unified endpoint: " + session.getSessionId());
            
            // Return null to signal JAX-RS that we're handling the response asynchronously
            return null;
            
        } catch (Exception e) {
            logger.error("Failed to establish SSE connection via unified endpoint: " + e.getMessage(), e);
            
            try {
                AsyncContext asyncContext = request.getAsyncContext();
                if (asyncContext != null) {
                    asyncContext.complete();
                }
            } catch (Exception completeError) {
                logger.error("Failed to complete async context: " + completeError.getMessage(), completeError);
            }
            
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to establish SSE connection")
                    .build();
        }
    }
    
    private Response handleStreamingJsonRpcRequest(HttpServletRequest request, String requestBody, String sessionId) {
        // For streaming responses, we need to check if there's a session or create one
        if (sessionId == null) {
            sessionId = request.getHeader("X-Session-ID");
        }
        if (sessionId == null) {
            sessionId = request.getParameter("session");
        }
        
        if (sessionId != null) {
            // Route to existing SSE session
            logger.debug("Routing streaming request to SSE session: " + sessionId);
            McpSseSessionManager.getInstance().handleMessageForSession(sessionId, requestBody);
            return Response.accepted()
                    .header("mcp-version", "2024-11-05")
                    .header("Mcp-Session-Id", sessionId)
                    .build();
        } else {
            // No session ID - this is an error for streaming requests
            return createJsonRpcErrorResponse(null, -32600, "Invalid Request", 
                    "Streaming requests require an active SSE session. Use GET with Accept: text/event-stream first.");
        }
    }
    
    private Response handleStandardJsonRpcRequest(String requestBody, HttpServletRequest request, Response.ResponseBuilder responseBuilder, String sessionId) {
        logger.debug("=== DEBUG: Standard JSON-RPC Request ===");
        logger.debug("Request body: " + requestBody);
        logger.debug("Content-Type: " + request.getContentType());
        logger.debug("Accept header: " + request.getHeader("Accept"));
        
        try {
            if (requestBody == null || requestBody.trim().isEmpty()) {
                logger.debug("ERROR: Request body is null or empty");
                return createJsonRpcErrorResponse(null, -32700, "Parse error", "Request body is required");
            }
            
            logger.debug("Parsing JSON request...");
            JsonNode requestJson = objectMapper.readTree(requestBody);
            logger.debug("Parsed JSON: " + requestJson.toString());
            
            // Validate JSON-RPC 2.0 format
            if (!requestJson.has("jsonrpc") || !"2.0".equals(requestJson.get("jsonrpc").asText())) {
                logger.debug("ERROR: Invalid JSON-RPC format");
                return createJsonRpcErrorResponse(null, -32600, "Invalid Request", "Missing or invalid jsonrpc field");
            }
            
            if (!requestJson.has("method")) {
                logger.debug("ERROR: Missing method field");
                return createJsonRpcErrorResponse(getRequestId(requestJson), -32600, "Invalid Request", "Missing method field");
            }
            
            String method = requestJson.get("method").asText();
            JsonNode params = requestJson.has("params") ? requestJson.get("params") : null;
            JsonNode id = getRequestId(requestJson);
            
            logger.debug("Method: " + method);
            logger.debug("Params: " + (params != null ? params.toString() : "null"));
            logger.debug("ID: " + (id != null ? id.toString() : "null"));
            
            // Handle different MCP methods
            Object result = null;
            logger.debug("Processing method: " + method);
            
            switch (method) {
                case "initialize":
                    logger.debug("Handling initialize method...");
                    result = handleInitialize(params, sessionId, responseBuilder);
                    logger.debug("Initialize result: " + (result != null ? result.toString() : "null"));
                    break;
                case "initialized":
                    logger.debug("Handling initialized notification...");
                    result = null;
                    break;
                case "tools/list":
                    logger.debug("Handling tools/list...");
                    result = listAllToolsForMcp();
                    break;
                case "tools/call":
                    logger.debug("Handling tools/call...");
                    result = handleToolCallForMcp(params);
                    break;
                case "resources/list":
                    logger.debug("Handling resources/list...");
                    result = listAllResourcesForMcp();
                    break;
                case "resources/read":
                    logger.debug("Handling resources/read...");
                    result = handleResourceReadForMcp(params);
                    break;
                default:
                    logger.debug("ERROR: Unknown method: " + method);
                    ObjectNode errorResponse = objectMapper.createObjectNode();
                    errorResponse.put("jsonrpc", "2.0");
                    if (id != null) {
                        errorResponse.set("id", id);
                    }
                    ObjectNode error = objectMapper.createObjectNode();
                    error.put("code", -32601);
                    error.put("message", "Method not found");
                    error.put("data", "Unknown method: " + method);
                    errorResponse.set("error", error);
                    
                    return responseBuilder
                            .entity(objectMapper.writeValueAsString(errorResponse))
                            .type(MediaType.APPLICATION_JSON)
                            .status(Response.Status.OK)
                            .build();
            }
            
            // Handle notifications (methods without id)
            if (id == null && "initialized".equals(method)) {
                logger.debug("Returning 204 No Content for initialized notification");
                return responseBuilder
                        .status(Response.Status.NO_CONTENT)
                        .build();
            }
            
            logger.debug("Creating success response...");
            Response response = createJsonRpcSuccessResponseWithHeaders(id, result, responseBuilder);
            logger.debug("Success response created");
            return response;
            
        } catch (JsonProcessingException e) {
            logger.error("JSON parsing error: " + e.getMessage(), e);
            return createJsonRpcErrorResponse(null, -32700, "Parse error", "Invalid JSON: " + e.getMessage());
        } catch (IOException e) {
            logger.error("IO error: " + e.getMessage(), e);
            return createJsonRpcErrorResponse(null, -32700, "Parse error", "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error: " + e.getMessage(), e);
            return createJsonRpcErrorResponse(null, -32603, "Internal error", "Server error: " + e.getMessage());
        }
    }
    
    private Response createJsonRpcSuccessResponseWithHeaders(JsonNode id, Object result, Response.ResponseBuilder responseBuilder) throws JsonProcessingException {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        response.set("result", objectMapper.valueToTree(result));
        
        String json = objectMapper.writeValueAsString(response);
        return responseBuilder
                .entity(json)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    // =================================================================
    // Legacy Endpoints (Backward Compatibility)
    // =================================================================

    @GET
    @Path("/tools")
    public Response listTools() {
        try {
            init(); // Ensure initialized
            // Use ObjectMapper to serialize to JSON string
            String json = objectMapper.writeValueAsString(aggregatedTools);
            return Response.ok(json, MediaType.APPLICATION_JSON).build();
            
        } catch (JsonProcessingException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to serialize tools: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to list tools: " + e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/resources")
    public Response listResources() {
        try {
            init(); // Ensure initialized
            // Use ObjectMapper to serialize to JSON string
            String json = objectMapper.writeValueAsString(aggregatedResources);
            return Response.ok(json, MediaType.APPLICATION_JSON).build();
            
        } catch (JsonProcessingException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to serialize resources: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to list resources: " + e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/tools/{toolName}")
    public Response callTool(@PathParam("toolName") String toolName, 
                            String parametersJsonString) {
        try {
            init(); // Ensure initialized
            
            // Parse JSON string to Map<String, Object>
            Map<String, Object> parameters = null;
            if (parametersJsonString != null && !parametersJsonString.trim().isEmpty() && !parametersJsonString.trim().equals("null")) {
                JsonNode parametersJson = objectMapper.readTree(parametersJsonString);
                if (parametersJson != null && !parametersJson.isNull()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> convertedParams = objectMapper.convertValue(parametersJson, Map.class);
                    parameters = convertedParams;
                }
            }
            
            Object result = callToolInternal(toolName, parameters);
            String json = objectMapper.writeValueAsString(result);
            return Response.ok(json, MediaType.APPLICATION_JSON).build();
            
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Tool not found: " + toolName)
                    .build();
        } catch (JsonProcessingException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid JSON parameters: " + e.getMessage())
                    .build();
        } catch (IOException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Invalid JSON parameters: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to call tool: " + e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/resources/{resourceName}")
    public Response getResourceEndpoint(@PathParam("resourceName") String resourceName) {
        try {
            init(); // Ensure initialized
            Object result = getResource(resourceName);
            String json = objectMapper.writeValueAsString(result);
            return Response.ok(json, MediaType.APPLICATION_JSON).build();
            
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Resource not found: " + resourceName)
                    .build();
        } catch (JsonProcessingException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to serialize resource: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to get resource: " + e.getMessage())
                    .build();
        }
    }

    @GET
    @Path("/health")
    public Response health() {
        try {
            init(); // Ensure initialized
            return Response.ok()
                    .entity("{\n" +
                            "  \"status\": \"" + (initialized ? "UP" : "DOWN") + "\",\n" +
                            "  \"service\": \"MCP Proxy\",\n" +
                            "  \"servers\": " + serverConnections.size() + ",\n" +
                            "  \"tools\": " + aggregatedTools.size() + ",\n" +
                            "  \"resources\": " + aggregatedResources.size() + "\n" +
                            "}")
                    .build();
            
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Health check failed: " + e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/message")
    @Deprecated
    public Response handleJsonRpcRequest(String requestJsonString, @Context HttpServletRequest request) {
        logger.debug("JSON-RPC request received (legacy endpoint - deprecated)");
        logger.warn("DEPRECATED: /message endpoint is deprecated. Use POST / with appropriate Accept headers instead.");
        
        try {
            init(); // Ensure initialized
            
            // Check if this is an SSE session request (look for session ID in headers or parameters)
            String sessionId = request.getHeader("X-Session-ID");
            if (sessionId == null) {
                sessionId = request.getParameter("session");
            }
            
            // If we have a session ID, route to SSE session manager
            if (sessionId != null) {
                logger.debug("Routing message to SSE session: " + sessionId);
                McpSseSessionManager.getInstance().handleMessageForSession(sessionId, requestJsonString);
                // Return accepted response - the actual response will come via SSE
                return Response.accepted()
                        .header("mcp-version", "2024-11-05")
                        .build();
            }
            
            // Otherwise handle as regular HTTP JSON-RPC request
            logger.debug("Handling as regular HTTP JSON-RPC request");
            
            // Parse JSON-RPC request
            if (requestJsonString == null || requestJsonString.trim().isEmpty()) {
                return createJsonRpcErrorResponse(null, -32700, "Parse error", "Request body is required");
            }
            
            JsonNode requestJson = objectMapper.readTree(requestJsonString);
            
            // Validate JSON-RPC 2.0 format
            if (!requestJson.has("jsonrpc") || !"2.0".equals(requestJson.get("jsonrpc").asText())) {
                return createJsonRpcErrorResponse(null, -32600, "Invalid Request", "Missing or invalid jsonrpc field");
            }
            
            if (!requestJson.has("method")) {
                return createJsonRpcErrorResponse(getRequestId(requestJson), -32600, "Invalid Request", "Missing method field");
            }
            
            String method = requestJson.get("method").asText();
            JsonNode params = requestJson.has("params") ? requestJson.get("params") : null;
            JsonNode id = getRequestId(requestJson);
            
            // Handle different MCP methods
            Object result = null;
            switch (method) {
                case "initialize":
                    result = handleInitialize(params, null, null);
                    break;
                case "initialized":
                    // This is a notification, no response needed
                    logger.debug("Received initialized notification");
                    result = null;
                    break;
                case "tools/list":
                    result = listAllTools();
                    break;
                case "tools/call":
                    result = handleToolCall(params);
                    break;
                case "resources/list":
                    result = listAllResources();
                    break;
                case "resources/read":
                    result = handleResourceRead(params);
                    break;
                default:
                    return createJsonRpcErrorResponse(id, -32601, "Method not found", "Unknown method: " + method);
            }
            
            // Handle notifications (methods without id)
            if (id == null && "initialized".equals(method)) {
                // Notification - return 204 No Content
                return Response.noContent().build();
            }
            
            // Add MCP version header to legacy responses
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) {
                response.set("id", id);
            }
            response.set("result", objectMapper.valueToTree(result));
            
            String json = objectMapper.writeValueAsString(response);
            return Response.ok(json, MediaType.APPLICATION_JSON)
                    .header("mcp-version", "2024-11-05")
                    .build();
            
        } catch (JsonProcessingException e) {
            return createJsonRpcErrorResponse(null, -32700, "Parse error", "Invalid JSON: " + e.getMessage());
        } catch (IOException e) {
            return createJsonRpcErrorResponse(null, -32700, "Parse error", "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            return createJsonRpcErrorResponse(null, -32603, "Internal error", "Server error: " + e.getMessage());
        }
    }
    
    private JsonNode getRequestId(JsonNode request) {
        return request.has("id") ? request.get("id") : null;
    }
    
    private Response createJsonRpcSuccessResponse(JsonNode id, Object result) throws JsonProcessingException {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        response.set("result", objectMapper.valueToTree(result));
        
        String json = objectMapper.writeValueAsString(response);
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
    }
    
    private Response createJsonRpcErrorResponse(JsonNode id, int code, String message, String data) {
        try {
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
            
            String json = objectMapper.writeValueAsString(response);
            return Response.ok(json, MediaType.APPLICATION_JSON).build();
        } catch (JsonProcessingException e) {
            // Fallback to plain text error
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error creating JSON-RPC error response")
                    .build();
        }
    }
    
    private Object handleToolCall(JsonNode params) throws Exception {
        if (params == null || !params.has("name")) {
            throw new IllegalArgumentException("Missing 'name' parameter for tools/call");
        }
        
        String toolName = params.get("name").asText();
        Map<String, Object> arguments = null;
        
        if (params.has("arguments") && !params.get("arguments").isNull()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> convertedArgs = objectMapper.convertValue(params.get("arguments"), Map.class);
            arguments = convertedArgs;
        }
        
        return callToolInternal(toolName, arguments);
    }
    
    private Object handleResourceRead(JsonNode params) throws Exception {
        if (params == null || !params.has("uri")) {
            throw new IllegalArgumentException("Missing 'uri' parameter for resources/read");
        }
        
        String uri = params.get("uri").asText();
        return getResource(uri);
    }
    
    private Object listAllTools() throws Exception {
        // Return tools in MCP format
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode toolsArray = objectMapper.createArrayNode();
        
        // Get tools from all aggregated servers
        for (Map.Entry<String, Object> entry : aggregatedTools.entrySet()) {
            String toolName = entry.getKey();
            Object toolData = entry.getValue();
            
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("name", toolName);
            
            if (toolData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> toolMap = (Map<String, Object>) toolData;
                
                if (toolMap.containsKey("description")) {
                    toolNode.put("description", String.valueOf(toolMap.get("description")));
                }
                
                if (toolMap.containsKey("inputSchema")) {
                    toolNode.set("inputSchema", objectMapper.valueToTree(toolMap.get("inputSchema")));
                }
            }
            
            toolsArray.add(toolNode);
        }
        
        result.set("tools", toolsArray);
        return result;
    }
    
    private Object listAllResources() throws Exception {
        // Return resources in MCP format
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode resourcesArray = objectMapper.createArrayNode();
        
        // Get resources from all aggregated servers
        for (Map.Entry<String, Object> entry : aggregatedResources.entrySet()) {
            String resourceUri = entry.getKey();
            Object resourceData = entry.getValue();
            
            ObjectNode resourceNode = objectMapper.createObjectNode();
            resourceNode.put("uri", resourceUri);
            
            if (resourceData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resourceMap = (Map<String, Object>) resourceData;
                
                if (resourceMap.containsKey("name")) {
                    resourceNode.put("name", String.valueOf(resourceMap.get("name")));
                }
                
                if (resourceMap.containsKey("description")) {
                    resourceNode.put("description", String.valueOf(resourceMap.get("description")));
                }
                
                if (resourceMap.containsKey("mimeType")) {
                    resourceNode.put("mimeType", String.valueOf(resourceMap.get("mimeType")));
                }
            }
            
            resourcesArray.add(resourceNode);
        }
        
        result.set("resources", resourcesArray);
        return result;
    }

    @GET
    @Path("/sse")
    @Produces("text/event-stream")
    @Deprecated
    public void handleSseConnection(@Context HttpServletRequest request) {
        logger.debug("SSE connection request received (legacy endpoint - deprecated)");
        logger.warn("DEPRECATED: /sse endpoint is deprecated. Use GET / with Accept: text/event-stream header instead.");
        
        // Delegate to unified SSE handler to avoid code duplication
        try {
            init(); // Ensure initialized
            
            // Enable async processing for SSE
            AsyncContext asyncContext = request.startAsync();
            asyncContext.setTimeout(0); // No timeout for SSE connections
            
            // Create SSE session
            McpSseSession session = McpSseSessionManager.getInstance().createSession(asyncContext, this, request);
            
            logger.debug("SSE session created via legacy endpoint: " + session.getSessionId());
            
            // The session will handle the response and keep the connection alive
            // AsyncContext will be completed when the session is closed
            
        } catch (Exception e) {
            System.err.println("ERROR: Failed to establish SSE connection: " + e.getMessage());
            e.printStackTrace();
            
            // Send error response
            try {
                AsyncContext asyncContext = request.getAsyncContext();
                if (asyncContext != null) {
                    asyncContext.complete();
                }
            } catch (Exception completeError) {
                System.err.println("ERROR: Failed to complete async context: " + completeError.getMessage());
            }
        }
    }
    
    /**
     * Determines if text/event-stream is preferred according to MCP Streamable HTTP spec.
     * Handles quality values (q) to determine the preferred media type.
     */
    private boolean isEventStreamPreferred(String acceptHeader) {
        if (acceptHeader == null || acceptHeader.trim().isEmpty()) {
            return false;
        }
        
        String normalized = acceptHeader.trim().toLowerCase();
        
        // Check for exact match first
        if (normalized.equals("text/event-stream")) {
            return true;
        }
        
        // Parse quality values
        double sseQuality = -1.0;
        double jsonQuality = -1.0;
        
        String[] mediaTypes = normalized.split(",");
        for (String mediaType : mediaTypes) {
            String trimmed = mediaType.trim();
            
            if (trimmed.startsWith("text/event-stream")) {
                sseQuality = parseQualityValue(trimmed);
                if (sseQuality == -1.0) sseQuality = 1.0; // Default quality
            } else if (trimmed.startsWith("application/json")) {
                jsonQuality = parseQualityValue(trimmed);
                if (jsonQuality == -1.0) jsonQuality = 1.0; // Default quality
            }
        }
        
        // If SSE is present but JSON is not, prefer SSE
        if (sseQuality >= 0 && jsonQuality < 0) {
            return true;
        }
        
        // If both are present, compare quality values
        if (sseQuality >= 0 && jsonQuality >= 0) {
            if (sseQuality > jsonQuality) {
                return true;
            } else if (sseQuality < jsonQuality) {
                return false;
            } else {
                // Equal quality values - check order (first one wins)
                return normalized.indexOf("text/event-stream") < normalized.indexOf("application/json");
            }
        }
        
        return false;
    }
    
    private double parseQualityValue(String mediaType) {
        int qIndex = mediaType.indexOf("q=");
        if (qIndex == -1) {
            return -1.0; // No quality value specified
        }
        
        try {
            String qValue = mediaType.substring(qIndex + 2);
            // Remove any trailing parameters after the q value
            int semicolonIndex = qValue.indexOf(";");
            if (semicolonIndex != -1) {
                qValue = qValue.substring(0, semicolonIndex);
            }
            int commaIndex = qValue.indexOf(",");
            if (commaIndex != -1) {
                qValue = qValue.substring(0, commaIndex);
            }
            return Double.parseDouble(qValue.trim());
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            return -1.0; // Invalid quality value
        }
    }
    

    // Methods for SSE session to call
    public Object listAllToolsForMcp() throws Exception {
        init(); // Ensure initialized
        return listAllTools();
    }
    
    public Object listAllResourcesForMcp() throws Exception {
        init(); // Ensure initialized
        return listAllResources();
    }
    
    public Object handleToolCallForMcp(JsonNode params) throws Exception {
        init(); // Ensure initialized
        return handleToolCall(params);
    }
    
    public Object handleResourceReadForMcp(JsonNode params) throws Exception {
        init(); // Ensure initialized
        return handleResourceRead(params);
    }

    private Object handleInitialize(JsonNode params, String sessionId, Response.ResponseBuilder responseBuilder) throws Exception {
        logger.debug("=== DEBUG: handleInitialize ===");
        logger.debug("Params: " + (params != null ? params.toString() : "null"));
        logger.debug("Session ID: " + sessionId);
        
        try {
            // Parse client capabilities
            String protocolVersion = "2024-11-05";
            if (params != null && params.has("protocolVersion")) {
                protocolVersion = params.get("protocolVersion").asText();
                logger.debug("Client protocol version: " + protocolVersion);
            }
            
            // Generate session ID if not provided and store session info
            if (sessionId == null) {
                sessionId = java.util.UUID.randomUUID().toString();
                logger.debug("Generated new session ID: " + sessionId);
            }
            
            // Store session information
            sessionStorage.put(sessionId, "active");
            
            // Add session ID to response headers
            if (responseBuilder != null) {
                responseBuilder.header("Mcp-Session-Id", sessionId);
            }
            
            // Return server capabilities
            ObjectNode result = objectMapper.createObjectNode();
            result.put("protocolVersion", "2024-11-05");
            
            // Server info
            ObjectNode serverInfo = objectMapper.createObjectNode();
            serverInfo.put("name", "Knox MCP Proxy");
            serverInfo.put("version", "1.0.0");
            result.set("serverInfo", serverInfo);
            
            // Server capabilities
            ObjectNode capabilities = objectMapper.createObjectNode();
            
            // Tools capability
            ObjectNode tools = objectMapper.createObjectNode();
            tools.put("listChanged", false);
            capabilities.set("tools", tools);
            
            // Resources capability  
            ObjectNode resources = objectMapper.createObjectNode();
            resources.put("subscribe", false);
            resources.put("listChanged", false);
            capabilities.set("resources", resources);
            
            result.set("capabilities", capabilities);
            
            logger.debug("Initialize result created: " + result.toString());
            return result;
            
        } catch (Exception e) {
            logger.error("Error in handleInitialize: " + e.getMessage(), e);
            throw e;
        }
    }
}