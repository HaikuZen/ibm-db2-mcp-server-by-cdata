package com.cdata.mcp.tools;

import com.cdata.mcp.*;

import static com.cdata.mcp.StringUtil.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SearchColumnsTool implements ITool {
  private static final String[][] META_COLS = {
      { "TABLE_SCHEM", "Schema" },
      { "TABLE_NAME", "Table" },
      { "COLUMN_NAME", "Column" },
      { "TYPE_NAME", "DataType" },
      { "REMARKS", "Remarks" }
  };

  private Config config;
  private Logger logger = LoggerFactory.getLogger(SearchColumnsTool.class);

  public SearchColumnsTool(Config config) {
    this.config = config;
  }

  public void register(McpServer.SyncSpecification<?> mcp) throws Exception {
    String defaultSchema = config.defaultSchema();
    String schema = new JsonSchemaBuilder()
        .addString("column", "Column name pattern, e.g. `%CUST%ID%` (% matches any characters, _ one character; DB2 stores unquoted names in upper case)")
        .addString("schema", isNullOrEmpty(defaultSchema)
            ? "The schema name; when omitted, all schemas except DB2 system schemas (" + config.getSystemSchemas() + ") are searched"
            : "The schema name; defaults to `" + defaultSchema + "`, pass `%` to search all schemas except DB2 system schemas (" + config.getSystemSchemas() + ")")
        .addString("table", "Table name pattern to narrow the search")
        .required("column")
        .build();
    mcp.toolCall(
        McpSchema.Tool.builder()
            .name(config.getPrefix() + "_search_columns")
            .description("Finds columns by name pattern and lists the tables that contain them, e.g. to find which tables hold a customer ID. "
                + "Searching all schemas can be slow on large systems; pass `schema` when possible. "
                + (config.getMaxRows() > 0 ? "At most " + config.getMaxRows() + " columns are returned. " : "")
                + Constants.FORMAT_DESC)
            .inputSchema(McpJsonDefaults.getMapper(), schema)
            .build(),
        (exchange, request) -> run(ITool.arguments(request))
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String column = (String)args.get("column");
    String schema = (String)args.get("schema");
    String table = (String)args.get("table");
    if (isNullOrEmpty(schema)) {
      schema = this.config.defaultSchema();
    }
    boolean allSchemas = isNullOrEmpty(schema) || schema.equals("%");
    this.logger.info("SearchColumnsTool({}, {}, {})", column, schema, table);
    try {
      if (isNullOrEmpty(column)) {
        throw new java.sql.SQLException("The `column` argument is required");
      }
      try (Connection cn = config.newConnection()) {
        CsvUtils.RowFilter filter = allSchemas
            ? rs -> !this.config.isSystemSchema(rs.getString("TABLE_SCHEM"))
            : null;
        CsvUtils.CsvResult result;
        try (ResultSet rs = cn.getMetaData().getColumns(null, allSchemas ? null : schema,
            isNullOrEmpty(table) ? "%" : table, column)) {
          result = CsvUtils.limitedResultSetToCsv(rs, META_COLS, filter, this.config.getMaxRows());
        }
        List<McpSchema.Content> content = new ArrayList<>();
        content.add(new McpSchema.TextContent(result.csv()));
        if (result.truncated()) {
          content.add(new McpSchema.TextContent("Only the first " + result.rows() + " columns were returned. "
              + "Narrow the search with a more specific `column` pattern, `schema` or `table`."));
        }
        if (allSchemas) {
          content.add(new McpSchema.TextContent("Columns in DB2 system schemas (" + config.getSystemSchemas() + ") are not listed; pass `schema` to search them."));
        }
        return McpSchema.CallToolResult.builder().content(content).isError(false).build();
      }
    } catch ( Exception ex ) {
      this.logger.warn("SearchColumnsTool failed: {}", ex.getMessage());
      return ErrorUtil.toolError(ex);
    }
  }
}
