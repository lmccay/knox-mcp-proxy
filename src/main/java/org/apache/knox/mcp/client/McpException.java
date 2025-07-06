package org.apache.knox.mcp.client;

/**
 * Exception thrown when MCP operations fail
 */
public class McpException extends Exception {
    private final int code;
    
    public McpException(int code, String message) {
        super(message);
        this.code = code;
    }
    
    public McpException(String message) {
        this(-1, message);
    }
    
    public McpException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }
    
    public int getCode() {
        return code;
    }
}