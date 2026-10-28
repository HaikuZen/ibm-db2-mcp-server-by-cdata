package com.cdata.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

public interface ITool {
  public void register(McpServer.SyncSpecification<?> mcp) throws Exception;
  public McpSchema.CallToolResult run(Map<String, Object> args);

  /** The call's arguments; clients may leave them out entirely for tools with no required input. */
  public static Map<String, Object> arguments(McpSchema.CallToolRequest request) {
    return request.arguments() == null ? Map.of() : request.arguments();
  }
}
