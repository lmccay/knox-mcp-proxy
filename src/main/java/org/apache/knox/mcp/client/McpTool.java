package org.apache.knox.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents an MCP tool definition
 */
public class McpTool {
    private final String name;
    private final String description;
    private final JsonNode inputSchema;
    
    public McpTool(String name, String description, JsonNode inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public JsonNode getInputSchema() {
        return inputSchema;
    }
    
    @Override
    public String toString() {
        return "McpTool{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}