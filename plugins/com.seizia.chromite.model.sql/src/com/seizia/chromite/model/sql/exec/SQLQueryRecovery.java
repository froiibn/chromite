/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.sql.exec;

import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPErrorAssistant;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.runtime.DBRRunnableParametrized;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryContainer;
import com.seizia.chromite.model.sql.SQLQueryType;
import com.seizia.chromite.model.sql.messages.ModelSQLMessages;
import com.seizia.chromite.runtime.jobs.TransactionRecoveryGuard;
import com.seizia.chromite.utils.GeneralUtils;
import net.sf.jsqlparser.statement.Block;
import net.sf.jsqlparser.statement.IfElseStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import org.eclipse.osgi.util.NLS;
import org.jkiss.dbeaver.DBException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.Set;

/**
 * Runs a query of the SQL editor or of a SQL script so that a lost connection never makes Chromite send again a
 * statement whose outcome is unknown.
 *
 * 설계: 사용자 규칙(2026-11-12, 앱 변경 #75·#86) "결과를 모르는 문장은 Chromite가 스스로 다시 보내지 않는다".
 * <ul>
 *     <li>연결이 끊긴 뒤 자동으로 다시 실행하는 것은 허용 목록이다({@link #canRunAgain}): 파서가 한 문장으로 읽은 SELECT이고, INTO가 없고,
 *     잠그지 않으며(FOR UPDATE 등), WITH 안에 데이터를 바꾸는 부분(INSERT·UPDATE·DELETE … RETURNING)이 없을 때만. 그 밖(INSERT·UPDATE·
 *     DELETE·MERGE, DDL, EXEC·CALL, 여러 문장 배치, 블록, 파싱하지 못한 글)은 다시 실행하지 않는다. 모르면 다시 실행하지 않는다.
 *     전에는 반대로 하나로 파싱된 INSERT·UPDATE·DELETE·SELECT INTO만 뺀 금지 목록이라, SQL Server 배치(INSERT + WAITFOR)가 끊긴 뒤
 *     통째로 다시 실행되어 앞 INSERT가 두 번 커밋되었다.</li>
 *     <li>읽기라도 세션에 열린 트랜잭션이 있으면 다시 실행하지 않는다. 새 세션에서는 그 트랜잭션의 변경이 보이지 않아 결과가 달라지고,
 *     트랜잭션이 사라진 것을 사용자가 먼저 알아야 한다.</li>
 *     <li>허용 목록은 연결이 끊긴 뒤에만 적용한다. 서버가 트랜잭션을 중단한 경우(25P02)는 서버가 문장을 거부했으므로 결과를 모르는 것이
 *     없어 전처럼 복구한다(SQL 편집기는 한 INSERT·UPDATE·DELETE·SELECT INTO를 전처럼 복구하지 않음).</li>
 *     <li>다시 실행하지 않을 때도 끊긴 격리 세션(쿼리 창의 자기 세션)은 그 자리에서 다시 연다({@code TransactionRecoveryGuard.reopenLostSession},
 *     연결의 자동 복구 설정이 켜져 있을 때만: 복구의 다시 연결과 같은 설정):
 *     아무 문장도 다시 보내지 않으며, 세션이 정말 끊겼을 때만 열고, 잃은 트랜잭션이 있으면 그 문장 수를 알리고 열린 트랜잭션 표시를 지운다.
 *     전에는 다음 읽기 문장이 복구할 때까지 세션이 죽은 채 남아, 그 사이의 쓰기 문장은 보내지지도 않고 실패했고 트랜잭션 표시도 남았다.</li>
 *     <li>SQL SELECT 안에서 부르는 함수의 부작용(시퀀스, 데이터를 바꾸는 사용자 함수)은 파서로 알 수 없다. 이런 SELECT는 다시 실행된다
 *     (전과 같음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: {@code SQLQueryExecuteJob.shouldRecoverQuery}(금지 목록)와 {@code SQLScriptProcessor}의 무조건 복구를
 *   한 허용 목록으로 (앱 변경 #86, docs/chromite/transactions.md "연결이 끊긴 쿼리")
 * - 2026-11-12 허용 목록과 열린 트랜잭션 조건은 연결이 끊긴 뒤에만 적용: 모든 실행이 다시 {@code tryExecuteRecover}를 지나고, 연결 끊김으로
 *   다시 실행하지 않을 문장은 실행 함수 안에서 멈춘다({@code DBExecUtils.preventRecovery}). 처음 판에서는 허용 목록 밖의 문장이 복구를 아예
 *   거치지 않아 서버가 중단한 트랜잭션(PostgreSQL 25P02)의 롤백·다시 실행(또는 멈춤과 알림)이 사라졌다. 다시 실행했다는 알림은 연결이
 *   끊긴 뒤의 다시 실행에만. 세션 다시 열기는 연결의 자동 복구 설정을 따른다. 열린 트랜잭션이 있었을 때 "롤백했다"는 그 문장이 스스로
 *   커밋할 수 없는 한 문장(SELECT·INSERT·UPDATE·DELETE·MERGE)일 때만, 나머지(DDL, 배치, 블록, 프로시저 호출)는 "커밋했는지 알 수 없다"
 */
public final class SQLQueryRecovery {

    // One statement of these types cannot commit by itself: after a lost connection its transaction was rolled back
    private static final Set<SQLQueryType> ROLLED_BACK_TYPES = Set.of(
        SQLQueryType.SELECT, SQLQueryType.INSERT, SQLQueryType.UPDATE, SQLQueryType.DELETE, SQLQueryType.MERGE);

    private SQLQueryRecovery() {
    }

    /**
     * Whether the query may be run again automatically after a lost connection: one parsed SELECT that only reads.
     */
    public static boolean canRunAgain(@NotNull SQLQuery query) {
        Statement statement = query.getStatement();
        return query.getParseError() == null && statement instanceof Select select && onlyReads(select);
    }

    /**
     * Whether reading the data container may be repeated after a lost connection: always for tables and other
     * containers, only by {@link #canRunAgain} for a container that runs a query (a query exported or copied).
     */
    public static boolean canReadAgain(@Nullable Object dataContainer) {
        SQLQueryContainer queryContainer = GeneralUtils.adapt(dataContainer, SQLQueryContainer.class);
        return queryContainer == null || (queryContainer.getQuery() instanceof SQLQuery query && canRunAgain(query));
    }

    private static boolean onlyReads(@NotNull Select select) {
        if (select.getForMode() != null) {
            // FOR UPDATE / FOR SHARE: locks rows
            return false;
        }
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                // A data-modifying WITH (PostgreSQL INSERT/UPDATE/DELETE ... RETURNING) is not a ParenthesedSelect
                if (!(item.getParenthesedStatement() instanceof ParenthesedSelect parenthesed) || !onlyReads(parenthesed)) {
                    return false;
                }
            }
        }
        if (select instanceof PlainSelect plainSelect) {
            return CommonUtils.isEmpty(plainSelect.getIntoTables());
        }
        if (select instanceof SetOperationList setOperations) {
            for (Select part : CommonUtils.safeList(setOperations.getSelects())) {
                if (!onlyReads(part)) {
                    return false;
                }
            }
            return true;
        }
        if (select instanceof ParenthesedSelect parenthesed) {
            return parenthesed.getSelect() != null && onlyReads(parenthesed.getSelect());
        }
        // VALUES lists and other forms: not known to be a plain read
        return false;
    }

    /**
     * Whether the query is one statement the server runs as a whole: one parsed statement that is not a procedure
     * call or a block (those run several statements, which can commit one by one).
     */
    private static boolean isSingleStatement(@NotNull SQLQuery query) {
        Statement statement = query.getStatement();
        return query.getParseError() == null && statement != null
            && !(statement instanceof Execute || statement instanceof Block || statement instanceof IfElseStatement
            || statement instanceof UnsupportedStatement);
    }

    /**
     * Runs one execution of the query through the execution recovery ({@code DBExecUtils.tryExecuteRecover}), the one
     * decision point for both cases:
     * <ul>
     *     <li>Lost connection: the query runs again only when {@link #canRunAgain} allows it and the session had no
     *     open transaction; {@code ranAgain} is called before that repeat. Otherwise it fails with an error that says it
     *     was not run again, after the lost isolated session was reopened (nothing is sent; a lost transaction is told)
     *     when the connection's automatic recovery is on.</li>
     *     <li>Transaction aborted by the server (PostgreSQL 25P02): unchanged. The recovery rolls the aborted transaction
     *     back and runs the statement again unless a session holds the user's statements ({@code TransactionRecoveryGuard});
     *     the server refused the statement, so nothing is unknown. As before, the SQL editor does not recover one parsed
     *     INSERT, UPDATE, DELETE or SELECT INTO ({@code recoverAbortedWrites} false); SQL scripts recover every statement.</li>
     * </ul>
     *
     * @throws Throwable the error of the execution (unwrapped from {@link InvocationTargetException})
     */
    public static void execute(
        @NotNull DBCSession session,
        @NotNull SQLQuery query,
        @NotNull DBRRunnableParametrized<DBCSession> execution,
        @NotNull Runnable ranAgain,
        boolean recoverAbortedWrites
    ) throws Throwable {
        TransactionRecoveryGuard.Attempt attempt = TransactionRecoveryGuard.Attempt.start(session.getExecutionContext());
        boolean mayRunAgain = canRunAgain(query) && !attempt.transactionBefore().isOpen();
        // Whether the failed try lost the connection: only a repeat after that is told
        boolean[] lost = {false};
        int[] tries = {0};
        DBRRunnableParametrized<DBCSession> guarded = param -> {
            if (tries[0]++ > 0 && lost[0]) {
                ranAgain.run();
            }
            lost[0] = false;
            try {
                execution.run(param);
            } catch (Throwable e) {
                Throwable error = unwrap(e);
                if (!session.getProgressMonitor().isCanceled()
                    && DBExecUtils.discoverErrorType(session.getDataSource(), error) == DBPErrorAssistant.ErrorType.CONNECTION_LOST) {
                    if (!mayRunAgain) {
                        throw notRunAgain(session, query, attempt, error);
                    }
                    lost[0] = true;
                }
                throw rethrow(e);
            }
        };
        if (recoverAbortedWrites || !isSingleWrite(query)) {
            DBExecUtils.tryExecuteRecover(session, session.getDataSource(), guarded);
        } else {
            try {
                guarded.run(session);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            }
        }
    }

    /**
     * One parsed INSERT, UPDATE, DELETE or SELECT INTO: the SQL editor never recovered these (upstream
     * {@code shouldRecoverQuery}), also not after an aborted transaction.
     */
    private static boolean isSingleWrite(@NotNull SQLQuery query) {
        Statement statement = query.getStatement();
        return statement instanceof Insert || statement instanceof Delete || statement instanceof Update
            || (statement instanceof PlainSelect select && !CommonUtils.isEmpty(select.getIntoTables()));
    }

    /**
     * The error of a query not run again after a lost connection, saying what is certain about it; reopens the lost
     * session first and keeps any recovery around from running the query again.
     */
    @NotNull
    private static DBCException notRunAgain(
        @NotNull DBCSession session,
        @NotNull SQLQuery query,
        @NotNull TransactionRecoveryGuard.Attempt attempt,
        @NotNull Throwable error
    ) {
        if (session.getDataSource().getContainer().getPreferenceStore().getBoolean(ModelPreferences.EXECUTE_RECOVER_ENABLED)) {
            // Makes the window usable again and tells a lost transaction; sends nothing. Like the reconnect of the
            // recovery, only when the connection's automatic recovery is on
            TransactionRecoveryGuard.reopenLostSession(session.getProgressMonitor(), session.getExecutionContext(), error, attempt);
        }
        // The error says CONNECTION_LOST: the recovery around must not read it as a reason to run the query again
        DBExecUtils.preventRecovery();
        String outcome;
        if (attempt.transactionBefore().isOpen()) {
            // The rollback is certain only when the statement in flight cannot commit by itself: DDL (an implicit commit
            // in Oracle, MySQL, MariaDB), a batch, a block or a procedure call may have committed the transaction
            outcome = isSingleStatement(query) && ROLLED_BACK_TYPES.contains(query.getType())
                ? ModelSQLMessages.model_sql_exec_lost_transaction
                : ModelSQLMessages.model_sql_exec_lost_transaction_unknown;
        } else if (isSingleStatement(query)) {
            outcome = ModelSQLMessages.model_sql_exec_lost_outcome_unknown;
        } else {
            outcome = ModelSQLMessages.model_sql_exec_lost_part_may_have_run;
        }
        return new DBCException(NLS.bind(ModelSQLMessages.model_sql_exec_lost_not_run_again, outcome, error.getMessage()), error);
    }

    @NotNull
    private static Throwable unwrap(@NotNull Throwable e) {
        return e instanceof InvocationTargetException ite && ite.getTargetException() != null ? ite.getTargetException() : e;
    }

    /**
     * The error as the recovery's runnable may throw it (as it came, so that the recovery sees the same error).
     */
    @NotNull
    private static DBException rethrow(@NotNull Throwable e) throws InvocationTargetException, InterruptedException {
        if (e instanceof DBException dbe) {
            return dbe;
        }
        if (e instanceof InvocationTargetException ite) {
            throw ite;
        }
        if (e instanceof InterruptedException ie) {
            throw ie;
        }
        throw new InvocationTargetException(e);
    }
}
