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
import javax.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Field;

/**
 * Test to verify the exact format of MCP tools/list response
 */
class McpToolsListFormatTest {
    
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
    void testMcpToolsListJsonRpcFormat() throws Exception {
        // Get the aggregatedTools field and add a mock tool
        Field aggregatedToolsField = McpProxyResource.class.getDeclaredField("aggregatedTools");
        aggregatedToolsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> aggregatedTools = (Map<String, Object>) aggregatedToolsField.get(resource);
        
        // Add a mock tool with problematic name
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
        inputSchema.put("required", new String[]{"path"});
        toolData.put("inputSchema", inputSchema);
        
        // Add tool with a problematic name that needs sanitization
        aggregatedTools.put("file_server_read_file", toolData);
        
        // Test the MCP JSON-RPC tools/list request
        String jsonRpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        Response response = resource.handleJsonRpcRequest(jsonRpcRequest, request);
        
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        
        String jsonResponse = (String) response.getEntity();
        System.out.println("MCP tools/list JSON-RPC response: " + jsonResponse);
        
        // Parse the JSON-RPC response
        JsonNode responseNode = objectMapper.readTree(jsonResponse);
        assertTrue(responseNode.has("jsonrpc"), "Should have jsonrpc field");
        assertEquals("2.0", responseNode.get("jsonrpc").asText());
        assertTrue(responseNode.has("id"), "Should have id field");
        assertEquals(1, responseNode.get("id").asInt());
        assertTrue(responseNode.has("result"), "Should have result field");
        
        JsonNode result = responseNode.get("result");
        assertTrue(result.has("tools"), "Result should have tools array");
        
        JsonNode toolsArray = result.get("tools");
        assertTrue(toolsArray.isArray(), "Tools should be an array");
        
        if (toolsArray.size() > 0) {
            JsonNode firstTool = toolsArray.get(0);
            assertTrue(firstTool.has("name"), "Tool should have name field");
            assertTrue(firstTool.has("description"), "Tool should have description field");
            assertTrue(firstTool.has("inputSchema"), "Tool should have inputSchema field");
            
            String toolName = firstTool.get("name").asText();
            System.out.println("Tool name in MCP response: " + toolName);
            
            // Verify this is the sanitized name
            assertTrue(toolName.matches("^[a-zA-Z0-9_-]+$"), 
                      "Tool name should be sanitized: " + toolName);
            
            // This is the MCP format - no "function" wrapper, no "type" field
            assertFalse(firstTool.has("type"), "MCP format should not have 'type' field");
            assertFalse(firstTool.has("function"), "MCP format should not have 'function' wrapper");
            
            System.out.println("MCP tool format: " + firstTool.toPrettyString());
        }
        
        System.out.println("MCP tools/list format validation passed!");
    }
}
