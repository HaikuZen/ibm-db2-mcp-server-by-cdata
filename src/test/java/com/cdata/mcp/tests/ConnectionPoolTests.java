package com.cdata.mcp.tests;

import com.cdata.mcp.ConnectionPool;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class ConnectionPoolTests {
  /** Records what the pool does to a physical connection. */
  static class Fake {
    boolean closed;
    boolean valid = true;
    boolean autoCommit = true;
    boolean readOnly;
    int rollbacks;
    List<boolean[]> statements = new ArrayList<>();

    Connection connection() {
      return (Connection)Proxy.newProxyInstance(
          Connection.class.getClassLoader(), new Class[] { Connection.class },
          (p, m, a) -> switch (m.getName()) {
            case "close" -> { closed = true; yield null; }
            case "isClosed" -> closed;
            case "isValid" -> valid && !closed;
            case "getAutoCommit" -> autoCommit;
            case "setAutoCommit" -> { autoCommit = (Boolean)a[0]; yield null; }
            case "setReadOnly" -> { readOnly = (Boolean)a[0]; yield null; }
            case "rollback" -> { rollbacks++; yield null; }
            case "createStatement" -> statement();
            default -> null;
          });
    }

    Statement statement() {
      boolean[] closedFlag = { false };
      statements.add(closedFlag);
      return (Statement)Proxy.newProxyInstance(
          Statement.class.getClassLoader(), new Class[] { Statement.class },
          (p, m, a) -> {
            if (m.getName().equals("close")) {
              closedFlag[0] = true;
            }
            return null;
          });
    }
  }

  static class Factory implements ConnectionPool.ConnectionFactory {
    List<Fake> opened = new ArrayList<>();

    @Override
    public Connection open() {
      Fake f = new Fake();
      opened.add(f);
      return f.connection();
    }
  }

  @Test
  public void connectionIsReused() throws Exception {
    Factory factory = new Factory();
    ConnectionPool pool = new ConnectionPool(factory, 2);
    pool.getConnection().close();
    pool.getConnection().close();
    Assert.assertEquals(1, factory.opened.size());
    Assert.assertFalse(factory.opened.get(0).closed);
    Assert.assertEquals(1, pool.idleCount());
  }

  @Test
  public void concurrentUseOpensMoreButKeepsAtMostMaxIdle() throws Exception {
    Factory factory = new Factory();
    ConnectionPool pool = new ConnectionPool(factory, 2);
    Connection a = pool.getConnection();
    Connection b = pool.getConnection();
    Connection c = pool.getConnection();
    a.close();
    b.close();
    c.close();
    Assert.assertEquals(3, factory.opened.size());
    Assert.assertEquals(2, pool.idleCount());
    Assert.assertTrue(factory.opened.get(2).closed);
  }

  @Test
  public void invalidIdleConnectionIsReplaced() throws Exception {
    Factory factory = new Factory();
    ConnectionPool pool = new ConnectionPool(factory, 2);
    pool.getConnection().close();
    factory.opened.get(0).valid = false;
    pool.getConnection().close();
    Assert.assertEquals(2, factory.opened.size());
    Assert.assertTrue(factory.opened.get(0).closed);
  }

  @Test
  public void stateIsResetOnRelease() throws Exception {
    Factory factory = new Factory();
    ConnectionPool pool = new ConnectionPool(factory, 2);
    Connection cn = pool.getConnection();
    cn.setReadOnly(true);
    cn.setAutoCommit(false);
    cn.createStatement();
    cn.close();
    Fake f = factory.opened.get(0);
    Assert.assertEquals(1, f.rollbacks);
    Assert.assertTrue(f.autoCommit);
    Assert.assertFalse(f.readOnly);
    Assert.assertTrue("statement left open by the caller", f.statements.get(0)[0]);
  }

  @Test
  public void closedHandleCannotBeUsed() throws Exception {
    ConnectionPool pool = new ConnectionPool(new Factory(), 2);
    Connection cn = pool.getConnection();
    cn.close();
    cn.close(); // a second close must not release the connection twice
    Assert.assertTrue(cn.isClosed());
    Assert.assertEquals(1, pool.idleCount());
    Assert.assertThrows(SQLException.class, cn::createStatement);
  }

  @Test
  public void sizeZeroDisablesPooling() throws Exception {
    Factory factory = new Factory();
    ConnectionPool pool = new ConnectionPool(factory, 0);
    pool.getConnection().close();
    Assert.assertTrue(factory.opened.get(0).closed);
    Assert.assertEquals(0, pool.idleCount());
  }

  @Test
  public void closeClosesIdleAndLaterReleases() throws Exception {
    Factory factory = new Factory();
    ConnectionPool pool = new ConnectionPool(factory, 2);
    Connection inUse = pool.getConnection();
    pool.getConnection().close();
    pool.close();
    Assert.assertTrue(factory.opened.get(1).closed);
    inUse.close();
    Assert.assertTrue(factory.opened.get(0).closed);
    Assert.assertEquals(0, pool.idleCount());
  }
}
