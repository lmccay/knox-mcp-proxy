package org.apache.knox.mcp.deploy;

import org.apache.knox.gateway.deploy.DeploymentContext;
import org.apache.knox.gateway.deploy.ServiceDeploymentContributor;
import org.apache.knox.gateway.topology.Service;
import org.apache.knox.gateway.topology.Version;

public class McpProxyServiceDeploymentContributor implements ServiceDeploymentContributor {

    private static final String ROLE = "MCPPROXY";
    private static final String NAME = "mcp";
 
    @Override
    public String getRole() {
        return ROLE;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Version getVersion() {
        return new Version(1, 0, 0);
    }

    @Override
    public void initializeContribution(DeploymentContext context) {
        // No backend service initialization needed - all logic in resource class
    }

    @Override
    public void contributeService(DeploymentContext context, Service service) throws Exception {
        // TODO: Implement proper Knox service contribution
        // For now, this is a placeholder to allow compilation
    }

    @Override
    public void finalizeContribution(DeploymentContext context) {
        // Any final setup can be done here
    }
}