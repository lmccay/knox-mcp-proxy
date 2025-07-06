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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Path("/mcp/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpProxyResource {

    @Context
    private HttpServletRequest request;

    private final Map<String, McpServerConnection> serverConnections = new ConcurrentHashMap<>();
    private final Map<String, Object> aggregatedTools = new ConcurrentHashMap<>();
    private final Map<String, Object> aggregatedResources = new ConcurrentHashMap<>();
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
                String[] parts = serverConfig.trim().split(":");
                if (parts.length >= 2) {
                    String name = parts[0];
                    String endpoint = parts[1];
                    
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
        // Route tool calls to appropriate server
        if (toolName.contains(".")) {
            String[] parts = toolName.split("\\.", 2);
            String serverName = parts[0];
            String actualToolName = parts[1];
            
            McpServerConnection connection = serverConnections.get(serverName);
            if (connection != null) {
                return connection.callTool(actualToolName, parameters);
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
            return Response.ok(aggregatedTools).build();
            
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
            return Response.ok(aggregatedResources).build();
            
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to list resources: " + e.getMessage())
                    .build();
        }
    }

    @POST
    @Path("/tools/{toolName}")
    public Response callTool(@PathParam("toolName") String toolName, 
                            Map<String, Object> parameters) {
        try {
            init(); // Ensure initialized
            Object result = callToolInternal(toolName, parameters);
            return Response.ok(result).build();
            
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Tool not found: " + toolName)
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
            return Response.ok(result).build();
            
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Resource not found: " + resourceName)
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
}