package org.apache.knox.mcp;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

/**
 * Tests for MCP Server Connection auto-reconnection logic.
 */
public class McpServerConnectionReconnectTest {
    
    @Test
    public void testEnsureConnectionAliveMethod() {
        // Create a server connection with stdio (which doesn't need auto-reconnection)
        McpServerConnection connection = new McpServerConnection("test-server", "stdio://echo test");
        
        try {
            // Get the private ensureConnectionAlive method
            Method ensureAliveMethod = McpServerConnection.class.getDeclaredMethod("ensureConnectionAlive");
            ensureAliveMethod.setAccessible(true);
            
            // Initially the connection is not connected, so this should throw an exception
            boolean exceptionThrown = false;
            try {
                ensureAliveMethod.invoke(connection);
            } catch (Exception e) {
                exceptionThrown = true;
                System.out.println("Expected exception for disconnected server: " + e.getCause().getMessage());
            }
            assert(exceptionThrown);
            
            System.out.println("Auto-reconnection method test passed!");
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }
    
    public static void main(String[] args) {
        McpServerConnectionReconnectTest test = new McpServerConnectionReconnectTest();
        test.testEnsureConnectionAliveMethod();
        System.out.println("Server connection auto-reconnection tests passed!");
    }
}
