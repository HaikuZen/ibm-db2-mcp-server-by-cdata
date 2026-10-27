package com.cdata.mcp.tools;

import com.cdata.mcp.*;

import static com.cdata.mcp.StringUtil.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GetColumnsTool implements ITool {
  private Config config;
  private Logger logger = LoggerFactory.getLogger(GetColumnsTool.class);

  public GetColumnsTool(Config config) {
    this.config = config;
  }

  public void register(McpServer.SyncSpecification<?> mcp) throws Exception {
    String schema = new JsonSchemaBuilder()
        .addString("catalog", "The catalog name")
        .addString("schema", "The schema name")
        .addString("table", "The table name")
        .required("table")
        .build();
    mcp.toolCall(
        McpSchema.Tool.builder()
            .name(config.getPrefix() + "_get_columns")
            .description("Retrieves a list of fields, dimensions, or measures (as columns) for an object, entity or collection (table). Use the `" + config.getPrefix() + "_get_tables` tool to get a list of available tables. " +
                    Constants.FORMAT_DESC)
            .inputSchema(McpJsonDefaults.getMapper(), schema)
            .build(),
        (exchange, request) -> run(request.arguments())
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String catalog = (String)args.get("catalog");
    String schema = (String)args.get("schema");
    String table = (String)args.get("table");

    this.logger.info("GetColumnsTool({}, {}, {})", catalog, schema, table);
    try {
      try (Connection cn = config.newConnection()) {
        List<McpSchema.Content> content = new ArrayList<>();
        String csv = columnsToCsv(cn, catalog, schema, table);

        content.add(
            new McpSchema.TextContent(csv)
        );
        return McpSchema.CallToolResult.builder().content(content).isError(false).build();
      }
    } catch ( Exception ex ) {
      this.logger.warn("GetColumnsTool failed: {}", ex.getMessage());
      return ErrorUtil.toolError(ex);
    }
  }

  private String columnsToCsv(Connection cn, String catalog, String schema, String table) throws SQLException {
    List<String[]> META_COLS = new ArrayList<String[]>();
    if (this.config.supportsMultipleCatalogs()) {
      META_COLS.add(new String[]{"TABLE_CAT", "Catalog"});
    }
    if (this.config.supportsMultipleSchemas()) {
      META_COLS.add(new String[]{"TABLE_SCHEM", "Schema"});
    }
    META_COLS.add(new String[] { "TABLE_NAME", "Table" });
    META_COLS.add(new String[] { "COLUMN_NAME", "Column" });
    META_COLS.add(new String[] { "TYPE_NAME", "DataType" });
    META_COLS.add(new String[] { "REMARKS", "Remarks" });
    DatabaseMetaData meta = cn.getMetaData();
    try (ResultSet rs = meta.getColumns(emptyNull(catalog), emptyNull(schema), table, null)) {
      return CsvUtils.resultSetToCsv(rs, META_COLS.toArray(new String[0][]));
    }
  }

}
