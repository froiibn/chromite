Tune this slow query on connection "{connection}":

```sql
{sql}
```

Work as a DBA. Steps:
1. getServerInfo and probePermissions. Skip checks the login cannot run and say which permission is missing.
2. explainQuery: find the expensive operators, scans vs seeks, key lookups, warnings (implicit conversions, missing statistics), missing-index hints.
3. If validateSql says the query is read-only, getActualPlan for actual rows, time and I/O. If it is not read-only, STOP and ask the user: measuring it runs the statement (rolled back afterwards).
4. For every table in the plan: describeTable, getTableStatistics (row counts, statistics age, modifications since update), listIndexes, getIndexUsage, getMissingIndexes, getIndexFragmentation.
5. Propose a rewritten query and/or index changes. Use comparePlans to compare the original and the rewrite (estimated costs). On PostgreSQL, whatIfIndex compares the plan with a hypothetical index first (it needs the hypopg extension and says when it is missing).
6. STOP and show the user the DDL (generateDdl) and the expected effect. Only if the user agrees: createIndex (asks for confirmation), then getActualPlan / comparePlans to measure the effect.

Answer with: the cause(s), the evidence (operators, numbers), the proposed change, and before/after costs.
