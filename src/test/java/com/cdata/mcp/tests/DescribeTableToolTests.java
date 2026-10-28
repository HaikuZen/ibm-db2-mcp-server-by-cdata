package com.cdata.mcp.tests;

import com.cdata.mcp.tools.DescribeTableTool;
import org.junit.Assert;
import org.junit.Test;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.Callable;

public class DescribeTableToolTests {
  private static Callable<ResultSet> tables(String... schemas) {
    Object[][] rows = new Object[schemas.length][];
    for (int i = 0; i < schemas.length; i++) {
      rows[i] = new Object[] { schemas[i], "ORDERS" };
    }
    return () -> FakeJdbc.resultSet(new String[] { "TABLE_SCHEM", "TABLE_NAME" }, rows);
  }

  @Test
  public void describesColumnsKeysAndIndexes() throws Exception {
    DatabaseMetaData meta = FakeJdbc.metaData(Map.of(
        "getColumns", () -> FakeJdbc.resultSet(
            new String[] { "COLUMN_NAME", "TYPE_NAME", "COLUMN_SIZE", "DECIMAL_DIGITS", "IS_NULLABLE", "COLUMN_DEF", "REMARKS" },
            new Object[] { "ORDER_ID", "INTEGER", 10, 0, "NO", null, "Order number" },
            new Object[] { "CUST_ID", "INTEGER", 10, 0, "YES", null, null }),
        "getPrimaryKeys", () -> FakeJdbc.resultSet(
            new String[] { "COLUMN_NAME", "KEY_SEQ", "PK_NAME" },
            new Object[] { "ORDER_ID", 1, "PK_ORDERS" }),
        "getImportedKeys", () -> FakeJdbc.resultSet(
            new String[] { "FK_NAME", "FKCOLUMN_NAME", "PKTABLE_SCHEM", "PKTABLE_NAME", "PKCOLUMN_NAME" },
            new Object[] { "FK_CUST", "CUST_ID", "SALES", "CUSTOMERS", "CUST_ID" }),
        "getExportedKeys", () -> {
          throw new SQLException("SYSIBM.SQLFOREIGNKEYS not found", "42884", -440);
        },
        "getIndexInfo", () -> FakeJdbc.resultSet(
            new String[] { "INDEX_NAME", "NON_UNIQUE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC" },
            // the table statistics row has no index name
            new Object[] { null, null, 0, null, null },
            new Object[] { "IX_ORDERS_CUST", "true", 1, "CUST_ID", "A" })));

    String text = DescribeTableTool.describe(meta, "SALES", "ORDERS");
    Assert.assertEquals(String.join("\n",
        "Table: SALES.ORDERS",
        "",
        "## Columns",
        "\"Column\",\"DataType\",\"Size\",\"Scale\",\"Nullable\",\"Default\",\"Remarks\"",
        "\"ORDER_ID\",\"INTEGER\",\"10\",\"0\",\"NO\",,\"Order number\"",
        "\"CUST_ID\",\"INTEGER\",\"10\",\"0\",\"YES\",,",
        "",
        "## Primary key",
        "\"Column\",\"Position\",\"Name\"",
        "\"ORDER_ID\",\"1\",\"PK_ORDERS\"",
        "",
        "## Foreign keys",
        "\"Name\",\"Column\",\"ReferencedSchema\",\"ReferencedTable\",\"ReferencedColumn\"",
        "\"FK_CUST\",\"CUST_ID\",\"SALES\",\"CUSTOMERS\",\"CUST_ID\"",
        "",
        "## Referenced by",
        "(not available: SYSIBM.SQLFOREIGNKEYS not found [SQLSTATE=42884, SQLCODE=-440])",
        "",
        "## Indexes",
        "\"Name\",\"NonUnique\",\"Position\",\"Column\",\"Order\"",
        "\"IX_ORDERS_CUST\",\"true\",\"1\",\"CUST_ID\",\"A\"",
        ""), text);
  }

  @Test
  public void unknownTableFails() {
    DatabaseMetaData meta = FakeJdbc.metaData(Map.of(
        "getColumns", () -> FakeJdbc.resultSet(new String[] { "COLUMN_NAME" })));
    SQLException ex = Assert.assertThrows(SQLException.class, () -> DescribeTableTool.describe(meta, "SALES", "orders"));
    Assert.assertTrue(ex.getMessage().contains("SALES.orders was not found"));
  }

  @Test
  public void schemaIsFoundFromTheTableName() throws Exception {
    // z/OS pads some schema names with blanks
    Assert.assertEquals("SALES", DescribeTableTool.findSchema(FakeJdbc.metaData(Map.of("getTables", tables("SALES   "))), "ORDERS"));
  }

  @Test
  public void ambiguousOrMissingTableFails() {
    SQLException several = Assert.assertThrows(SQLException.class,
        () -> DescribeTableTool.findSchema(FakeJdbc.metaData(Map.of("getTables", tables("TEST", "SALES"))), "ORDERS"));
    Assert.assertTrue(several.getMessage().contains("several schemas (SALES, TEST); pass `schema`"));

    SQLException none = Assert.assertThrows(SQLException.class,
        () -> DescribeTableTool.findSchema(FakeJdbc.metaData(Map.of("getTables", tables())), "ORDERS"));
    Assert.assertTrue(none.getMessage().contains("ORDERS was not found"));
  }
}
