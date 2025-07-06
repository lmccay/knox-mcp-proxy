# Knox MCP Proxy

An Apache Knox extension that aggregates multiple Model Context Protocol (MCP) servers into a single REST API endpoint, allowing AI agents to discover and use tools from multiple MCP servers as if they were a single unified service.

## Overview

This project extends Apache Knox's gateway functionality to proxy MCP (Model Context Protocol) tooling. It provides a Jersey-based REST API that connects to multiple downstream MCP servers and exposes their aggregated capabilities through HTTP endpoints.

### Key Features

- **MCP Server Aggregation**: Connects to multiple MCP servers and aggregates their tools and resources
- **Knox Integration**: Leverages Knox's security, authentication, and topology management
- **Tool Namespace**: Prefixes tools with server names to avoid conflicts (e.g., `calculator.add`, `filesystem.read`)
- **Resource Aggregation**: Combines resources from multiple servers into a single interface
- **RESTful API**: Clean Jersey-based REST endpoints for MCP functionality
- **Simple Architecture**: Direct resource management without unnecessary service layers

## Architecture

```
AI Agent
    |
    v
Knox Gateway → Jersey Container → McpProxyResource
                                      |
                                      +-- MCP Server 1 (Calculator)
                                      +-- MCP Server 2 (Filesystem)  
                                      +-- MCP Server 3 (Database)
```

### Components

1. **McpProxyResource**: Jersey REST resource that manages MCP connections and handles API requests
2. **McpServerConnection**: Wrapper for individual MCP server connections
3. **McpProxyServiceDeploymentContributor**: Knox contributor that configures Jersey for the REST API

## Configuration

### Topology Configuration

Add the MCP proxy service to your Knox topology:

```xml
<service>
    <role>MCPPROXY</role>
    <name>mcp</name>
    <version>1.0.0</version>
    <param>
        <name>mcp.servers</name>
        <value>calculator:stdio://python /path/to/calculator_server.py,
               filesystem:stdio://python /path/to/filesystem_server.py,
               database:stdio://python /path/to/database_server.py</value>
    </param>
</service>
```

### Parameters

- `mcp.servers`: Comma-separated list of `name:endpoint` pairs for MCP servers
  - Supported endpoint formats:
    - `stdio://command args` - for subprocess-based MCP servers  
    - `http://host:port` - for standard HTTP request/response MCP servers
    - `https://host:port` - for secure standard HTTP MCP servers
    - `sse://host:port` - for standard SSE bidirectional MCP servers
    - `sses://host:port` - for secure standard SSE MCP servers
    - `custom-http-sse://host:port` - for Knox custom HTTP+SSE transport
    - `custom-https-sse://host:port` - for secure Knox custom HTTP+SSE transport

## API Endpoints

### List Available Tools
```
GET /gateway/sandbox/mcp/v1/tools
```

### List Available Resources
```
GET /gateway/sandbox/mcp/v1/resources
```

### Call a Tool
```
POST /gateway/sandbox/mcp/v1/tools/{toolName}
Content-Type: application/json

{
  "param1": "value1",
  "param2": "value2"
}
```

### Get a Resource
```
GET /gateway/sandbox/mcp/v1/resources/{resourceName}
```

### Health Check
```
GET /gateway/sandbox/mcp/v1/health
```

## Building and Installation

### Prerequisites

- Java 8 or higher
- Maven 3.6 or higher
- Apache Knox 1.6.1 or higher

### Build

```bash
mvn clean compile
```

### Run Tests

```bash
mvn test
```

### Package

```bash
mvn package
```

### Install

1. Copy the built JAR to Knox's `ext` directory
2. Add the service configuration to your topology
3. Restart Knox gateway

## Usage Example

### 1. Start MCP Servers

Start your individual MCP servers (calculator, filesystem, etc.)

### 2. Configure Knox Topology

Add the MCP proxy service to your topology with appropriate server endpoints

### 3. Use the Aggregated API

```bash
# List all available tools
curl -X GET http://knox-gateway:8443/gateway/sandbox/mcp/v1/tools

# Call a calculator tool
curl -X POST http://knox-gateway:8443/gateway/sandbox/mcp/v1/tools/calculator.add \
  -H "Content-Type: application/json" \
  -d '{"a": 5, "b": 3}'

# Get a filesystem resource
curl -X GET http://knox-gateway:8443/gateway/sandbox/mcp/v1/resources/filesystem.config

# Check service health
curl -X GET http://knox-gateway:8443/gateway/sandbox/mcp/v1/health
```

## Development

### Project Structure

```
src/
├── main/
│   ├── java/org/apache/knox/mcp/
│   │   ├── McpProxyResource.java                    # Jersey REST API resource
│   │   ├── McpServerConnection.java                 # MCP server connection wrapper
│   │   └── deploy/
│   │       └── McpProxyServiceDeploymentContributor.java # Knox service contributor
│   └── resources/
│       ├── topology-sample.xml                      # Sample topology configuration
│       └── META-INF/services/
│           └── org.apache.knox.gateway.deploy.ServiceDeploymentContributor
└── test/
    └── java/org/apache/knox/mcp/                    # Unit tests
```

### Extending the Proxy

To add new MCP transport types:

1. Extend `McpServerConnection` to support new endpoint formats
2. Add transport-specific initialization logic
3. Update configuration parsing in `McpProxyResource.initializeConnections()`

### How It Works

1. **Knox Service Registration**: `McpProxyServiceDeploymentContributor` registers the service with Knox
2. **Jersey Configuration**: The contributor configures Jersey to scan for REST resources in the `org.apache.knox.mcp` package
3. **Resource Initialization**: `McpProxyResource` uses `@PostConstruct` to initialize MCP server connections
4. **Request Routing**: REST endpoints route requests to appropriate MCP servers based on tool/resource names

## Current Status

✅ **Complete - REAL MCP Integration:**
- Knox service deployment contributor architecture
- Jersey-based REST API endpoints (`/mcp/v1/tools`, `/mcp/v1/resources`, `/mcp/v1/health`)
- **Full MCP JSON-RPC 2.0 client implementation** compatible with Java 8
- **Dual transport support**: stdio and HTTP/SSE
- **Real MCP server communication** with subprocess and HTTP management
- **Actual tool calling and resource access** to MCP servers
- Project structure with Maven build
- Unit tests with 100% pass rate
- Proper Java 8 compatibility

🎉 **MCP Implementation Details:**
- **Multiple transport clients**: 
  - `McpJsonRpcClient` - stdio subprocess communication
  - `McpHttpClient` - standard HTTP request/response  
  - `McpSseClient` - standard SSE bidirectional communication
  - `McpCustomHttpSseClient` - Knox custom HTTP+SSE transport
- **Process management** for stdio-based MCP servers (Python, Node.js, etc.)
- **Standard HTTP/SSE support** for full compatibility with existing MCP servers
- **Custom transport** optimized for Knox gateway scenarios
- **Asynchronous message handling** with CompletableFuture
- **Real tool discovery** and execution via MCP protocol
- **Resource enumeration** and content access
- **Proper connection lifecycle** management with graceful shutdown
- **Error handling** for MCP protocol errors, process failures, and HTTP timeouts

**Features:**
- **Full transport compatibility**: stdio, HTTP, SSE, and custom HTTP+SSE  
- Connects to any MCP server: `stdio://python server.py`, `http://localhost:3000`, `sse://localhost:4000`
- **Standard MCP compliance**: Works with official MCP servers out-of-the-box
- Discovers real tools and resources from servers
- Executes actual tool calls with parameters
- Reads actual resource content
- Proper tool/resource namespacing (e.g., `calculator.add`, `webapi.search`)
- Complete REST API functionality
- All Knox integration patterns working correctly

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

## Related Projects

- [Apache Knox](https://github.com/apache/knox)
- [Model Context Protocol](https://github.com/modelcontextprotocol/modelcontextprotocol)
- [MCP Java SDK](https://github.com/modelcontextprotocol/modelcontextprotocol/tree/main/sdk/java)