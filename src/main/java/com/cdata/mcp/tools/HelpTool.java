package com.cdata.mcp.tools;

import com.cdata.mcp.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cdata.mcp.StringUtil.isNullOrEmpty;

/**
 * Returns usage instructions for the server; registered once for the whole server.
 * The same text is sent as the server's MCP instructions.
 */
public class HelpTool implements ITool {
  public static final String NAME = "help";
  /** Tool names of an instance are its prefix, '_' and one of these, in workflow order. */
  public static final List<String> INSTANCE_TOOLS = List.of(
      "list_schemas", "get_tables", "table_stats", "get_columns", "describe_table", "search_columns", "run_query");
  private List<Config> configs;
  private Logger logger = LoggerFactory.getLogger(HelpTool.class);

  public HelpTool(List<Config> configs) {
    this.configs = configs;
  }

  @Override
  public void register(McpServer.SyncSpecification<?> mcp) throws Exception {
    mcp.toolCall(
        McpSchema.Tool.builder()
            .name(NAME)
            .description("Returns instructions for using this IBM DB2 server: the available instances and their tools, "
                + "the recommended workflow, the read-only rules and limits, and DB2 SQL tips. "
                + "Call it before the first query if unsure how to proceed.")
            .inputSchema(McpJsonDefaults.getMapper(), new JsonSchemaBuilder().build())
            .build(),
        (exchange, request) -> run(request.arguments())
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    this.logger.info("HelpTool()");
    List<McpSchema.Content> content = new ArrayList<>();
    content.add(new McpSchema.TextContent(instructions(this.configs)));
    return McpSchema.CallToolResult.builder().content(content).isError(false).build();
  }

  public static String instructions(List<Config> configs) {
    StringBuilder text = new StringBuilder();
    text.append("# IBM DB2 MCP Server\n\n")
        .append("Read-only SQL access to ").append(configs.size())
        .append(configs.size() == 1 ? " IBM DB2 instance" : " IBM DB2 instances")
        .append(". `").append(ListInstancesTool.NAME).append("` lists them with their details.\n\n");

    text.append("## Instances\n");
    for (Config config : configs) {
      String p = config.getPrefix();
      text.append("- `").append(p).append("`");
      List<String> details = new ArrayList<>();
      if (!config.getPlatform().isEmpty()) {
        details.add("DB2 for " + config.getPlatform());
      }
      if (!config.getDatabase().isEmpty()) {
        details.add("database " + config.getDatabase() + (config.getHost().isEmpty() ? "" : " on " + config.getHost()));
      }
      if (!isNullOrEmpty(config.defaultSchema())) {
        details.add("default schema " + config.defaultSchema());
      }
      details.add(config.getMaxRows() > 0 ? "at most " + config.getMaxRows() + " rows per query" : "no row limit");
      details.add(config.getQueryTimeout() > 0 ? config.getQueryTimeout() + " s query timeout" : "no query timeout");
      List<String> tools = new ArrayList<>();
      for (String tool : INSTANCE_TOOLS) {
        tools.add("`" + p + "_" + tool + "`");
      }
      text.append(" (").append(String.join(", ", details)).append("): tools ").append(String.join(", ", tools)).append("\n");
    }

    text.append("\n## Workflow\n")
        .append("1. Find the data:\n")
        .append("   - `<instance>_list_schemas` lists the schemas with their number of tables (system schemas only with a `pattern` such as `SYS%`).\n")
        .append("   - `<instance>_get_tables` lists tables. Without `schema` it lists the instance's default schema if it has one, ")
        .append("otherwise all schemas except DB2 system schemas (SYS*, NULLID, SQLJ). Pass `schema` to list one schema, or `%` for all.\n")
        .append("   - `<instance>_search_columns` finds the tables that have a column, e.g. `column` = `%CUST%ID%`.\n")
        .append("2. Understand the tables:\n")
        .append("   - `<instance>_describe_table` gives the columns, primary key, foreign keys and indexes; use the keys for joins.\n")
        .append("   - `<instance>_get_columns` gives just the column names and types.\n")
        .append("   - `<instance>_table_stats` gives row counts and sizes from the catalog; check it before querying a large table.\n")
        .append("3. `<instance>_run_query` with one SQL statement.\n")
        .append("Results are CSV with a header line. Errors are returned as text with the DB2 SQLSTATE and SQLCODE; ")
        .append("correct the statement and try again.\n");

    text.append("\n## Rules\n")
        .append("- Only SELECT, WITH and VALUES statements are accepted, one per call. Every query runs in a transaction that is rolled back.\n")
        .append("- When a result is cut off at the row limit, the tool says so: narrow the query with WHERE, GROUP BY or FETCH FIRST n ROWS ONLY.\n")
        .append("- Prefer aggregates (COUNT, SUM, AVG, GROUP BY) over fetching many rows.\n");

    text.append("\n## DB2 SQL tips\n")
        .append("- Limit rows with `FETCH FIRST n ROWS ONLY` (not LIMIT or TOP).\n")
        .append("- Unquoted names are stored in upper case: `SALES.ORDERS`. Quote mixed-case names: `\"MySchema\".\"MyTable\"`.\n")
        .append("- Strings use single quotes; concatenate with `||`.\n")
        .append("- Dates: `CURRENT DATE`, `CURRENT TIMESTAMP`, `YEAR(col)`, `col + 7 DAYS`.\n")
        .append("- Constant expressions: `VALUES CURRENT DATE` or `SELECT ... FROM SYSIBM.SYSDUMMY1`.\n")
        .append("- Do not end the statement with a semicolon.\n");

    Set<String> platforms = new LinkedHashSet<>();
    for (Config config : configs) {
      platforms.add(config.getPlatform());
    }
    boolean unknown = platforms.contains("");
    if (unknown || platforms.contains("z/OS")) {
      text.append("- DB2 for z/OS catalog: `SYSIBM.SYSTABLES` (CREATOR, NAME, TYPE) and `SYSIBM.SYSCOLUMNS` (TBCREATOR, TBNAME, NAME, COLTYPE).\n");
    }
    if (unknown || platforms.contains("LUW")) {
      text.append("- DB2 for Linux, UNIX and Windows catalog: `SYSCAT.TABLES` (TABSCHEMA, TABNAME) and `SYSCAT.COLUMNS`.\n");
    }
    if (unknown || platforms.contains("IBM i")) {
      text.append("- DB2 for IBM i catalog: `QSYS2.SYSTABLES` (TABLE_SCHEMA, TABLE_NAME) and `QSYS2.SYSCOLUMNS`.\n");
    }
    return text.toString();
  }
}
