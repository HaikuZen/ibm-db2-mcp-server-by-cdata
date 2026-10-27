package com.cdata.mcp.tests;

import com.cdata.mcp.Config;
import com.cdata.mcp.Program;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Talks JSON-RPC to the server over in-memory stdio pipes; no database is needed. */
public class McpProtocolTests {
  private final McpJsonMapper json = McpJsonDefaults.getMapper();
  private McpSyncServer server;
  private OutputStream toServer;
  private BufferedReader fromServer;

  @Before
  public void start() throws Exception {
    File f = File.createTempFile("config", ".prp");
    f.deleteOnExit();
    Files.writeString(f.toPath(), "Instances=prod,test\nServerName=DB2\nServerVersion=1.0\nprod.DefaultSchema=SALES\n");
    List<Config> configs = Config.loadAll(f.getPath());

    PipedInputStream serverIn = new PipedInputStream(1 << 16);
    this.toServer = new PipedOutputStream(serverIn);
    PipedOutputStream serverOut = new PipedOutputStream();
    this.fromServer = new BufferedReader(new InputStreamReader(new PipedInputStream(serverOut, 1 << 16), StandardCharsets.UTF_8));
    this.server = Program.createServer(new StdioServerTransportProvider(json, serverIn, serverOut), configs);

    Map<String, Object> init = request(1, "initialize", Map.of(
        "protocolVersion", "2025-06-18",
        "capabilities", Map.of(),
        "clientInfo", Map.of("name", "test", "version", "1.0")));
    Map<String, Object> serverInfo = (Map<String, Object>)((Map<String, Object>)init.get("result")).get("serverInfo");
    Assert.assertEquals("DB2", serverInfo.get("name"));
    send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
  }

  @After
  public void stop() {
    this.server.close();
  }

  @Test(timeout = 20000)
  public void listsToolsForEveryInstance() throws Exception {
    Map<String, Object> result = (Map<String, Object>)request(2, "tools/list", Map.of()).get("result");
    List<Map<String, Object>> tools = (List<Map<String, Object>>)result.get("tools");
    List<String> names = tools.stream().map(t -> (String)t.get("name")).sorted().collect(Collectors.toList());
    Assert.assertEquals(List.of(
        "prod_get_columns", "prod_get_tables", "prod_run_query",
        "test_get_columns", "test_get_tables", "test_run_query"), names);

    Map<String, Object> runQuery = tools.stream().filter(t -> t.get("name").equals("prod_run_query")).findFirst().get();
    Map<String, Object> schema = (Map<String, Object>)runQuery.get("inputSchema");
    Assert.assertEquals("object", schema.get("type"));
    Assert.assertTrue(((Map<String, Object>)schema.get("properties")).containsKey("sql"));
  }

  @Test(timeout = 20000)
  public void descriptionsNameTheDefaultSchema() throws Exception {
    Map<String, Object> result = (Map<String, Object>)request(4, "tools/list", Map.of()).get("result");
    Map<String, String> descriptions = new java.util.HashMap<>();
    for (Map<String, Object> tool : (List<Map<String, Object>>)result.get("tools")) {
      Map<String, Object> props = (Map<String, Object>)((Map<String, Object>)tool.get("inputSchema")).get("properties");
      String argHelp = props.values().stream().map(p -> (String)((Map<String, Object>)p).get("description")).collect(Collectors.joining(" "));
      descriptions.put((String)tool.get("name"), tool.get("description") + " " + argHelp);
    }
    Assert.assertTrue(descriptions.get("prod_get_tables").contains("tables in the `SALES` schema are listed"));
    Assert.assertTrue(descriptions.get("prod_get_columns").contains("defaults to `SALES`"));
    Assert.assertTrue(descriptions.get("prod_run_query").contains("Unqualified table names refer to the `SALES` schema"));
    Assert.assertFalse(descriptions.get("test_get_tables").contains("SALES"));
    Assert.assertTrue(descriptions.get("test_run_query").contains("Qualify tables with their schema"));
  }

  @Test(timeout = 20000)
  public void writeStatementIsReturnedAsToolError() throws Exception {
    Map<String, Object> response = request(3, "tools/call", Map.of(
        "name", "prod_run_query",
        "arguments", Map.of("sql", "DELETE FROM SALES.ORDERS")));
    Map<String, Object> result = (Map<String, Object>)response.get("result");
    Assert.assertEquals(Boolean.TRUE, result.get("isError"));
    Map<String, Object> text = ((List<Map<String, Object>>)result.get("content")).get(0);
    Assert.assertEquals("text", text.get("type"));
    Assert.assertEquals("ERROR: Only SELECT, WITH and VALUES statements are allowed", text.get("text"));
  }

  private void send(Map<String, Object> message) throws Exception {
    this.toServer.write((json.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
    this.toServer.flush();
  }

  private Map<String, Object> request(int id, String method, Map<String, Object> params) throws Exception {
    send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    while (true) {
      String line = this.fromServer.readLine();
      Assert.assertNotNull("server closed the stream", line);
      Map<String, Object> message = json.readValue(line, Map.class);
      if (message.containsKey("id") && ((Number)message.get("id")).intValue() == id) {
        return message;
      }
    }
  }
}
