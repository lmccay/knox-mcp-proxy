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

import java.lang.reflect.Field;
import java.util.Set;
import java.util.HashSet;
import java.io.IOException;

/**
 * Test class to verify stdio command allowlist security feature
 */
class StdioSecurityTest {
    
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
    void testStdioAllowlistParsing() throws Exception {
        // Test parsing of allowed commands
        when(servletContext.getInitParameter("mcp.stdio.allowed.commands"))
            .thenReturn("python,node,npm,java");
        when(servletContext.getInitParameter("mcp.servers")).thenReturn(null);
        
        // Use reflection to call the private method
        java.lang.reflect.Method parseMethod = McpProxyResource.class.getDeclaredMethod("parseAllowedStdioCommands");
        parseMethod.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) parseMethod.invoke(resource);
        
        assertNotNull(result);
        assertEquals(4, result.size());
        assertTrue(result.contains("python"));
        assertTrue(result.contains("node"));
        assertTrue(result.contains("npm"));
        assertTrue(result.contains("java"));
        
        System.out.println("Stdio allowlist parsing test passed!");
    }
    
    @Test
    void testStdioAllowlistEmpty() throws Exception {
        // Test empty allowlist
        when(servletContext.getInitParameter("mcp.stdio.allowed.commands")).thenReturn("");
        when(servletContext.getInitParameter("mcp.servers")).thenReturn(null);
        
        // Use reflection to call the private method
        java.lang.reflect.Method parseMethod = McpProxyResource.class.getDeclaredMethod("parseAllowedStdioCommands");
        parseMethod.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) parseMethod.invoke(resource);
        
        assertNull(result, "Empty allowlist should return null to allow all commands");
        
        System.out.println("Empty stdio allowlist test passed!");
    }
    
    @Test
    void testStdioAllowlistWithSpaces() throws Exception {
        // Test allowlist with extra spaces
        when(servletContext.getInitParameter("mcp.stdio.allowed.commands"))
            .thenReturn("  python  ,  node,npm  ,  java  ");
        when(servletContext.getInitParameter("mcp.servers")).thenReturn(null);
        
        // Use reflection to call the private method
        java.lang.reflect.Method parseMethod = McpProxyResource.class.getDeclaredMethod("parseAllowedStdioCommands");
        parseMethod.setAccessible(true);
        
        @SuppressWarnings("unchecked")
        Set<String> result = (Set<String>) parseMethod.invoke(resource);
        
        assertNotNull(result);
        assertEquals(4, result.size());
        assertTrue(result.contains("python"));
        assertTrue(result.contains("node"));
        assertTrue(result.contains("npm"));
        assertTrue(result.contains("java"));
        assertFalse(result.contains("  python  "), "Should not contain spaces");
        
        System.out.println("Stdio allowlist with spaces test passed!");
    }
    
    @Test
    void testStdioCommandValidation() throws Exception {
        // Test command validation logic without actually starting processes
        Set<String> allowedCommands = new HashSet<>();
        allowedCommands.add("python");
        allowedCommands.add("node");
        
        // Test that validation passes for allowed commands by checking that SecurityException is NOT thrown
        try {
            // Create a client but catch IOException (process start failure) while allowing SecurityException to bubble up
            org.apache.knox.mcp.client.McpJsonRpcClient client1 = 
                new org.apache.knox.mcp.client.McpJsonRpcClient("python", new String[]{"script.py"}, allowedCommands);
            client1.close();
            System.out.println("Allowed command 'python' validation passed!");
        } catch (SecurityException e) {
            fail("Should allow python command: " + e.getMessage());
        } catch (IOException e) {
            // Expected - process doesn't exist, but validation passed
            System.out.println("Allowed command 'python' validation passed (process start failed as expected)!");
        }
        
        try {
            // Test node command validation
            org.apache.knox.mcp.client.McpJsonRpcClient client2 = 
                new org.apache.knox.mcp.client.McpJsonRpcClient("node", new String[]{"script.js"}, allowedCommands);
            client2.close();
            System.out.println("Allowed command 'node' validation passed!");
        } catch (SecurityException e) {
            fail("Should allow node command: " + e.getMessage());
        } catch (IOException e) {
            // Expected - process doesn't exist, but validation passed
            System.out.println("Allowed command 'node' validation passed (process start failed as expected)!");
        }
        
        // This should fail - curl is not allowed
        SecurityException securityException = assertThrows(SecurityException.class, () -> {
            new org.apache.knox.mcp.client.McpJsonRpcClient("curl", new String[]{"http://example.com"}, allowedCommands);
        });
        
        assertTrue(securityException.getMessage().contains("curl"));
        assertTrue(securityException.getMessage().contains("not in the allowed"));
        System.out.println("Blocked disallowed command 'curl' test passed!");
        
        // This should fail - arbitrary command is not allowed
        SecurityException securityException2 = assertThrows(SecurityException.class, () -> {
            new org.apache.knox.mcp.client.McpJsonRpcClient("/bin/sh", new String[]{"-c", "rm -rf /"}, allowedCommands);
        });
        
        assertTrue(securityException2.getMessage().contains("sh"));
        assertTrue(securityException2.getMessage().contains("not in the allowed"));
        System.out.println("Blocked dangerous command '/bin/sh' test passed!");
    }
    
    @Test
    void testStdioCommandPathHandling() throws Exception {
        // Test command validation with full paths
        Set<String> allowedCommands = new HashSet<>();
        allowedCommands.add("python");
        
        try {
            // Should work - extracts 'python' from path
            org.apache.knox.mcp.client.McpJsonRpcClient client = 
                new org.apache.knox.mcp.client.McpJsonRpcClient("/usr/bin/python", new String[]{"script.py"}, allowedCommands);
            client.close();
            System.out.println("Command path extraction validation passed!");
        } catch (SecurityException e) {
            fail("Should allow python command with full path: " + e.getMessage());
        } catch (IOException e) {
            // Expected - process doesn't exist, but validation passed
            System.out.println("Command path extraction validation passed (process start failed as expected)!");
        }
        
        try {
            // Should work - extracts 'python3' from python3.9, so we need python3 in allowlist
            allowedCommands.add("python3"); // Add python3 to allowlist for this test
            org.apache.knox.mcp.client.McpJsonRpcClient client2 = 
                new org.apache.knox.mcp.client.McpJsonRpcClient("/usr/bin/python3.9", new String[]{"script.py"}, allowedCommands);
            client2.close();
            System.out.println("Command extension removal validation passed!");
        } catch (SecurityException e) {
            fail("Should allow python3.9 command: " + e.getMessage());
        } catch (IOException e) {
            // Expected - process doesn't exist, but validation passed
            System.out.println("Command extension removal validation passed (process start failed as expected)!");
        }
    }
}
