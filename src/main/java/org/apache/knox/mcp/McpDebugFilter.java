package org.apache.knox.mcp;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class McpDebugFilter implements ContainerRequestFilter {
    
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        System.out.println("=== DEBUG FILTER ===");
        System.out.println("Method: " + requestContext.getMethod());
        System.out.println("Path: " + requestContext.getUriInfo().getPath());
        System.out.println("Content-Type: " + requestContext.getHeaderString("Content-Type"));
        System.out.println("Accept: " + requestContext.getHeaderString("Accept"));
        System.out.println("Headers: " + requestContext.getHeaders());
        System.out.println("==================");
    }
}