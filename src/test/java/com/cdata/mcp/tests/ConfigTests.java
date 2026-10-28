package com.cdata.mcp.tests;

import com.cdata.mcp.Config;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

public class ConfigTests {
  private static List<Config> load(String text) throws Exception {
    return load(text, Map.of());
  }

  private static List<Config> load(String text, Map<String, String> env) throws Exception {
    File f = File.createTempFile("config", ".prp");
    f.deleteOnExit();
    Files.writeString(f.toPath(), text);
    return Config.loadAll(f.getPath(), env);
  }

  private static String validationErrors(Config config) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    config.validate(new PrintStream(out));
    return out.toString();
  }

  @Test
  public void singleInstance() throws Exception {
    List<Config> configs = load("Prefix=db2\nJdbcUrl=jdbc:db2://h:50000/SAMPLE\n");
    Assert.assertEquals(1, configs.size());
    Assert.assertEquals("db2", configs.get(0).getPrefix());
    Assert.assertEquals("jdbc:db2://h:50000/SAMPLE", configs.get(0).getJdbcUrl());
  }

  @Test
  public void multipleInstances() throws Exception {
    List<Config> configs = load(
        "Instances=prod, test\n"
        + "DriverPath=/lib/db2jcc4.jar\n"
        + "User=reader\n"
        + "prod.JdbcUrl=jdbc:db2://prod:50000/SALES\n"
        + "test.JdbcUrl=jdbc:db2://test:50000/SALES\n"
        + "test.User=tester\n"
        + "test.Prefix=db2test\n");
    Assert.assertEquals(2, configs.size());

    Config prod = configs.get(0);
    Assert.assertEquals("prod", prod.getPrefix());
    Assert.assertEquals("jdbc:db2://prod:50000/SALES", prod.getJdbcUrl());
    Assert.assertEquals("reader", prod.getUser());
    Assert.assertEquals("/lib/db2jcc4.jar", prod.getDriverJar());

    Config test = configs.get(1);
    Assert.assertEquals("db2test", test.getPrefix());
    Assert.assertEquals("jdbc:db2://test:50000/SALES", test.getJdbcUrl());
    Assert.assertEquals("tester", test.getUser());
    Assert.assertEquals("/lib/db2jcc4.jar", test.getDriverJar());
  }

  @Test
  public void schemeIsValidForUnderscorePrefix() throws Exception {
    Config config = load("Prefix=IBM_DB2\n").get(0);
    Assert.assertEquals("ibm-db2://", config.getMcpScheme());
    new java.net.URI(config.getMcpScheme() + "/SALES/ORDERS");
  }

  @Test
  public void invalidPrefixIsRejected() throws Exception {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    Assert.assertFalse(load("Prefix=1db2\n").get(0).validate(new java.io.PrintStream(out)));
    Assert.assertTrue(out.toString().contains("'Prefix' option must start with a letter"));
  }

  @Test
  public void queryLimits() throws Exception {
    Config defaults = load("Prefix=db2\n").get(0);
    Assert.assertEquals(1000, defaults.getMaxRows());
    Assert.assertEquals(60, defaults.getQueryTimeout());
    Assert.assertEquals(4, defaults.getPoolSize());
    Assert.assertEquals(0, load("Prefix=db2\nPoolSize=0\n").get(0).getPoolSize());

    List<Config> configs = load("Instances=a,b\nMaxRows=50\nb.MaxRows=0\nb.QueryTimeout=5\n");
    Assert.assertEquals(50, configs.get(0).getMaxRows());
    Assert.assertEquals(0, configs.get(1).getMaxRows());
    Assert.assertEquals(5, configs.get(1).getQueryTimeout());
  }

  @Test
  public void invalidMaxRowsIsRejected() throws Exception {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    Assert.assertFalse(load("Prefix=db2\nMaxRows=-1\n").get(0).validate(new java.io.PrintStream(out)));
    Assert.assertTrue(out.toString().contains("'MaxRows' option must be a whole number"));
  }

  @Test
  public void plainValuesAreUnchanged() throws Exception {
    Config config = load("Prefix=db2\nPassword=pa$$word\n", Map.of("X", "y")).get(0);
    Assert.assertEquals("pa$$word", config.getPassword());
  }

  @Test
  public void environmentVariablesAreExpanded() throws Exception {
    Config config = load(
        "Prefix=db2\nUser=${DB2_USER}\nPassword=${DB2_PASSWORD}\n"
            + "JdbcUrl=jdbc:db2://${DB2_HOST}:50000/SALES\n",
        Map.of("DB2_USER", "reader", "DB2_PASSWORD", "s3cr$t\\1", "DB2_HOST", "db.example.com")).get(0);
    Assert.assertEquals("reader", config.getUser());
    Assert.assertEquals("s3cr$t\\1", config.getPassword());
    Assert.assertEquals("jdbc:db2://db.example.com:50000/SALES", config.getJdbcUrl());
  }

  @Test
  public void escapedVariableIsLiteral() throws Exception {
    Config config = load("Prefix=db2\nPassword=ab$${NOT_A_VAR}cd\n", Map.of("NOT_A_VAR", "x")).get(0);
    Assert.assertEquals("ab${NOT_A_VAR}cd", config.getPassword());
  }

  @Test
  public void missingVariableIsReported() throws Exception {
    Config config = load("Prefix=db2\nPassword=${DB2_PASSWORD}\n").get(0);
    Assert.assertTrue(validationErrors(config).contains(
        "The 'Password' option uses the environment variable 'DB2_PASSWORD', which is not set"));
  }

  @Test
  public void variablesArePerInstance() throws Exception {
    List<Config> configs = load(
        "Instances=prod,test\nPassword=${SHARED_PASSWORD}\n"
            + "prod.Password=${PROD_PASSWORD}\ntest.Password=${TEST_PASSWORD}\n",
        Map.of("PROD_PASSWORD", "p1"));
    Assert.assertEquals("p1", configs.get(0).getPassword());
    // prod overrides the shared password, so the unset SHARED_PASSWORD is not an error for it
    Assert.assertFalse(validationErrors(configs.get(0)).contains("environment variable"));
    Assert.assertTrue(validationErrors(configs.get(1)).contains("'TEST_PASSWORD'"));
  }

  @Test
  public void defaultSchema() throws Exception {
    Assert.assertNull(load("Prefix=db2\n").get(0).defaultSchema());
    Assert.assertNull(load("Prefix=db2\nDefaultSchema=  \n").get(0).defaultSchema());
    List<Config> configs = load("Instances=a,b\nDefaultSchema=SALES \nb.DefaultSchema=${B_SCHEMA}\n", Map.of("B_SCHEMA", "HR"));
    Assert.assertEquals("SALES", configs.get(0).defaultSchema());
    Assert.assertEquals("HR", configs.get(1).defaultSchema());
  }

  @Test
  public void addressFromJdbcUrl() throws Exception {
    Config config = load("Prefix=db2\nJdbcUrl=jdbc:db2://db.example.com:446/LOCDB01:sslConnection=true;password=x;\n").get(0);
    Assert.assertEquals("db.example.com", config.getHost());
    Assert.assertEquals("446", config.getPort());
    Assert.assertEquals("LOCDB01", config.getDatabase());

    Config noPort = load("Prefix=db2\nJdbcUrl=jdbc:db2://localhost/SAMPLE\n").get(0);
    Assert.assertEquals("localhost", noPort.getHost());
    Assert.assertEquals("", noPort.getPort());
    Assert.assertEquals("SAMPLE", noPort.getDatabase());

    Assert.assertEquals("", load("Prefix=db2\nJdbcUrl=jdbc:other:x\n").get(0).getHost());
    Assert.assertEquals("", load("Prefix=db2\n").get(0).getDatabase());
  }

  @Test
  public void platformFromProductVersion() {
    Assert.assertEquals("z/OS", Config.platformOf("DSN12015"));
    Assert.assertEquals("LUW", Config.platformOf("SQL11058"));
    Assert.assertEquals("IBM i", Config.platformOf("QSQ07050"));
    Assert.assertEquals("", Config.platformOf("XYZ"));
    Assert.assertEquals("", Config.platformOf(null));
  }

  @Test
  public void defaultSystemSchemas() throws Exception {
    Config config = load("Prefix=db2\n").get(0);
    for (String schema : new String[] { "SYSIBM", "SYSIBMADM", "SYSIBMTS", "SYSCAT", "SYSFUN", "SYSSTAT", "SYSPROC",
        "SYSPUBLIC", "SYSTOOLS", "SYSACCEL", "NULLID", "SQLJ", "sysibm", "SYSIBM  " }) {
      Assert.assertTrue(schema, config.isSystemSchema(schema));
    }
    // user schemas, including the z/OS SYSADM convention
    for (String schema : new String[] { "SYSADM", "SYSTEMX", "SALES", "DB2INST1", "SQLJX" }) {
      Assert.assertFalse(schema, config.isSystemSchema(schema));
    }
    Assert.assertFalse(config.isSystemSchema(null));
    Assert.assertTrue(config.getSystemSchemas().startsWith("SYSIBM%, SYSCAT"));
  }

  @Test
  public void systemSchemasCanBeReplacedPerInstance() throws Exception {
    List<Config> configs = load("Instances=zos,i\nzos.SystemSchemas=SYS%, DSN%\ni.SystemSchemas=Q%,SYSIBM\n");
    Config zos = configs.get(0);
    Assert.assertTrue(zos.isSystemSchema("SYSADM"));
    Assert.assertTrue(zos.isSystemSchema("DSNACC"));
    Assert.assertFalse(zos.isSystemSchema("NULLID"));
    Assert.assertEquals("SYS%, DSN%", zos.getSystemSchemas());
    Config i = configs.get(1);
    Assert.assertTrue(i.isSystemSchema("QSYS2"));
    Assert.assertFalse(i.isSystemSchema("SYSIBMADM"));
  }

  @Test
  public void emptySystemSchemasHidesNothing() throws Exception {
    Config config = load("Prefix=db2\nSystemSchemas=\n").get(0);
    Assert.assertFalse(config.isSystemSchema("SYSIBM"));
    Assert.assertEquals("none", config.getSystemSchemas());
  }

  @Test
  public void systemSchemaNamesAreNotRegexes() throws Exception {
    Config config = load("Prefix=db2\nSystemSchemas=A.B, C$\n").get(0);
    Assert.assertTrue(config.isSystemSchema("A.B"));
    Assert.assertFalse(config.isSystemSchema("AXB"));
    Assert.assertTrue(config.isSystemSchema("C$"));
  }

  @Test
  public void topLevelPrefixIsNotShared() throws Exception {
    List<Config> configs = load("Instances=a,b\nPrefix=db2\n");
    Assert.assertEquals("a", configs.get(0).getPrefix());
    Assert.assertEquals("b", configs.get(1).getPrefix());
  }
  private static String transportErrors(Config config) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    config.validateTransport(new PrintStream(out));
    return out.toString();
  }

  @Test
  public void stdioIsTheDefaultTransport() throws Exception {
    Config config = load("Prefix=db2\n").get(0);
    Assert.assertEquals(Config.STDIO, config.getTransport());
    Assert.assertFalse(config.isHttp());
    Assert.assertEquals("", transportErrors(config));
  }

  @Test
  public void httpDefaults() throws Exception {
    Config config = load("Prefix=db2\nTransport=HTTP\n").get(0);
    Assert.assertTrue(config.isHttp());
    Assert.assertEquals("127.0.0.1", config.getHttpHost());
    Assert.assertEquals(8080, config.getHttpPort());
    Assert.assertEquals("/mcp", config.getHttpPath());
    Assert.assertNull(config.getHttpToken());
    Assert.assertTrue(config.getHttpAllowedOrigins().contains("http://localhost:*"));
    Assert.assertEquals("", transportErrors(config));
  }

  @Test
  public void httpSettingsAreSharedByInstances() throws Exception {
    List<Config> configs = load(
        "Instances=a,b\nTransport=http\nHttpPort=9000\nHttpPath=db2\nHttpToken=${TOKEN}\nHttpAllowedOrigins=https://app.example.com\n",
        Map.of("TOKEN", "t0ken"));
    for (Config config : configs) {
      Assert.assertEquals(9000, config.getHttpPort());
      Assert.assertEquals("/db2", config.getHttpPath());
      Assert.assertEquals("t0ken", config.getHttpToken());
      Assert.assertEquals(List.of("https://app.example.com"), config.getHttpAllowedOrigins());
    }
  }

  @Test
  public void invalidTransportSettings() throws Exception {
    Assert.assertTrue(transportErrors(load("Prefix=db2\nTransport=tcp\n").get(0)).contains("'Transport'"));
    Assert.assertTrue(transportErrors(load("Prefix=db2\nTransport=http\nHttpPort=0\n").get(0)).contains("'HttpPort'"));
    Assert.assertTrue(transportErrors(load("Prefix=db2\nTransport=http\nHttpPort=x\n").get(0)).contains("'HttpPort'"));
    Assert.assertTrue(transportErrors(load("Prefix=db2\nTransport=http\nHttpPath=/a?b\n").get(0)).contains("'HttpPath'"));
    // HTTP settings are ignored with stdio
    Assert.assertEquals("", transportErrors(load("Prefix=db2\nHttpPort=x\n").get(0)));
  }

  @Test
  public void listeningOnTheNetworkRequiresAToken() throws Exception {
    String errors = transportErrors(load("Prefix=db2\nTransport=http\nHttpHost=0.0.0.0\n").get(0));
    Assert.assertTrue(errors, errors.contains("'HttpToken'"));
    Assert.assertEquals("", transportErrors(load("Prefix=db2\nTransport=http\nHttpHost=0.0.0.0\nHttpToken=t\n").get(0)));
    Assert.assertEquals("", transportErrors(load("Prefix=db2\nTransport=http\nHttpHost=localhost\n").get(0)));
    Assert.assertEquals("", transportErrors(load("Prefix=db2\nTransport=http\nHttpHost=::1\n").get(0)));
  }
}
