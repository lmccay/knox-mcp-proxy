package org.apache.knox.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class McpServerConnectionTest {
    
    private McpServerConnection connection;
    
    @BeforeEach
    void setUp() {
        connection = new McpServerConnection("test-server", "stdio://echo");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (connection.isConnected()) {
            connection.disconnect();
        }
    }
    
    @Test
    void testConnectionProperties() {
        assertEquals("test-server", connection.getName());
        assertEquals("stdio://echo", connection.getEndpoint());
        assertFalse(connection.isConnected());
    }
    
    @Test
    void testInitialState() {
        assertFalse(connection.isConnected());
        assertTrue(connection.getTools().isEmpty());
        assertTrue(connection.getResources().isEmpty());
    }
    
    @Test
    void testInvalidEndpoint() {
        McpServerConnection invalidConnection = new McpServerConnection("invalid", "invalid://endpoint");
        
        assertThrows(Exception.class, () -> {
            invalidConnection.connect();
        });
    }
    
    @Test
    void testOperationsWhenDisconnected() {
        assertThrows(IllegalStateException.class, () -> {
            connection.callTool("test", new HashMap<>());
        });
        
        assertThrows(IllegalStateException.class, () -> {
            connection.getResource("test");
        });
    }
    
    @Test
    void testDisconnectWhenNotConnected() {
        assertDoesNotThrow(() -> {
            connection.disconnect();
        });
    }
}