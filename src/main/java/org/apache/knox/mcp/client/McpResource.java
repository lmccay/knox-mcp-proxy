package org.apache.knox.mcp.client;

/**
 * Represents an MCP resource definition
 */
public class McpResource {
    private final String uri;
    private final String name;
    private final String description;
    private final String mimeType;
    
    public McpResource(String uri, String name, String description, String mimeType) {
        this.uri = uri;
        this.name = name;
        this.description = description;
        this.mimeType = mimeType;
    }
    
    public String getUri() {
        return uri;
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public String getMimeType() {
        return mimeType;
    }
    
    @Override
    public String toString() {
        return "McpResource{" +
                "uri='" + uri + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", mimeType='" + mimeType + '\'' +
                '}';
    }
}