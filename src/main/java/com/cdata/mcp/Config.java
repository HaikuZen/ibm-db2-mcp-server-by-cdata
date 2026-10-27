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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
  private static final String POOL_SIZE = "PoolSize";
  private static final int DEFAULT_POOL_SIZE = 4;
  private static final int DEFAULT_MAX_ROWS = 1000;
  private static final int DEFAULT_QUERY_TIMEOUT = 60;
  private static final String DEFAULT_DRIVER = "com.ibm.db2.jcc.DB2Driver";

  // ${NAME} reads an environment variable; $${NAME} is a literal ${NAME}
  private static final Pattern VARIABLE = Pattern.compile("\\$(\\$)?\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

  // instances using the same JARs and driver class share one loaded driver
  private static final Map<List<String>, Driver> DRIVERS = new HashMap<>();

  private Properties props = new Properties();
  private List<String> variableErrors = new ArrayList<>();
  private Driver driver;
  // discovered from the driver's metadata
  private String identifierQuote;
  private boolean multipleCatalogs;
  private boolean multipleSchemas;
  private ConnectionPool pool;
  private String defCatalog;
  private String defSchema;

  public Config() {
  }

  private Config(Properties props, Map<String, String> env) {
    for (String key : props.stringPropertyNames()) {
      this.props.setProperty(key, expandVariables(key, props.getProperty(key), env));
    }
  }

  /**
   * Loads one Config per DB2 instance. If the file has no 'Instances' list it describes
   * a single instance. Otherwise each listed instance reads its settings from keys
   * prefixed with its name (e.g. prod.JdbcUrl), falling back to the unprefixed keys,
   * and its Prefix defaults to the instance name.
   */
  public static List<Config> loadAll(String filepath) throws IOException {
    return loadAll(filepath, System.getenv());
  }

  /** Like {@link #loadAll(String)}, resolving ${NAME} references from the given variables. */
  public static List<Config> loadAll(String filepath, Map<String, String> env) throws IOException {
    Properties all = new Properties();
    try (FileInputStream fis = new FileInputStream(filepath)) {
      all.load(fis);
    }
    List<Config> result = new ArrayList<>();
    String instances = all.getProperty(INSTANCES);
    if (isNullOrEmpty(instances)) {
      result.add(new Config(all, env));
      return result;
    }
    List<String> names = new ArrayList<>();
    for (String name : instances.split(",")) {
      if (!name.trim().isEmpty()) {
        names.add(name.trim());
      }
    }
    for (String name : names) {
      String keyPrefix = name + ".";
      Properties merged = new Properties();
      // shared settings only: another instance's settings (and their variables) don't apply here
      for (String key : all.stringPropertyNames()) {
        if (names.stream().noneMatch(n -> key.startsWith(n + "."))) {
          merged.setProperty(key, all.getProperty(key));
        }
      }
      merged.setProperty(PREFIX, name);
      for (String key : all.stringPropertyNames()) {
        if (key.startsWith(keyPrefix)) {
          merged.setProperty(key.substring(keyPrefix.length()), all.getProperty(key));
        }
      }
      result.add(new Config(merged, env));
    }
    return result;
  }

  private String expandVariables(String key, String value, Map<String, String> env) {
    Matcher m = VARIABLE.matcher(value);
    StringBuilder expanded = new StringBuilder();
    while (m.find()) {
      String replacement;
      if (m.group(1) != null) {
        replacement = "${" + m.group(2) + "}";
      } else {
        replacement = env.get(m.group(2));
        if (replacement == null) {
          this.variableErrors.add("The '" + key + "' option uses the environment variable '"
              + m.group(2) + "', which is not set");
          replacement = "";
        }
      }
      m.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(expanded);
    return expanded.toString();
  }

  public boolean validate(PrintStream errors) {
    boolean result = true;
    for (String error : this.variableErrors) {
      errors.println(error);
      result = false;
    }
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
    if (!verifyNonNegativeInt(POOL_SIZE, errors)) {
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
    return this.identifierQuote + this.identifierQuote;
  }
  public boolean supportsMultipleCatalogs() {
    return this.multipleCatalogs;
  }
  public boolean supportsMultipleSchemas() {
    return this.multipleSchemas;
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

  /** Idle connections kept open for reuse; 0 opens a new connection for every request. */
  public int getPoolSize() {
    return getInt(POOL_SIZE, DEFAULT_POOL_SIZE);
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

  public Connection newConnection() throws SQLException {
    return this.pool.getConnection();
  }

  /** Closes the idle pooled connections. */
  public void close() {
    if (this.pool != null) {
      this.pool.close();
    }
  }

  private Connection openConnection() throws SQLException {
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
    this.driver = loadSharedDriver(this.getDriverJar(), this.getLicenseJar(), this.getDriver());
    this.pool = new ConnectionPool(this::openConnection, getPoolSize());

    loadSqlInfo();
  }

  private static synchronized Driver loadSharedDriver(String driverJar, String licenseJar, String driverClass)
      throws Exception {
    String license = isNullOrEmpty(licenseJar) ? "" : new File(licenseJar).getCanonicalPath();
    List<String> key = List.of(new File(driverJar).getCanonicalPath(), license, driverClass);
    Driver driver = DRIVERS.get(key);
    if (driver == null) {
      // db2jcc4 looks for the db2jcc_license_*.jar on its own class path
      List<URL> jars = new ArrayList<>();
      jars.add(new File(driverJar).toURI().toURL());
      if (!license.isEmpty()) {
        jars.add(new File(license).toURI().toURL());
      }
      URLClassLoader ucl = new URLClassLoader(
          jars.toArray(new URL[0]),
          Config.class.getClassLoader()
      );
      Class<?> dc = ucl.loadClass(driverClass);
      driver = (Driver)dc.getDeclaredConstructor().newInstance();
      DRIVERS.put(key, driver);
    }
    return driver;
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
    this.identifierQuote = quote;
    this.multipleCatalogs = meta.supportsCatalogsInDataManipulation();
    this.multipleSchemas = meta.supportsSchemasInDataManipulation();
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
