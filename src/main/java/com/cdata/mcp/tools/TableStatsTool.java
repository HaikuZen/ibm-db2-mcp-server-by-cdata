package com.cdata.mcp.tools;

import com.cdata.mcp.*;

import static com.cdata.mcp.StringUtil.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TableStatsTool implements ITool {
  private Config config;
  private Logger logger = LoggerFactory.getLogger(TableStatsTool.class);

  public TableStatsTool(Config config) {
    this.config = config;
  }

  public void register(McpServer.SyncSpecification<?> mcp) throws Exception {
    String defaultSchema = config.defaultSchema();
    String schema = new JsonSchemaBuilder()
        .addString("schema", "The schema name (DB2 stores unquoted names in upper case)"
            + (isNullOrEmpty(defaultSchema) ? "; required" : "; defaults to `" + defaultSchema + "`"))
        .addString("table", "Table name pattern, e.g. `ORD%`; when omitted, all tables in the schema are listed")
        .build();
    mcp.toolCall(
        McpSchema.Tool.builder()
            .name(config.getPrefix() + "_table_stats")
            .description("Returns the size of tables from the DB2 catalog, without reading them: row count, pages, size in KB "
                + "and when statistics were last collected. Use it before querying a table to judge whether to aggregate or filter. "
                + "On z/OS and Linux/UNIX/Windows the figures come from the last RUNSTATS and Rows is -1 when statistics were never collected. "
                + "Tables are listed largest first. "
                + Constants.FORMAT_DESC)
            .inputSchema(McpJsonDefaults.getMapper(), schema)
            .build(),
        (exchange, request) -> run(request.arguments())
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String schema = (String)args.get("schema");
    String table = (String)args.get("table");
    if (isNullOrEmpty(schema)) {
      schema = this.config.defaultSchema();
    }
    this.logger.info("TableStatsTool({}, {})", schema, table);
    try {
      String sql = Db2Catalog.tableStatsSql(this.config.getPlatform());
      if (sql == null) {
        throw new SQLException("Table statistics are not available for this DB2 platform");
      }
      if (isNullOrEmpty(schema)) {
        throw new SQLException("The `schema` argument is required (no DefaultSchema is configured)");
      }
      try (Connection cn = config.newConnection()) {
        CsvUtils.CsvResult result;
        try (PreparedStatement ps = cn.prepareStatement(sql)) {
          ps.setQueryTimeout(this.config.getQueryTimeout());
          int maxRows = this.config.getMaxRows();
          if (maxRows > 0) {
            ps.setMaxRows(maxRows + 1);
          }
          ps.setString(1, schema);
          ps.setString(2, isNullOrEmpty(table) ? "%" : table);
          result = CsvUtils.limitedResultSetToCsv(ps.executeQuery(), maxRows);
        }
        List<McpSchema.Content> content = new ArrayList<>();
        content.add(new McpSchema.TextContent(result.csv()));
        if (result.rows() == 0) {
          content.add(new McpSchema.TextContent("No tables found in schema " + schema
              + (isNullOrEmpty(table) ? "" : " matching " + table) + " (DB2 stores unquoted names in upper case)."));
        }
        if (result.truncated()) {
          content.add(new McpSchema.TextContent("Only the " + result.rows() + " largest tables were returned; narrow the `table` pattern."));
        }
        return McpSchema.CallToolResult.builder().content(content).isError(false).build();
      }
    } catch ( Exception ex ) {
      this.logger.warn("TableStatsTool failed: {}", ex.getMessage());
      return ErrorUtil.toolError(ex);
    }
  }
}
