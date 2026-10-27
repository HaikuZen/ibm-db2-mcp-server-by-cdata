package com.cdata.mcp.tests;

import com.cdata.mcp.tools.GetTablesTool;
import org.junit.Assert;
import org.junit.Test;

public class GetTablesToolTests {
  @Test
  public void systemSchemas() {
    Assert.assertTrue(GetTablesTool.isSystemSchema("SYSIBM"));
    Assert.assertTrue(GetTablesTool.isSystemSchema("SYSCAT"));
    Assert.assertTrue(GetTablesTool.isSystemSchema("SYSTOOLS"));
    Assert.assertTrue(GetTablesTool.isSystemSchema("NULLID"));
    Assert.assertTrue(GetTablesTool.isSystemSchema("SQLJ"));
    // DB2 pads some schema names with blanks
    Assert.assertTrue(GetTablesTool.isSystemSchema("SYSIBM  "));
  }

  @Test
  public void userSchemas() {
    Assert.assertFalse(GetTablesTool.isSystemSchema("SALES"));
    Assert.assertFalse(GetTablesTool.isSystemSchema("DB2INST1"));
    Assert.assertFalse(GetTablesTool.isSystemSchema(null));
  }
}
