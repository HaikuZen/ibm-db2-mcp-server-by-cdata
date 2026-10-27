package com.cdata.mcp;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.cdata.mcp.resources.TableMetadataResource;
import com.cdata.mcp.tools.GetColumnsTool;
import com.cdata.mcp.tools.GetTablesTool;
import com.cdata.mcp.tools.RunQueryTool;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;

public class Program {
  
  private ServerMcpTransport transport;
  private List<Config> configs;
  private McpSyncServer mcpServer;
  private static final boolean STDIO = true;

  public void init(String configPath) throws Exception {
    this.configs = Config.loadAll(configPath);
    if (this.configs.isEmpty()) {
      System.err.println("The 'Instances' option does not list any instance");
      System.exit(-1);
    }
    Config first = this.configs.get(0);
    if (!StringUtil.isNullOrEmpty(first.getLogFile())) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
      System.setProperty("org.slf4j.simpleLogger.logFile", first.getLogFile());
    }
    boolean valid = true;
    Set<String> prefixes = new HashSet<>();
    for (Config config : this.configs) {
      if (!config.validate(System.err)) {
        System.err.println("Instance '" + config.getPrefix() + "' is not configured correctly");
        valid = false;
      } else if (!prefixes.add(config.getPrefix())) {
        // tool names and resource URIs are derived from the prefix, so it must be unique
        System.err.println("The '" + config.getPrefix() + "' prefix is used by more than one instance");
        valid = false;
      }
    }
    if (!valid) {
      System.exit(-1);
    }

    this.transport = new StdioServerTransport(new ObjectMapper());
  }

  public void configureMcp() throws Exception {
    McpServer.SyncSpec spec =
        McpServer.sync(this.transport)
            .serverInfo(this.configs.get(0).getServerName(), this.configs.get(0).getServerVersion())
            .capabilities(
                McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .resources(false, true)
                    //.logging()
                    .build()
            );

    for (Config config : this.configs) {
      registerResources(config, spec);
      registerTools(config, spec);
    }

    this.mcpServer = spec.build();
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: <properties-file-path>");
      System.exit(-1);
    }
    String path = args[0];

    final Program p = new Program();
    p.init(args[0]);
    p.configureMcp();
    if (!STDIO) {
      //p.runHttpServer();
    } else {
      Runtime.getRuntime().addShutdownHook(new Thread() {
        public void run() {
          synchronized (p) {
            p.notify();
          }
        }
      });
      synchronized (p) {
        p.wait();
        p.mcpServer.closeGracefully();
      }
    }
  }

  private static void registerResources(Config config, McpServer.SyncSpec mcp) throws SQLException {
    List<Table> tables = config.getTables();
    TableMetadataResource resource = new TableMetadataResource(config);

    for (Table r : tables) {
      resource.register(mcp, r);
    }
  }
  private static void registerTools(Config config, McpServer.SyncSpec mcp) throws Exception {
    ITool[] tools = new ITool[] {
        new GetTablesTool(config),
        new GetColumnsTool(config),
        new RunQueryTool(config)
    };
    for (ITool tool : tools) {
      if (tool != null) {
        tool.register(mcp);
      }
    }
  }
}