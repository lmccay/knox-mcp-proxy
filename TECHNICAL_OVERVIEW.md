# Knox MCP Proxy - Technical Implementation

## Architecture Overview

The Knox MCP Proxy provides a complete Java 8 compatible implementation of the Model Context Protocol client, avoiding the Java 17+ requirement of the official MCP SDK.

## Key Components

### 1. Multiple MCP Transport Clients

**Stdio Client (`McpJsonRpcClient`):**
- **JSON-RPC 2.0 Protocol**: Full implementation of MCP protocol over stdio
- **Process Management**: Spawns and manages MCP server processes
- **Asynchronous Communication**: Uses CompletableFuture for non-blocking operations
- **Message Routing**: Correlates requests with responses using message IDs

**Standard HTTP Client (`McpHttpClient`):**
- **HTTP Request/Response**: Standard HTTP POST with JSON-RPC in request/response body
- **Stateless**: Each request is independent, no persistent connections
- **Compatible**: Works with standard MCP HTTP servers out-of-the-box
- **Simple**: Direct request/response pattern

**Standard SSE Client (`McpSseClient`):**
- **Bidirectional SSE**: Both requests and responses over single SSE connection
- **Persistent Connection**: Maintains long-lived connection for real-time communication
- **Compatible**: Works with standard MCP SSE servers
- **Event-driven**: Server can push notifications and responses

**Custom HTTP+SSE Client (`McpCustomHttpSseClient`):**
- **Hybrid Transport**: HTTP POST for requests, SSE for responses
- **Knox-optimized**: Designed for gateway scenarios with multiple clients
- **Message Correlation**: Uses request IDs to match HTTP requests with SSE responses
- **Gateway-friendly**: Optimized for proxy/aggregation use cases

**Technical Details:**
```java
// Stdio transport - Process creation and management
ProcessBuilder pb = new ProcessBuilder();
pb.command("python", "/path/to/mcp_server.py");
Process mcpProcess = pb.start();

// Standard HTTP transport - Request and response in same connection
HttpPost request = new HttpPost(baseUrl);
request.setEntity(new StringEntity(jsonRpcMessage));
HttpResponse response = httpClient.execute(request);
JsonNode result = parseResponse(response);

// Standard SSE transport - Bidirectional over SSE
sseConnection.getOutputStream().write(jsonRpcMessage);
JsonNode response = readFromSSE(sseConnection);

// Custom HTTP+SSE transport - Request via HTTP, response via SSE
HttpPost request = new HttpPost(baseUrl + "/message");
request.setEntity(new StringEntity(jsonRpcMessage));
httpClient.execute(request); // Fire and forget
JsonNode response = readFromSSE("/sse"); // Response comes via SSE

// JSON-RPC message format (same for all transports)
{
  "jsonrpc": "2.0",
  "id": 123,
  "method": "tools/list",
  "params": {}
}
```

### 2. Protocol Implementation

**MCP Messages Supported:**
- `initialize` - Handshake and capability negotiation
- `tools/list` - Discover available tools
- `tools/call` - Execute tools with parameters
- `resources/list` - Enumerate available resources
- `resources/read` - Read resource content

**Message Flow:**
1. **Initialize**: Client sends capabilities, server responds with its capabilities
2. **Discovery**: Client lists tools and resources
3. **Execution**: Client calls tools or reads resources as needed

### 3. Knox Integration

**ServiceDeploymentContributor Pattern:**
- Registers MCP service with Knox topology
- Configures Jersey REST endpoints
- Manages service lifecycle

**Jersey REST API:**
- `/mcp/v1/tools` - Tool management (MCP format)
- `/mcp/v1/resources` - Resource access
- `/mcp/v1/health` - Service health

## Connection Management

**Lifecycle:**
1. **Connect**: Parse endpoint (stdio://, http://, sse://, custom-http-sse://), establish connection, initialize MCP
2. **Discover**: List tools and resources, cache results
3. **Execute**: Route tool calls and resource requests via appropriate transport
4. **Disconnect**: Gracefully close connection and cleanup

**Error Handling:**
- Process failures (exit codes, crashes) for stdio transport
- HTTP connection failures and timeouts for HTTP transport
- SSE connection issues and reconnection for SSE transport
- Custom transport-specific error handling
- JSON-RPC errors (method not found, invalid params)
- Timeout handling for unresponsive servers
- Resource cleanup on failure

## Implementation Advantages

### 1. Java 8 Compatibility
- **No Java 17+ requirement** - works with Knox 1.6.1
- **Standard Java libraries** - no external dependencies beyond Jackson
- **Proven patterns** - uses familiar Java concurrency primitives

### 2. Real MCP Protocol
- **Standards compliant** - implements actual MCP JSON-RPC 2.0
- **Full feature support** - tools, resources, capabilities
- **Interoperable** - works with any MCP server (Python, Node.js, etc.)

### 3. Production Ready
- **Robust error handling** - graceful degradation
- **Resource management** - proper cleanup and lifecycle
- **Monitoring** - health checks and status reporting
- **Scalable** - supports multiple concurrent MCP servers

## Protocol Flow Examples

### Stdio Transport Example
```
1. Knox MCP Proxy starts up
2. Reads topology: calculator:stdio://python calc_server.py
3. Spawns process: python calc_server.py
4. Sends initialize message to establish connection
5. Sends tools/list to discover available tools
6. Caches: calculator.add, calculator.multiply

User Request:
POST /mcp/v1/tools/calculator.add {"a": 5, "b": 3}

7. Routes to calculator server via stdin/stdout
8. Sends tools/call with parameters
9. Returns result to user
```

### HTTP/SSE Transport Example
```
1. Knox MCP Proxy starts up
2. Reads topology: webapi:http://localhost:3000
3. Establishes SSE connection to http://localhost:3000/sse
4. Sends initialize via HTTP POST to /message endpoint
5. Receives response via SSE channel
6. Sends tools/list and caches results

User Request:
POST /mcp/v1/tools/webapi.search {"query": "example"}

7. Routes to webapi server via HTTP POST
8. Sends tools/call message to /message endpoint
9. Receives response via SSE and returns to user
```

## Comparison with Official SDK

| Feature | Official MCP SDK | Knox MCP Proxy |
|---------|------------------|----------------|
| Java Version | Java 17+ | Java 8+ |
| Protocol | JSON-RPC 2.0 | JSON-RPC 2.0 |
| Transport | stdio, SSE, HTTP | stdio, HTTP, SSE, custom HTTP+SSE |
| Dependencies | Multiple | Jackson, Apache HttpClient |
| Knox Integration | None | Native |
| Process Management | External | Built-in |
| Standard Compatibility | Full | Full (all standard transports) |
| Gateway Optimization | None | Custom HTTP+SSE transport |

## Future Enhancements

### 1. Additional Transports
- **TCP/Unix Sockets**: For high-performance local scenarios
- **WebSocket**: For full-duplex communication

### 2. Advanced Features
- **Connection pooling**: Multiple connections per server
- **Load balancing**: Distribute requests across server instances
- **Caching**: Cache tool results for performance

### 3. Monitoring
- **Metrics**: Tool call latency, error rates
- **Logging**: Detailed MCP protocol logs
- **Health checks**: Server availability monitoring

## Security Considerations

### 1. Process Security
- **Sandboxing**: Run MCP servers in restricted environments
- **Resource limits**: CPU, memory, file access controls
- **Input validation**: Sanitize tool parameters

### 2. Knox Integration
- **Authentication**: Leverage Knox security providers
- **Authorization**: Role-based access to tools/resources
- **Audit**: Log all MCP operations for compliance

This implementation provides a complete MCP proxy that integrates seamlessly with Apache Knox while maintaining Java 8 compatibility.
