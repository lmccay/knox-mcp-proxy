package org.apache.knox.mcp;

import org.apache.knox.mcp.client.McpJsonRpcClient;
import org.apache.knox.mcp.client.McpHttpSseClient;
import org.apache.knox.mcp.client.McpTool;
import org.apache.knox.mcp.client.McpResource;
import org.apache.knox.mcp.client.McpException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.List;

public class McpServerConnection {
    private final String name;
    private final String endpoint;
    private McpJsonRpcClient stdioClient;
    private McpHttpSseClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private boolean connected = false;
    private boolean isHttpTransport = false;
    
    private final Map<String, Object> cachedTools = new ConcurrentHashMap<>();
    private final Map<String, Object> cachedResources = new ConcurrentHashMap<>();

    public McpServerConnection(String name, String endpoint) {
        this.name = name;
        this.endpoint = endpoint;
    }

    public void connect() throws Exception {
        if (connected) {
            return;
        }

        try {
            if (endpoint.startsWith("stdio://")) {
                connectStdio();
            } else if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
                connectHttp();
            } else {
                throw new IllegalArgumentException("Unsupported endpoint type: " + endpoint + 
                    ". Supported types: stdio://, http://, https://");
            }
            
            // Discover tools and resources
            refreshToolsAndResources();
            connected = true;
            
            System.out.println("Successfully connected to MCP server: " + name + " at " + endpoint);
            
        } catch (Exception e) {
            cleanup();
            throw new Exception("Failed to connect to MCP server: " + name, e);
        }
    }
    
    private void connectStdio() throws Exception {
        // Parse the stdio command
        String command = endpoint.substring("stdio://".length());
        String[] parts = command.split("\\s+");
        String cmd = parts[0];
        String[] args = null;
        if (parts.length > 1) {
            args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
        }
        
        // Create and initialize the stdio MCP client
        stdioClient = new McpJsonRpcClient(cmd, args);
        
        // Initialize the connection with capabilities
        JsonNode clientCapabilities = objectMapper.createObjectNode();
        stdioClient.initialize(clientCapabilities);
        isHttpTransport = false;
    }
    
    private void connectHttp() throws Exception {
        // Create and initialize the HTTP/SSE MCP client
        httpClient = new McpHttpSseClient(endpoint);
        httpClient.connect();
        
        // Initialize the connection with capabilities
        JsonNode clientCapabilities = objectMapper.createObjectNode();
        httpClient.initialize(clientCapabilities);
        isHttpTransport = true;
    }
    
    private void cleanup() {
        if (stdioClient != null) {
            try {
                stdioClient.close();
            } catch (Exception cleanupException) {
                System.err.println("Failed to cleanup stdio client for server: " + name);
            }
        }
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (Exception cleanupException) {
                System.err.println("Failed to cleanup HTTP client for server: " + name);
            }
        }
    }

    public void disconnect() throws Exception {
        if (!connected) {
            return;
        }

        try {
            cleanup();
            System.out.println("Disconnected from MCP server: " + name);
        } finally {
            connected = false;
            cachedTools.clear();
            cachedResources.clear();
        }
    }

    public Map<String, Object> getTools() {
        return new ConcurrentHashMap<>(cachedTools);
    }

    public Map<String, Object> getResources() {
        return new ConcurrentHashMap<>(cachedResources);
    }

    public Object callTool(String toolName, Map<String, Object> parameters) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server: " + name);
        }

        try {
            JsonNode result;
            if (isHttpTransport) {
                result = httpClient.callTool(toolName, parameters);
            } else {
                result = stdioClient.callTool(toolName, parameters);
            }
            System.out.println("Called tool: " + toolName + " on server: " + name);
            return objectMapper.convertValue(result, Object.class);
        } catch (Exception e) {
            throw new Exception("Failed to call tool '" + toolName + "' on server: " + name, e);
        }
    }

    public Object getResource(String resourceName) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server: " + name);
        }

        try {
            JsonNode result;
            if (isHttpTransport) {
                result = httpClient.readResource(resourceName);
            } else {
                result = stdioClient.readResource(resourceName);
            }
            System.out.println("Read resource: " + resourceName + " from server: " + name);
            return objectMapper.convertValue(result, Object.class);
        } catch (Exception e) {
            throw new Exception("Failed to get resource '" + resourceName + "' from server: " + name, e);
        }
    }

    private void refreshToolsAndResources() {
        try {
            List<McpTool> tools;
            List<McpResource> resources;
            
            if (isHttpTransport) {
                tools = httpClient.listTools();
                resources = httpClient.listResources();
            } else {
                tools = stdioClient.listTools();
                resources = stdioClient.listResources();
            }
            
            // Refresh tools
            cachedTools.clear();
            for (McpTool tool : tools) {
                Map<String, Object> toolMap = new HashMap<>();
                toolMap.put("name", tool.getName());
                toolMap.put("description", tool.getDescription());
                if (tool.getInputSchema() != null) {
                    toolMap.put("inputSchema", objectMapper.convertValue(tool.getInputSchema(), Object.class));
                }
                cachedTools.put(tool.getName(), toolMap);
            }
            
            // Refresh resources
            cachedResources.clear();
            for (McpResource resource : resources) {
                Map<String, Object> resourceMap = new HashMap<>();
                resourceMap.put("uri", resource.getUri());
                resourceMap.put("name", resource.getName());
                resourceMap.put("description", resource.getDescription());
                if (resource.getMimeType() != null) {
                    resourceMap.put("mimeType", resource.getMimeType());
                }
                cachedResources.put(resource.getUri(), resourceMap);
            }
            
            System.out.println("Discovered " + cachedTools.size() + " tools and " + 
                             cachedResources.size() + " resources from server: " + name);
            
        } catch (Exception e) {
            System.err.println("Failed to refresh tools/resources from server: " + name + " - " + e.getMessage());
            // Keep empty caches on error
        }
    }

    public String getName() {
        return name;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public boolean isConnected() {
        return connected;
    }
}