# ibm-db2-mcp-server-by-cdata
CData's Model Context Protocol (MCP) Server for IBM DB2

:heavy_exclamation_mark: This project builds a read-only MCP server. For full read, write, update, delete, and action capabilities and a simplified setup, check out our [CData MCP Server for IBM DB2](https://www.cdata.com/drivers/db2/download/mcp/). 
## Purpose
This read-only MCP Server allows LLMs (like Claude Desktop) to query live IBM DB2 data using IBM's own JDBC driver (`db2jcc4.jar`, the IBM Data Server Driver for JDBC and SQLJ).

This server wraps that driver and makes IBM DB2 data available through a simple MCP interface, so LLMs can retrieve live information by asking natural language questions — no SQL required.

## Setup Guide
1. Clone the repository:
      ```bash
      git clone https://github.com/cdatasoftware/ibm-db2-mcp-server-by-cdata.git
      cd ibm-db2-mcp-server-by-cdata
      ```
2. Build the server:
      ```bash
      mvn clean install
      ``` 
      This creates the JAR file: CDataMCP-jar-with-dependencies.jar
3. Obtain the IBM Data Server Driver for JDBC and SQLJ (`db2jcc4.jar`) from [IBM Fix Central](https://www.ibm.com/support/pages/db2-jdbc-driver-versions-and-downloads), or copy it from the `java` folder of an existing DB2 client/server installation.
4. Locate the license JAR that matches your DB2 server, if one is needed:
    * **DB2 for Linux, UNIX and Windows** - `db2jcc_license_cu.jar` (shipped with the driver)
    * **DB2 for z/OS or DB2 for i** - `db2jcc_license_cisuz.jar` (shipped with DB2 Connect), unless the DB2 Connect license is activated on the server
5. Create a `.prp` file for your JDBC connection (e.g. `ibm-db2.prp`) using the following properties and format. You can start from [`ibm-db2.example.prp`](./ibm-db2.example.prp), which documents every setting:
    * **Prefix** - a prefix to be used for the tools exposed; it must start with a letter and contain only letters, digits, `_` or `-`
    * **ServerName** - a name for your server
    * **ServerVersion** - a version for your server
    * **DriverPath** - the full path to `db2jcc4.jar`
    * **LicensePath** - (optional) the full path to the `db2jcc_license_*.jar` file
    * **DriverClass** - (optional) the JDBC Driver Class, defaults to `com.ibm.db2.jcc.DB2Driver`
    * **JdbcUrl** - the JDBC URL, in the form `jdbc:db2://host:port/database`. Driver properties can be appended as `:prop1=value1;prop2=value2;` (e.g. `:sslConnection=true;currentSchema=MYSCHEMA;`). Adding `retrieveMessagesFromServerOnGetMessage=true;` makes DB2 error messages readable for the AI client
    * **User** / **Password** - the DB2 credentials
    * **DefaultSchema** - (optional) the schema used by `get_tables` and `get_columns` when no `schema` is given, and the current schema for queries, so unqualified table names resolve to it. Recommended on DB2 for z/OS, where listing every schema is slow and user schemas such as `SYSADM` start with `SYS`. Use upper case unless the schema name was created quoted
    * **SystemSchemas** - (optional) comma-separated schemas left out when the tools list all schemas; `%` matches any characters. Defaults to IBM's schemas: `SYSIBM%, SYSCAT, SYSFUN, SYSSTAT, SYSPROC, SYSPUBLIC, SYSTOOLS, SYSACCEL, NULLID, SQLJ`, so user schemas such as `SYSADM` are listed. An empty value hides nothing. For example, use `Q%, SYSIBM%, SYSPROC, SYSTOOLS` on IBM i
    * **MaxRows** - (optional) the maximum number of rows a query returns, defaults to `1000`; `0` means no limit
    * **QueryTimeout** - (optional) the query timeout in seconds, defaults to `60`; `0` means no timeout
    * **PoolSize** - (optional) the number of idle connections kept open for reuse, defaults to `4`; `0` opens a new connection for every request
    * **Tables** - leave blank to access all data, otherwise you can explicitly declare the tables you wish to create access for (e.g. `MYSCHEMA.EMPLOYEE, MYSCHEMA.DEPARTMENT`). DB2 stores unquoted names in upper case.
      ```env
      Prefix=db2
      ServerName=DB2
      ServerVersion=1.0
      DriverPath=/PATH/TO/db2jcc4.jar
      LicensePath=/PATH/TO/db2jcc_license_cu.jar
      JdbcUrl=jdbc:db2://localhost:50000/SAMPLE
      User=db2inst1
      Password=secret
      Tables=
      ```
      > **Note:** Use a DB2 user that only has `SELECT` privileges. The server rejects anything other than `SELECT`, `WITH` and `VALUES` statements and rolls back every query's transaction, but a stored procedure or function that commits on its own can get around that. DB2 permissions are the real safeguard.

      > **Tip:** To keep secrets out of the file, any value can reference an environment variable as `${NAME}`, e.g. `Password=${DB2_PASSWORD}` or `JdbcUrl=jdbc:db2://${DB2_HOST}:50000/SALES`. Plain values work as before. The server will not start if a referenced variable is not set. Write `$${NAME}` for a literal `${NAME}`.
6. (Optional) To expose several DB2 instances from one server, list them in **Instances** and prefix each instance's properties with its name. Properties without a prefix are shared by all instances, and each instance's **Prefix** defaults to its name, so its tools are named e.g. `prod_run_query` and `test_run_query`:
      ```env
      ServerName=DB2
      ServerVersion=1.0
      DriverPath=/PATH/TO/db2jcc4.jar
      LicensePath=/PATH/TO/db2jcc_license_cu.jar
      User=db2reader
      Instances=prod,test

      prod.JdbcUrl=jdbc:db2://prod-host:50000/SALES
      prod.Password=secret1

      test.JdbcUrl=jdbc:db2://test-host:50000/SALES
      test.User=tester
      test.Password=secret2
      test.Tables=SALES.ORDERS
      ```
      > **Note:** Every instance must be reachable when the server starts; if any instance fails to connect, the server exits.

## Using the Server with Claude Desktop
1. Create the config file for Claude Desktop ( claude_desktop_config.json) to add the new MCP server, using the format below. If the file already exists, add the entry to the `mcpServers` in the config file.

      **Windows**
      ```json
      {
        "mcpServers": {
          "{classname_dash}": {
            "command": "PATH\\TO\\java.exe",
            "args": [
              "-jar",
              "PATH\\TO\\CDataMCP-jar-with-dependencies.jar",
              "PATH\\TO\\ibm-db2.prp"
            ]
          },
          ...
        }
      }
      ```
      
      **Linux/Mac**
      ```json
      {
        "mcpServers": {
          "{classname_dash}": {
            "command": "/PATH/TO/java",
            "args": [
              "-jar",
              "/PATH/TO/CDataMCP-jar-with-dependencies.jar",
              "/PATH/TO/ibm-db2.prp"
            ]
          },
          ...
        }
      }
      ```
      If the `.prp` file references environment variables (e.g. `Password=${DB2_PASSWORD}`), you can set them for the server in the same entry:
      ```json
      "{classname_dash}": {
        "command": "/PATH/TO/java",
        "args": ["-jar", "/PATH/TO/CDataMCP-jar-with-dependencies.jar", "/PATH/TO/ibm-db2.prp"],
        "env": {
          "DB2_PASSWORD": "secret"
        }
      }
      ```
      If needed, copy the config file to the appropriate directory (Claude Desktop as the example).
      **Windows**
      ```bash
      cp C:\PATH\TO\claude_desktop_config.json %APPDATA%\Claude\claude_desktop_config.json
      ```
      **Linux/Mac**
      ```bash
      cp /PATH/TO/claude_desktop_config.json /Users/{user}/Library/Application\ Support/Claude/claude_desktop_config.json'
      ```
2. Run or refresh your client (Claude Desktop).
   
> **Note:** You may need to fully exit or quit your Claude Desktop client and re-open it for the MCP Servers to appear.

## Running the Server
1. Run the follow the command to run the MCP Server on its own
      ```bash
      java -jar /PATH/TO/CDataMCP-jar-with-dependencies.jar /PATH/TO/ibm-db2.prp
> **Note:** The server uses `stdio` so can only be used with clients that run on the same machine as the server.
## Usage Details
Once the MCP Server is configured, the AI client will be able to use the built-in tools to read the underlying data. In general, you do not need to call the tools explicitly. Simply ask the client to answer questions about the underlying data system. For example:
* "What is the correlation between my closed won opportunities and the account industry?"
* "How many open tickets do I have in the SUPPORT project?"
* "Can you tell me what calendar events I have today?"

The list of tools available and their descriptions follow:
### Tools & Descriptions
In the definitions below, `{servername}` refers to the name of the MCP Server in the config file (e.g. `{classname_dash}` above).
* `{servername}_get_tables` - Retrieves a list of tables available in the data source. Use the `{servername}_get_columns` tool to list available columns on a table. When `schema` is omitted, the `DefaultSchema` is listed if one is configured (pass `%` to list all schemas); otherwise all schemas are listed except the DB2 system schemas (see `SystemSchemas`), which are only listed when the `schema` argument names one. The output of the tool will be returned in CSV format, with the first line containing column headers.
* `{servername}_get_columns` - Retrieves a list of columns for a table. Use the `{servername}_get_tables` tool to get a list of available tables. The output of the tool will be returned in CSV format, with the first line containing column headers.
* `{servername}_list_schemas` - Lists the schemas with the number of tables and views in each. The DB2 system schemas (see `SystemSchemas`) are only listed when a `pattern` such as `SYS%` is given.
* `{servername}_describe_table` - Describes a table: columns (type, size, nullability, default), primary key, foreign keys, the foreign keys that reference it, and indexes. When no `schema` is given and there is no `DefaultSchema`, the schema is found from the table name.
* `{servername}_search_columns` - Finds columns by name pattern (e.g. `%CUST%ID%`) and lists the tables that contain them. Results are capped at `MaxRows`.
* `{servername}_table_stats` - Row count, pages, size in KB and statistics date for each table in a schema, read from the DB2 catalog (`SYSIBM.SYSTABLES` on z/OS, `SYSCAT.TABLES` on Linux/UNIX/Windows, `QSYS2.SYSTABLESTAT` on IBM i) without reading the tables. On z/OS and LUW the figures come from the last RUNSTATS.
* `{servername}_run_query` - Execute a SQL SELECT query

The server also has two tools that cover all instances:
* `list_instances` - Lists the configured DB2 instances: tool prefix, platform (z/OS, LUW or IBM i), product version, host, port, database, default schema and limits. Credentials and JDBC URL properties are never included.
* `help` - Returns instructions for the AI client: each instance's tools, the recommended workflow, the read-only rules and limits, and DB2 SQL tips for the platforms in use. The same text is sent as the server's MCP `instructions` when a client connects.

## JSON-RPC Request Examples
If you are scripting out the requests sent to the MCP Server instead of using an AI Client (e.g. Claude), then you can refer to the JSON payload examples below – following the JSON-RPC 2.0 specification - when calling the available tools. 

#### ibm_db2_get_tables
```json
{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
        "name": "ibm_db2_get_tables",
        "arguments": {}
    }
}
```

#### ibm_db2_get_columns
```json
{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
        "name": "ibm_db2_get_columns",
        "arguments": {
            "schema": "MYSCHEMA",
            "table":  "EMPLOYEE"
        }
    }
}
```

#### ibm_db2_run_query
```json
{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
        "name": "ibm_db2_run_query",
        "arguments": {
            "sql":  "SELECT * FROM MYSCHEMA.EMPLOYEE WHERE WORKDEPT = 'A00' FETCH FIRST 10 ROWS ONLY"
        }
    }
}
```

## Troubleshooting
1. If you cannot see your MCP Server in Claude Desktop, be sure that you have fully quit Claude Desktop (Windows: use the Task Manager, Mac: use the Activity Monitor)
2. If Claude Desktop is unable to retrieve data, be sure that you have configured your connection properly. Check the `JdbcUrl`, `User` and `Password` in the property (.prp) file. A `-4230` license error means the `LicensePath` JAR is missing or does not match your DB2 server.
3. If you are having trouble connecting to DB2, check the [IBM JDBC driver documentation](https://www.ibm.com/docs/en/db2/11.5?topic=apis-jdbc).
4. If you are having trouble using the MCP server, or have any other feedback, join the [CData Community](https://community.cdata.com).

## License
This MCP server is licensed under the MIT License. This means you are free to use, modify, and distribute the software, subject to the terms and conditions of the MIT License. For more details, please see the [LICENSE](./LICENSE) file in the project repository.

## All Supported Sources
<table>
<tr><td>Access</td><td>Act CRM</td><td>Act-On</td><td>Active Directory</td></tr>
<tr><td>ActiveCampaign</td><td>Acumatica</td><td>Adobe Analytics</td><td>Adobe Commerce</td></tr>
<tr><td>ADP</td><td>Airtable</td><td>AlloyDB</td><td>Amazon Athena</td></tr>
<tr><td>Amazon DynamoDB</td><td>Amazon Marketplace</td><td>Amazon S3</td><td>Asana</td></tr>
<tr><td>Authorize.Net</td><td>Avalara AvaTax</td><td>Avro</td><td>Azure Active Directory</td></tr>
<tr><td>Azure Analysis Services</td><td>Azure Data Catalog</td><td>Azure Data Lake Storage</td><td>Azure DevOps</td></tr>
<tr><td>Azure Synapse</td><td>Azure Table</td><td>Basecamp</td><td>BigCommerce</td></tr>
<tr><td>BigQuery</td><td>Bing Ads</td><td>Bing Search</td><td>Bitbucket</td></tr>
<tr><td>Blackbaud FE NXT</td><td>Box</td><td>Bullhorn CRM</td><td>Cassandra</td></tr>
<tr><td>Certinia</td><td>Cloudant</td><td>CockroachDB</td><td>Confluence</td></tr>
<tr><td>Cosmos DB</td><td>Couchbase</td><td>CouchDB</td><td>CSV</td></tr>
<tr><td>Cvent</td><td>Databricks</td><td>DB2</td><td>DocuSign</td></tr>
<tr><td>Dropbox</td><td>Dynamics 365</td><td>Dynamics 365 Business Central</td><td>Dynamics CRM</td></tr>
<tr><td>Dynamics GP</td><td>Dynamics NAV</td><td>eBay</td><td>eBay Analytics</td></tr>
<tr><td>Elasticsearch</td><td>Email</td><td>EnterpriseDB</td><td>Epicor Kinetic</td></tr>
<tr><td>Exact Online</td><td>Excel</td><td>Excel Online</td><td>Facebook</td></tr>
<tr><td>Facebook Ads</td><td>FHIR</td><td>Freshdesk</td><td>FTP</td></tr>
<tr><td>GitHub</td><td>Gmail</td><td>Google Ad Manager</td><td>Google Ads</td></tr>
<tr><td>Google Analytics</td><td>Google Calendar</td><td>Google Campaign Manager 360</td><td>Google Cloud Storage</td></tr>
<tr><td>Google Contacts</td><td>Google Data Catalog</td><td>Google Directory</td><td>Google Drive</td></tr>
<tr><td>Google Search</td><td>Google Sheets</td><td>Google Spanner</td><td>GraphQL</td></tr>
<tr><td>Greenhouse</td><td>Greenplum</td><td>HarperDB</td><td>HBase</td></tr>
<tr><td>HCL Domino</td><td>HDFS</td><td>Highrise</td><td>Hive</td></tr>
<tr><td>HubDB</td><td>HubSpot</td><td>IBM Cloud Data Engine</td><td>IBM Cloud Object Storage</td></tr>
<tr><td>IBM Informix</td><td>Impala</td><td>Instagram</td><td>JDBC-ODBC Bridge</td></tr>
<tr><td>Jira</td><td>Jira Assets</td><td>Jira Service Management</td><td>JSON</td></tr>
<tr><td>Kafka</td><td>Kintone</td><td>LDAP</td><td>LinkedIn</td></tr>
<tr><td>LinkedIn Ads</td><td>MailChimp</td><td>MariaDB</td><td>Marketo</td></tr>
<tr><td>MarkLogic</td><td>Microsoft Dataverse</td><td>Microsoft Entra ID</td><td>Microsoft Exchange</td></tr>
<tr><td>Microsoft OneDrive</td><td>Microsoft Planner</td><td>Microsoft Project</td><td>Microsoft Teams</td></tr>
<tr><td>Monday.com</td><td>MongoDB</td><td>MYOB AccountRight</td><td>MySQL</td></tr>
<tr><td>nCino</td><td>Neo4J</td><td>NetSuite</td><td>OData</td></tr>
<tr><td>Odoo</td><td>Office 365</td><td>Okta</td><td>OneNote</td></tr>
<tr><td>Oracle</td><td>Oracle Eloqua</td><td>Oracle Financials Cloud</td><td>Oracle HCM Cloud</td></tr>
<tr><td>Oracle Sales</td><td>Oracle SCM</td><td>Oracle Service Cloud</td><td>Outreach.io</td></tr>
<tr><td>Parquet</td><td>Paylocity</td><td>PayPal</td><td>Phoenix</td></tr>
<tr><td>PingOne</td><td>Pinterest</td><td>Pipedrive</td><td>PostgreSQL</td></tr>
<tr><td>Power BI XMLA</td><td>Presto</td><td>Quickbase</td><td>QuickBooks</td></tr>
<tr><td>QuickBooks Online</td><td>QuickBooks Time</td><td>Raisers Edge NXT</td><td>Reckon</td></tr>
<tr><td>Reckon Accounts Hosted</td><td>Redis</td><td>Redshift</td><td>REST</td></tr>
<tr><td>RSS</td><td>Sage 200</td><td>Sage 300</td><td>Sage 50 UK</td></tr>
<tr><td>Sage Cloud Accounting</td><td>Sage Intacct</td><td>Salesforce</td><td>Salesforce Data Cloud</td></tr>
<tr><td>Salesforce Financial Service Cloud</td><td>Salesforce Marketing</td><td>Salesforce Marketing Cloud Account Engagement</td><td>Salesforce Pardot</td></tr>
<tr><td>Salesloft</td><td>SAP</td><td>SAP Ariba Procurement</td><td>SAP Ariba Source</td></tr>
<tr><td>SAP Business One</td><td>SAP BusinessObjects BI</td><td>SAP ByDesign</td><td>SAP Concur</td></tr>
<tr><td>SAP Fieldglass</td><td>SAP HANA</td><td>SAP HANA XS Advanced</td><td>SAP Hybris C4C</td></tr>
<tr><td>SAP Netweaver Gateway</td><td>SAP SuccessFactors</td><td>SAS Data Sets</td><td>SAS xpt</td></tr>
<tr><td>SendGrid</td><td>ServiceNow</td><td>SFTP</td><td>SharePoint</td></tr>
<tr><td>SharePoint Excel Services</td><td>ShipStation</td><td>Shopify</td><td>SingleStore</td></tr>
<tr><td>Slack</td><td>Smartsheet</td><td>Snapchat Ads</td><td>Snowflake</td></tr>
<tr><td>Spark</td><td>Splunk</td><td>SQL Analysis Services</td><td>SQL Server</td></tr>
<tr><td>Square</td><td>Stripe</td><td>Sugar CRM</td><td>SuiteCRM</td></tr>
<tr><td>SurveyMonkey</td><td>Sybase</td><td>Sybase IQ</td><td>Tableau CRM Analytics</td></tr>
<tr><td>Tally</td><td>TaxJar</td><td>Teradata</td><td>Tier1</td></tr>
<tr><td>TigerGraph</td><td>Trello</td><td>Trino</td><td>Twilio</td></tr>
<tr><td>Twitter</td><td>Twitter Ads</td><td>Veeva CRM</td><td>Veeva Vault</td></tr>
<tr><td>Wave Financial</td><td>WooCommerce</td><td>WordPress</td><td>Workday</td></tr>
<tr><td>xBase</td><td>Xero</td><td>XML</td><td>YouTube Analytics</td></tr>
<tr><td>Zendesk</td><td>Zoho Books</td><td>Zoho Creator</td><td>Zoho CRM</td></tr>
<tr><td>Zoho Inventory</td><td>Zoho Projects</td><td>Zuora</td><td>... Dozens More</td></tr>
</table>

