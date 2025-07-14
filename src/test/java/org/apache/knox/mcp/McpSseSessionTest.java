package org.apache.knox.mcp;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.StringWriter;
import java.io.PrintWriter;

import static org.mockito.Mockito.*;

/**
 * Test class to verify SSE session behavior and MCP protocol compliance
 */
class McpSseSessionTest {
    
    @Mock
    private AsyncContext asyncContext;
    
    @Mock
    private HttpServletResponse response;
    
    @Mock
    private HttpServletRequest request;
    
    @Mock
    private McpProxyResource proxyResource;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Test
    void testSseSessionInitialization() {
        try {
            MockitoAnnotations.openMocks(this);
            
            // Setup mock request
            when(request.getRequestURI()).thenReturn("/mcp/v1/sse");
            when(request.getContextPath()).thenReturn("");
            when(request.getServletPath()).thenReturn("/mcp/v1");
            
            // Setup mock response
            StringWriter stringWriter = new StringWriter();
            PrintWriter printWriter = new PrintWriter(stringWriter);
            when(response.getWriter()).thenReturn(printWriter);
            when(asyncContext.getResponse()).thenReturn(response);
            
            // Create SSE session
            McpSseSession session = new McpSseSession("test-session", asyncContext, proxyResource, request);
            
            // Get the output that was written
            printWriter.flush();
            String output = stringWriter.toString();
            
            System.out.println("SSE session output:");
            System.out.println(output);
            
            // Verify that the session sent the expected events
            assert(output.contains("event: connected"));
            assert(output.contains("data: test-session"));
            assert(output.contains("event: endpoint"));
            assert(output.contains("data: /mcp/v1/message?session=test-session"));
            
            // Test initialization request
            String initRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"Test Client\",\"version\":\"1.0\"}}}";
            JsonNode request = objectMapper.readTree(initRequest);
            
            // Handle the request
            session.handleJsonRpcRequest(request);
            
            printWriter.flush();
            String fullOutput = stringWriter.toString();
            
            System.out.println("Full SSE session output after initialize:");
            System.out.println(fullOutput);
            
            // Verify that a response was sent
            assert(fullOutput.contains("event: message"));
            assert(fullOutput.contains("\"jsonrpc\":\"2.0\""));
            assert(fullOutput.contains("\"id\":1"));
            assert(fullOutput.contains("\"result\""));
            
            session.close();
            
            System.out.println("SSE session initialization test passed!");
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }
    
    public static void main(String[] args) {
        McpSseSessionTest test = new McpSseSessionTest();
        test.testSseSessionInitialization();
        System.out.println("SSE session tests passed!");
    }
}
