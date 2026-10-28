package com.cdata.mcp;

/**
 * Catalog queries that differ per DB2 platform (see {@link Config#getPlatform()}).
 * Each returns null when the platform is unknown.
 */
public class Db2Catalog {
  /** Two columns: schema name and number of tables and views. */
  public static String schemaTableCountsSql(String platform) {
    switch (platform) {
      case "z/OS":
        return "SELECT CREATOR, COUNT(*) FROM SYSIBM.SYSTABLES WHERE TYPE IN ('T','V','M','H') GROUP BY CREATOR";
      case "LUW":
        return "SELECT TABSCHEMA, COUNT(*) FROM SYSCAT.TABLES WHERE TYPE IN ('T','V','S') GROUP BY TABSCHEMA";
      case "IBM i":
        return "SELECT TABLE_SCHEMA, COUNT(*) FROM QSYS2.SYSTABLES WHERE TABLE_TYPE <> 'A' GROUP BY TABLE_SCHEMA";
      default:
        return null;
    }
  }

  /**
   * Parameters: schema, table name LIKE pattern. Columns: Schema, Table, Type, Rows, Pages, SizeKB, StatsTime.
   * On z/OS and LUW the figures come from the last RUNSTATS; Rows is -1 when statistics were never collected.
   */
  public static String tableStatsSql(String platform) {
    switch (platform) {
      case "z/OS":
        return "SELECT CREATOR AS \"Schema\", NAME AS \"Table\", TYPE AS \"Type\", "
            + "CAST(CARDF AS BIGINT) AS \"Rows\", CAST(NPAGESF AS BIGINT) AS \"Pages\", "
            + "CAST(SPACEF AS BIGINT) AS \"SizeKB\", "
            + "NULLIF(STATSTIME, TIMESTAMP('0001-01-01-00.00.00')) AS \"StatsTime\" "
            + "FROM SYSIBM.SYSTABLES WHERE CREATOR = ? AND NAME LIKE ? AND TYPE IN ('T','M','H') "
            + "ORDER BY CARDF DESC, NAME";
      case "LUW":
        return "SELECT TABSCHEMA AS \"Schema\", TABNAME AS \"Table\", TYPE AS \"Type\", "
            + "CARD AS \"Rows\", NPAGES AS \"Pages\", CAST(NULL AS BIGINT) AS \"SizeKB\", "
            + "STATS_TIME AS \"StatsTime\" "
            + "FROM SYSCAT.TABLES WHERE TABSCHEMA = ? AND TABNAME LIKE ? AND TYPE IN ('T','S') "
            + "ORDER BY CARD DESC, TABNAME";
      case "IBM i":
        return "SELECT TABLE_SCHEMA AS \"Schema\", TABLE_NAME AS \"Table\", CAST('T' AS CHAR(1)) AS \"Type\", "
            + "NUMBER_ROWS AS \"Rows\", CAST(NULL AS BIGINT) AS \"Pages\", DATA_SIZE / 1024 AS \"SizeKB\", "
            + "CAST(NULL AS TIMESTAMP) AS \"StatsTime\" "
            + "FROM QSYS2.SYSTABLESTAT WHERE TABLE_SCHEMA = ? AND TABLE_NAME LIKE ? "
            + "ORDER BY NUMBER_ROWS DESC, TABLE_NAME";
      default:
        return null;
    }
  }
}
