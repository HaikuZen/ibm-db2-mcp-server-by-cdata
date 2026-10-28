package com.cdata.mcp.tools;

import com.cdata.mcp.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lists the configured DB2 instances; registered once for the whole server. */
public class ListInstancesTool implements ITool {
  public static final String NAME = "list_instances";
  private List<Config> configs;
  private Logger logger = LoggerFactory.getLogger(ListInstancesTool.class);

  public ListInstancesTool(List<Config> configs) {
    this.configs = configs;
  }

  @Override
  public void register(McpServer.SyncSpecification<?> mcp) throws Exception {
    mcp.toolCall(
        McpSchema.Tool.builder()
            .name(NAME)
            .description("Lists the IBM DB2 instances this server can query, with their platform, database, "
                + "default schema and limits. The `Instance` column is the prefix of that instance's tools. "
                + "Use the `" + HelpTool.NAME + "` tool for instructions. " + Constants.FORMAT_DESC)
            .inputSchema(McpJsonDefaults.getMapper(), new JsonSchemaBuilder().build())
            .build(),
        (exchange, request) -> run(request.arguments())
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    this.logger.info("ListInstancesTool()");
    List<McpSchema.Content> content = new ArrayList<>();
    content.add(new McpSchema.TextContent(toCsv(this.configs)));
    content.add(new McpSchema.TextContent("The tools of an instance are named <Instance>_get_tables, "
        + "<Instance>_get_columns and <Instance>_run_query."));
    return McpSchema.CallToolResult.builder().content(content).isError(false).build();
  }

  public static String toCsv(List<Config> configs) {
    CsvWriter csv = new CsvWriter();
    csv.row()
        .column("Instance").column("Platform").column("Product").column("Version")
        .column("Host").column("Port").column("Database").column("DefaultSchema")
        .column("MaxRows").column("QueryTimeout")
        .end();
    for (Config config : configs) {
      csv.row()
          .column(config.getPrefix())
          .column(config.getPlatform())
          .column(config.getDatabaseProductName())
          .column(config.getDatabaseProductVersion())
          .column(config.getHost())
          .column(config.getPort())
          .column(config.getDatabase())
          .column(config.defaultSchema())
          .column(String.valueOf(config.getMaxRows()))
          .column(String.valueOf(config.getQueryTimeout()))
          .end();
    }
    return csv.end();
  }
}
