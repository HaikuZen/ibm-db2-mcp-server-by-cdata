# DB2 for z/OS

Read this when `list_instances` shows the platform `z/OS` (product versions start with `DSN`, e.g. `DSN12015` is DB2 12).

## Catalog tables

Use these through `<i>_run_query` when the metadata tools are not enough (view definitions, column labels, filtering many tables at once). Schema columns are called `CREATOR` / `TBCREATOR`.

| Table | Key columns |
|---|---|
| `SYSIBM.SYSTABLES` | `CREATOR`, `NAME`, `TYPE`, `DBNAME`, `TSNAME`, `CARDF` (rows), `NPAGESF`, `SPACEF` (KB), `STATSTIME`, `REMARKS`, `LABEL` |
| `SYSIBM.SYSCOLUMNS` | `TBCREATOR`, `TBNAME`, `NAME`, `COLNO`, `COLTYPE`, `LENGTH`, `SCALE`, `NULLS`, `REMARKS`, `LABEL` |
| `SYSIBM.SYSINDEXES` | `CREATOR`, `NAME`, `TBCREATOR`, `TBNAME`, `UNIQUERULE` (`D` duplicates allowed, `U` unique, `P` primary key), `CLUSTERING` |
| `SYSIBM.SYSKEYS` | `IXCREATOR`, `IXNAME`, `COLNAME`, `COLSEQ`, `ORDERING` |
| `SYSIBM.SYSRELS` | `CREATOR`, `TBNAME`, `RELNAME`, `REFTBCREATOR`, `REFTBNAME` (foreign keys) |
| `SYSIBM.SYSFOREIGNKEYS` | `CREATOR`, `TBNAME`, `RELNAME`, `COLNAME`, `COLSEQ` |
| `SYSIBM.SYSVIEWS` | `CREATOR`, `NAME` and the view text |
| `SYSIBM.SYSDUMMY1` | One-row table for constant expressions |

`SYSTABLES.TYPE`: `T` table, `V` view, `A` alias, `M` materialized query table, `G` created global temporary table, `H` history table, `X` auxiliary (LOB) table.

`LABEL` often holds a readable name for cryptic tables and columns; check it alongside `REMARKS`:

```sql
SELECT NAME, COLTYPE, LENGTH, SCALE, LABEL, REMARKS
FROM SYSIBM.SYSCOLUMNS
WHERE TBCREATOR = 'SALES' AND TBNAME = 'ORDHDR'
ORDER BY COLNO
```

## Schemas and names

- Schema names are often the owner's user ID or a naming convention such as `SYSADM`, `PRODxx` or an application code. `SYSADM` is a user schema, not an IBM one, so the tools list it.
- Older applications use 8-character table and column names. Read `LABEL` and `REMARKS`, and use `search_columns` rather than guessing.
- `CURRENT SQLID` is the authorization ID for dynamic SQL; `CURRENT SCHEMA` decides how unqualified names resolve. `VALUES (CURRENT SQLID, CURRENT SCHEMA, CURRENT SERVER)` shows them.

## EBCDIC ordering

z/OS data is usually EBCDIC, which sorts differently from ASCII/Unicode: lower-case letters before upper case, and letters before digits (blank < special characters < `a`-`z` < `A`-`Z` < `0`-`9`).

- `ORDER BY` results look "wrong" to people used to ASCII; mention it if order matters.
- Range predicates such as `CODE BETWEEN 'A' AND '9'` or `> '9'` do not mean what they would on ASCII. Prefer explicit lists, `LIKE`, or `SUBSTR` comparisons.

## Performance and limits

- The resource limit facility (RLF) can stop dynamic queries that use too much CPU: `SQLCODE -905`. Filter on indexed columns (`SYSINDEXES` / `describe_table`), reduce joins, aggregate earlier.
- `WITH UR` at the end of a `SELECT` avoids lock waits on busy tables; results may include uncommitted changes.
- `FETCH FIRST n ROWS ONLY` works on all supported releases. `OFFSET` and `LIMIT` exist only from DB2 12 at certain function levels, so don't rely on them.
- Statistics (`CARDF`, `STATSTIME`) are only as fresh as the last RUNSTATS; `-1` means never collected.

## Data conventions on the mainframe

- Dates in COBOL-era tables are often `DECIMAL(8,0)` (`YYYYMMDD`), `DECIMAL(7,0)` (`CYYMMDD`, C = 0 for 19xx, 1 for 20xx) or `CHAR(8)`, with `0` meaning "no date". Convert before date arithmetic with `DIGITS`, which gives the digits only (`CHAR` of a decimal adds leading zeros and a decimal point):
  ```sql
  DATE(TIMESTAMP_FORMAT(DIGITS(NULLIF(ORDER_DT, 0)), 'YYYYMMDD'))
  ```
- Amounts are usually `DECIMAL` (packed decimal); keep them `DECIMAL` in calculations to avoid rounding.
- Flags and codes are typically `CHAR(1)`/`CHAR(2)`; look for a code table (`search_columns` with the code column's name) before guessing what a value means.
- Timestamps print as `YYYY-MM-DD-HH.MM.SS.NNNNNN`.

## Metadata procedures

The JDBC metadata calls behind `get_tables`, `get_columns` and `describe_table` use stored procedures in schema `SYSIBM` (`SQLTABLES`, `SQLCOLUMNS`, `SQLFOREIGNKEYS`, ...) installed by job DSNTIJMS. If they are missing you get `SQLCODE -440` or "(not available)" sections; query the catalog tables above directly instead, and tell the user so their DBA can install them.
