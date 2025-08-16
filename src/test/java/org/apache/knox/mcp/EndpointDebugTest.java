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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Debug test to isolate potential issues with the endpoint
 */
public class EndpointDebugTest {

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
        
        // Set up the mock request field via reflection
        try {
            java.lang.reflect.Field requestField = McpProxyResource.class.getDeclaredField("request");
            requestField.setAccessible(true);
            requestField.set(mcpResource, mockRequest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up test", e);
        }
    }

    @Test
    public void debugBasicJsonRequest() {
        System.out.println("=== DEBUG: Testing basic JSON request ===");
        
        // Simulate a typical browser request
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn(null);
        
        System.out.println("Accept header: application/json");
        System.out.println("MCP-Version header: null");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        System.out.println("Response status: " + response.getStatus());
        System.out.println("Response media type: " + response.getMediaType());
        System.out.println("Response headers: " + response.getHeaders());
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        
        String entity = response.getEntity().toString();
        System.out.println("Response entity preview: " + entity.substring(0, Math.min(100, entity.length())) + "...");
        
        assertTrue(entity.contains("Knox MCP Proxy"));
        
        System.out.println("✅ Basic JSON request test passed");
    }

    @Test
    public void debugTypicalBrowserRequest() {
        System.out.println("=== DEBUG: Testing typical browser request ===");
        
        // Simulate a typical browser Accept header
        when(mockHeaders.getHeaderString("Accept")).thenReturn("text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn(null);
        
        System.out.println("Accept header: text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7");
        System.out.println("MCP-Version header: null");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        System.out.println("Response status: " + response.getStatus());
        System.out.println("Response media type: " + response.getMediaType());
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        
        System.out.println("✅ Typical browser request test passed");
    }

    @Test
    public void debugMcpClientRequest() {
        System.out.println("=== DEBUG: Testing MCP client request ===");
        
        // Simulate an MCP client request with proper headers
        when(mockHeaders.getHeaderString("Accept")).thenReturn("application/json");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2024-11-05");
        
        System.out.println("Accept header: application/json");
        System.out.println("MCP-Version header: 2024-11-05");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        System.out.println("Response status: " + response.getStatus());
        System.out.println("Response media type: " + response.getMediaType());
        System.out.println("Response headers: " + response.getHeaders());
        
        assertNotNull(response);
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        assertEquals("2024-11-05", response.getHeaderString("mcp-version"));
        
        System.out.println("✅ MCP client request test passed");
    }

    @Test 
    public void debugSseRequest() {
        System.out.println("=== DEBUG: Testing SSE request ===");
        
        // Simulate an SSE connection request
        when(mockHeaders.getHeaderString("Accept")).thenReturn("text/event-stream");
        when(mockHeaders.getHeaderString("mcp-version")).thenReturn("2024-11-05");
        
        // Mock startAsync to see if SSE path is taken
        when(mockRequest.startAsync()).thenThrow(new RuntimeException("SSE path taken as expected"));
        
        System.out.println("Accept header: text/event-stream");
        System.out.println("MCP-Version header: 2024-11-05");
        
        Response response = mcpResource.handleMcpGetRequest(mockRequest, mockHeaders);
        
        System.out.println("Response status: " + response.getStatus());
        System.out.println("Response entity: " + response.getEntity());
        
        assertNotNull(response);
        assertEquals(500, response.getStatus()); // Expected SSE failure in test
        assertTrue(response.getEntity().toString().contains("Failed to establish SSE connection"));
        
        System.out.println("✅ SSE request test passed (expected failure)");
    }

    @Test
    public void debugJerseyEndpointRegistration() {
        System.out.println("=== DEBUG: Checking Jersey endpoint registration ===");
        
        java.lang.reflect.Method[] methods = McpProxyResource.class.getDeclaredMethods();
        
        for (java.lang.reflect.Method method : methods) {
            if (method.isAnnotationPresent(javax.ws.rs.GET.class) || 
                method.isAnnotationPresent(javax.ws.rs.POST.class)) {
                
                String httpMethod = method.isAnnotationPresent(javax.ws.rs.GET.class) ? "GET" : "POST";
                String path = method.isAnnotationPresent(javax.ws.rs.Path.class) ? 
                            method.getAnnotation(javax.ws.rs.Path.class).value() : "(root)";
                
                System.out.println("JAX-RS endpoint: " + httpMethod + " " + path + " -> " + method.getName());
            }
        }
    }
}
