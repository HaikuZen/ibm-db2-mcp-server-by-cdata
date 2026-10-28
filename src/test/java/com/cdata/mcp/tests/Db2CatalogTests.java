package com.cdata.mcp.tests;

import com.cdata.mcp.Db2Catalog;
import org.junit.Assert;
import org.junit.Test;

public class Db2CatalogTests {
  @Test
  public void queriesForKnownPlatforms() {
    for (String platform : new String[] { "z/OS", "LUW", "IBM i" }) {
      Assert.assertNotNull(platform, Db2Catalog.schemaTableCountsSql(platform));
      String stats = Db2Catalog.tableStatsSql(platform);
      Assert.assertEquals(platform + " takes schema and table parameters", 2, stats.chars().filter(c -> c == '?').count());
      for (String column : new String[] { "Schema", "Table", "Type", "Rows", "Pages", "SizeKB", "StatsTime" }) {
        Assert.assertTrue(platform + " has " + column, stats.contains("AS \"" + column + "\""));
      }
    }
    Assert.assertTrue(Db2Catalog.tableStatsSql("z/OS").contains("SYSIBM.SYSTABLES"));
  }

  @Test
  public void noQueriesForUnknownPlatform() {
    Assert.assertNull(Db2Catalog.schemaTableCountsSql(""));
    Assert.assertNull(Db2Catalog.tableStatsSql(""));
  }
}
