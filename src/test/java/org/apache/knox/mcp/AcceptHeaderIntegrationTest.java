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
 * Integration tests for Accept header content negotiation
 */
public class AcceptHeaderIntegrationTest {

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
    public void testNoAcceptHeaderDefaultsToJson() throws Exception {
        // Test that when no Accept header is provided, JSON response is returned
        when(mockHeaders.getHeaderString("Accept")).thenReturn(null);
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2024-11-05");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        assertTrue(response.getEntity().toString().contains("Knox MCP Proxy"));
    }

    @Test
    public void testExplicitJsonAcceptHeader() throws Exception {
        // Test that explicit application/json returns JSON
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2024-11-05");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        assertTrue(response.getEntity().toString().contains("Knox MCP Proxy"));
    }

    @Test
    public void testEventStreamOnlyTriggersSSE() throws Exception {
        // Test that text/event-stream only triggers SSE path
        when(mockHeaders.getHeaderString("Accept")).thenReturn("text/event-stream");
        
        // Mock startAsync to verify SSE path is taken
        when(mockRequest.startAsync()).thenThrow(new RuntimeException("SSE path taken"));
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(500, response.getStatus()); // Internal server error for failed SSE connection
        assertTrue(response.getEntity().toString().contains("Failed to establish SSE connection"));
    }

    @Test
    public void testJsonFirstInAcceptHeader() throws Exception {
        // Test that application/json listed first returns JSON
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json, text/event-stream");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2024-11-05");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        assertTrue(response.getEntity().toString().contains("Knox MCP Proxy"));
    }

    @Test
    public void testEventStreamFirstInAcceptHeader() throws Exception {
        // Test that text/event-stream listed first triggers SSE
        when(mockHeaders.getHeaderString("Accept")).thenReturn("text/event-stream, application/json");
        
        // Mock startAsync to verify SSE path is taken
        when(mockRequest.startAsync()).thenThrow(new RuntimeException("SSE path taken"));
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(500, response.getStatus()); // Internal server error for failed SSE connection
        assertTrue(response.getEntity().toString().contains("Failed to establish SSE connection"));
    }

    @Test
    public void testQualityValuesSsePreferred() throws Exception {
        // Test that higher quality value for text/event-stream triggers SSE
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json;q=0.5, text/event-stream;q=0.9");
        
        // Mock startAsync to verify SSE path is taken
        when(mockRequest.startAsync()).thenThrow(new RuntimeException("SSE path taken"));
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(500, response.getStatus()); // Internal server error for failed SSE connection
        assertTrue(response.getEntity().toString().contains("Failed to establish SSE connection"));
    }

    @Test
    public void testQualityValuesJsonPreferred() throws Exception {
        // Test that higher quality value for application/json returns JSON
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json;q=0.9, text/event-stream;q=0.5");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2024-11-05");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        assertTrue(response.getEntity().toString().contains("Knox MCP Proxy"));
    }

    @Test
    public void testComplexAcceptHeaderWithMultipleTypes() throws Exception {
        // Test complex Accept header with multiple types but JSON preferred
        when(mockHeaders.getHeaderString("Accept")).thenReturn("text/html;q=0.8, application/json;q=0.9, text/event-stream;q=0.7, */*;q=0.1");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2024-11-05");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        assertTrue(response.getEntity().toString().contains("Knox MCP Proxy"));
    }

    @Test
    public void testComplexAcceptHeaderWithSsePreferred() throws Exception {
        // Test complex Accept header with multiple types but SSE preferred
        when(mockHeaders.getHeaderString("Accept")).thenReturn("text/html;q=0.8, application/json;q=0.7, text/event-stream;q=0.9, */*;q=0.1");
        
        // Mock startAsync to verify SSE path is taken
        when(mockRequest.startAsync()).thenThrow(new RuntimeException("SSE path taken"));
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        assertNotNull(response);
        assertEquals(500, response.getStatus()); // Internal server error for failed SSE connection
        assertTrue(response.getEntity().toString().contains("Failed to establish SSE connection"));
    }
}
