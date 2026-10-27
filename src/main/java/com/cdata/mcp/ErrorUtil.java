package com.cdata.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class ErrorUtil {
  private static final int MAX_CHAINED = 10;

  public static String describe(Throwable t) {
    if (!(t instanceof SQLException)) {
      return t.getMessage() != null ? t.getMessage() : t.getClass().getName();
    }
    // DB2 chains extra detail as next exceptions
    StringBuilder msg = new StringBuilder();
    SQLException ex = (SQLException)t;
    for (int i = 0; ex != null && i < MAX_CHAINED; i++, ex = ex.getNextException()) {
      if (msg.length() > 0) {
        msg.append("\n");
      }
      msg.append(ex.getMessage());
      if (ex.getSQLState() != null) {
        msg.append(" [SQLSTATE=").append(ex.getSQLState())
            .append(", SQLCODE=").append(ex.getErrorCode()).append("]");
      }
    }
    return msg.toString();
  }

  /** A tool result flagged as an error, so the client can read it and correct the request. */
  public static McpSchema.CallToolResult toolError(Throwable t) {
    List<McpSchema.Content> content = new ArrayList<>();
    content.add(new McpSchema.TextContent("ERROR: " + describe(t)));
    return McpSchema.CallToolResult.builder().content(content).isError(true).build();
  }
}
