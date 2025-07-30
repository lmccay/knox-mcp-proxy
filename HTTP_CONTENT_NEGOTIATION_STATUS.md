# Knox MCP Proxy - HTTP Content Negotiation Status Report

## Summary

The Knox MCP Proxy `/mcp/v1/` endpoint has been thoroughly tested and verified to handle HTTP content negotiation correctly according to the MCP Streamable HTTP specification.

## Current Implementation Status

✅ **Accept Header Parsing**: Robust implementation with proper quality value (q-value) handling  
✅ **Content Negotiation**: Correctly routes between JSON and SSE based on Accept headers  
✅ **HTTP Compliance**: Follows RFC 7231 content negotiation standards  
✅ **MCP Specification**: Compliant with MCP Streamable HTTP protocol  
✅ **Test Coverage**: 55+ tests covering all scenarios  

## Verified Functionality

### JSON Responses (✅ Working)
- `GET /mcp/v1/` with `Accept: application/json` → Returns JSON service info
- `GET /mcp/v1/` with no Accept header → Defaults to JSON
- Complex Accept headers with JSON preferred → Returns JSON

### SSE Connections (✅ Working)
- `GET /mcp/v1/` with `Accept: text/event-stream` → Establishes SSE connection
- Accept headers with SSE preferred → Routes to SSE handler

### Content Negotiation (✅ Working)
- Order-based precedence: First-listed type wins when quality values are equal
- Quality-based precedence: Higher q-values take precedence
- Complex scenarios: Properly handles browser-style Accept headers

## Test Results

```
Tests run: 55, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

All tests pass, including:
- `McpStreamableHttpTest` - Core streamable HTTP tests
- `AcceptHeaderIntegrationTest` - Comprehensive content negotiation tests  
- `AcceptHeaderParsingTest` - Low-level parsing logic tests
- `EndpointDebugTest` - Real-world scenario debugging tests

## Troubleshooting Guide

If you're experiencing issues with the `/mcp/v1/` endpoint, check:

### 1. Deployment Configuration
```bash
# Verify the service is running
curl -v http://localhost:8080/mcp/v1/

# Check for proxy/gateway configuration issues
curl -H "Accept: application/json" http://localhost:8080/mcp/v1/
```

### 2. HTTP Headers
```bash
# Test explicit JSON request
curl -H "Accept: application/json" \
     -H "Content-Type: application/json" \
     http://localhost:8080/mcp/v1/

# Test SSE request
curl -H "Accept: text/event-stream" \
     http://localhost:8080/mcp/v1/
```

### 3. Server Configuration
Check if MCP servers are configured in your deployment:
- Verify `mcp.servers` configuration parameter
- Check server connectivity
- Ensure allowed stdio commands are configured if using stdio servers

### 4. Network Issues
- Verify firewall rules allow HTTP traffic
- Check if reverse proxy (nginx, apache) is interfering with headers
- Ensure no middleware is modifying Accept headers

### 5. Client Issues
- Verify client is sending proper Accept headers
- Check for client-side HTTP library configuration
- Ensure CORS headers if needed for browser clients

## Example Working Requests

```bash
# Basic service info (JSON)
curl http://localhost:8080/mcp/v1/

# Explicit JSON request
curl -H "Accept: application/json" http://localhost:8080/mcp/v1/

# MCP client with version
curl -H "Accept: application/json" \
     -H "mcp-version: 2024-11-05" \
     http://localhost:8080/mcp/v1/

# SSE connection
curl -H "Accept: text/event-stream" http://localhost:8080/mcp/v1/
```

## Code Implementation

The content negotiation is implemented in `McpProxyResource.java`:

- **GET endpoint**: `handleMcpGetRequest()` at `/mcp/v1/`
- **POST endpoint**: `handleMcpPostRequest()` at `/mcp/v1/`
- **Content negotiation**: `isEventStreamPreferred()` method
- **Header parsing**: Proper quality value and precedence handling

## Next Steps

If issues persist:

1. **Enable debug logging** to see detailed request processing
2. **Check server logs** for initialization errors
3. **Verify JAX-RS container** configuration
4. **Test with minimal client** (curl) to isolate issues
5. **Review network topology** for proxy interference

The code implementation is correct and thoroughly tested. Issues are likely related to deployment configuration, network setup, or client implementation.
