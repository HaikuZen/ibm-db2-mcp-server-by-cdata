package com.cdata.mcp.tools;

import com.cdata.mcp.*;

import static com.cdata.mcp.StringUtil.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class DescribeTableTool implements ITool {
  private static final String[][] COLUMNS = {
      { "COLUMN_NAME", "Column" },
      { "TYPE_NAME", "DataType" },
      { "COLUMN_SIZE", "Size" },
      { "DECIMAL_DIGITS", "Scale" },
      { "IS_NULLABLE", "Nullable" },
      { "COLUMN_DEF", "Default" },
      { "REMARKS", "Remarks" }
  };
  private static final String[][] PRIMARY_KEY = {
      { "COLUMN_NAME", "Column" },
      { "KEY_SEQ", "Position" },
      { "PK_NAME", "Name" }
  };
  private static final String[][] FOREIGN_KEYS = {
      { "FK_NAME", "Name" },
      { "FKCOLUMN_NAME", "Column" },
      { "PKTABLE_SCHEM", "ReferencedSchema" },
      { "PKTABLE_NAME", "ReferencedTable" },
      { "PKCOLUMN_NAME", "ReferencedColumn" }
  };
  private static final String[][] REFERENCED_BY = {
      { "FK_NAME", "Name" },
      { "FKTABLE_SCHEM", "Schema" },
      { "FKTABLE_NAME", "Table" },
      { "FKCOLUMN_NAME", "Column" },
      { "PKCOLUMN_NAME", "ReferencedColumn" }
  };
  private static final String[][] INDEXES = {
      { "INDEX_NAME", "Name" },
      { "NON_UNIQUE", "NonUnique" },
      { "ORDINAL_POSITION", "Position" },
      { "COLUMN_NAME", "Column" },
      { "ASC_OR_DESC", "Order" }
  };

  private Config config;
  private Logger logger = LoggerFactory.getLogger(DescribeTableTool.class);

  public DescribeTableTool(Config config) {
    this.config = config;
  }

  public void register(McpServer.SyncSpecification<?> mcp) throws Exception {
    String schema = new JsonSchemaBuilder()
        .addString("schema", "The schema name (DB2 stores unquoted names in upper case)"
            + (isNullOrEmpty(config.defaultSchema()) ? "; when omitted, the schema is found from the table name" : "; defaults to `" + config.defaultSchema() + "`"))
        .addString("table", "The table name")
        .required("table")
        .build();
    mcp.toolCall(
        McpSchema.Tool.builder()
            .name(config.getPrefix() + "_describe_table")
            .description("Describes a table: its columns (type, size, nullability, default), primary key, foreign keys, "
                + "the foreign keys of other tables that reference it, and its indexes. "
                + "Use the keys to join tables correctly. Each section is in CSV format, with the first line containing column headers.")
            .inputSchema(McpJsonDefaults.getMapper(), schema)
            .build(),
        (exchange, request) -> run(ITool.arguments(request))
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = (String)args.get("schema");
    String table = (String)args.get("table");
    if (isNullOrEmpty(schema)) {
      schema = this.config.defaultSchema();
    }
    this.logger.info("DescribeTableTool({}, {})", schema, table);
    try {
      if (isNullOrEmpty(table)) {
        throw new SQLException("The `table` argument is required");
      }
      try (Connection cn = config.newConnection()) {
        DatabaseMetaData meta = cn.getMetaData();
        if (isNullOrEmpty(schema)) {
          schema = findSchema(meta, table);
        }
        List<McpSchema.Content> content = new ArrayList<>();
        content.add(new McpSchema.TextContent(describe(meta, schema, table)));
        return McpSchema.CallToolResult.builder().content(content).isError(false).build();
      }
    } catch ( Exception ex ) {
      this.logger.warn("DescribeTableTool failed: {}", ex.getMessage());
      return ErrorUtil.toolError(ex);
    }
  }

  /** The schema of the only table with this name; fails when there is none or more than one. */
  public static String findSchema(DatabaseMetaData meta, String table) throws SQLException {
    TreeSet<String> schemas = new TreeSet<>();
    try (ResultSet rs = meta.getTables(null, null, table, null)) {
      while (rs.next()) {
        schemas.add(rs.getString("TABLE_SCHEM").trim());
      }
    }
    if (schemas.isEmpty()) {
      throw new SQLException("Table " + table + " was not found (DB2 stores unquoted names in upper case)");
    }
    if (schemas.size() > 1) {
      throw new SQLException("Table " + table + " exists in several schemas (" + String.join(", ", schemas) + "); pass `schema`");
    }
    return schemas.first();
  }

  public static String describe(DatabaseMetaData meta, String schema, String table) throws SQLException {
    String columns;
    try (ResultSet rs = meta.getColumns(null, schema, table, "%")) {
      columns = CsvUtils.resultSetToCsv(rs, COLUMNS);
    }
    if (columns.lines().count() <= 1) {
      throw new SQLException("Table " + schema + "." + table + " was not found (DB2 stores unquoted names in upper case)");
    }
    StringBuilder text = new StringBuilder();
    text.append("Table: ").append(schema).append('.').append(table).append("\n\n");
    text.append("## Columns\n").append(columns);
    section(text, "Primary key", () -> CsvUtils.resultSetToCsv(meta.getPrimaryKeys(null, schema, table), PRIMARY_KEY));
    section(text, "Foreign keys", () -> CsvUtils.resultSetToCsv(meta.getImportedKeys(null, schema, table), FOREIGN_KEYS));
    section(text, "Referenced by", () -> CsvUtils.resultSetToCsv(meta.getExportedKeys(null, schema, table), REFERENCED_BY));
    // approximate=true: use the catalog statistics instead of computing them
    section(text, "Indexes", () -> CsvUtils.resultSetToCsv(meta.getIndexInfo(null, schema, table, false, true), INDEXES,
        rs -> rs.getString("INDEX_NAME") != null));
    return text.toString();
  }

  private interface Section {
    String csv() throws SQLException;
  }

  private static void section(StringBuilder text, String title, Section section) {
    text.append("\n## ").append(title).append("\n");
    try {
      text.append(section.csv());
    } catch (SQLException ex) {
      // one unavailable section (e.g. missing metadata procedures on z/OS) should not hide the others
      text.append("(not available: ").append(ErrorUtil.describe(ex)).append(")\n");
    }
  }
}
