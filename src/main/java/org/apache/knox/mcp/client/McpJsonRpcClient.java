package org.apache.knox.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Java 8 compatible MCP client implementation using JSON-RPC 2.0 over stdio
 */
public class McpJsonRpcClient implements AutoCloseable {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    
    private Process mcpProcess;
    private BufferedWriter processInput;
    private BufferedReader processOutput;
    private Thread readerThread;
    private volatile boolean closed = false;
    
    private String serverName;
    private JsonNode serverCapabilities;
    
    public McpJsonRpcClient(String command, String[] args) throws IOException {
        this.serverName = extractServerName(command, args);
        startProcess(command, args);
        startReaderThread();
    }
    
    private String extractServerName(String command, String[] args) {
        // Extract a meaningful name from the command path
        String baseName = command.substring(command.lastIndexOf('/') + 1);
        
        // Remove common file extensions
        if (baseName.contains(".")) {
            baseName = baseName.substring(0, baseName.lastIndexOf('.'));
        }
        
        // If we have args, look for a --name or similar parameter
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length - 1; i++) {
                if (args[i].equals("--name") || args[i].equals("-n")) {
                    return args[i + 1];
                }
            }
            
            // Look for config files that might indicate the server type
            for (String arg : args) {
                if (arg.endsWith(".json") || arg.endsWith(".yaml") || arg.endsWith(".yml")) {
                    String configName = arg.substring(arg.lastIndexOf('/') + 1);
                    if (configName.contains(".")) {
                        configName = configName.substring(0, configName.lastIndexOf('.'));
                    }
                    return configName;
                }
            }
        }
        
        // Return the base command name, or "mcp-server" if empty
        return baseName.isEmpty() ? "mcp-server" : baseName;
    }
    
    private void startProcess(String command, String[] args) throws IOException {
        System.out.println("DEBUG: Starting MCP process - command: " + command + 
                          ", args: " + (args != null ? java.util.Arrays.toString(args) : "null"));
        
        ProcessBuilder pb = new ProcessBuilder();
        
        if (args != null && args.length > 0) {
            String[] fullCommand = new String[args.length + 1];
            fullCommand[0] = command;
            System.arraycopy(args, 0, fullCommand, 1, args.length);
            pb.command(fullCommand);
            System.out.println("DEBUG: Full command: " + java.util.Arrays.toString(fullCommand));
        } else {
            pb.command(command.split("\\s+"));
            System.out.println("DEBUG: Split command: " + java.util.Arrays.toString(command.split("\\s+")));
        }
        
        pb.redirectErrorStream(false);
        mcpProcess = pb.start();
        
        System.out.println("DEBUG: MCP process started with PID: " + mcpProcess.hashCode() + 
                          ", alive: " + mcpProcess.isAlive());
        
        processInput = new BufferedWriter(new OutputStreamWriter(mcpProcess.getOutputStream()));
        processOutput = new BufferedReader(new InputStreamReader(mcpProcess.getInputStream()));
        
        System.out.println("DEBUG: Process streams established for server: " + serverName);
    }
    
    private void startReaderThread() {
        readerThread = new Thread(() -> {
            System.out.println("DEBUG: Starting reader thread for server: " + serverName);
            try {
                String line;
                while (!closed && (line = processOutput.readLine()) != null) {
                    System.out.println("DEBUG: Raw line from " + serverName + ": " + line);
                    handleIncomingMessage(line);
                }
                System.out.println("DEBUG: Reader thread ending for server: " + serverName + " (closed=" + closed + ")");
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("Error reading from MCP process " + serverName + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
        
        // Give the process a moment to start up
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        System.out.println("DEBUG: Reader thread started for server: " + serverName);
    }
    
    private void handleIncomingMessage(String message) {
        System.out.println("DEBUG: Received message from " + serverName + ": " + message);
        
        try {
            // Check if message looks like JSON
            if (!message.trim().startsWith("{")) {
                System.out.println("DEBUG: Non-JSON message from " + serverName + ", treating as plain text output: " + message);
                // This might be plain text output from a non-MCP process
                // For now, we'll just log it and ignore it
                return;
            }
            
            JsonNode response = objectMapper.readTree(message);
            
            if (response.has("id") && !response.get("id").isNull()) {
                // This is a response to a request
                long id = response.get("id").asLong();
                System.out.println("DEBUG: Processing response for ID: " + id);
                
                CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                if (future != null) {
                    if (response.has("error")) {
                        JsonNode error = response.get("error");
                        System.err.println("ERROR: MCP server returned error for ID " + id + ": " + error);
                        future.completeExceptionally(new McpException(
                            error.get("code").asInt(),
                            error.get("message").asText()
                        ));
                    } else {
                        System.out.println("DEBUG: Completing future for ID " + id + " with result");
                        future.complete(response.get("result"));
                    }
                } else {
                    System.err.println("WARNING: No pending request found for ID: " + id);
                }
            } else {
                // This is a notification - ignore for now
                System.out.println("Received notification from " + serverName + ": " + message);
            }
        } catch (Exception e) {
            System.err.println("Error parsing message from " + serverName + ": " + e.getMessage());
            System.err.println("Raw message was: " + message);
            // Don't print stack trace for JSON parsing errors from non-MCP processes
            if (!(e instanceof com.fasterxml.jackson.core.JsonParseException)) {
                e.printStackTrace();
            }
        }
    }
    
    private CompletableFuture<JsonNode> sendRequest(String method, JsonNode params) {
        System.out.println("DEBUG: Sending request - method: " + method + ", server: " + serverName);
        
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
            System.out.println("DEBUG: Sending JSON request: " + requestJson);
            
            synchronized (processInput) {
                processInput.write(requestJson);
                processInput.newLine();
                processInput.flush();
            }
            System.out.println("DEBUG: Request sent successfully, waiting for response with ID: " + id);
        } catch (IOException e) {
            System.err.println("ERROR: Failed to send request: " + e.getMessage());
            pendingRequests.remove(id);
            future.completeExceptionally(e);
        }
        
        return future;
    }
    
    public JsonNode initialize(JsonNode clientCapabilities) throws Exception {
        System.out.println("DEBUG: Initializing MCP client for server: " + serverName);
        
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.set("capabilities", clientCapabilities != null ? clientCapabilities : objectMapper.createObjectNode());
        params.set("clientInfo", createClientInfo());
        
        System.out.println("DEBUG: Sending initialize request to server: " + serverName);
        
        try {
            JsonNode result = sendRequest("initialize", params).get(5, TimeUnit.SECONDS);
            
            if (result != null && result.has("capabilities")) {
                this.serverCapabilities = result.get("capabilities");
                System.out.println("DEBUG: Initialization successful for server: " + serverName + 
                                 ", capabilities: " + serverCapabilities);
            } else {
                System.out.println("DEBUG: Initialization completed for server: " + serverName + 
                                 ", no capabilities returned");
            }
            
            return result;
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("WARNING: Initialization timeout for server: " + serverName + 
                             ". This might not be a proper MCP server.");
            // Return a minimal capabilities object so the connection doesn't fail completely
            ObjectNode emptyResult = objectMapper.createObjectNode();
            ObjectNode emptyCaps = objectMapper.createObjectNode();
            emptyResult.set("capabilities", emptyCaps);
            return emptyResult;
        } catch (Exception e) {
            System.err.println("ERROR: Initialization failed for server: " + serverName + ": " + e.getMessage());
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
        System.out.println("DEBUG: Starting listTools for server: " + serverName);
        
        if (!isAlive()) {
            throw new IllegalStateException("MCP process is not alive for server: " + serverName);
        }
        
        try {
            System.out.println("DEBUG: Sending tools/list request to server: " + serverName);
            CompletableFuture<JsonNode> future = sendRequest("tools/list", null);
            
            System.out.println("DEBUG: Waiting for tools/list response from server: " + serverName);
            JsonNode result = future.get(5, TimeUnit.SECONDS);
            
            System.out.println("DEBUG: Received tools/list response from server: " + serverName + ", result: " + 
                              (result != null ? result.toString() : "null"));
            
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
            
            System.out.println("DEBUG: Returning " + tools.size() + " tools from server: " + serverName);
            return tools;
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("WARNING: tools/list timeout for server: " + serverName + 
                             ". This might not be a proper MCP server.");
            return new ArrayList<>(); // Return empty list for non-MCP servers
        }
    }
    
    public JsonNode callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        if (arguments != null) {
            params.set("arguments", objectMapper.valueToTree(arguments));
        }
        
        try {
            return sendRequest("tools/call", params).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("WARNING: tools/call timeout for server: " + serverName + 
                             ", tool: " + toolName + ". This might not be a proper MCP server.");
            throw new IllegalArgumentException("Tool not found or server not responding: " + toolName);
        }
    }
    
    public List<McpResource> listResources() throws Exception {
        try {
            JsonNode result = sendRequest("resources/list", null).get(10, TimeUnit.SECONDS);
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
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("WARNING: resources/list timeout for server: " + serverName + 
                             ". This might not be a proper MCP server.");
            return new ArrayList<>(); // Return empty list for non-MCP servers
        }
    }
    
    public JsonNode readResource(String uri) throws Exception {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("uri", uri);
        
        return sendRequest("resources/read", params).get(10, TimeUnit.SECONDS);
    }
    
    @Override
    public void close() {
        closed = true;
        
        // Cancel pending requests
        for (CompletableFuture<JsonNode> future : pendingRequests.values()) {
            future.cancel(true);
        }
        pendingRequests.clear();
        
        // Close process streams
        try {
            if (processInput != null) {
                processInput.close();
            }
            if (processOutput != null) {
                processOutput.close();
            }
        } catch (IOException e) {
            // Ignore
        }
        
        // Terminate process
        if (mcpProcess != null) {
            mcpProcess.destroyForcibly();
            try {
                mcpProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Interrupt reader thread
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
    
    public String getServerName() {
        return serverName;
    }
    
    public JsonNode getServerCapabilities() {
        return serverCapabilities;
    }
    
    public boolean isAlive() {
        boolean processAlive = mcpProcess != null && mcpProcess.isAlive() && !closed;
        System.out.println("DEBUG: isAlive check for " + serverName + 
                          " - process: " + (mcpProcess != null ? "exists" : "null") + 
                          ", alive: " + (mcpProcess != null ? mcpProcess.isAlive() : "N/A") + 
                          ", closed: " + closed + 
                          ", pending requests: " + pendingRequests.size());
        return processAlive;
    }
    
    public int getPendingRequestCount() {
        return pendingRequests.size();
    }
}