package com.cdata.mcp;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static com.cdata.mcp.StringUtil.isNullOrEmpty;

public class Config {
  private static final String INSTANCES = "Instances";
  private static final String SERVER_NAME = "ServerName";
  private static final String PREFIX = "Prefix";
  private static final String DRIVER = "DriverClass";
  private static final String DRIVER_JAR = "DriverPath";
  private static final String LICENSE_JAR = "LicensePath";
  private static final String USER = "User";
  private static final String PASSWORD = "Password";
  private static final String JDBC_URL = "JdbcUrl";
  private static final String TABLES = "Tables";
  private static final String LOG_FILE = "LogFile";
  private static final String MAX_ROWS = "MaxRows";
  private static final String QUERY_TIMEOUT = "QueryTimeout";
  private static final int DEFAULT_MAX_ROWS = 1000;
  private static final int DEFAULT_QUERY_TIMEOUT = 60;
  private static final String DEFAULT_DRIVER = "com.ibm.db2.jcc.DB2Driver";

  // following properties will be discovered dynamically from driver
  private static final String ID_QUOTE_OPEN_CHAR = "IDENTIFIER_QUOTE_OPEN_CHAR";
  private static final String ID_QUOTE_CLOSE_CHAR = "IDENTIFIER_QUOTE_CLOSE_CHAR";
  private static final String SUPPORTS_MULTIPLE_CATALOGS = "SUPPORTS_MULTIPLE_CATALOGS";
  private static final String SUPPORTS_MULTIPLE_SCHEMAS = "SUPPORTS_MULTIPLE_SCHEMAS";
  private Properties props = new Properties();
  private Properties sqlInfo = new Properties();
  private Driver driver;
  private String defCatalog;
  private String defSchema;

  public Config() {
  }

  private Config(Properties props) {
    this.props = props;
  }

  /**
   * Loads one Config per DB2 instance. If the file has no 'Instances' list it describes
   * a single instance. Otherwise each listed instance reads its settings from keys
   * prefixed with its name (e.g. prod.JdbcUrl), falling back to the unprefixed keys,
   * and its Prefix defaults to the instance name.
   */
  public static List<Config> loadAll(String filepath) throws IOException {
    Properties all = new Properties();
    try (FileInputStream fis = new FileInputStream(filepath)) {
      all.load(fis);
    }
    List<Config> result = new ArrayList<>();
    String instances = all.getProperty(INSTANCES);
    if (isNullOrEmpty(instances)) {
      result.add(new Config(all));
      return result;
    }
    for (String name : instances.split(",")) {
      name = name.trim();
      if (name.isEmpty()) {
        continue;
      }
      String keyPrefix = name + ".";
      Properties merged = new Properties();
      merged.putAll(all);
      merged.setProperty(PREFIX, name);
      for (String key : all.stringPropertyNames()) {
        if (key.startsWith(keyPrefix)) {
          merged.setProperty(key.substring(keyPrefix.length()), all.getProperty(key));
        }
      }
      result.add(new Config(merged));
    }
    return result;
  }

  public boolean validate(PrintStream errors) {
    boolean result = true;
    if (isNullOrEmpty(getPrefix())) {
      errors.println("The '" + PREFIX + "' option is missing");
      result = false;
    } else if (!getPrefix().matches("[A-Za-z][A-Za-z0-9_-]*")) {
      // the prefix is used in tool names and resource URIs
      errors.println("The '" + PREFIX + "' option must start with a letter and contain only letters, digits, '_' or '-'");
      result = false;
    }

    if (!verifyNonNegativeInt(MAX_ROWS, errors)) {
      result = false;
    }
    if (!verifyNonNegativeInt(QUERY_TIMEOUT, errors)) {
      result = false;
    }

    if (!isNullOrEmpty(getLicenseJar()) && !new File(getLicenseJar()).exists()) {
      errors.println("The '" + LICENSE_JAR + "' option is not a valid JAR file");
      result = false;
    }

    if (isNullOrEmpty(getDriverJar())) {
      errors.println("The '" + DRIVER_JAR + "' option is missing");
      result = false;
    } else if (result && !verifyDriverLoad(errors)) {
      result = false;
    }

    if (isNullOrEmpty(getJdbcUrl())) {
      errors.println("The '" + JDBC_URL + "' option is missing");
      result = false;
    } else if (result && !verifyJdbcUrl(errors)) {
      result = false;
    }
    return result;
  }

  public String getServerName() {
    return this.props.getProperty(SERVER_NAME, this.getPrefix());
  }
  public String getServerVersion() {
    return "1.0";
  }
  public String getPrefix() {
    return this.props.getProperty(PREFIX);
  }
  public String getMcpScheme() {
    // URI schemes cannot contain '_'
    return getPrefix().toLowerCase().replace('_', '-') + "://";
  }
  public String getDriver() {
    return this.props.getProperty(DRIVER, DEFAULT_DRIVER);
  }
  public String getDriverJar() {
    return this.props.getProperty(DRIVER_JAR);
  }
  public String getLicenseJar() {
    return this.props.getProperty(LICENSE_JAR);
  }
  public String getUser() {
    return this.props.getProperty(USER);
  }
  public String getPassword() {
    return this.props.getProperty(PASSWORD);
  }
  public String getJdbcUrl() {
    return this.props.getProperty(JDBC_URL);
  }
  public List<Table> getTables() throws SQLException {
    String tables = this.props.getProperty(TABLES);
    if (isNullOrEmpty(tables)) {
      return new ArrayList<>();
    }
    List<Table> entries = Table.parseList(tables);
    return completeTableList(entries);
  }
  public String getIdentifierQuotes() {
    return this.sqlInfo.getProperty(ID_QUOTE_OPEN_CHAR)
        + this.sqlInfo.getProperty(ID_QUOTE_CLOSE_CHAR);
  }
  public boolean supportsMultipleCatalogs() {
    String val = this.sqlInfo.getProperty(SUPPORTS_MULTIPLE_CATALOGS);
    return val.equalsIgnoreCase("YES");
  }
  public boolean supportsMultipleSchemas() {
    String val = this.sqlInfo.getProperty(SUPPORTS_MULTIPLE_SCHEMAS);
    return val.equalsIgnoreCase("YES");
  }
  public String defaultCatalog() {
    return this.defCatalog;
  }
  public String defaultSchema() {
    return this.defSchema;
  }

  public String getLogFile() {
    return this.props.getProperty(LOG_FILE);
  }

  /** Maximum rows returned by a query; 0 means no limit. */
  public int getMaxRows() {
    return getInt(MAX_ROWS, DEFAULT_MAX_ROWS);
  }

  /** Query timeout in seconds; 0 means no timeout. */
  public int getQueryTimeout() {
    return getInt(QUERY_TIMEOUT, DEFAULT_QUERY_TIMEOUT);
  }

  private int getInt(String key, int defaultValue) {
    String value = this.props.getProperty(key);
    return isNullOrEmpty(value) ? defaultValue : Integer.parseInt(value.trim());
  }

  private boolean verifyNonNegativeInt(String key, PrintStream errors) {
    String value = this.props.getProperty(key);
    if (isNullOrEmpty(value)) {
      return true;
    }
    try {
      if (Integer.parseInt(value.trim()) >= 0) {
        return true;
      }
    } catch (NumberFormatException ex) {
    }
    errors.println("The '" + key + "' option must be a whole number of 0 or more");
    return false;
  }

  public String quoteIdentifier(String id) {
    String open = this.sqlInfo.getProperty(ID_QUOTE_OPEN_CHAR);
    String close = this.sqlInfo.getProperty(ID_QUOTE_CLOSE_CHAR);
    // TODO: Properly escape things
    return open + id + close;
  }

  public Connection newConnection() throws SQLException {
    Properties info = new Properties();
    if (!isNullOrEmpty(getUser())) {
      info.setProperty("user", getUser());
    }
    if (!isNullOrEmpty(getPassword())) {
      info.setProperty("password", getPassword());
    }
    Connection cn = this.driver.connect(this.getJdbcUrl(), info);
    if (cn == null) {
      // Driver.connect() returns null when the URL is meant for a different driver
      throw new SQLException("The '" + JDBC_URL + "' option is not accepted by the driver "
          + "(expected jdbc:db2://host:port/database)");
    }
    return cn;
  }

  private boolean verifyDriverLoad(PrintStream errors) {
    if (!new File(getDriverJar()).exists()) {
      errors.println("The '" + DRIVER_JAR + "' option is not a valid JAR file");
      return false;
    }
    try {
      loadDriver();
      return true;
    } catch (Throwable t) {
      String msg = t.getClass().getName() + ": " + t.getMessage();
      errors.println("Attempting to load the JDBC driver failed: " + msg);
    }
    return false;
  }

  private boolean verifyJdbcUrl(PrintStream errors) {
    try {
      try (Connection cn = newConnection()) {
      }
      return true;
    } catch ( SQLException ex ) {
      errors.println("Failed to open JDBC connection: " + ex.getMessage());
    }
    return false;
  }

  private void loadDriver() throws Exception {
    // db2jcc4 looks for the db2jcc_license_*.jar on its own class path
    List<URL> jars = new ArrayList<>();
    jars.add(new File(this.getDriverJar()).toURI().toURL());
    if (!isNullOrEmpty(this.getLicenseJar())) {
      jars.add(new File(this.getLicenseJar()).toURI().toURL());
    }
    URLClassLoader ucl = new URLClassLoader(
        jars.toArray(new URL[0]),
        this.getClass().getClassLoader()
    );
    Class dc = ucl.loadClass(this.getDriver());
    this.driver = (Driver)dc.getDeclaredConstructor().newInstance();

    loadSqlInfo();
  }

  private void loadSqlInfo() throws SQLException {
    try (Connection cn = newConnection()) {
      retrieveSqlInfo(cn);
      if (!this.supportsMultipleCatalogs()) {
        if (!this.supportsMultipleSchemas()) {
          retrieveDefaultCatalogAndSchema(cn);
        } else {
          retrieveDefaultCatalog(cn);
        }
      }
    }
  }

  private void retrieveDefaultCatalogAndSchema(Connection cn) throws SQLException {
    retrieveDefaultCatalog(cn);
    try (Statement st = cn.createStatement()) {
      try (ResultSet rs = st.executeQuery("VALUES CURRENT SCHEMA")) {
        if (rs.next()) {
          this.defSchema = rs.getString(1).trim();
        }
      }
    }
  }

  private void retrieveDefaultCatalog(Connection cn) throws SQLException {
    // DB2 has no catalogs, so getCatalogs() may be empty; use the connection's own
    this.defCatalog = cn.getCatalog();
  }

  private void retrieveSqlInfo(Connection cn) throws SQLException {
    DatabaseMetaData meta = cn.getMetaData();
    String quote = meta.getIdentifierQuoteString();
    // per JDBC, a single space means quoting is not supported
    if (quote == null || quote.isBlank()) {
      quote = "";
    }
    this.sqlInfo.put(ID_QUOTE_OPEN_CHAR, quote);
    this.sqlInfo.put(ID_QUOTE_CLOSE_CHAR, quote);
    this.sqlInfo.put(SUPPORTS_MULTIPLE_CATALOGS, meta.supportsCatalogsInDataManipulation() ? "YES" : "NO");
    this.sqlInfo.put(SUPPORTS_MULTIPLE_SCHEMAS, meta.supportsSchemasInDataManipulation() ? "YES" : "NO");
  }

  private List<Table> completeTableList(List<Table> list) throws SQLException {
    List<Table> result = new ArrayList<>();
    try (Connection cn = newConnection()) {
      DatabaseMetaData meta = cn.getMetaData();
      // Not the most efficient, but oh well
      for (Table t : list) {
        addMatchingTables(t, meta, result);
      }
    }
    return result;
  }

  private void addMatchingTables(Table t, DatabaseMetaData meta, List<Table> result) throws SQLException {
    String catalog = t.hasCatalog() ? t.catalog() : null;
    String schema = t.hasSchema() ? t.schema() : null;
    String name = t.name();
    try (ResultSet rs = meta.getTables(catalog, schema, name, null)) {
      while (rs.next()) {
        catalog = rs.getString("TABLE_CAT");
        schema = rs.getString("TABLE_SCHEM");
        name = rs.getString("TABLE_NAME");
        result.add(new Table(catalog, schema, name));
      }
    }
  }
}
