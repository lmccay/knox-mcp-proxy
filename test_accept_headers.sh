#!/bin/bash

echo "Testing Accept header content negotiation with curl..."

# Test 1: Default JSON response (no Accept header)
echo "Test 1: No Accept header (should return JSON):"
curl -s -X GET "http://localhost:8080/mcp/v1/" \
     -H "Content-Type: application/json" | head -1

# Test 2: Explicit JSON (should return JSON)  
echo -e "\nTest 2: Accept: application/json (should return JSON):"
curl -s -X GET "http://localhost:8080/mcp/v1/" \
     -H "Accept: application/json" \
     -H "Content-Type: application/json" | head -1

# Test 3: Event-stream first (should try SSE)
echo -e "\nTest 3: Accept: text/event-stream, application/json (should try SSE):"
curl -s -X GET "http://localhost:8080/mcp/v1/" \
     -H "Accept: text/event-stream, application/json" \
     -H "Content-Type: application/json" | head -1

# Test 4: JSON first (should return JSON)
echo -e "\nTest 4: Accept: application/json, text/event-stream (should return JSON):"
curl -s -X GET "http://localhost:8080/mcp/v1/" \
     -H "Accept: application/json, text/event-stream" \
     -H "Content-Type: application/json" | head -1

# Test 5: Quality values - SSE higher
echo -e "\nTest 5: Accept: application/json;q=0.5, text/event-stream;q=0.9 (should try SSE):"
curl -s -X GET "http://localhost:8080/mcp/v1/" \
     -H "Accept: application/json;q=0.5, text/event-stream;q=0.9" \
     -H "Content-Type: application/json" | head -1

# Test 6: Quality values - JSON higher
echo -e "\nTest 6: Accept: application/json;q=0.9, text/event-stream;q=0.5 (should return JSON):"
curl -s -X GET "http://localhost:8080/mcp/v1/" \
     -H "Accept: application/json;q=0.9, text/event-stream;q=0.5" \
     -H "Content-Type: application/json" | head -1

