Review the logic and performance of the routine "{object}" (procedure, function or trigger) on connection "{connection}".

Steps:
1. getRoutineSource: the source with line numbers and the parameters. For a trigger, listTriggers for its table and events.
2. getObjectDependencies (depth 2): the routines it calls and the tables it uses. Read what you need (getRoutineSource, describeTable).
3. getRoutineStats: executions, CPU, reads and duration per statement, with line numbers. getCachedPlans for the plans of the expensive statements (compiled parameter values).
4. Review for:
   - cursors or row-by-row processing that a set-based statement could replace;
   - non-sargable predicates (functions on columns, leading wildcards, OR on different columns);
   - implicit conversions (parameter or variable types that differ from the column types);
   - transaction and error handling: TRY/CATCH, XACT_ABORT, rollback paths, open transactions on error;
   - parameter sniffing risks (skewed data, optional parameters);
   - dynamic SQL built by concatenation (SQL injection), missing QUOTENAME / bind parameters;
   - unused variables and dead branches;
   - triggers that assume a single row (reading inserted/NEW into variables).

Answer with one item per problem: line numbers, evidence, severity, and the fixed code (text only).
STOP before applying anything: changing the routine is DDL (runScript), shows the full DDL and asks the user.
