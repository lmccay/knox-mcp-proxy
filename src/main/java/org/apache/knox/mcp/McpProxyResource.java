package org.apache.knox.mcp;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.ServletContext;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;

@Singleton
@Path("/mcp/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpProxyResource {

    @Context
    private HttpServletRequest request;

    private final Map<String, McpServerConnection> serverConnections = new ConcurrentHashMap<>();
    private final Map<String, Object> aggregatedTools = new ConcurrentHashMap<>();
    private final Map<String, Object> aggregatedResources = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
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
        } catch (Exception e) {
            // Log error but don't fail shutdown
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }

    private void initializeConnections() throws Exception {
        // Get topology configuration from servlet context
        String serversConfig = getConfigParameter("mcp.servers");
        if (serversConfig != null) {
            String[] servers = serversConfig.split(",");
            for (String serverConfig : servers) {
                String trimmedConfig = serverConfig.trim();
                // Split only on the first colon to separate name from URL
                int firstColonIndex = trimmedConfig.indexOf(':');
                if (firstColonIndex > 0 && firstColonIndex < trimmedConfig.length() - 1) {
                    String name = trimmedConfig.substring(0, firstColonIndex).trim();
                    String endpoint = trimmedConfig.substring(firstColonIndex + 1).trim();
                    
                    McpServerConnection connection = new McpServerConnection(name, endpoint);
                    serverConnections.put(name, connection);
                    
                    // Connect and aggregate tools/resources
                    connection.connect();
                    aggregateToolsAndResources(connection);
                }
            }
        }
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
            tools.forEach((key, value) -> aggregatedTools.put(serverName + "." + key, value));
            resources.forEach((key, value) -> aggregatedResources.put(serverName + "." + key, value));
            
        } catch (Exception e) {
            System.err.println("Failed to aggregate tools/resources from server: " + connection.getName());
        }
    }

    private Object callToolInternal(String toolName, Map<String, Object> parameters) throws Exception {
        // First, try the tool name as provided (might be serverName.toolName format)
        if (toolName.contains(".")) {
            String[] parts = toolName.split("\\.", 2);
            String serverName = parts[0];
            String actualToolName = parts[1];
            
            McpServerConnection connection = serverConnections.get(serverName);
            if (connection != null) {
                return connection.callTool(actualToolName, parameters);
            }
        }
        
        // If not found, search through all aggregated tools for a match
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
    public Response handleJsonRpcRequest(String requestJsonString) {
        try {
            init(); // Ensure initialized
            
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
            
            return createJsonRpcSuccessResponse(id, result);
            
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
        // For now, return empty array - this should be populated from actual server connections
        result.set("tools", objectMapper.createArrayNode());
        return result;
    }
    
    private Object listAllResources() throws Exception {
        // Return resources in MCP format
        ObjectNode result = objectMapper.createObjectNode();
        // For now, return empty array - this should be populated from actual server connections
        result.set("resources", objectMapper.createArrayNode());
        return result;
    }

    @GET
    @Path("/sse")
    @Produces("text/event-stream")
    public Response handleSseConnection() {
        // For a complete MCP implementation, we should support SSE connections
        // This is a placeholder for SSE endpoint - full implementation would require
        // streaming response handling and session management
        return Response.status(Response.Status.NOT_IMPLEMENTED)
                .entity("SSE endpoint not yet implemented")
                .build();
    }
}