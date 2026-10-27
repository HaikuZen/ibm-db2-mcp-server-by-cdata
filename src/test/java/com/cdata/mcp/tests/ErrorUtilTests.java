package com.cdata.mcp.tests;

import com.cdata.mcp.ErrorUtil;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Assert;
import org.junit.Test;

import java.sql.SQLException;

public class ErrorUtilTests {
  @Test
  public void sqlExceptionIncludesStateAndCode() {
    SQLException ex = new SQLException("Table not found", "42704", -204);
    ex.setNextException(new SQLException("Statement rolled back", "56098", -727));
    Assert.assertEquals(
        "Table not found [SQLSTATE=42704, SQLCODE=-204]\n"
            + "Statement rolled back [SQLSTATE=56098, SQLCODE=-727]",
        ErrorUtil.describe(ex));
  }

  @Test
  public void sqlExceptionWithoutState() {
    Assert.assertEquals("Only SELECT allowed", ErrorUtil.describe(new SQLException("Only SELECT allowed")));
  }

  @Test
  public void toolErrorIsFlagged() {
    McpSchema.CallToolResult result = ErrorUtil.toolError(new RuntimeException("boom"));
    Assert.assertTrue(result.isError());
    Assert.assertEquals("ERROR: boom", ((McpSchema.TextContent)result.content().get(0)).text());
  }
}
