package com.cdata.mcp.tests;

import com.cdata.mcp.CsvUtils;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

public class CsvUtilsTests {
  /** A one-column result set with the given number of rows. */
  private static ResultSet rows(int count) {
    ResultSetMetaData meta = (ResultSetMetaData)Proxy.newProxyInstance(
        ResultSetMetaData.class.getClassLoader(), new Class[] { ResultSetMetaData.class },
        (p, m, a) -> m.getName().equals("getColumnCount") ? 1 : "ID");
    int[] current = { 0 };
    return (ResultSet)Proxy.newProxyInstance(
        ResultSet.class.getClassLoader(), new Class[] { ResultSet.class },
        (p, m, a) -> switch (m.getName()) {
          case "getMetaData" -> meta;
          case "next" -> ++current[0] <= count;
          case "getString" -> String.valueOf(current[0]);
          default -> null;
        });
  }

  @Test
  public void resultSetIsClosedWhenReadingFails() {
    boolean[] closed = { false };
    ResultSet rs = (ResultSet)Proxy.newProxyInstance(
        ResultSet.class.getClassLoader(), new Class[] { ResultSet.class },
        (p, m, a) -> switch (m.getName()) {
          case "getMetaData" -> throw new java.sql.SQLException("connection lost");
          case "close" -> { closed[0] = true; yield null; }
          default -> null;
        });
    Assert.assertThrows(java.sql.SQLException.class,
        () -> CsvUtils.resultSetToCsv(rs, new String[][] { { "ID", "Id" } }));
    Assert.assertTrue(closed[0]);
  }

  @Test
  public void underLimit() throws Exception {
    CsvUtils.CsvResult result = CsvUtils.limitedResultSetToCsv(rows(2), 3);
    Assert.assertEquals("\"ID\"\n\"1\"\n\"2\"\n", result.csv());
    Assert.assertEquals(2, result.rows());
    Assert.assertFalse(result.truncated());
  }

  @Test
  public void exactlyAtLimit() throws Exception {
    CsvUtils.CsvResult result = CsvUtils.limitedResultSetToCsv(rows(3), 3);
    Assert.assertEquals(3, result.rows());
    Assert.assertFalse(result.truncated());
  }

  @Test
  public void overLimit() throws Exception {
    CsvUtils.CsvResult result = CsvUtils.limitedResultSetToCsv(rows(5), 3);
    Assert.assertEquals(3, result.rows());
    Assert.assertTrue(result.truncated());
  }

  @Test
  public void limitCountsOnlyFilteredRows() throws Exception {
    CsvUtils.RowFilter even = rs -> Integer.parseInt(rs.getString(1)) % 2 == 0;
    CsvUtils.CsvResult result = CsvUtils.limitedResultSetToCsv(rows(9), null, even, 3);
    Assert.assertEquals("\"ID\"\n\"2\"\n\"4\"\n\"6\"\n", result.csv());
    Assert.assertTrue(result.truncated());

    // rows after the last accepted one do not count as truncation
    result = CsvUtils.limitedResultSetToCsv(rows(7), null, even, 3);
    Assert.assertEquals(3, result.rows());
    Assert.assertFalse(result.truncated());
  }

  @Test
  public void noLimit() throws Exception {
    CsvUtils.CsvResult result = CsvUtils.limitedResultSetToCsv(rows(5), 0);
    Assert.assertEquals(5, result.rows());
    Assert.assertFalse(result.truncated());
  }
}
