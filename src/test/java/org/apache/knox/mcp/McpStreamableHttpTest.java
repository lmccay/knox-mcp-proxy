package org.apache.knox.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MCP Streamable HTTP specification compliance
 */
public class McpStreamableHttpTest {

    @Mock
    private HttpServletRequest mockRequest;
    
    @Mock
    private HttpHeaders mockHeaders;
    
    @Mock
    private ServletContext mockServletContext;
    
    private McpProxyResource mcpResource;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        mcpResource = new McpProxyResource();
        
        // Mock the servlet context for configuration
        when(mockRequest.getServletContext()).thenReturn(mockServletContext);
        when(mockServletContext.getInitParameter(anyString())).thenReturn(null);
        
        // Set up the mock request field via reflection (simulating @Context injection)
        try {
            java.lang.reflect.Field requestField = McpProxyResource.class.getDeclaredField("request");
            requestField.setAccessible(true);
            requestField.set(mcpResource, mockRequest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up test", e);
        }
    }

    @Test
    public void testUnifiedEndpointGetWithJsonAccept() throws Exception {
        // Test GET request with JSON Accept header should return service info
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2024-11-05");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        
        // Verify MCP version header is set
        assertTrue(response.getHeaders().containsKey("mcp-version"));
        assertEquals("2024-11-05", response.getHeaderString("mcp-version"));
    }

    @Test
    public void testUnifiedEndpointPostWithJsonRpc() throws Exception {
        // Test POST request with JSON-RPC tools/list
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn(null);
        
        String jsonRpcRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}";
        
        Response response = mcpResource.handleMcpPostRequest(mockRequest, mockHeaders, jsonRpcRequest);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        
        // Verify default MCP version header is set when not provided
        assertTrue(response.getHeaders().containsKey("mcp-version"));
        assertEquals("2024-11-05", response.getHeaderString("mcp-version"));
    }

    @Test
    public void testUnifiedEndpointInvalidJsonRpc() throws Exception {
        // Test POST with invalid JSON-RPC
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json");
        
        String invalidJsonRpc = "{\"method\":\"tools/list\"}"; // Missing jsonrpc field
        
        Response response = mcpResource.handleMcpPostRequest(mockRequest, mockHeaders, invalidJsonRpc);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus()); // JSON-RPC errors return 200 with error payload
        
        // The response should contain a JSON-RPC error
        String responseBody = (String) response.getEntity();
        assertTrue(responseBody.contains("\"error\""));
        assertTrue(responseBody.contains("-32600")); // Invalid Request error code
    }

    @Test
    public void testUnifiedEndpointStreamingWithoutSession() throws Exception {
        // Test POST with SSE Accept header but no session ID (should fail)
        when(mockHeaders.getHeaderString("Accept")).thenReturn("text/event-stream");
        when(mockRequest.getHeader("X-Session-ID")).thenReturn(null);
        when(mockRequest.getParameter("session")).thenReturn(null);
        
        String jsonRpcRequest = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}";
        
        Response response = mcpResource.handleMcpPostRequest(mockRequest, mockHeaders, jsonRpcRequest);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus()); // JSON-RPC errors return 200
        
        // Should return error about missing session
        String responseBody = (String) response.getEntity();
        assertTrue(responseBody.contains("\"error\""), "Response should contain error field");
        assertTrue(responseBody.contains("Streaming requests require"), 
                   "Response should mention streaming requirement");
    }

    @Test
    public void testContentNegotiationPrecedence() throws Exception {
        // Test that the endpoint correctly handles different Accept header combinations
        
        // Test text/event-stream takes precedence when it appears first
        when(mockHeaders.getHeaderString("Accept")).thenReturn("text/event-stream, application/json");
        
        // Mock startAsync to verify SSE path is taken
        when(mockRequest.startAsync()).thenThrow(new RuntimeException("SSE path taken"));
        
        // The unified endpoint should catch the exception and return an error response for SSE failures
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(500, response.getStatus()); // Internal server error for failed SSE connection
        assertTrue(response.getEntity().toString().contains("Failed to establish SSE connection"));
    }
    
    @Test
    public void testContentNegotiationJsonFirst() throws Exception {
        // Test that application/json takes precedence when it appears first
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json, text/event-stream");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2024-11-05");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        
        // Verify MCP version header is set
        assertTrue(response.getHeaders().containsKey("mcp-version"));
        assertEquals("2024-11-05", response.getHeaderString("mcp-version"));
    }

    @Test 
    public void testMcpVersionHeaderHandling() throws Exception {
        // Test that custom MCP version headers are preserved
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2025-01-01");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals("2025-01-01", response.getHeaderString("mcp-version"));
    }
}
