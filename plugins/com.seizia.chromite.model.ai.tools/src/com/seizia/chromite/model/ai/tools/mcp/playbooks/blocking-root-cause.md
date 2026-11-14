Find the root cause of blocking and locking on connection "{connection}".

Steps:
1. getServerInfo and checkDiagnosticAccess (VIEW SERVER STATE / pg_monitor / PROCESS are needed for other sessions).
2. getBlockingChain: the head blockers and the tree of waiters; each head blocker's login, host, program, transaction age, isolation level, open transaction count and last statement; the rule-based findings.
3. listLocks for the head blocker sessions: which objects and indexes they lock, how many locks (lock escalation).
4. explainQuery on the head blocker's statement if it is running: a scan locks far more rows than a seek. describeTable (indexes) of the locked tables.
5. listOpenTransactions (idle in transaction) and getDeadlocks (recent deadlock graphs).

Answer: WHY the block happens, as a story with evidence (for example: "session 57 of app X on host Y has kept a transaction open since 10:02 while idle; its UPDATE holds key locks on dbo.Orders that sessions 61 and 64 wait for"). Then the fixes: index, shorter transactions, isolation level (read committed snapshot), application change.
STOP before killSession: it is DANGEROUS, rolls back the head blocker's transaction, and is the last resort. Ask the user first.
