Report the system status from the monitoring queries of the Chromite Query Library (folder "{folder}") on connection "{connection}".

Steps:
1. listLibraryQueries. If the folder is empty or not given, STOP and ask the user which folder holds the monitoring queries.
2. runMonitoringChecks with the folder and the connection. Each query is judged against its @threshold entries.
3. For queries without thresholds, report the values and flag only obvious problems; say that no thresholds are set.
4. For WARN or CRITICAL results, look deeper with the matching diagnostic tools (for example getBlockingChain, getSpaceUsage, getWaitStats).

Answer with the overall status, a table per query (status, observed value, threshold), and the follow-up findings. Skipped queries: say why.
