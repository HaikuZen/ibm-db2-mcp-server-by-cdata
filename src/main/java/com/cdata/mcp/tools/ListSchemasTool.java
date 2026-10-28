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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ListSchemasTool implements ITool {
  private Config config;
  private Logger logger = LoggerFactory.getLogger(ListSchemasTool.class);

  public ListSchemasTool(Config config) {
    this.config = config;
  }

  public void register(McpServer.SyncSpecification<?> mcp) throws Exception {
    String schema = new JsonSchemaBuilder()
        .addString("pattern", "Schema name pattern, e.g. `SALES%` (% matches any characters; DB2 stores unquoted names in upper case)")
        .build();
    mcp.toolCall(
        McpSchema.Tool.builder()
            .name(config.getPrefix() + "_list_schemas")
            .description("Lists the schemas in the database with the number of tables and views in each. "
                + "Without `pattern`, DB2 system schemas (" + config.getSystemSchemas() + ") are left out; pass e.g. `SYS%` as `pattern` to list them. "
                + "Use the `" + config.getPrefix() + "_get_tables` tool with `schema` to list a schema's tables. "
                + Constants.FORMAT_DESC)
            .inputSchema(McpJsonDefaults.getMapper(), schema)
            .build(),
        (exchange, request) -> run(request.arguments())
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String pattern = (String)args.get("pattern");
    this.logger.info("ListSchemasTool({})", pattern);
    try {
      try (Connection cn = config.newConnection()) {
        Map<String, Long> counts = tableCounts(cn);
        CsvWriter csv = new CsvWriter();
        csv.row().column("Schema").column("Tables").end();
        try (ResultSet rs = cn.getMetaData().getSchemas(null, emptyNull(pattern))) {
          while (rs.next()) {
            String name = rs.getString("TABLE_SCHEM").trim();
            if (isNullOrEmpty(pattern) && this.config.isSystemSchema(name)) {
              continue;
            }
            csv.row()
                .column(name)
                .column(counts == null ? null : String.valueOf(counts.getOrDefault(name, 0L)))
                .end();
          }
        }

        List<McpSchema.Content> content = new ArrayList<>();
        content.add(new McpSchema.TextContent(csv.end()));
        if (isNullOrEmpty(pattern)) {
          content.add(new McpSchema.TextContent("DB2 system schemas (" + config.getSystemSchemas() + ") are not listed; pass `pattern` to list them."));
        }
        if (counts == null) {
          content.add(new McpSchema.TextContent("Table counts are not available for this DB2 platform."));
        }
        return McpSchema.CallToolResult.builder().content(content).isError(false).build();
      }
    } catch ( Exception ex ) {
      this.logger.warn("ListSchemasTool failed: {}", ex.getMessage());
      return ErrorUtil.toolError(ex);
    }
  }

  /** Tables and views per schema from the catalog, or null when not available. */
  private Map<String, Long> tableCounts(Connection cn) {
    String sql = Db2Catalog.schemaTableCountsSql(this.config.getPlatform());
    if (sql == null) {
      return null;
    }
    Map<String, Long> counts = new HashMap<>();
    try (Statement st = cn.createStatement()) {
      st.setQueryTimeout(this.config.getQueryTimeout());
      try (ResultSet rs = st.executeQuery(sql)) {
        while (rs.next()) {
          counts.put(rs.getString(1).trim(), rs.getLong(2));
        }
      }
      return counts;
    } catch (SQLException ex) {
      // e.g. no SELECT privilege on the catalog: the schema list is still useful
      this.logger.warn("ListSchemasTool table counts failed: {}", ex.getMessage());
      return null;
    }
  }
}
