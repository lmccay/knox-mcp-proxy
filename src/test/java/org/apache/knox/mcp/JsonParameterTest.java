package org.apache.knox.mcp;

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

/**
 * Test class to verify JSON parameter handling in tool invocation
 */
class JsonParameterTest {
    
    @Mock
    private HttpServletRequest request;
    
    @Mock
    private ServletContext servletContext;
    
    private McpProxyResource resource;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        resource = new McpProxyResource();
        
        // Mock the servlet context behavior
        when(request.getServletContext()).thenReturn(servletContext);
        when(servletContext.getInitParameter("mcp.servers")).thenReturn(null);
        
        // Use reflection to set the request field since @Context won't work in tests
        try {
            java.lang.reflect.Field requestField = McpProxyResource.class.getDeclaredField("request");
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
    void testCallToolWithEmptyJsonObject() {
        String emptyParams = "{}";
        Response response = resource.callTool("nonexistent", emptyParams);
        assertNotNull(response);
        // Should return 404 for nonexistent tool, not a JSON parsing error
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void testCallToolWithValidJsonParameters() {
        String jsonParams = "{\"param1\": \"value1\", \"param2\": 123, \"param3\": true}";
        Response response = resource.callTool("nonexistent", jsonParams);
        assertNotNull(response);
        // Should return 404 for nonexistent tool, not a JSON parsing error
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void testCallToolWithNullParameters() {
        Response response = resource.callTool("nonexistent", null);
        assertNotNull(response);
        // Should return 404 for nonexistent tool, not a JSON parsing error
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void testCallToolWithEmptyStringParameters() {
        String emptyParams = "";
        Response response = resource.callTool("nonexistent", emptyParams);
        assertNotNull(response);
        // Should return 404 for nonexistent tool, not a JSON parsing error
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void testCallToolWithNestedJsonParameters() {
        String nestedParams = "{\"user\": {\"name\": \"John\", \"age\": 30}, \"options\": [\"opt1\", \"opt2\"]}";
        Response response = resource.callTool("nonexistent", nestedParams);
        assertNotNull(response);
        // Should return 404 for nonexistent tool, not a JSON parsing error
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
    }

    @Test
    void testCallToolWithInvalidJsonParameters() {
        String invalidParams = "{invalid json";
        Response response = resource.callTool("nonexistent", invalidParams);
        assertNotNull(response);
        // Should return 400 for invalid JSON, not 404
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        String entity = (String) response.getEntity();
        assertTrue(entity.contains("Invalid JSON parameters"));
    }

    @Test
    void testJsonRpcToolsListRequest() {
        String jsonRpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        Response response = resource.handleJsonRpcRequest(jsonRpcRequest);
        assertNotNull(response);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
    }

    @Test
    void testJsonRpcToolsCallRequest() {
        String jsonRpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"nonexistent\",\"arguments\":{}}}";
        Response response = resource.handleJsonRpcRequest(jsonRpcRequest);
        assertNotNull(response);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        // Should return JSON-RPC error response for tool not found
    }

    @Test
    void testJsonRpcInvalidMethod() {
        String jsonRpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"invalid/method\"}";
        Response response = resource.handleJsonRpcRequest(jsonRpcRequest);
        assertNotNull(response);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        // Should return JSON-RPC error response for method not found
    }

    @Test
    void testJsonRpcMissingMethod() {
        String jsonRpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":4}";
        Response response = resource.handleJsonRpcRequest(jsonRpcRequest);
        assertNotNull(response);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        // Should return JSON-RPC error response for missing method
    }
}
