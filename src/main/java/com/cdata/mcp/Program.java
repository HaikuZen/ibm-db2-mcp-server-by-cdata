package com.cdata.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.time.Duration;
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
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

public class Program {
  
  private McpServerTransportProvider transport;
  private HttpServletStreamableServerTransportProvider httpTransport;
  private Server httpServer;
  private List<Config> configs;
  private McpSyncServer mcpServer;

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
    // the transport settings are shared, so the first instance has them
    boolean valid = first.validateTransport(System.err);
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

    if (first.isHttp()) {
      this.httpTransport = HttpServletStreamableServerTransportProvider.builder()
          .jsonMapper(McpJsonDefaults.getMapper())
          .mcpEndpoint(first.getHttpPath())
          .httpHeaderValidator(new HttpAccessValidator(first.getHttpAllowedOrigins(), first.getHttpToken()))
          .build();
    } else {
      this.transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), exitOnEndOfInput(System.in), System.out);
    }
  }

  public void configureMcp() throws Exception {
    if (this.httpTransport != null) {
      this.mcpServer = createServer(McpServer.sync(this.httpTransport), this.configs);
      this.httpServer = startHttpServer(this.configs, this.httpTransport);
    } else {
      this.mcpServer = createServer(this.transport, this.configs);
    }
  }

  /** Builds the server exposing the tools and resources of every configured instance. */
  public static McpSyncServer createServer(McpServerTransportProvider transport, List<Config> configs) throws Exception {
    return createServer(McpServer.sync(transport), configs);
  }

  /** Like {@link #createServer(McpServerTransportProvider, List)}, for any transport's specification. */
  public static McpSyncServer createServer(McpServer.SyncSpecification<?> spec, List<Config> configs) throws Exception {
    spec.serverInfo(configs.get(0).getServerName(), configs.get(0).getServerVersion())
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
    // runs on SIGTERM, Ctrl+C and, with stdio, on the System.exit() made when the client closes stdin
    Runtime.getRuntime().addShutdownHook(new Thread(p::shutdown));
    synchronized (p) {
      while (true) {
        p.wait();
      }
    }
  }

  private void shutdown() {
    this.mcpServer.closeGracefully();
    if (this.httpServer != null) {
      try {
        this.httpServer.stop();
      } catch (Exception ex) {
        System.err.println("Stopping the HTTP server failed: " + ex.getMessage());
      }
    }
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

  /** Serves the MCP endpoint (Streamable HTTP) on HttpHost:HttpPort. */
  private static Server startHttpServer(List<Config> configs, HttpServletStreamableServerTransportProvider transport)
      throws Exception {
    Config first = configs.get(0);
    Server server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost(first.getHttpHost());
    connector.setPort(first.getHttpPort());
    // a tool call's response stays silent while its query runs, so don't drop it before the query timeout
    connector.setIdleTimeout(idleTimeout(configs).toMillis());
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    ServletHolder holder = new ServletHolder(transport);
    holder.setAsyncSupported(true);
    context.addServlet(holder, first.getHttpPath());
    server.setHandler(context);
    server.start();

    String host = first.getHttpHost();
    System.err.println("MCP server listening on http://" + (host.contains(":") ? "[" + host + "]" : host)
        + ":" + connector.getLocalPort() + first.getHttpPath());
    return server;
  }

  private static Duration idleTimeout(List<Config> configs) {
    int longest = 0;
    for (Config config : configs) {
      if (config.getQueryTimeout() == 0) {
        return Duration.ZERO; // queries without a timeout: never close an idle connection
      }
      longest = Math.max(longest, config.getQueryTimeout());
    }
    return Duration.ofSeconds(longest + 60);
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