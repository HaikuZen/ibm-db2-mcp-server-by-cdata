---
name: ibm-db2
description: How to explore and query IBM DB2 databases (DB2 for z/OS, DB2 for Linux/UNIX/Windows, DB2 for IBM i) through the read-only IBM DB2 MCP server, whose tools are list_instances, help, and per-instance tools ending in _list_schemas, _get_tables, _table_stats, _get_columns, _describe_table, _search_columns and _run_query (e.g. prod_run_query). Use this skill whenever those tools are available and the user asks anything about data that may live in DB2 - counts, reports, trends, lookups, "which tables have...", "how big is...", comparing prod and test - or mentions DB2, Db2, the mainframe, z/OS, AS/400, iSeries, IBM i, a DB2 schema or table name, SQLCODE/SQLSTATE errors, or wants SQL written for DB2, even if they never say "DB2" or "MCP". Also use it when the DB2 MCP server seems missing or not connected, or the user is configuring or testing it (over stdio or HTTP).
---

# Querying IBM DB2 through the MCP server

The server gives read-only SQL access to one or more DB2 databases ("instances"). Every instance has its own set of tools, named `<instance>_<tool>`, where `<instance>` is the prefix shown by `list_instances` (for example `prod_run_query`, `test_get_tables`). Some clients show the tools with an extra server prefix; match on the suffix.

| Tool | Use it to |
|---|---|
| `list_instances` | See every instance: prefix, platform (z/OS, LUW, IBM i), version, database, default schema, row limit, timeout |
| `help` | Read the server's own instructions, with the live list of instances and limits |
| `<i>_list_schemas` | List schemas with their number of tables; `pattern` narrows it (`SALES%`) |
| `<i>_get_tables` | List the tables of a schema |
| `<i>_table_stats` | Row count, size and statistics date per table, from the catalog, largest first |
| `<i>_search_columns` | Find which tables have a column, by name pattern (`%CUST%ID%`) |
| `<i>_get_columns` | Column names and types of a table |
| `<i>_describe_table` | Columns, primary key, foreign keys, referencing keys and indexes of a table |
| `<i>_run_query` | Run one `SELECT`, `WITH` or `VALUES` statement |

Results are CSV with a header line. Extra text items after the CSV are notes from the server (rows cut off, schemas hidden, default schema): read them, they change what the CSV means.

## 0. How the server is connected

The `Transport` setting in the server's `.prp` file chooses one of two ways:

- **stdio** (the default): the MCP client starts the server itself (`command` and `args` in the client's MCP configuration, typically `java -jar CDataMCP-jar-with-dependencies.jar /path/to/file.prp`) and exchanges JSON-RPC messages over its stdin and stdout. There is no port or URL, so `curl` or a `url` / `serverUrl` entry cannot reach it, and a copy started by hand in a terminal is not the one the client uses. Closing its stdin stops it; that is normal at the end of a session.
- **http** (`Transport=http`): the server is started separately and keeps running; clients connect to its URL, `http://HttpHost:HttpPort/HttpPath` (default `http://127.0.0.1:8080/mcp`, MCP Streamable HTTP), with a `url` / `serverUrl` entry. If `HttpToken` is set, the client must send `Authorization: Bearer <token>`. The server prints its URL on stderr when it starts.

If none of the tools above (`list_instances`, `help`, `*_run_query`, ...) are available to you, the client is not connected to the server; usually it exited at startup because of a configuration error, or the client points at the wrong place. Do not pretend to query DB2, and do not try to reach it another way. Tell the user, and point them at the usual causes:

- The client entry must match the transport: `command`/`args` for stdio, the server's URL for http. A `command` entry for an http server starts a copy that never answers on stdin (and fails if another copy already has the port).
- With stdio, use absolute paths to `java`, the JAR and the `.prp` file, and set every `${NAME}` variable used in the `.prp` in the entry's `env`: clients started from a desktop launcher do not inherit the user's shell variables.
- With http, the server must be running; `401` means a missing or wrong token, `403` a browser page from an origin not in `HttpAllowedOrigins`.
- `DriverPath`, `LicensePath` and `LogFile` in the `.prp` must be absolute paths.
- Every instance listed in `Instances=` must be reachable: the server checks each connection at startup and exits if one fails, printing the reason on stderr (and in `LogFile`, if set).
- After fixing the configuration, restart or reconnect the server in the client and start a new conversation, so the tools are loaded.

To test a stdio server outside the client, pipe the protocol messages into it, keeping stdin open long enough for the replies:

```bash
( printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  sleep 10 ) | java -jar /path/to/CDataMCP-jar-with-dependencies.jar /path/to/file.prp
```

A working server prints one JSON line per request; a misconfigured one prints an error and exits. To test an http server, send the `initialize` request with `curl`:

```bash
curl -i http://127.0.0.1:8080/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}'
```

A working server answers `200` with an `Mcp-Session-Id` header.

## 1. Orient yourself first

Call `list_instances` once at the start of a conversation. It is cheap and tells you three things you need before writing any SQL:

- **Which instance to use.** With several instances (often prod and test), use the one the user named. If they did not and the choice matters, ask, or say which one you used. Never mix rows from different instances in one answer without saying so.
- **The platform.** z/OS, LUW and IBM i have different catalogs, collation and quirks. Read the matching reference before writing catalog queries or when something behaves oddly. The references are in the `references/` folder next to this `SKILL.md` (the same directory you read this file from); if your client did not tell you that directory, look for the `ibm-db2` skill folder in the workspace's or user's skills directory:
  - z/OS → `references/zos.md`
  - Linux, UNIX, Windows → `references/luw.md`
  - IBM i → `references/ibmi.md`
- **The default schema and limits.** With a default schema, tools that take `schema` use it when you leave it out, and unqualified table names in queries resolve to it. `MaxRows` caps every result; `QueryTimeout` cancels slow queries.

`help` repeats much of this skill plus the live instance list; call it if the tools behave differently from what is described here.

## 2. Find the data before querying it

Guessing table and column names wastes calls and produces `SQLCODE -204` / `-206` errors. DB2 systems, especially mainframe ones, often have cryptic 8-character names (`CUSTMAST`, `ORDHDR`), so discover them:

1. **Schemas**: if there is no default schema, or the data may be elsewhere, `list_schemas`. IBM's system schemas (SYSIBM, SYSCAT, ...) are hidden unless you pass a `pattern`.
2. **Tables**: `get_tables` with `schema`. Pass `%` as `schema` to list every non-system schema, but expect a long, slow result on big systems.
3. **Concepts, not names**: when the user talks about "customers" or "invoice dates", `search_columns` with patterns like `%CUST%`, `%INV%DT%`, `%DATE%` finds the tables faster than reading table lists. Column remarks often explain cryptic names.
4. **Structure**: `describe_table` on each table you plan to use. Use the primary and foreign keys for joins instead of guessing from names; use the indexes to decide which columns to filter on.
5. **Size**: `table_stats` before querying a table you have not seen. Row counts come from the last RUNSTATS, so they are estimates and may be old (`StatsTime`); `Rows = -1` means statistics were never collected, not that the table is empty.

Names are stored in upper case. Pass `SALES`, not `sales`, to every `schema` and `table` argument; lower-case names only exist if they were created quoted.

## 3. Write DB2 SQL

The server accepts one `SELECT`, `WITH` or `VALUES` statement per call. Anything else is rejected before it reaches DB2, and every query runs in a transaction that is rolled back.

**Dialect** - these differ from PostgreSQL, MySQL and SQL Server and are the most common causes of `SQLCODE -104` (syntax error):

- Limit rows with `FETCH FIRST 10 ROWS ONLY`, not `LIMIT` or `TOP`. Paging: `OFFSET 20 ROWS FETCH FIRST 10 ROWS ONLY` (not on older z/OS).
- No trailing semicolon, one statement only.
- Qualify tables as `SCHEMA.TABLE` unless the instance has a default schema. Quote only mixed-case names: `"MySchema"."MyTable"`.
- Strings use single quotes; concatenate with `||`. Double quotes are identifiers, never strings.
- Constants without a table: `VALUES CURRENT DATE` or `SELECT CURRENT TIMESTAMP FROM SYSIBM.SYSDUMMY1`.
- Dates: `CURRENT DATE - 30 DAYS`, `YEAR(col)`, `MONTH(col)`, `DAYS(d2) - DAYS(d1)` for a difference in days, `DATE(ts)`, `TIMESTAMP(d, t)`.
- `CASE WHEN ... THEN ... END`, `COALESCE`, `NULLIF`, `TRIM`, `UPPER`, `SUBSTR(s, start, len)` work as expected.

**Data traps** - these return wrong numbers without an error, so check for them:

- **Integer arithmetic truncates.** `AVG(int_col)` and `int_col / 3` return integers. Use `AVG(DECIMAL(col, 15, 2))` or `AVG(DOUBLE(col))`.
- **Big counts.** `COUNT(*)` is INTEGER and overflows (`SQLCODE -802`) above about 2.1 billion; use `COUNT_BIG(*)` on very large tables.
- **CHAR padding.** Fixed-length `CHAR` columns are padded with blanks. Equality ignores the padding, but `LIKE '%XYZ'`, `LENGTH()` and concatenation do not: use `RTRIM(col)`.
- **Dates stored as numbers or text.** Legacy tables often keep dates as `DECIMAL(8,0)` `YYYYMMDD`, `CHAR(8)`, or `DECIMAL(7,0)` `CYYMMDD`, with `0` for "no date". `describe_table` shows the type. Convert before date arithmetic, and say in the answer that you did:
  - `DECIMAL(8,0)`: `DATE(TIMESTAMP_FORMAT(DIGITS(NULLIF(col, 0)), 'YYYYMMDD'))`. Use `DIGITS`, not `CHAR`: `CHAR` of a decimal keeps the decimal point (and on z/OS leading zeros), which breaks the format.
  - `CHAR(8)`: `DATE(TIMESTAMP_FORMAT(NULLIF(col, '00000000'), 'YYYYMMDD'))`.
  - `CYYMMDD`: `DATE(TIMESTAMP_FORMAT(DIGITS(DECIMAL(NULLIF(col, 0) + 19000000, 8, 0)), 'YYYYMMDD'))`.
- **Case-sensitive comparisons.** `WHERE NAME = 'smith'` does not match `SMITH`. Use `UPPER(NAME) = 'SMITH'` or `UPPER(NAME) LIKE '%SMITH%'` when the case of stored values is unknown (it prevents index use, so filter on something else too on big tables).

## 4. Be a good citizen on production

These databases are often production systems shared with batch jobs and online transactions. A careless query can run for minutes, hit the `QueryTimeout`, or on z/OS be cancelled by the resource limit facility (`SQLCODE -905`).

- Aggregate in DB2 (`COUNT`, `SUM`, `GROUP BY`) rather than pulling rows to count them yourself.
- On tables with millions of rows (`table_stats`), filter on indexed columns (`describe_table`) and avoid functions on those columns in the `WHERE` clause.
- Select only the columns you need; avoid `SELECT *` on wide tables.
- Explore with `FETCH FIRST 10 ROWS ONLY` before running the full query.
- For large read-only reports where slightly stale data is fine, end the query with `WITH UR` (uncommitted read) so it does not wait for or hold locks. Mention it in the answer, because the result can include uncommitted changes.

When results are cut off at `MaxRows`, the server says so. Do not present a truncated result as complete: narrow the query or aggregate, and rerun.

## 5. When a query fails

Errors come back as text with the DB2 `SQLSTATE` and `SQLCODE`. Read them, fix the statement and retry; don't give up after one error, and don't retry the same statement unchanged.

| SQLCODE | Meaning | Usual fix |
|---|---|---|
| -104 | Syntax error | `LIMIT`/`TOP`, trailing `;`, double-quoted string, MySQL/PostgreSQL function |
| -204 | Object not found | Wrong schema, lower-case name, or table in another schema: `get_tables` / `search_columns` |
| -206 | Column not found | Check `get_columns`; quoted lower-case name; alias used in `WHERE` |
| -199, -105 | Bad keyword or string | Often `"text"` used for a string; use single quotes |
| -171, -420 | Wrong argument type, bad conversion | Cast explicitly; numeric-looking `CHAR` data with stray values |
| -802 | Arithmetic overflow | `COUNT_BIG`, `DECIMAL` with more precision |
| -811 | Scalar subquery returned several rows | Add a condition or aggregate inside the subquery |
| -551, -552 | No privilege | The server's DB2 user cannot read that object; tell the user, don't work around it |
| -905 | z/OS resource limit exceeded | Make the query cheaper: filters on indexed columns, fewer joins |
| -911, -913 | Deadlock or lock timeout | Retry once; consider `WITH UR` for reports |
| -952, -4470 | Cancelled or connection problem | Query too slow for `QueryTimeout`, or the database is unreachable |

A section of `describe_table` marked "(not available: ...)" usually means DB2's metadata stored procedures are missing (common on z/OS); the other sections are still valid, and the catalog tables in the platform reference give the same information through `run_query`.

## 6. Answer the user

- Lead with the answer in plain language, then the supporting numbers in a small table if useful.
- Say which instance and tables the answer comes from, and show the SQL you ran (briefly) so it can be checked or reused.
- State caveats that change the meaning: truncated results, catalog row counts being estimates, `WITH UR`, converted date columns, filters you assumed.
- If the user asks you to change data (`UPDATE`, `DELETE`, `INSERT`, DDL), explain that this server is read-only. You may draft the statement for them to review and run through their own tools, and point out what it would affect, using a `SELECT` with the same `WHERE` clause to show the rows first.
