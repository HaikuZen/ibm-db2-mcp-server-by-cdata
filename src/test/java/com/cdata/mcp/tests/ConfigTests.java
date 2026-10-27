package com.cdata.mcp.tests;

import com.cdata.mcp.Config;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class ConfigTests {
  private static List<Config> load(String text) throws Exception {
    File f = File.createTempFile("config", ".prp");
    f.deleteOnExit();
    Files.writeString(f.toPath(), text);
    return Config.loadAll(f.getPath());
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
  public void topLevelPrefixIsNotShared() throws Exception {
    List<Config> configs = load("Instances=a,b\nPrefix=db2\n");
    Assert.assertEquals("a", configs.get(0).getPrefix());
    Assert.assertEquals("b", configs.get(1).getPrefix());
  }
}
