package com.cdata.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;

public interface IResource {
  public void register(McpServer.SyncSpecification<?> mcp, Table table);
  public McpSchema.ReadResourceResult run(McpSchema.ReadResourceRequest args);
}
