package com.cdata.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;

public interface IResource {
  public void register(McpServer.SyncSpec mcp, Table table);
  public McpSchema.ReadResourceResult run(McpSchema.ReadResourceRequest args);
}
