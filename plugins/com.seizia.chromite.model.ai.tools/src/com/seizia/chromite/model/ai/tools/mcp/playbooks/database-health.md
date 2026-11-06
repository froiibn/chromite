Check the health of the database on connection "{connection}" right now. Change nothing.

Steps:
1. getServerInfo and probePermissions. Run only the checks this login may run; for the others say which permission is missing.
2. Activity: listSessions (activeOnly), getBlockingChain, getLongRunningQueries, listOpenTransactions (minSeconds 60).
3. Load: getWaitStats, getTopQueries (cpu, then io).
4. Space: getDatabaseSizes, getSpaceUsage (log usage, free disk).
5. Safety: getBackupHistory (age of the last full and log backup), getJobHistory (failures), getErrorLog (recent errors).
6. Configuration: getServerConfig (findings).

Answer with a table: area, status (OK / WARNING / CRITICAL / NOT CHECKED), evidence (numbers), and what to do. List the checks that were skipped and why.
