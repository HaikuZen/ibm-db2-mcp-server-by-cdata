package com.cdata.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.cdata.mcp.resources.TableMetadataResource;
import com.cdata.mcp.tools.DescribeTableTool;
import com.cdata.mcp.tools.GetColumnsTool;
import com.cdata.mcp.tools.GetTablesTool;
import com.cdata.mcp.tools.HelpTool;
import com.cdata.mcp.tools.ListInstancesTool;
import com.cdata.mcp.tools.ListSchemasTool;
import com.cdata.mcp.tools.RunQueryTool;
import com.cdata.mcp.tools.SearchColumnsTool;
import com.cdata.mcp.tools.TableStatsTool;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

public class Program {
  
  private McpServerTransportProvider transport;
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
    Set<String> schemes = new HashSet<>();
    for (Config config : this.configs) {
      if (!config.validate(System.err)) {
        System.err.println("Instance '" + config.getPrefix() + "' is not configured correctly");
        valid = false;
      } else if (!prefixes.add(config.getPrefix()) | !schemes.add(config.getMcpScheme())) {
        // tool names and resource URIs are derived from the prefix, so it must be unique
        System.err.println("The '" + config.getPrefix() + "' prefix conflicts with another instance's prefix");
        valid = false;
      }
    }
    if (!valid) {
      System.exit(-1);
    }

    this.transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), exitOnEndOfInput(System.in), System.out);
  }

  public void configureMcp() throws Exception {
    this.mcpServer = createServer(this.transport, this.configs);
  }

  /** Builds the server exposing the tools and resources of every configured instance. */
  public static McpSyncServer createServer(McpServerTransportProvider transport, List<Config> configs) throws Exception {
    McpServer.SyncSpecification<?> spec =
        McpServer.sync(transport)
            .serverInfo(configs.get(0).getServerName(), configs.get(0).getServerVersion())
            .instructions(HelpTool.instructions(configs))
            .capabilities(
                McpSchema.ServerCapabilities.builder()
                    .tools(true)
                    .resources(false, true)
                    // no prompts, but some clients ask for them regardless; answer with an empty list
                    .prompts(false)
                    //.logging()
                    .build()
            );

    for (Config config : configs) {
      registerResources(config, spec);
      registerTools(config, spec);
    }
    // these cover the whole server, so they are registered once rather than per instance
    new ListInstancesTool(configs).register(spec);
    new HelpTool(configs).register(spec);

    return spec.build();
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.err.println("Usage: <properties-file-path>");
      System.exit(-1);
    }
    final Program p = new Program();
    p.init(args[0]);
    p.configureMcp();
    if (!STDIO) {
      //p.runHttpServer();
    } else {
      // runs on SIGTERM and on the System.exit() made when the client closes stdin
      Runtime.getRuntime().addShutdownHook(new Thread(p::shutdown));
      synchronized (p) {
        while (true) {
          p.wait();
        }
      }
    }
  }

  private void shutdown() {
    this.mcpServer.closeGracefully();
    for (Config config : this.configs) {
      config.close();
    }
  }

  /**
   * The client ends a stdio session by closing our stdin. The SDK then closes the session but leaves
   * the process running, so exit: otherwise every client restart leaves a JVM (and its DB2 connections) behind.
   */
  private static InputStream exitOnEndOfInput(InputStream in) {
    return new FilterInputStream(in) {
      private boolean exiting;

      @Override
      public int read() throws IOException {
        return check(super.read());
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        return check(super.read(b, off, len));
      }

      private int check(int result) {
        if (result < 0 && !this.exiting) {
          this.exiting = true;
          // not on this thread: shutting down closes the transport, which waits for this reader
          new Thread(() -> System.exit(0), "stdin-closed").start();
        }
        return result;
      }
    };
  }

  private static void registerResources(Config config, McpServer.SyncSpecification<?> mcp) throws SQLException {
    List<Table> tables = config.getTables();
    TableMetadataResource resource = new TableMetadataResource(config);

    for (Table r : tables) {
      resource.register(mcp, r);
    }
  }
  private static void registerTools(Config config, McpServer.SyncSpecification<?> mcp) throws Exception {
    ITool[] tools = new ITool[] {
        new ListSchemasTool(config),
        new GetTablesTool(config),
        new TableStatsTool(config),
        new GetColumnsTool(config),
        new DescribeTableTool(config),
        new SearchColumnsTool(config),
        new RunQueryTool(config)
    };
    for (ITool tool : tools) {
      if (tool != null) {
        tool.register(mcp);
      }
    }
  }
}