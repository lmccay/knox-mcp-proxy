package org.apache.knox.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages SSE sessions for MCP communication
 */
public class McpSseSessionManager {
    
    private static final McpSseSessionManager INSTANCE = new McpSseSessionManager();
    
    private final Map<String, McpSseSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionCounter = new AtomicLong(1);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService cleanupExecutor = Executors.newScheduledThreadPool(1);
    
    private McpSseSessionManager() {
        // Start cleanup task to remove closed sessions
        cleanupExecutor.scheduleAtFixedRate(this::cleanupClosedSessions, 30, 30, TimeUnit.SECONDS);
    }
    
    public static McpSseSessionManager getInstance() {
        return INSTANCE;
    }
    
    public McpSseSession createSession(AsyncContext asyncContext, McpProxyResource proxyResource, HttpServletRequest request) throws IOException {
        String sessionId = "mcp-sse-" + sessionCounter.getAndIncrement();
        
        McpSseSession session = new McpSseSession(sessionId, asyncContext, proxyResource, request);
        sessions.put(sessionId, session);
        
        System.out.println("DEBUG: Created SSE session " + sessionId + " (total: " + sessions.size() + ")");
        return session;
    }
    
    public McpSseSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }
    
    public void removeSession(String sessionId) {
        McpSseSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
            System.out.println("DEBUG: Removed SSE session " + sessionId + " (remaining: " + sessions.size() + ")");
        }
    }
    
    public void handleMessageForSession(String sessionId, String messageJson) {
        McpSseSession session = sessions.get(sessionId);
        if (session == null) {
            System.err.println("ERROR: No SSE session found for ID: " + sessionId);
            return;
        }
        
        if (session.isClosed()) {
            System.err.println("ERROR: SSE session is closed: " + sessionId);
            removeSession(sessionId);
            return;
        }
        
        try {
            System.out.println("DEBUG: Processing message for SSE session " + sessionId + ": " + messageJson);
            
            JsonNode request = objectMapper.readTree(messageJson);
            
            // Validate JSON-RPC format
            if (!request.has("jsonrpc") || !"2.0".equals(request.get("jsonrpc").asText())) {
                session.sendJsonRpcError(null, -32600, "Invalid Request", "Missing or invalid jsonrpc field");
                return;
            }
            
            if (!request.has("method")) {
                JsonNode id = request.has("id") ? request.get("id") : null;
                session.sendJsonRpcError(id, -32600, "Invalid Request", "Missing method field");
                return;
            }
            
            // Handle the request
            session.handleJsonRpcRequest(request);
            
        } catch (Exception e) {
            System.err.println("ERROR: Failed to handle message for session " + sessionId + ": " + e.getMessage());
            e.printStackTrace();
            
            try {
                session.sendJsonRpcError(null, -32700, "Parse error", "Invalid JSON: " + e.getMessage());
            } catch (IOException ioError) {
                System.err.println("ERROR: Failed to send error response: " + ioError.getMessage());
                removeSession(sessionId);
            }
        }
    }
    
    private void cleanupClosedSessions() {
        int initialSize = sessions.size();
        sessions.entrySet().removeIf(entry -> {
            McpSseSession session = entry.getValue();
            if (session.isClosed()) {
                System.out.println("DEBUG: Cleaning up closed session: " + entry.getKey());
                return true;
            }
            return false;
        });
        
        int removedCount = initialSize - sessions.size();
        if (removedCount > 0) {
            System.out.println("DEBUG: Cleaned up " + removedCount + " closed SSE sessions (remaining: " + sessions.size() + ")");
        }
    }
    
    public int getActiveSessionCount() {
        return sessions.size();
    }
    
    public void shutdown() {
        System.out.println("DEBUG: Shutting down SSE session manager");
        
        // Close all sessions
        for (McpSseSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        
        // Shutdown cleanup executor
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}