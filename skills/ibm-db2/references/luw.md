# DB2 for Linux, UNIX and Windows (LUW)

Read this when `list_instances` shows the platform `LUW` (product versions start with `SQL`, e.g. `SQL11058` is Db2 11.5.8).

## Catalog views

Use these through `<i>_run_query` when the metadata tools are not enough. Schema columns are called `TABSCHEMA`.

| View | Key columns |
|---|---|
| `SYSCAT.TABLES` | `TABSCHEMA`, `TABNAME`, `TYPE`, `CARD` (rows), `NPAGES`, `FPAGES`, `STATS_TIME`, `REMARKS`, `CREATE_TIME` |
| `SYSCAT.COLUMNS` | `TABSCHEMA`, `TABNAME`, `COLNAME`, `COLNO`, `TYPENAME`, `LENGTH`, `SCALE`, `NULLS`, `REMARKS` |
| `SYSCAT.INDEXES` | `INDSCHEMA`, `INDNAME`, `TABSCHEMA`, `TABNAME`, `COLNAMES` (e.g. `+CUST_ID-ORDER_DT`), `UNIQUERULE` (`D`, `U`, `P`) |
| `SYSCAT.REFERENCES` | `CONSTNAME`, `TABSCHEMA`, `TABNAME`, `REFTABSCHEMA`, `REFTABNAME`, `FK_COLNAMES`, `PK_COLNAMES` |
| `SYSCAT.VIEWS` | `VIEWSCHEMA`, `VIEWNAME`, `TEXT` |
| `SYSCAT.ROUTINES` | `ROUTINESCHEMA`, `ROUTINENAME`, `ROUTINETYPE` |

`SYSCAT.TABLES.TYPE`: `T` table, `V` view, `A` alias, `S` materialized query table, `N` nickname (federated), `G` global temporary table.

```sql
SELECT TABNAME, CARD, STATS_TIME
FROM SYSCAT.TABLES
WHERE TABSCHEMA = 'SALES' AND TYPE = 'T'
ORDER BY CARD DESC
FETCH FIRST 20 ROWS ONLY
```

## Schemas and names

- Schemas are often the instance owner (`DB2INST1`) or application names. IBM's schemas start with `SYS` (`SYSIBM`, `SYSCAT`, `SYSSTAT`, `SYSIBMADM`, `SYSTOOLS`, ...) plus `NULLID` and `SQLJ`.
- `SYSIBMADM` views (e.g. `SYSIBMADM.ENV_INST_INFO`) and `MON_GET_*` table functions give monitoring data, but often need extra privileges.

## Dialect notes

- `FETCH FIRST n ROWS ONLY` and `OFFSET n ROWS` work on all supported versions. Db2 11.1+ also accepts `LIMIT`, but `FETCH FIRST` is portable to z/OS and IBM i.
- Text is usually Unicode or a code page with ASCII ordering, so `ORDER BY` behaves as on other databases (unlike z/OS EBCDIC).
- `LISTAGG(col, ', ')` builds comma-separated lists; `VARCHAR_FORMAT(ts, 'YYYY-MM')` formats dates.
- Statistics (`CARD`, `STATS_TIME`) come from the last RUNSTATS or automatic statistics; `CARD = -1` means never collected.
- `WITH UR` at the end of a `SELECT` reads without waiting for locks; results may include uncommitted changes.
