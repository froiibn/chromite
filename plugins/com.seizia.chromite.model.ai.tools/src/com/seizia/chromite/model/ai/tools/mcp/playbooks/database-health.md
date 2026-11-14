Check the health of the database on connection "{connection}" right now. Change nothing.

Steps:
1. getServerInfo and checkDiagnosticAccess. Run only the checks this login may run; for the others say which permission is missing.
2. Activity: listSessions (activeOnly), getBlockingChain, getLongRunningQueries, listOpenTransactions (minSeconds 60).
3. Load: getWaitStats, getTopQueries (cpu, then io). SQL Server: getTopQueries with source=queryStore and getQueryStoreRegressions when Query Store is on (they say when it is off).
4. Space: getDatabaseSizes, getSpaceUsage (log usage, free disk).
5. Safety: getBackupHistory (age of the last full and log backup), getJobHistory (failures; SQL Server: getAgentJobHistory with failedOnly=true), getErrorLog (recent errors). SQL Server: listAgentJobs (is the agent running, last outcomes).
6. Configuration: getServerConfig (findings).

Answer with a table: area, status (OK / WARNING / CRITICAL / NOT CHECKED), evidence (numbers), and what to do. List the checks that were skipped and why.
