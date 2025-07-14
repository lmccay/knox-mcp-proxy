package org.apache.knox.mcp.client;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Tests for SSE connection lifecycle and endpoint management.
 */
public class McpSseConnectionTest {
    
    @Test
    public void testEndpointResetOnConnectionDrop() {
        // Create an SSE client instance
        McpSseClient client = new McpSseClient("http://localhost:9090/mcp");
        
        try {
            // Get private fields and methods for testing
            Field messageEndpointField = McpSseClient.class.getDeclaredField("messageEndpoint");
            messageEndpointField.setAccessible(true);
            
            Method handleEventMethod = McpSseClient.class.getDeclaredMethod("handleSseEvent", String.class, String.class);
            handleEventMethod.setAccessible(true);
            
            // Initially, the message endpoint should be null
            String initialEndpoint = (String) messageEndpointField.get(client);
            assert(initialEndpoint == null);
            System.out.println("Initial message endpoint (should be null): " + initialEndpoint);
            
            // Simulate receiving an endpoint event
            handleEventMethod.invoke(client, "endpoint", "/mcp/messages/session-123");
            String endpointAfterEvent = (String) messageEndpointField.get(client);
            assert("/mcp/messages/session-123".equals(endpointAfterEvent));
            System.out.println("Message endpoint after event: " + endpointAfterEvent);
            
            // Simulate connection drop by closing the client
            client.close();
            
            // After close, the endpoint should be reset to null
            String endpointAfterClose = (String) messageEndpointField.get(client);
            assert(endpointAfterClose == null);
            System.out.println("Message endpoint after close (should be null): " + endpointAfterClose);
            
            // Verify isAlive returns false after close
            assert(!client.isAlive());
            System.out.println("Client alive status after close: " + client.isAlive());
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }
    
    @Test
    public void testConnectionHealthChecking() {
        // Create an SSE client instance
        McpSseClient client = new McpSseClient("http://localhost:9090/mcp");
        
        try {
            // Initially, isAlive should return false (not connected, no endpoint)
            assert(!client.isAlive());
            System.out.println("Initial alive status (should be false): " + client.isAlive());
            
            // Set an endpoint (simulating receiving endpoint event)
            Field messageEndpointField = McpSseClient.class.getDeclaredField("messageEndpoint");
            messageEndpointField.setAccessible(true);
            messageEndpointField.set(client, "/mcp/messages/session-456");
            
            // Still should be false because no SSE thread is running
            assert(!client.isAlive());
            System.out.println("Alive status with endpoint but no thread (should be false): " + client.isAlive());
            
            // Clean up
            client.close();
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }
    
    public static void main(String[] args) {
        McpSseConnectionTest test = new McpSseConnectionTest();
        test.testEndpointResetOnConnectionDrop();
        test.testConnectionHealthChecking();
        System.out.println("SSE connection lifecycle tests passed!");
    }
}
