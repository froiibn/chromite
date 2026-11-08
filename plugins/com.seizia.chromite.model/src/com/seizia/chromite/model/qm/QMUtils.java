/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.seizia.chromite.model.qm;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.auth.SMAuthSpace;
import com.seizia.chromite.model.auth.SMSession;
import com.seizia.chromite.model.auth.SMSessionContext;
import com.seizia.chromite.model.auth.SMSessionPersistent;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCServerTransaction;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.filters.QMEventCriteria;
import com.seizia.chromite.model.qm.meta.QMMConnectionInfo;
import com.seizia.chromite.model.qm.meta.QMMStatementExecuteInfo;
import com.seizia.chromite.model.qm.meta.QMMTransactionInfo;
import com.seizia.chromite.model.qm.meta.QMMTransactionSavepointInfo;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.LoggingProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.qm.QMRegistryImpl;
import com.seizia.chromite.runtime.qm.QMTransactionTracker;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Query Manager utils
 *
 * 변경 이력:
 * - 2026-11-07 {@link #getTransactionState}: 열린 트랜잭션 표시와 유휴 트랜잭션 감시가 쓰도록 마지막 문장 시각과 실행 중 여부를 더함
 *   (docs/chromite/idle-transactions.md)
 * - 2026-11-07 {@link #getLastUseTime}: 유휴 연결 닫기가 연결마다 마지막으로 쓴 시각을 읽음(전역 마우스·키보드 활동 대신)
 *   (docs/chromite/idle-transactions.md "유휴 연결 닫기")
 * - 2026-11-07 {@link #getTransactionStatements}: 트랜잭션 로그가 상태 줄의 문장 수와 같은 문장을 보이도록 같은 기준({@link #isUserStatement})으로 읽음
 *   (docs/chromite/transactions.md)
 * - 2026-11-08 {@link #getRecentTransactionStatements}: 트랜잭션 로그의 "지난 트랜잭션 보이기"(같은 문장 기준)
 *   (docs/chromite/connection-lifecycle.md "트랜잭션 로그의 지난 트랜잭션")
 * - 2026-11-08 {@link #getTransactionState}: 열림을 DB의 답({@link QMTransactionTracker})으로 정하고, 답할 수 없을 때만 문장 분류로.
 *   세는 문장은 트랜잭션이 없다고 확인된 뒤의 것(자동 커밋 모드 포함) (docs/chromite/idle-transactions.md "트랜잭션 상태")
 */
public class QMUtils {

    private static final Log log = Log.getLog(QMUtils.class);

    // Statements looked at, at most, for one transaction state (auto-commit mode without a probe has no end to stop at)
    private static final int MAX_TRANSACTION_STATEMENTS = 1000;

    private static QMRegistryImpl qmRegistry;
    private static QMExecutionHandler defaultHandler;

    public static void initPlatform(boolean useLogger) {
        QMUtils.qmRegistry = new QMRegistryImpl(useLogger);
    }

    @NotNull
    public static QMExecutionHandler getDefaultHandler() {
        if (defaultHandler == null) {
            defaultHandler = getQueryManager().getDefaultHandler();
        }
        return defaultHandler;
    }

    @NotNull
    private static QMRegistry getQueryManager() {
        if (qmRegistry == null) {
            throw new IllegalStateException("Query manager registry is not initialized");
        }
        return qmRegistry;
    }

    public static void registerHandler(@NotNull QMExecutionHandler handler) {
        getQueryManager().registerHandler(handler);
    }

    public static void unregisterHandler(@NotNull QMExecutionHandler handler) {
        getQueryManager().unregisterHandler(handler);
    }

    public static void registerMetaListener(@NotNull QMMetaListener metaListener) {
        getQueryManager().registerMetaListener(metaListener);
    }

    public static void unregisterMetaListener(@NotNull QMMetaListener metaListener) {
        getQueryManager().unregisterMetaListener(metaListener);
    }

    @Nullable
    public static QMEventBrowser getEventBrowser(boolean currentSessionOnly) {
        if (qmRegistry == null) {
            return null;
        }
        return getQueryManager().getEventBrowser(currentSessionOnly);
    }

    public static boolean isTransactionActive(@Nullable DBCExecutionContext executionContext) {
        return isTransactionActive(executionContext, true);
    }

    public static boolean isTransactionActive(@Nullable DBCExecutionContext executionContext, boolean checkQueries) {
        if (executionContext == null || qmRegistry == null) {
            return false;
        } else {
            QMMConnectionInfo sessionInfo = getCurrentConnection(executionContext);
            if (sessionInfo != null && sessionInfo.isTransactional()) {
                QMMTransactionInfo txnInfo = sessionInfo.getTransaction();
                if (txnInfo != null) {
                    QMMTransactionSavepointInfo sp = txnInfo.getCurrentSavepoint();
                    if (sp != null) {
                        if (checkQueries) {
                            // If transaction was enabled all statements are transactional
                            for (QMMStatementExecuteInfo ei = sp.getLastExecute(); ei != null && ei.getSavepoint() == sp; ei = ei.getPrevious()) {
                                if (ei.isTransactional()) {
                                    return true;
                                }
                            }
                        } else {
                            return sp.getLastExecute() != null;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    public static QMMConnectionInfo getCurrentConnection(@NotNull DBCExecutionContext executionContext) {
        return getQueryManager().getMetaCollector().getConnectionInfo(executionContext);
    }

    @Nullable
    public static QMMTransactionSavepointInfo getCurrentTransaction(@NotNull DBCExecutionContext executionContext) {
        QMMConnectionInfo sessionInfo = getCurrentConnection(executionContext);
        if (sessionInfo != null && !sessionInfo.isClosed() && sessionInfo.isTransactional()) {
            QMMTransactionInfo txnInfo = sessionInfo.getTransaction();
            if (txnInfo != null) {
                return txnInfo.getCurrentSavepoint();
            }
        }
        return null;
    }

    /**
     * The transaction of the context (see {@link QMTransactionState} for the rule): the database's last answer
     * ({@link QMTransactionTracker}) with the user statements run since the transaction was last seen ended. Reads only
     * in-memory records, so it is cheap enough for the UI thread.
     */
    @NotNull
    public static QMTransactionState getTransactionState(@Nullable DBCExecutionContext executionContext) {
        QMMConnectionInfo sessionInfo = executionContext == null || qmRegistry == null ? null : getCurrentConnection(executionContext);
        if (sessionInfo == null || sessionInfo.isClosed()) {
            return new QMTransactionState(0, 0, false, 0, 0, false, QMTransactionState.Status.NONE, Set.of(), false);
        }
        // The last statement of the context, of any purpose and transaction mode
        QMMStatementExecuteInfo lastExec = sessionInfo.getExecutionStack();
        boolean executing = lastExec != null && !lastExec.isClosed();
        boolean txnMode = sessionInfo.isTransactional();
        QMTransactionTracker tracker = qmRegistry.getTransactionTracker();
        QMTransactionTracker.Snapshot tracked = tracker == null ? null : tracker.get(executionContext);
        long since = tracked == null ? 0 : tracked.since();

        // Newest first. "Recent" stops at the newest transaction control statement (BEGIN, COMMIT, ROLLBACK), which tells
        // whether an explicit begin is still open; in auto-commit mode only the statements after it are in a transaction.
        StatementCounts all = new StatementCounts();
        StatementCounts recent = new StatementCounts();
        Boolean explicitOpen = null;
        int looked = 0;
        for (QMMStatementExecuteInfo exec = lastExec; since > 0 && exec != null && exec.getOpenTime() >= since
            && looked < MAX_TRANSACTION_STATEMENTS; exec = exec.getPrevious(), looked++) {
            if (!isUserStatement(exec)) {
                continue;
            }
            all.add(exec);
            if (explicitOpen == null) {
                recent.add(exec);
                explicitOpen = QMStatementClassifier.getTransactionControl(exec.getQueryString());
            }
        }
        boolean explicit = Boolean.TRUE.equals(explicitOpen);
        DBCServerTransaction server = tracked == null ? null : tracked.server();
        DBCServerTransaction.Kind kind = server == null ? null : server.kind();
        // Auto-commit mode that the database could not be asked about: only an explicit begin keeps a transaction
        boolean classifyAutoCommit = !txnMode && kind != DBCServerTransaction.Kind.NONE && kind != DBCServerTransaction.Kind.HOLDING
            && kind != DBCServerTransaction.Kind.EMPTY && kind != DBCServerTransaction.Kind.UNVERIFIED;
        StatementCounts counts = classifyAutoCommit ? recent : all;
        // What the statements show, for when the database cannot tell
        Set<DBCServerTransaction.Hold> seen = counts.holds(explicit);

        QMTransactionState.Status status;
        Set<DBCServerTransaction.Hold> holds = Set.of();
        boolean serverChecked = kind == DBCServerTransaction.Kind.NONE || kind == DBCServerTransaction.Kind.HOLDING
            || kind == DBCServerTransaction.Kind.EMPTY;
        if (kind == DBCServerTransaction.Kind.NONE) {
            status = QMTransactionState.Status.NONE;
        } else if (kind == DBCServerTransaction.Kind.HOLDING) {
            status = QMTransactionState.Status.OPEN;
            holds = EnumSet.noneOf(DBCServerTransaction.Hold.class);
            // The server's own list; a database that only says "there is one" (Oracle) is described by the statements
            holds.addAll(server.holds().isEmpty() ? seen : server.holds());
            if (explicit) {
                holds.add(DBCServerTransaction.Hold.EXPLICIT);
            }
        } else if (kind == DBCServerTransaction.Kind.EMPTY) {
            status = explicit ? QMTransactionState.Status.OPEN : QMTransactionState.Status.NONE;
            holds = explicit ? Set.of(DBCServerTransaction.Hold.EXPLICIT) : Set.of();
        } else if (classifyAutoCommit) {
            status = explicit ? QMTransactionState.Status.OPEN : QMTransactionState.Status.NONE;
            holds = explicit ? seen : Set.of();
        } else if (counts.count == 0 && kind != DBCServerTransaction.Kind.UNVERIFIED) {
            // Manual commit without a probe (or a failed question), and nothing ran since the transaction was last seen ended
            status = QMTransactionState.Status.NONE;
        } else if (!seen.isEmpty()) {
            // No probe, no permission or a failed question: the statements tell
            status = QMTransactionState.Status.OPEN;
            holds = seen;
        } else {
            status = QMTransactionState.Status.UNKNOWN;
        }
        int execCount = counts.count;
        int updateCount = counts.updates;
        long txnStartTime = counts.start;
        long lastExecTime = counts.last;
        if (status == QMTransactionState.Status.NONE) {
            txnStartTime = 0;
        } else {
            // Open with no statement of the user since it was last seen ended: a nested BEGIN TRAN that survived a commit
            txnStartTime = txnStartTime > 0 ? txnStartTime : since;
            lastExecTime = lastExecTime > 0 ? lastExecTime : since;
        }
        return new QMTransactionState(execCount, updateCount, txnMode, txnStartTime, lastExecTime, executing,
            status, holds.isEmpty() ? Set.of() : Collections.unmodifiableSet(holds), serverChecked);
    }

    /**
     * The user statements of the context's current transaction, oldest first: the statements that
     * {@link #getTransactionState} counts, for the transaction log. Empty in auto-commit mode and when none ran since
     * the last commit or rollback. Cheap like {@link #getTransactionState}.
     */
    @NotNull
    public static List<QMMStatementExecuteInfo> getTransactionStatements(@Nullable DBCExecutionContext executionContext) {
        List<QMMStatementExecuteInfo> statements = new ArrayList<>();
        QMMTransactionSavepointInfo sp = executionContext == null || qmRegistry == null ? null : getCurrentTransaction(executionContext);
        if (sp != null) {
            for (QMMStatementExecuteInfo exec = sp.getLastExecute(); exec != null && exec.getSavepoint() == sp; exec = exec.getPrevious()) {
                if (isUserStatement(exec)) {
                    statements.addFirst(exec);
                }
            }
        }
        return statements;
    }

    /**
     * The user statements of the context's last transactions in manual-commit mode, the current one included, oldest
     * first: the transaction log's "Show previous transactions". Each statement's transaction is
     * {@code getSavepoint().getTransaction()} (committed, rolled back, or still open). Statements run in auto-commit mode
     * belong to no transaction and are left out. Reads only the query manager's in-memory records.
     *
     * @param maxTransactions how many transactions, newest first
     * @param maxStatements   how many statements of the session to look through at most, newest first
     */
    @NotNull
    public static List<QMMStatementExecuteInfo> getRecentTransactionStatements(
        @Nullable DBCExecutionContext executionContext,
        int maxTransactions,
        int maxStatements
    ) {
        List<QMMStatementExecuteInfo> statements = new ArrayList<>();
        QMMConnectionInfo sessionInfo = executionContext == null || qmRegistry == null ? null : getCurrentConnection(executionContext);
        if (sessionInfo == null) {
            return statements;
        }
        Set<QMMTransactionInfo> transactions = Collections.newSetFromMap(new IdentityHashMap<>());
        int looked = 0;
        for (QMMStatementExecuteInfo exec = sessionInfo.getExecutionStack(); exec != null && looked < maxStatements; exec = exec.getPrevious()) {
            looked++;
            QMMTransactionSavepointInfo savepoint = exec.getSavepoint();
            if (savepoint == null || !isUserStatement(exec)) {
                continue;
            }
            if (transactions.add(savepoint.getTransaction()) && transactions.size() > maxTransactions) {
                break;
            }
            statements.addFirst(exec);
        }
        return statements;
    }

    /**
     * User statements of a stretch of the transaction, newest first ({@link #getTransactionState}).
     */
    private static final class StatementCounts {
        int count;
        int updates;
        boolean lockingRead;
        // First (oldest) statement's start, last (newest) statement's end (its start while it runs)
        long start;
        long last;

        void add(@NotNull QMMStatementExecuteInfo exec) {
            count++;
            if (exec.isTransactional() && !QMStatementClassifier.isSessionStatement(exec.getQueryString())) {
                updates++;
            }
            lockingRead |= QMStatementClassifier.isLockingRead(exec.getQueryString());
            if (last == 0) {
                last = exec.isClosed() ? exec.getCloseTime() : exec.getOpenTime();
            }
            start = exec.getOpenTime();
        }

        @NotNull
        Set<DBCServerTransaction.Hold> holds(boolean explicit) {
            Set<DBCServerTransaction.Hold> holds = EnumSet.noneOf(DBCServerTransaction.Hold.class);
            if (updates > 0) {
                holds.add(DBCServerTransaction.Hold.CHANGES);
            }
            if (lockingRead) {
                holds.add(DBCServerTransaction.Hold.LOCKS);
            }
            if (explicit) {
                holds.add(DBCServerTransaction.Hold.EXPLICIT);
            }
            return holds;
        }
    }

    /**
     * Metadata reads (META) and internal work (UTIL, the commit and rollback sessions among them) are not statements
     * of the user's transaction.
     */
    private static boolean isUserStatement(@NotNull QMMStatementExecuteInfo exec) {
        DBCExecutionPurpose purpose = exec.getStatement().getPurpose();
        return purpose != DBCExecutionPurpose.META && purpose != DBCExecutionPurpose.UTIL;
    }

    /**
     * When the context was last used, as the query manager saw it: the end of its last statement of any purpose (user,
     * metadata, internal), its last commit, rollback or commit mode change, or its (re)connection. The current time while
     * a statement runs or fetches; 0 when the query manager has no record of the context. Cheap like
     * {@link #getTransactionState}.
     */
    public static long getLastUseTime(@Nullable DBCExecutionContext executionContext) {
        if (executionContext == null || qmRegistry == null) {
            return 0;
        }
        QMMConnectionInfo sessionInfo = getCurrentConnection(executionContext);
        if (sessionInfo == null || sessionInfo.isClosed()) {
            return 0;
        }
        long time = sessionInfo.getOpenTime();
        QMMStatementExecuteInfo lastExec = sessionInfo.getExecutionStack();
        if (lastExec != null) {
            if (!lastExec.isClosed() || lastExec.isFetching()) {
                return System.currentTimeMillis();
            }
            time = Math.max(time, Math.max(lastExec.getCloseTime(), lastExec.getFetchEndTime()));
        }
        // A commit or rollback starts a new transaction record
        QMMTransactionInfo txnInfo = sessionInfo.getTransaction();
        if (txnInfo != null) {
            time = Math.max(time, txnInfo.getOpenTime());
        }
        return time;
    }

    @NotNull
    public static QMEventCriteria createDefaultCriteria(@NotNull DBPPreferenceStore store) {
        QMEventCriteria criteria = new QMEventCriteria();

        Collection<QMObjectType> objectTypes = QMObjectType.fromString(store.getString(QMConstants.PROP_OBJECT_TYPES));
        criteria.setObjectTypes(objectTypes.toArray(new QMObjectType[0]));
        List<DBCExecutionPurpose> queryTypes = new ArrayList<>();
        for (String queryType : CommonUtils.splitString(store.getString(QMConstants.PROP_QUERY_TYPES), ',')) {
            try {
                queryTypes.add(DBCExecutionPurpose.valueOf(queryType));
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
        criteria.setQueryTypes(queryTypes.toArray(new DBCExecutionPurpose[0]));
        return criteria;
    }

    /**
     * Extract QM session from execution context
     */
    @Nullable
    public static String getQmSessionId(@NotNull DBCExecutionContext executionContext) throws DBException {
        if (DBWorkbench.getPlatform().getApplication() instanceof QMSessionProvider provider) {
            return provider.getQueryManagerSessionId();
        }
        return getQmSessionId(executionContext.getDataSource());
    }

    /**
     * Extract QM session id from data source
     */
    @Nullable
    public static String getQmSessionId(@NotNull DBPDataSource dataSource) throws DBException {
        return getQmSessionId(dataSource.getContainer().getProject());
    }

    /**
     * Extract QM session id from project
     */
    @Nullable
    public static String getQmSessionId(@NotNull DBPProject project) throws DBException {
        SMSessionContext projectAuthContext = project.getSessionContext();
        SMAuthSpace projectPrimaryAuthSpace = projectAuthContext.getPrimaryAuthSpace();

        SMSession session = null;
        DBRProgressMonitor monitor = new LoggingProgressMonitor();
        if (projectPrimaryAuthSpace != null) {
            session = project.getSessionContext().getSpaceSession(monitor, projectPrimaryAuthSpace, false);
        }
        if (session == null) {
            DBPWorkspace workspace = project.getWorkspace();
            session = workspace.getAuthContext().getSpaceSession(monitor, workspace, false);
        }

        return getQmSessionId(session);
    }

    @Nullable
    public static String getQmSessionId(@Nullable SMSession session) {
        SMSessionPersistent sessionPersistent = DBUtils.getAdapter(SMSessionPersistent.class, session);
        if (sessionPersistent == null) {
            log.warn("Session persistent not found");
            return null;
        }

        return sessionPersistent.getAttribute(QMConstants.QM_SESSION_ID_ATTR);
    }

    /**
     * Return close time for events that were ended
     */
    public static long getObjectEventTime(@NotNull QMEvent event) {
        if (event instanceof QMMetaEvent metaEvent) {
            return metaEvent.getTimestamp();
        }

        if (event.getAction() == QMEventAction.END) {
            return event.getObject().getCloseTime();
        }
        return event.getObject().getOpenTime();
    }

    /**
     * Returns workspace session
     */
    public static SMSession getWorkspaceSession(@NotNull DBRProgressMonitor monitor) throws DBException {
        DBPWorkspace workspace = DBWorkbench.getPlatform().getWorkspace();
        SMSession workspaceSession = workspace.getAuthContext().getSpaceSession(monitor, workspace, false);
        if (workspaceSession == null) {
            throw new DBException("No workspace session");
        }
        return workspaceSession;
    }

    public static void disposePlatform() {
        if (qmRegistry != null) {
            qmRegistry.dispose();
        }
    }

    public static void flushPendingEvents() {
        if (qmRegistry != null) {
            qmRegistry.flushPendingEvents();
        }
    }

    public static class ListCursorImpl implements QMEventCursor {

        private final List<QMMetaEvent> events;
        private int position;

        public ListCursorImpl(List<QMMetaEvent> events) {
            this.events = events;
            this.position = 0;
        }

        @Override
        public long getTotalSize() {
            return events.size();
        }

        @Override
        public void scroll(int position, DBRProgressMonitor monitor) throws DBException {
            if (position < 0 || position >= events.size()) {
                throw new DBException("Position is out of range (" + getTotalSize() + ")");
            }
        }

        @Override
        public boolean hasNextEvent(DBRProgressMonitor monitor) {
            return position < events.size();
        }

        @Override
        public QMMetaEventEntity nextEvent(DBRProgressMonitor monitor) {
            QMMetaEvent event = events.get(position);
            position++;
            return new QMMetaEventEntity(event.getObject(), event.getAction(), position, "", null);
        }

        @Override
        public void close() {

        }

    }

}
