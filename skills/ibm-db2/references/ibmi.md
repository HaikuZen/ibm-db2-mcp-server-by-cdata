# DB2 for IBM i (AS/400, iSeries)

Read this when `list_instances` shows the platform `IBM i` (product versions start with `QSQ`, e.g. `QSQ07050` is IBM i 7.5).

## Libraries are schemas

- An IBM i *library* is a DB2 *schema*, and a *physical file* is a table; a *logical file* is an index or view. The server uses SQL naming, so write `LIBRARY.FILE` with a dot, never `LIBRARY/FILE`.
- Objects have a long SQL name and a 10-character system name (`SYSTEM_TABLE_NAME`). Both work in queries; the tools show the SQL name.
- IBM's libraries start with `Q` (`QSYS`, `QSYS2`, `QGPL`, `QTEMP`, ...). By default the server hides only IBM's `SYSIBM`/`SYSCAT`-style schemas, not the `Q` libraries; set `SystemSchemas=Q%, SYSIBM%, SYSPROC, SYSTOOLS` in the configuration to hide the `Q` libraries too.

## Catalog views

Schema columns are called `TABLE_SCHEMA`.

| View | Key columns |
|---|---|
| `QSYS2.SYSTABLES` | `TABLE_SCHEMA`, `TABLE_NAME`, `TABLE_TYPE`, `SYSTEM_TABLE_SCHEMA`, `SYSTEM_TABLE_NAME`, `TABLE_TEXT` |
| `QSYS2.SYSCOLUMNS` | `TABLE_SCHEMA`, `TABLE_NAME`, `COLUMN_NAME`, `SYSTEM_COLUMN_NAME`, `DATA_TYPE`, `LENGTH`, `NUMERIC_SCALE`, `IS_NULLABLE`, `COLUMN_TEXT`, `CCSID` |
| `QSYS2.SYSINDEXES` / `QSYS2.SYSKEYS` | Indexes and their columns |
| `QSYS2.SYSTABLESTAT` | `NUMBER_ROWS`, `DATA_SIZE` - current, not from statistics |

`TABLE_TYPE`: `T` table, `P` physical file (no SQL DDL), `V` view, `L` logical file, `A` alias, `M` materialized query table.

`TABLE_TEXT` and `COLUMN_TEXT` hold the descriptions written in DDS; they are the best clue to what cryptic names mean:

```sql
SELECT COLUMN_NAME, DATA_TYPE, LENGTH, NUMERIC_SCALE, COLUMN_TEXT
FROM QSYS2.SYSCOLUMNS
WHERE TABLE_SCHEMA = 'SALESLIB' AND TABLE_NAME = 'ORDHDR'
ORDER BY ORDINAL_POSITION
```

## Data conventions

- **CCSID 65535 columns** hold untagged bytes and come back as hexadecimal or garbage. If `SYSCOLUMNS.CCSID` is 65535, cast: `CAST(col AS VARCHAR(50) CCSID 37)` (37 = US/Canada EBCDIC; use the system's CCSID if known).
- **Dates as numbers** are very common: `DECIMAL(8,0)` `YYYYMMDD`, `DECIMAL(7,0)` `CYYMMDD` (C = 0 for 19xx, 1 for 20xx), or `DECIMAL(6,0)` `YYMMDD`. Convert before date arithmetic with `DIGITS` (not `CHAR`, which adds a decimal point), e.g. `DATE(TIMESTAMP_FORMAT(DIGITS(NULLIF(ORDDT, 0)), 'YYYYMMDD'))`; for `CYYMMDD` add 19000000 first: `DIGITS(DECIMAL(NULLIF(ORDDT, 0) + 19000000, 8, 0))`.
- **Multi-member files**: SQL reads only the first member of a physical file. If numbers look too small, mention that the file may have other members.
- Text is EBCDIC, so `ORDER BY` puts lower case before upper case and letters before digits.
- Row counts from `table_stats` are current (from `QSYS2.SYSTABLESTAT`), not estimates; `Pages` and `StatsTime` are empty on IBM i.
