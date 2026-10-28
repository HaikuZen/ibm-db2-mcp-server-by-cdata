package com.cdata.mcp.tests;

import java.lang.reflect.Proxy;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** Minimal in-memory JDBC objects for testing code that reads metadata. */
public class FakeJdbc {
  public static ResultSet resultSet(String[] labels, Object[]... rows) {
    List<String> names = Arrays.asList(labels);
    ResultSetMetaData meta = (ResultSetMetaData)Proxy.newProxyInstance(
        ResultSetMetaData.class.getClassLoader(), new Class[] { ResultSetMetaData.class },
        (p, m, a) -> switch (m.getName()) {
          case "getColumnCount" -> labels.length;
          case "getColumnLabel", "getColumnName" -> labels[(Integer)a[0] - 1];
          default -> null;
        });
    int[] current = { -1 };
    return (ResultSet)Proxy.newProxyInstance(
        ResultSet.class.getClassLoader(), new Class[] { ResultSet.class },
        (p, m, a) -> {
          switch (m.getName()) {
            case "getMetaData": return meta;
            case "next": return ++current[0] < rows.length;
            case "close": return null;
            case "getString":
            case "getLong":
              int index = a[0] instanceof Integer ? (Integer)a[0] - 1 : names.indexOf((String)a[0]);
              if (index < 0) {
                throw new SQLException("Unknown column " + a[0]);
              }
              Object value = rows[current[0]][index];
              if (m.getName().equals("getLong")) {
                return value == null ? 0L : ((Number)value).longValue();
              }
              return value == null ? null : String.valueOf(value);
            default: return null;
          }
        });
  }

  /** Metadata whose methods return the result of the callable registered under their name, or fail. */
  public static DatabaseMetaData metaData(Map<String, Callable<ResultSet>> byMethod) {
    return (DatabaseMetaData)Proxy.newProxyInstance(
        DatabaseMetaData.class.getClassLoader(), new Class[] { DatabaseMetaData.class },
        (p, m, a) -> {
          Callable<ResultSet> result = byMethod.get(m.getName());
          if (result == null) {
            throw new SQLException(m.getName() + " is not supported");
          }
          return result.call();
        });
  }
}
