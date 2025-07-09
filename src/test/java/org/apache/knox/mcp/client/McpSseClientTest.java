package org.apache.knox.mcp.client;

import org.junit.jupiter.api.Test;

public class McpSseClientTest {
    
    @Test
    public void testSseEventHandling() {
        // Create an SSE client instance
        McpSseClient client = new McpSseClient("http://localhost:9090/mcp");
        
        // Simulate the endpoint event
        try {
            java.lang.reflect.Method handleEvent = McpSseClient.class.getDeclaredMethod("handleSseEvent", String.class, String.class);
            handleEvent.setAccessible(true);
            
            // Test endpoint event
            handleEvent.invoke(client, "endpoint", "/mcp/messages/ZTYyMTA2OTEtYmFiMC00MzFiLTg2YjUtYmFlNGI5MDliZmZj");
            
            // Verify the message endpoint was set
            java.lang.reflect.Field messageEndpointField = McpSseClient.class.getDeclaredField("messageEndpoint");
            messageEndpointField.setAccessible(true);
            String messageEndpoint = (String) messageEndpointField.get(client);
            
            System.out.println("Message endpoint set to: " + messageEndpoint);
            assert("/mcp/messages/ZTYyMTA2OTEtYmFiMC00MzFiLTg2YjUtYmFlNGI5MDliZmZj".equals(messageEndpoint));
            
            client.close();
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
    
    public static void main(String[] args) {
        McpSseClientTest test = new McpSseClientTest();
        test.testSseEventHandling();
        System.out.println("SSE event handling test passed!");
    }
}
