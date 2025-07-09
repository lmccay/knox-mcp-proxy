package org.apache.knox.mcp.client;

import java.net.URI;
import java.net.URL;

public class McpSseUrlTest {
    
    public static void main(String[] args) {
        try {
            // Test URL construction like in the actual client
            String baseUrl = "http://localhost:9090/mcp";
            String messageEndpoint = "/mcp/messages/ZTYyMTA2OTEtYmFiMC00MzFiLTg2YjUtYmFlNGI5MDliZmZj";
            
            URL url;
            if (messageEndpoint.startsWith("http://") || messageEndpoint.startsWith("https://")) {
                // Full URL
                url = new URL(messageEndpoint);
            } else {
                // Path only - construct full URL using base server info
                URI baseUri = URI.create(baseUrl);
                String fullUrl = baseUri.getScheme() + "://" + baseUri.getHost() + 
                                (baseUri.getPort() != -1 ? ":" + baseUri.getPort() : "") + messageEndpoint;
                url = new URL(fullUrl);
            }
            
            System.out.println("Final message URL: " + url);
            
            // Verify it's the expected URL
            String expected = "http://localhost:9090/mcp/messages/ZTYyMTA2OTEtYmFiMC00MzFiLTg2YjUtYmFlNGI5MDliZmZj";
            assert(url.toString().equals(expected));
            
            System.out.println("URL construction test passed!");
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
