package org.apache.knox.mcp;

import org.apache.knox.mcp.client.McpJsonRpcClient;
import org.apache.knox.mcp.client.McpHttpClient;
import org.apache.knox.mcp.client.McpSseClient;
import org.apache.knox.mcp.client.McpCustomHttpSseClient;
import org.apache.knox.mcp.client.McpTool;
import org.apache.knox.mcp.client.McpResource;
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
    private McpHttpClient httpClient;
    private McpSseClient sseClient;
    private McpCustomHttpSseClient customHttpSseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private boolean connected = false;
    private TransportType transportType = TransportType.STDIO;
    
    private enum TransportType {
        STDIO, HTTP, SSE, CUSTOM_HTTP_SSE
    }
    
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
            } else if (endpoint.startsWith("sse://") || endpoint.startsWith("sses://")) {
                connectSse();
            } else if (endpoint.startsWith("custom-http-sse://") || endpoint.startsWith("custom-https-sse://")) {
                connectCustomHttpSse();
            } else {
                throw new IllegalArgumentException("Unsupported endpoint type: " + endpoint + 
                    ". Supported types: stdio://, http://, https://, sse://, sses://, custom-http-sse://, custom-https-sse://");
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
        transportType = TransportType.STDIO;
    }
    
    private void connectHttp() throws Exception {
        // Create and initialize the standard HTTP MCP client
        httpClient = new McpHttpClient(endpoint);
        httpClient.connect();
        
        // Initialize the connection with capabilities
        JsonNode clientCapabilities = objectMapper.createObjectNode();
        httpClient.initialize(clientCapabilities);
        transportType = TransportType.HTTP;
    }
    
    private void connectSse() throws Exception {
        // Convert sse:// to http:// for the actual connection
        String httpUrl = endpoint.replace("sse://", "http://").replace("sses://", "https://");
        
        // Create and initialize the standard SSE MCP client
        sseClient = new McpSseClient(httpUrl);
        sseClient.connect();
        
        // Initialize the connection with capabilities
        JsonNode clientCapabilities = objectMapper.createObjectNode();
        sseClient.initialize(clientCapabilities);
        transportType = TransportType.SSE;
    }
    
    private void connectCustomHttpSse() throws Exception {
        // Convert custom-http-sse:// to http:// for the actual connection
        String httpUrl = endpoint.replace("custom-http-sse://", "http://").replace("custom-https-sse://", "https://");
        
        // Create and initialize the custom HTTP/SSE MCP client
        customHttpSseClient = new McpCustomHttpSseClient(httpUrl);
        customHttpSseClient.connect();
        
        // Initialize the connection with capabilities
        JsonNode clientCapabilities = objectMapper.createObjectNode();
        customHttpSseClient.initialize(clientCapabilities);
        transportType = TransportType.CUSTOM_HTTP_SSE;
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
        if (sseClient != null) {
            try {
                sseClient.close();
            } catch (Exception cleanupException) {
                System.err.println("Failed to cleanup SSE client for server: " + name);
            }
        }
        if (customHttpSseClient != null) {
            try {
                customHttpSseClient.close();
            } catch (Exception cleanupException) {
                System.err.println("Failed to cleanup custom HTTP/SSE client for server: " + name);
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
        ensureConnectionAlive(); // Check and potentially reconnect

        try {
            JsonNode result;
            switch (transportType) {
                case STDIO:
                    result = stdioClient.callTool(toolName, parameters);
                    break;
                case HTTP:
                    result = httpClient.callTool(toolName, parameters);
                    break;
                case SSE:
                    result = sseClient.callTool(toolName, parameters);
                    break;
                case CUSTOM_HTTP_SSE:
                    result = customHttpSseClient.callTool(toolName, parameters);
                    break;
                default:
                    throw new IllegalStateException("Unknown transport type: " + transportType);
            }
            System.out.println("Called tool: " + toolName + " on server: " + name);
            return objectMapper.convertValue(result, Object.class);
        } catch (Exception e) {
            throw new Exception("Failed to call tool '" + toolName + "' on server: " + name, e);
        }
    }

    public Object getResource(String resourceName) throws Exception {
        ensureConnectionAlive(); // Check and potentially reconnect

        try {
            JsonNode result;
            switch (transportType) {
                case STDIO:
                    result = stdioClient.readResource(resourceName);
                    break;
                case HTTP:
                    result = httpClient.readResource(resourceName);
                    break;
                case SSE:
                    result = sseClient.readResource(resourceName);
                    break;
                case CUSTOM_HTTP_SSE:
                    result = customHttpSseClient.readResource(resourceName);
                    break;
                default:
                    throw new IllegalStateException("Unknown transport type: " + transportType);
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
            
            switch (transportType) {
                case STDIO:
                    tools = stdioClient.listTools();
                    resources = stdioClient.listResources();
                    break;
                case HTTP:
                    tools = httpClient.listTools();
                    resources = httpClient.listResources();
                    break;
                case SSE:
                    tools = sseClient.listTools();
                    resources = sseClient.listResources();
                    break;
                case CUSTOM_HTTP_SSE:
                    tools = customHttpSseClient.listTools();
                    resources = customHttpSseClient.listResources();
                    break;
                default:
                    throw new IllegalStateException("Unknown transport type: " + transportType);
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

    private void ensureConnectionAlive() throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to MCP server: " + name);
        }
        
        // Check if SSE connection is still alive and reconnect if needed
        if (transportType == TransportType.SSE && sseClient != null && !sseClient.isAlive()) {
            System.out.println("DEBUG: SSE connection appears to be dead for server: " + name + ", attempting reconnect");
            try {
                sseClient.close(); // Clean up the old connection
                connectSse(); // Reconnect
                refreshToolsAndResources(); // Refresh after reconnection
                System.out.println("DEBUG: Successfully reconnected SSE client for server: " + name);
            } catch (Exception e) {
                connected = false; // Mark as disconnected if reconnection fails
                throw new Exception("Failed to reconnect to SSE server: " + name, e);
            }
        }
        
        // Similar check for custom HTTP/SSE connections
        if (transportType == TransportType.CUSTOM_HTTP_SSE && customHttpSseClient != null && !customHttpSseClient.isAlive()) {
            System.out.println("DEBUG: Custom HTTP/SSE connection appears to be dead for server: " + name + ", attempting reconnect");
            try {
                customHttpSseClient.close(); // Clean up the old connection
                connectCustomHttpSse(); // Reconnect
                refreshToolsAndResources(); // Refresh after reconnection
                System.out.println("DEBUG: Successfully reconnected custom HTTP/SSE client for server: " + name);
            } catch (Exception e) {
                connected = false; // Mark as disconnected if reconnection fails
                throw new Exception("Failed to reconnect to custom HTTP/SSE server: " + name, e);
            }
        }
    }
}