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
        // Extract a meaningful name from command/args
        if (args != null && args.length > 0) {
            for (String arg : args) {
                if (arg.contains("calculator")) return "calculator";
                if (arg.contains("filesystem")) return "filesystem"; 
                if (arg.contains("database")) return "database";
            }
        }
        return command.substring(command.lastIndexOf('/') + 1).replace(".py", "");
    }
    
    private void startProcess(String command, String[] args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        
        if (args != null && args.length > 0) {
            String[] fullCommand = new String[args.length + 1];
            fullCommand[0] = command;
            System.arraycopy(args, 0, fullCommand, 1, args.length);
            pb.command(fullCommand);
        } else {
            pb.command(command.split("\\s+"));
        }
        
        pb.redirectErrorStream(false);
        mcpProcess = pb.start();
        
        processInput = new BufferedWriter(new OutputStreamWriter(mcpProcess.getOutputStream()));
        processOutput = new BufferedReader(new InputStreamReader(mcpProcess.getInputStream()));
    }
    
    private void startReaderThread() {
        readerThread = new Thread(() -> {
            try {
                String line;
                while (!closed && (line = processOutput.readLine()) != null) {
                    handleIncomingMessage(line);
                }
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("Error reading from MCP process: " + e.getMessage());
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }
    
    private void handleIncomingMessage(String message) {
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
            System.err.println("Error parsing message from " + serverName + ": " + e.getMessage());
        }
    }
    
    private CompletableFuture<JsonNode> sendRequest(String method, JsonNode params) {
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
            synchronized (processInput) {
                processInput.write(requestJson);
                processInput.newLine();
                processInput.flush();
            }
        } catch (IOException e) {
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
        
        JsonNode result = sendRequest("initialize", params).get(10, TimeUnit.SECONDS);
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
        JsonNode result = sendRequest("tools/list", null).get(10, TimeUnit.SECONDS);
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
        
        return sendRequest("tools/call", params).get(30, TimeUnit.SECONDS);
    }
    
    public List<McpResource> listResources() throws Exception {
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
        return mcpProcess != null && mcpProcess.isAlive() && !closed;
    }
}