package org.apache.knox.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.ServletContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;

/**
 * Test class to verify tool name sanitization for consistent MCP proxy behavior
 */
class ToolNameSanitizationTest {
    
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
    void testToolNameSanitization() {
        try {
            // Get the private sanitizeToolName method
            Method sanitizeMethod = McpProxyResource.class.getDeclaredMethod("sanitizeToolName", String.class);
            sanitizeMethod.setAccessible(true);
            
            // Test various invalid tool names
            String result1 = (String) sanitizeMethod.invoke(resource, "server.tool");
            assertEquals("server_tool", result1);
            System.out.println("Test 1 passed: 'server.tool' -> '" + result1 + "'");
            
            String result2 = (String) sanitizeMethod.invoke(resource, "my-server.read_file");
            assertEquals("my-server_read_file", result2);
            System.out.println("Test 2 passed: 'my-server.read_file' -> '" + result2 + "'");
            
            String result3 = (String) sanitizeMethod.invoke(resource, "calculator.add@numbers");
            assertEquals("calculator_add_numbers", result3);
            System.out.println("Test 3 passed: 'calculator.add@numbers' -> '" + result3 + "'");
            
            String result4 = (String) sanitizeMethod.invoke(resource, "123invalid");
            assertEquals("_123invalid", result4);
            System.out.println("Test 4 passed: '123invalid' -> '" + result4 + "'");
            
            String result5 = (String) sanitizeMethod.invoke(resource, "valid_tool-name");
            assertEquals("valid_tool-name", result5);
            System.out.println("Test 5 passed: 'valid_tool-name' -> '" + result5 + "'");
            
            String result6 = (String) sanitizeMethod.invoke(resource, "");
            assertEquals("unknown_tool", result6);
            System.out.println("Test 6 passed: '' -> '" + result6 + "'");
            
            String result7 = (String) sanitizeMethod.invoke(resource, (Object) null);
            assertEquals("unknown_tool", result7);
            System.out.println("Test 7 passed: null -> '" + result7 + "'");
            
            // Verify all results match consistent naming pattern: ^[a-zA-Z0-9_-]+$
            String[] results = {result1, result2, result3, result4, result5, result6, result7};
            for (String result : results) {
                assertTrue(result.matches("^[a-zA-Z0-9_-]+$"), 
                          "Tool name '" + result + "' does not match consistent naming pattern");
                assertTrue(result.matches("^[a-zA-Z_].*"), 
                          "Tool name '" + result + "' does not start with letter or underscore");
            }
            
            System.out.println("All tool name sanitization tests passed!");
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }
    
    public static void main(String[] args) {
        ToolNameSanitizationTest test = new ToolNameSanitizationTest();
        test.setUp();
        test.testToolNameSanitization();
        try {
            test.tearDown();
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Tool name sanitization tests completed!");
    }
}
