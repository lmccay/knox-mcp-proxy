package org.apache.knox.mcp.util;

/**
 * Simple logger utility for MCP Proxy to replace System.out.println calls.
 * Uses a simple debug flag to control debug output.
 */
public class McpLogger {
    
    private static final boolean DEBUG_ENABLED = Boolean.parseBoolean(
        System.getProperty("mcp.debug", "false"));
    
    private final String className;
    
    public McpLogger(Class<?> clazz) {
        this.className = clazz.getSimpleName();
    }
    
    public static McpLogger getLogger(Class<?> clazz) {
        return new McpLogger(clazz);
    }
    
    public void debug(String message) {
        if (DEBUG_ENABLED) {
            System.out.println("[DEBUG " + className + "] " + message);
        }
    }
    
    public void info(String message) {
        System.out.println("[INFO " + className + "] " + message);
    }
    
    public void warn(String message) {
        System.out.println("[WARN " + className + "] " + message);
    }
    
    public void error(String message) {
        System.err.println("[ERROR " + className + "] " + message);
    }
    
    public void error(String message, Throwable throwable) {
        System.err.println("[ERROR " + className + "] " + message);
        if (DEBUG_ENABLED && throwable != null) {
            throwable.printStackTrace();
        }
    }
}
