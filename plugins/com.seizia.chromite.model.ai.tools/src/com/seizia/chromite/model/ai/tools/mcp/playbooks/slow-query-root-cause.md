Find out why this query is slow on connection "{connection}". Do not change anything.

```sql
{sql}
```

Steps:
1. getServerInfo and probePermissions.
2. explainQuery; getActualPlan only if validateSql says the query is read-only.
3. Look at the plan findings: scans vs seeks, key lookups, spills (sort/hash to disk), implicit conversions, row estimates off by 10x or more, missing statistics, parallelism, memory grants.
4. Parameter sniffing: compare compiled and runtime parameter values (plan "parameters"; getCachedPlans for the cached plan).
5. Data: getTableStatistics (stale statistics?), listIndexes, getIndexUsage, getMissingIndexes.
6. If the query is running now: getLongRunningQueries, getBlockingChain, listLocks — is it waiting on locks?

Answer with a ranked list of causes. For each: the cause, the evidence (operator, numbers), confidence, and how to fix it (text only; do not run anything).
