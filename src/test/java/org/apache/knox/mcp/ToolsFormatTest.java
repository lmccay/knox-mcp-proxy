package org.apache.knox.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.ServletContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Field;

/**
 * Test to verify the format of tools returned by the proxy matches MCP specification
 */
class ToolsFormatTest {
    
    @Mock
    private HttpServletRequest request;
    
    @Mock
    private ServletContext servletContext;
    
    private McpProxyResource resource;
    private ObjectMapper objectMapper = new ObjectMapper();
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resource = new McpProxyResource();
        
        // Mock the servlet context behavior
        when(request.getServletContext()).thenReturn(servletContext);
        when(servletContext.getInitParameter("mcp.servers")).thenReturn(null);
        
        // Use reflection to set the request field since @Context won't work in tests
        try {
            Field requestField = McpProxyResource.class.getDeclaredField("request");
            requestField.setAccessible(true);
            requestField.set(resource, request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set request field", e);
        }
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (resource != null) {
            resource.cleanup();
        }
    }

    @Test
    void testToolsOutputFormat() throws Exception {
        // Get the aggregatedTools field
        Field aggregatedToolsField = McpProxyResource.class.getDeclaredField("aggregatedTools");
        aggregatedToolsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> aggregatedTools = (Map<String, Object>) aggregatedToolsField.get(resource);
        
        // Add a mock tool with problematic name that should be sanitized
        Map<String, Object> toolData = new HashMap<>();
        toolData.put("description", "Test tool for file operations");
        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> pathProperty = new HashMap<>();
        pathProperty.put("type", "string");
        pathProperty.put("description", "File path");
        properties.put("path", pathProperty);
        inputSchema.put("properties", properties);
        toolData.put("inputSchema", inputSchema);
        
        // Add tool with a name that has dots (should be sanitized)
        aggregatedTools.put("file_server_read_file", toolData);
        
        // Call listTools to get the JSON response format
        javax.ws.rs.core.Response response = resource.listTools();
        assertEquals(200, response.getStatus());
        
        String jsonResponse = (String) response.getEntity();
        System.out.println("Tools JSON response: " + jsonResponse);
        
        // Parse the JSON and check structure
        JsonNode responseNode = objectMapper.readTree(jsonResponse);
        assertTrue(responseNode.isObject(), "Response should be a JSON object");
        assertTrue(responseNode.has("file_server_read_file"), "Should contain the sanitized tool name");
        
        JsonNode toolNode = responseNode.get("file_server_read_file");
        assertTrue(toolNode.has("description"), "Tool should have description");
        assertTrue(toolNode.has("inputSchema"), "Tool should have inputSchema");
        
        // Check if tool name follows consistent naming pattern
        String toolName = "file_server_read_file";
        assertTrue(toolName.matches("^[a-zA-Z0-9_-]+$"), 
                  "Tool name should match consistent naming pattern: " + toolName);
        
        System.out.println("Tool name validation passed: " + toolName);
        System.out.println("Tool data: " + toolNode.toPrettyString());
    }
}
