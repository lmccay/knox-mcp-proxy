package org.apache.knox.mcp.deploy;

import org.apache.knox.gateway.jersey.JerseyServiceDeploymentContributorBase;
import org.apache.knox.gateway.topology.Version;

public class McpProxyServiceDeploymentContributor extends JerseyServiceDeploymentContributorBase {

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
    protected String[] getPackages() {
        return new String[]{ "org.apache.knox.mcp" };
    }

    @Override
    protected String[] getPatterns() {
        return new String[]{ "mcp/v1/**?**" };
    }
}