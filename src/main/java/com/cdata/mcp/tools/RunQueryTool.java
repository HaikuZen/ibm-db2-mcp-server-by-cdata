package com.cdata.mcp.tools;

import com.cdata.mcp.*;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RunQueryTool implements ITool {
  private Config config;
  private Logger logger = LoggerFactory.getLogger(RunQueryTool.class);

  public RunQueryTool(Config config) {
    this.config = config;
  }

  @Override
  public void register(McpServer.SyncSpec mcp) throws Exception {
    String quotes = this.config.getIdentifierQuotes();
    String prefix = this.config.getPrefix();
    String description = "The SELECT statement to execute. "
        + "Use the `" + prefix + "_get_tables` tool to get a list of available tables, "
        + "and the `" + prefix + "_get_columns` tool to list table columns. "
        + "The SQL dialect is IBM DB2 SQL. "
        + "Qualify tables with their schema (SCHEMA.TABLE). "
        + "Unquoted identifiers are folded to upper case; quote mixed-case identifiers using `" + quotes + "` characters. "
        + "Limit rows with FETCH FIRST n ROWS ONLY. "
        + (this.config.getMaxRows() > 0 ? "At most " + this.config.getMaxRows() + " rows are returned. " : "")
        + Constants.FORMAT_DESC;

    String schema = new JsonSchemaBuilder()
        .addString("sql", description)
        .build();
    mcp.tool(
        new Tool(
            prefix + "_run_query",
            "Execute a SQL SELECT statement.",
            schema
        ),
        this::run
    );
  }

  @Override
  public McpSchema.CallToolResult run(Map<String, Object> args) {
    String sql = (String)args.get("sql");
    this.logger.info("RunQueryTool({})", sql);
    try {
      try (Connection cn = config.newConnection()) {
        List<McpSchema.Content> content = new ArrayList<>();
        CsvUtils.CsvResult result = queryToCsv(cn, sql);

        List<McpSchema.Role> roles = new ArrayList<>();
        roles.add(McpSchema.Role.USER);
        content.add(
            new McpSchema.TextContent(roles, 1.0, result.csv())
        );
        if (result.truncated()) {
          content.add(
              new McpSchema.TextContent(roles, 1.0, "Only the first " + result.rows() + " rows were returned. "
                  + "Narrow the query with a WHERE clause, aggregates or FETCH FIRST n ROWS ONLY.")
          );
        }
        return new McpSchema.CallToolResult(content, false);
      }
    } catch ( Exception ex ) {
      this.logger.warn("RunQueryTool failed: {}", ex.getMessage());
      return ErrorUtil.toolError(ex);
    }
  }

  private CsvUtils.CsvResult queryToCsv(Connection cn, String sql) throws SQLException {
    if (!isReadStatement(sql)) {
      throw new SQLException("Only SELECT, WITH and VALUES statements are allowed");
    }
    // DB2 can modify data from within a query (e.g. SELECT ... FROM FINAL TABLE (INSERT ...)),
    // so run every query in a transaction that is always rolled back
    cn.setReadOnly(true);
    cn.setAutoCommit(false);
    CsvUtils.CsvResult result;
    try (Statement st = cn.createStatement()) {
      st.setQueryTimeout(this.config.getQueryTimeout());
      int maxRows = this.config.getMaxRows();
      if (maxRows > 0) {
        // one extra row tells us whether the result was cut off
        st.setMaxRows(maxRows + 1);
      }
      result = CsvUtils.limitedResultSetToCsv(st.executeQuery(sql), maxRows);
    } catch (SQLException | RuntimeException ex) {
      // report the query's error, not a rollback failure caused by it
      try {
        cn.rollback();
      } catch (SQLException rollbackEx) {
        ex.addSuppressed(rollbackEx);
      }
      throw ex;
    }
    // on success a failed rollback must be reported: changes may not have been undone
    cn.rollback();
    return result;
  }

  public static boolean isReadStatement(String sql) {
    if (sql == null) {
      return false;
    }
    String s = skipLeadingCommentsAndParens(sql);
    String keyword = s.split("[^A-Za-z]", 2)[0].toUpperCase();
    return keyword.equals("SELECT") || keyword.equals("WITH") || keyword.equals("VALUES");
  }

  /** Returns "" when the statement cannot be safely classified. */
  private static String skipLeadingCommentsAndParens(String sql) {
    String s = sql;
    while (true) {
      s = s.stripLeading();
      if (s.startsWith("--")) {
        int eol = s.indexOf('\n');
        s = eol < 0 ? "" : s.substring(eol + 1);
      } else if (s.startsWith("/*")) {
        int end = s.indexOf("*/", 2);
        // reject nested comments: whether DB2 nests them decides where the statement starts
        if (end < 0 || s.substring(2, end).contains("/*")) {
          return "";
        }
        s = s.substring(end + 2);
      } else if (s.startsWith("(")) {
        s = s.substring(1);
      } else {
        return s;
      }
    }
  }

}
