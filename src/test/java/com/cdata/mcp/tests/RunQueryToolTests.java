package com.cdata.mcp.tests;

import com.cdata.mcp.tools.RunQueryTool;
import org.junit.Assert;
import org.junit.Test;

public class RunQueryToolTests {
  @Test
  public void readStatements() {
    Assert.assertTrue(RunQueryTool.isReadStatement("SELECT 1 FROM SYSIBM.SYSDUMMY1"));
    Assert.assertTrue(RunQueryTool.isReadStatement("  with t as (select 1 from x) select * from t"));
    Assert.assertTrue(RunQueryTool.isReadStatement("VALUES CURRENT DATE"));
    Assert.assertTrue(RunQueryTool.isReadStatement("((SELECT 1 FROM T))"));
  }

  @Test
  public void writeStatementsAreRejected() {
    Assert.assertFalse(RunQueryTool.isReadStatement("DELETE FROM T"));
    Assert.assertFalse(RunQueryTool.isReadStatement("update T set A = 1"));
    Assert.assertFalse(RunQueryTool.isReadStatement("CALL PROC()"));
    Assert.assertFalse(RunQueryTool.isReadStatement("SELECTED"));
    Assert.assertFalse(RunQueryTool.isReadStatement(""));
    Assert.assertFalse(RunQueryTool.isReadStatement(null));
  }

  @Test
  public void leadingCommentsAreSkipped() {
    Assert.assertTrue(RunQueryTool.isReadStatement("-- top customers\nSELECT * FROM T"));
    Assert.assertTrue(RunQueryTool.isReadStatement("/* report */ SELECT * FROM T"));
    Assert.assertTrue(RunQueryTool.isReadStatement("/* a */ -- b\r\n ( /* c */ SELECT 1 FROM T)"));
  }

  @Test
  public void commentsCannotHideWrites() {
    Assert.assertFalse(RunQueryTool.isReadStatement("-- SELECT\nDELETE FROM T"));
    Assert.assertFalse(RunQueryTool.isReadStatement("/* SELECT */ DELETE FROM T"));
    Assert.assertFalse(RunQueryTool.isReadStatement("-- only a comment SELECT 1"));
    Assert.assertFalse(RunQueryTool.isReadStatement("/* unterminated SELECT 1"));
    Assert.assertFalse(RunQueryTool.isReadStatement("/* /* */ SELECT 1 */ DELETE FROM T"));
  }
}
