package com.cdata.mcp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keeps up to maxIdle physical connections open for reuse. Connections handed out are
 * proxies: closing one rolls back any open transaction, restores the default settings,
 * closes statements left open, and returns the physical connection to the pool.
 */
public class ConnectionPool implements AutoCloseable {
  private static final int VALIDATION_TIMEOUT = 5;

  public interface ConnectionFactory {
    Connection open() throws SQLException;
  }

  private final ConnectionFactory factory;
  private final int maxIdle;
  private final Deque<Connection> idle = new ArrayDeque<>();
  private boolean closed;

  public ConnectionPool(ConnectionFactory factory, int maxIdle) {
    this.factory = factory;
    this.maxIdle = maxIdle;
  }

  public Connection getConnection() throws SQLException {
    Connection physical;
    while ((physical = takeIdle()) != null) {
      // the server or a firewall may have dropped it while idle
      if (isUsable(physical)) {
        return wrap(physical);
      }
      closeQuietly(physical);
    }
    return wrap(this.factory.open());
  }

  public synchronized int idleCount() {
    return this.idle.size();
  }

  @Override
  public void close() {
    List<Connection> toClose;
    synchronized (this) {
      this.closed = true;
      toClose = new ArrayList<>(this.idle);
      this.idle.clear();
    }
    for (Connection cn : toClose) {
      closeQuietly(cn);
    }
  }

  private synchronized Connection takeIdle() {
    // most recently used first, so rarely needed extras are the ones left to go stale
    return this.idle.pollFirst();
  }

  private void release(Connection physical, List<Statement> statements) {
    try {
      for (Statement st : statements) {
        st.close();
      }
      if (!physical.getAutoCommit()) {
        physical.rollback();
        physical.setAutoCommit(true);
      }
      physical.setReadOnly(false);
    } catch (SQLException ex) {
      closeQuietly(physical);
      return;
    }
    synchronized (this) {
      if (!this.closed && this.idle.size() < this.maxIdle) {
        this.idle.addFirst(physical);
        return;
      }
    }
    closeQuietly(physical);
  }

  private Connection wrap(Connection physical) {
    List<Statement> statements = new ArrayList<>();
    boolean[] released = { false };
    return (Connection)Proxy.newProxyInstance(
        Connection.class.getClassLoader(),
        new Class[] { Connection.class },
        (proxy, method, args) -> {
          synchronized (released) {
            String name = method.getName();
            if (name.equals("close")) {
              if (!released[0]) {
                released[0] = true;
                release(physical, statements);
              }
              return null;
            }
            if (name.equals("isClosed") && released[0]) {
              return true;
            }
            if (released[0]) {
              throw new SQLException("The connection is closed");
            }
            Object result;
            try {
              result = method.invoke(physical, args);
            } catch (InvocationTargetException ex) {
              throw ex.getCause();
            }
            if (result instanceof Statement) {
              statements.add((Statement)result);
            }
            return result;
          }
        }
    );
  }

  private static boolean isUsable(Connection cn) {
    try {
      return cn.isValid(VALIDATION_TIMEOUT);
    } catch (SQLException ex) {
      return false;
    }
  }

  private static void closeQuietly(Connection cn) {
    try {
      cn.close();
    } catch (SQLException ex) {
    }
  }
}
