package org.apache.knox.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for the Accept header parsing logic
 */
public class AcceptHeaderParsingTest {

    private McpProxyResource mcpResource;

    @BeforeEach
    public void setUp() {
        mcpResource = new McpProxyResource();
    }

    @Test 
    public void testAcceptHeaderParsing() throws Exception {
        // Use reflection to test the private isEventStreamPreferred method
        java.lang.reflect.Method method = McpProxyResource.class.getDeclaredMethod("isEventStreamPreferred", String.class);
        method.setAccessible(true);
        
        // Test cases that should return false (prefer JSON)
        assertFalse((Boolean) method.invoke(mcpResource, (String) null));
        assertFalse((Boolean) method.invoke(mcpResource, ""));
        assertFalse((Boolean) method.invoke(mcpResource, "application/json"));
        assertFalse((Boolean) method.invoke(mcpResource, "application/json, text/event-stream"));
        assertFalse((Boolean) method.invoke(mcpResource, "application/json;q=0.9, text/event-stream;q=0.5"));
        assertFalse((Boolean) method.invoke(mcpResource, "text/html, application/json, text/plain"));
        
        // Test cases that should return true (prefer SSE)
        assertTrue((Boolean) method.invoke(mcpResource, "text/event-stream"));
        assertTrue((Boolean) method.invoke(mcpResource, "text/event-stream, application/json"));
        assertTrue((Boolean) method.invoke(mcpResource, "application/json;q=0.5, text/event-stream;q=0.9"));
        assertTrue((Boolean) method.invoke(mcpResource, "text/html;q=0.8, text/event-stream;q=1.0, application/json;q=0.9"));
        
        System.out.println("✅ All Accept header parsing tests passed!");
    }

    @Test
    public void testEdgeCases() throws Exception {
        java.lang.reflect.Method method = McpProxyResource.class.getDeclaredMethod("isEventStreamPreferred", String.class);
        method.setAccessible(true);
        
        // Edge cases
        assertFalse((Boolean) method.invoke(mcpResource, "*/*"));
        assertFalse((Boolean) method.invoke(mcpResource, "text/*"));
        assertFalse((Boolean) method.invoke(mcpResource, "application/*"));
        
        // Malformed but containing our types
        assertTrue((Boolean) method.invoke(mcpResource, "text/event-stream;charset=utf-8"));
        assertFalse((Boolean) method.invoke(mcpResource, "application/json;charset=utf-8"));
        
        // Complex real-world examples
        assertFalse((Boolean) method.invoke(mcpResource, "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7"));
        assertTrue((Boolean) method.invoke(mcpResource, "text/html,application/xhtml+xml,application/xml;q=0.9,text/event-stream;q=0.8,application/json;q=0.7,*/*;q=0.6"));
        
        System.out.println("✅ All edge case tests passed!");
    }
}
