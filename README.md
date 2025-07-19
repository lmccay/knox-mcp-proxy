# Knox MCP Proxy

A comprehensive Apache Knox extension that aggregates multiple Model Context Protocol (MCP) servers into a unified MCP gateway, providing seamless access to distributed AI tools and resources with full transport compatibility.

## 🌟 Overview

Knox MCP Proxy extends Apache Knox to serve as a central gateway for MCP ecosystems. It provides a Jersey-based REST API and MCP Server that connects to multiple downstream MCP servers using any transport protocol and exposes their aggregated capabilities through secure, authenticated HTTP endpoints.

### ✨ Key Features

- **🔗 Universal MCP Compatibility**: Supports ALL MCP transport protocols (stdio, HTTP, SSE, custom)
- **🚀 Multi-Server Aggregation**: Seamlessly combines tools and resources from multiple MCP servers
- **🛡️ Knox Security Integration**: Leverages Knox's authentication, authorization, and security providers
- **🏷️ Intelligent Namespacing**: Prevents tool/resource conflicts with server-prefixed names

## 🏗️ Architecture

```
AI Agents & Applications
    |
    v
Knox Gateway (Security, Auth, SSL)
    |
    v
Jersey REST API (/mcp/v1/*)
    |
    v
MCP Proxy Resource (Aggregation)
    |
    ├── stdio://python calculator_server.py    (Process)
    ├── http://webapi.example.com              (HTTP)
    ├── sse://realtime.service.com             (SSE)
    └── custom-http-sse://gateway.internal     (Custom)
```

## 🚀 Transport Support Matrix

| Transport | Endpoint Format | Compatible With | Best For |
|-----------|----------------|-----------------|----------|
| **stdio** | `stdio://python server.py` | Standard MCP subprocess servers | Local Python/Node.js tools |
| **HTTP** | `http://localhost:3000` | Standard MCP HTTP servers | Stateless web services |
| **SSE** | `sse://localhost:4000` | Standard MCP SSE servers | Real-time applications |
| **Custom HTTP+SSE** | `custom-http-sse://localhost:5000` | Knox-optimized servers | Multi-client gateways |

## ⚙️ Configuration

### Knox Topology Setup

```xml
<service>
    <role>MCPPROXY</role>
    <name>mcp</name>
    <version>1.0.0</version>
    <param>
        <name>mcp.servers</name>
        <value>calculator:stdio://python /path/to/calculator_server.py,
               webapi:http://localhost:3000,
               realtime:sse://localhost:4000,
               gateway:custom-http-sse://localhost:5000</value>
    </param>
</service>
```

### Supported Endpoint Formats

- **`stdio://command args`** - Subprocess-based MCP servers (Python, Node.js, etc.)
- **`http://host:port`** - Standard HTTP request/response MCP servers  
- **`https://host:port`** - Secure HTTP MCP servers
- **`sse://host:port`** - Standard SSE bidirectional MCP servers
- **`sses://host:port`** - Secure SSE MCP servers
- **`custom-http-sse://host:port`** - Knox-optimized hybrid transport
- **`custom-https-sse://host:port`** - Secure Knox hybrid transport

## 🔒 Security Configuration

### Stdio Command Allowlist

To protect against remote code execution, stdio-based MCP servers are restricted to an allowlist of permitted commands:

```xml
<param>
    <n>mcp.stdio.allowed.commands</n>
    <value>python,node,java,npm</value>
</param>
```

**Security Features:**
- ✅ **Command Validation**: Only allowlisted commands can be executed
- ✅ **Path Protection**: Commands are validated by base name only (e.g., `/usr/bin/python` → `python`)
- ✅ **Default Security**: If no allowlist is configured, a warning is logged
- ✅ **Clear Error Messages**: Blocked commands receive descriptive error responses

**Example Secure Configuration:**
```xml
<service>
    <role>MCPPROXY</role>
    <n>mcp</n>
    <version>1.0.0</version>
    <param>
        <n>mcp.servers</n>
        <value>calculator:stdio://python /opt/mcp/calculator.py</value>
    </param>
    <param>
        <n>mcp.stdio.allowed.commands</n>
        <value>python,node</value>
    </param>
</service>
```

## 📚 API Reference

### 🔍 Discovery Endpoints

```bash
# List all available tools across all servers
GET /gateway/sandbox/mcp/v1/tools

# List all available resources across all servers  
GET /gateway/sandbox/mcp/v1/resources

# Health check for all connected servers
GET /gateway/sandbox/mcp/v1/health
```

### ⚡ Execution Endpoints

```bash
# Execute a tool with parameters
POST /gateway/sandbox/mcp/v1/tools/{serverName.toolName}
Content-Type: application/json

{
  "param1": "value1",
  "param2": "value2"
}

# Access a resource
GET /gateway/sandbox/mcp/v1/resources/{serverName.resourceName}
```

## 💡 Usage Examples

### Multi-Transport Configuration

```xml
<param>
    <name>mcp.servers</name>
    <value>
        python_tools:stdio://python /opt/mcp/python_server.py,
        web_services:http://api.internal.com:8080,
        live_data:sse://streaming.service.com:4000,
        legacy_system:custom-http-sse://legacy.gateway.com:9000
    </value>
</param>
```

### API Usage

```bash
# Discover available tools
curl -X GET https://knox.company.com/gateway/prod/mcp/v1/tools

# Call a Python-based calculator tool
curl -X POST https://knox.company.com/gateway/prod/mcp/v1/tools/python_tools.calculate \
  -H "Content-Type: application/json" \
  -d '{"expression": "2 + 2 * 3"}'

# Access a web service API
curl -X POST https://knox.company.com/gateway/prod/mcp/v1/tools/web_services.weather \
  -H "Content-Type: application/json" \
  -d '{"location": "San Francisco", "units": "metric"}'

# Read real-time data
curl -X GET https://knox.company.com/gateway/prod/mcp/v1/resources/live_data.stock_prices
```

## 🛠️ Development

### Prerequisites

- **Java 8+** (compatible with Knox 1.6.1)
- **Maven 3.6+** 
- **Apache Knox 1.6.1+**

### Build & Test

```bash
# Clean build
mvn clean compile

# Run comprehensive test suite  
mvn test

# Package for deployment
mvn package
```

### Installation

1. **Build the JAR**: `mvn package`
2. **Deploy to Knox**: Copy `target/knox-mcp-proxy-1.0.0-SNAPSHOT.jar` to Knox's `ext/` directory
3. **Configure Topology**: Add MCP service configuration
4. **Restart Knox**: Restart the Knox gateway service

### Project Structure

```
src/main/java/org/apache/knox/mcp/
├── McpProxyResource.java              # Main REST API resource
├── McpServerConnection.java           # Connection management
├── client/                            # Transport implementations
│   ├── McpJsonRpcClient.java         #   - stdio transport
│   ├── McpHttpClient.java            #   - standard HTTP transport  
│   ├── McpSseClient.java             #   - standard SSE transport
│   ├── McpCustomHttpSseClient.java   #   - Knox custom transport
│   ├── McpTool.java                  #   - Tool model
│   ├── McpResource.java              #   - Resource model
│   └── McpException.java             #   - Exception handling
└── deploy/
    └── McpProxyServiceDeploymentContributor.java  # Knox integration
```

## 🎯 Implementation Highlights

### 🔧 **Complete Transport Support**
- **4 transport protocols** with full MCP standard compliance
- **Automatic protocol detection** based on endpoint URLs
- **Seamless fallback** and error handling per transport type

### 🚀 **Production Features**
- **Java 8 compatibility** - no Java 17+ requirement like official SDK
- **Asynchronous processing** with CompletableFuture
- **Connection pooling** and lifecycle management
- **Comprehensive error handling** and graceful degradation
- **Real-time monitoring** via health check endpoints

### 🛡️ **Enterprise Security**
- **Knox authentication** integration
- **Role-based authorization** for tools and resources  
- **Audit logging** for all MCP operations
- **SSL/TLS termination** through Knox

### ⚡ **Performance Optimized**
- **Connection reuse** and persistent connections where appropriate
- **Request batching** and response caching
- **Resource cleanup** and memory management
- **Configurable timeouts** and retry logic

## 📊 Comparison with Official MCP SDK

| Feature | Official MCP SDK | Knox MCP Proxy |
|---------|------------------|----------------|
| **Java Version** | Java 17+ | Java 8+ |
| **Transports** | stdio, HTTP, SSE | stdio, HTTP, SSE, custom HTTP+SSE |
| **Multi-server** | Manual | Automatic aggregation |
| **Security** | None | Knox enterprise security |
| **Gateway Features** | None | Load balancing, SSL, auth |
| **Production Ready** | Basic | Enterprise grade |
| **Knox Integration** | None | Native |

## 🔮 Advanced Features

### Custom Transport Protocol
Our Knox-optimized `custom-http-sse://` transport provides:
- **HTTP POST** for requests (stateless, scalable)
- **Server-Sent Events** for responses (real-time, persistent)
- **Message correlation** via request IDs
- **Multi-client optimization** for gateway scenarios

### Tool & Resource Namespacing
```json
{
  "calculator.add": {
    "description": "Add two numbers",
    "server": "calculator"
  },
  "webapi.weather": {
    "description": "Get weather data", 
    "server": "webapi"
  }
}
```

### Health Monitoring
```json
{
  "status": "healthy",
  "servers": {
    "calculator": {"status": "connected", "tools": 5, "resources": 2},
    "webapi": {"status": "connected", "tools": 12, "resources": 8}
  },
  "total_tools": 17,
  "total_resources": 10
}
```

## 🤝 Contributing

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/amazing-feature`)
3. **Commit** your changes (`git commit -m 'Add amazing feature'`)
4. **Push** to branch (`git push origin feature/amazing-feature`)
5. **Open** a Pull Request

## 📄 License

This project is licensed under the **Apache License 2.0** - see the [LICENSE](LICENSE) file for details.

## 🔗 Related Projects

- **[Apache Knox](https://github.com/apache/knox)** - Enterprise gateway for Hadoop clusters
- **[Model Context Protocol](https://github.com/modelcontextprotocol/modelcontextprotocol)** - Standard protocol for AI tool integration
- **[MCP Java SDK](https://github.com/modelcontextprotocol/modelcontextprotocol/tree/main/sdk/java)** - Official Java implementation

---

**🎉 Ready to aggregate your MCP ecosystem through Knox?** Start with the [Configuration](#-configuration) section above!
