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

public class GetTablesTool implements ITool {
  private Config config;
  private Logger logger = LoggerFactory.getLogger(GetTablesTool.class);

  public GetTablesTool(Config config) {
    this.config = config;
  }

  public void register(McpServer.SyncSpecification<?> mcp) throws Exception {
    String defaultSchema = config.defaultSchema();
    String schemaHelp = isNullOrEmpty(defaultSchema)
        ? "DB2 system schemas (SYS*, NULLID, SQLJ) are only listed when requested with `schema`. "
        : "When `schema` is omitted, tables in the `" + defaultSchema + "` schema are listed; pass `%` to list all schemas except DB2 system schemas (SYS*, NULLID, SQLJ). ";
    String schema = new JsonSchemaBuilder()
        .addString("catalog", "The catalog name")
        .addString("schema", "The schema name (DB2 stores unquoted names in upper case)")
        .build();
    mcp.toolCall(
        McpSchema.Tool.builder()
            .name(config.getPrefix() + "_get_tables")
            .description("Retrieves a list of objects, entities, collections, etc. (as tables) available in the data source. Use the `" + config.getPrefix() + "_get_columns` tool to list available columns on a table. " +
                    "Both `catalog` and `schema` are optional parameters. " +
                    schemaHelp +
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
    if (isNullOrEmpty(schema)) {
      schema = this.config.defaultSchema();
    }

    this.logger.info("GetTablesTool({}, {})", catalog, schema);
    try {
      try (Connection cn = config.newConnection()) {
        List<McpSchema.Content> content = new ArrayList<>();
        String csv = tablesToCsv(cn, catalog, schema);

        content.add(
            new McpSchema.TextContent(csv)
        );
        if (!this.config.supportsMultipleCatalogs()) {
          content.add(
              new McpSchema.TextContent("Default Catalog: " + this.config.defaultCatalog())
          );
        }
        if (!isNullOrEmpty(this.config.defaultSchema())) {
          content.add(
              new McpSchema.TextContent("Default Schema: " + this.config.defaultSchema())
          );
        }
        if (listsAllSchemas(schema)) {
          content.add(
              new McpSchema.TextContent("Tables in DB2 system schemas (SYS*, NULLID, SQLJ) are not listed; pass `schema` to list them.")
          );
        }
        return McpSchema.CallToolResult.builder().content(content).isError(false).build();
      }
    } catch ( Exception ex ) {
      this.logger.warn("GetTablesTool failed: {}", ex.getMessage());
      return ErrorUtil.toolError(ex);
    }
  }

  private String tablesToCsv(Connection cn, String catalog, String schema) throws SQLException {
    List<String[]> META_COLS = new ArrayList<String[]>();
    if (this.config.supportsMultipleCatalogs()) {
      META_COLS.add(new String[]{"TABLE_CAT", "Catalog"});
    }
    if (this.config.supportsMultipleSchemas()) {
      META_COLS.add(new String[]{"TABLE_SCHEM", "Schema"});
    }
    META_COLS.add(new String[] { "TABLE_NAME", "Table" });
    META_COLS.add(new String[] { "REMARKS", "Description" });

    DatabaseMetaData meta = cn.getMetaData();
    // without a schema, DB2 would list thousands of catalog tables and views
    CsvUtils.RowFilter filter = listsAllSchemas(schema)
        ? rs -> !isSystemSchema(rs.getString("TABLE_SCHEM"))
        : null;
    try (ResultSet rs = meta.getTables(emptyNull(catalog), listsAllSchemas(schema) ? null : schema, null, null)) {
      return CsvUtils.resultSetToCsv(rs, META_COLS.toArray(new String[0][]), filter);
    }
  }

  private static boolean listsAllSchemas(String schema) {
    return isNullOrEmpty(schema) || schema.equals("%");
  }

  public static boolean isSystemSchema(String schema) {
    if (schema == null) {
      return false;
    }
    String s = schema.trim().toUpperCase();
    return s.startsWith("SYS") || s.equals("NULLID") || s.equals("SQLJ");
  }
}
