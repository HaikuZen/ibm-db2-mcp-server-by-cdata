package com.cdata.mcp;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

public class CsvUtils {
  public static final String MIME = "text/csv";

  public record CsvResult(String csv, int rows, boolean truncated) {
  }

  public interface RowFilter {
    boolean include(ResultSet rs) throws SQLException;
  }

  public static String resultSetToCsv(ResultSet rs) throws SQLException {
    return resultSetToCsv(rs, null);
  }

  public static String resultSetToCsv(ResultSet rs, String[][] columns) throws SQLException {
    return resultSetToCsv(rs, columns, null);
  }

  public static String resultSetToCsv(ResultSet rs, String[][] columns, RowFilter filter) throws SQLException {
    return limitedResultSetToCsv(rs, columns, filter, 0).csv();
  }

  /** Writes at most maxRows rows (0 means no limit) and reports whether more were available. */
  public static CsvResult limitedResultSetToCsv(ResultSet rs, int maxRows) throws SQLException {
    return limitedResultSetToCsv(rs, null, null, maxRows);
  }

  /**
   * Writes the rows accepted by filter (null accepts all), at most maxRows of them (0 means no limit),
   * and reports whether more accepted rows were available.
   */
  public static CsvResult limitedResultSetToCsv(ResultSet rs, String[][] columns, RowFilter filter, int maxRows)
      throws SQLException {
    try {
      CsvWriter csv = new CsvWriter();
      ResultSetMetaData meta = rs.getMetaData();
      writeMeta(csv, meta, columns);
      int rows = 0;
      boolean truncated = false;
      while (rs.next()) {
        if (filter != null && !filter.include(rs)) {
          continue;
        }
        if (maxRows > 0 && rows == maxRows) {
          truncated = true;
          break;
        }
        writeRow(csv, meta, columns, rs);
        rows++;
      }
      return new CsvResult(csv.end(), rows, truncated);
    } finally {
      rs.close();
    }
  }

  private static void writeMeta(CsvWriter csv, ResultSetMetaData meta, String[][] columns) throws SQLException {
    CsvWriter.Row row = csv.row();
    if (columns == null) {
      for (int i = 1; i <= meta.getColumnCount(); i++) {
        row.column(meta.getColumnLabel(i));
      }
    } else {
      for ( int i=0; i < columns.length; i++ ) {
        row.column(columns[i][1]);
      }
    }
    row.end();
  }
  private static void writeRow(CsvWriter csv, ResultSetMetaData meta, String[][] columns, ResultSet rs) throws SQLException {
    CsvWriter.Row row = csv.row();
    if ( columns == null ) {
      for (int i = 1; i <= meta.getColumnCount(); i++) {
        row.column(rs.getString(i));
      }
    } else {
      for (int i = 0; i < columns.length; i++) {
        row.column(rs.getString(columns[i][0]));
      }
    }
    row.end();
  }
}
