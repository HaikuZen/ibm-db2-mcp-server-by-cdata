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
            .inputSchema(McpJsonDefaults.getMapper(), new JsonSchemaBuilder()
                .addString("instance", "Only list this instance (its prefix); when omitted, all instances are listed")
                .build())
            .build(),
        (exchange, request) -> run(ITool.arguments(request))
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String instance = (String)args.get("instance");
    this.logger.info("ListInstancesTool({})", instance);
    List<Config> selected;
    try {
      selected = select(this.configs, instance);
    } catch (IllegalArgumentException ex) {
      return ErrorUtil.toolError(ex);
    }
    List<McpSchema.Content> content = new ArrayList<>();
    content.add(new McpSchema.TextContent(toCsv(selected)));
    content.add(new McpSchema.TextContent("The tools of an instance are named <Instance>_<tool>, with <tool> one of: "
        + String.join(", ", HelpTool.INSTANCE_TOOLS) + "."));
    return McpSchema.CallToolResult.builder().content(content).isError(false).build();
  }

  /** All instances, or only the one with this prefix. */
  public static List<Config> select(List<Config> configs, String instance) {
    if (StringUtil.isNullOrEmpty(instance)) {
      return configs;
    }
    List<String> prefixes = new ArrayList<>();
    for (Config config : configs) {
      if (config.getPrefix().equalsIgnoreCase(instance.trim())) {
        return List.of(config);
      }
      prefixes.add(config.getPrefix());
    }
    throw new IllegalArgumentException("Unknown instance '" + instance + "'; the instances are: " + String.join(", ", prefixes));
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
