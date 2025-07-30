# MCP Proxy Accept Header Content Negotiation

The Knox MCP Proxy implements proper HTTP content negotiation for the `/mcp/v1/` endpoint according to the MCP Streamable HTTP specification.

## Accept Header Handling

The proxy supports two main content types:
- `application/json` - Standard JSON request/response
- `text/event-stream` - Server-Sent Events (SSE) for streaming

### Content Negotiation Rules

1. **No Accept Header**: Defaults to `application/json`
2. **Single Content Type**: Uses the specified type if supported
3. **Multiple Content Types**: Considers order and quality values (q-values)
4. **Quality Values**: Higher q-values take precedence (0.0 to 1.0)
5. **Equal Quality**: First-listed type takes precedence

### Examples

#### Simple Cases
```http
GET /mcp/v1/
Accept: application/json
# Returns: JSON response

GET /mcp/v1/
Accept: text/event-stream
# Returns: SSE connection
```

#### Order-based Precedence
```http
GET /mcp/v1/
Accept: application/json, text/event-stream
# Returns: JSON (application/json listed first)

GET /mcp/v1/
Accept: text/event-stream, application/json
# Returns: SSE connection (text/event-stream listed first)
```

#### Quality-based Precedence
```http
GET /mcp/v1/
Accept: application/json;q=0.9, text/event-stream;q=0.5
# Returns: JSON (higher quality value)

GET /mcp/v1/
Accept: application/json;q=0.5, text/event-stream;q=0.9
# Returns: SSE connection (higher quality value)
```

#### Complex Accept Headers
```http
GET /mcp/v1/
Accept: text/html;q=0.8, application/json;q=0.9, text/event-stream;q=0.7, */*;q=0.1
# Returns: JSON (highest supported quality value)
```

## Implementation Details

The content negotiation is implemented in the `isEventStreamPreferred(String acceptHeader)` method which:

1. Parses the Accept header for supported media types
2. Extracts quality values (defaults to 1.0 if not specified)
3. Compares quality values between `application/json` and `text/event-stream`
4. Falls back to order-based precedence for equal quality values
5. Returns `false` (JSON) if neither type is present

## Testing

The content negotiation behavior is thoroughly tested in:
- `McpStreamableHttpTest.java` - Basic precedence tests
- `AcceptHeaderIntegrationTest.java` - Comprehensive integration tests

All tests verify that the endpoint correctly routes requests to either JSON response handlers or SSE connection handlers based on the Accept header analysis.
