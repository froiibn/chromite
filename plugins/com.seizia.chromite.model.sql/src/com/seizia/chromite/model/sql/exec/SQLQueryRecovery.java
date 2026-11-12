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

import com.seizia.chromite.model.DBPErrorAssistant;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
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
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;

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
 *     <li>다시 실행하지 않을 때도 끊긴 격리 세션(쿼리 창의 자기 세션)은 그 자리에서 다시 연다({@code TransactionRecoveryGuard.reopenLostSession}):
 *     아무 문장도 다시 보내지 않으며, 세션이 정말 끊겼을 때만 열고, 잃은 트랜잭션이 있으면 그 문장 수를 알리고 열린 트랜잭션 표시를 지운다.
 *     전에는 다음 읽기 문장이 복구할 때까지 세션이 죽은 채 남아, 그 사이의 쓰기 문장은 보내지지도 않고 실패했고 트랜잭션 표시도 남았다.</li>
 *     <li>SQL SELECT 안에서 부르는 함수의 부작용(시퀀스, 데이터를 바꾸는 사용자 함수)은 파서로 알 수 없다. 이런 SELECT는 다시 실행된다
 *     (전과 같음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: {@code SQLQueryExecuteJob.shouldRecoverQuery}(금지 목록)와 {@code SQLScriptProcessor}의 무조건 복구를
 *   한 허용 목록으로 (앱 변경 #86, docs/chromite/transactions.md "연결이 끊긴 쿼리")
 */
public final class SQLQueryRecovery {

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
     * Runs one execution of the query. After a lost connection it is run again only when {@link #canRunAgain} allows
     * it and the session had no open transaction ({@code DBExecUtils.tryExecuteRecover}); {@code ranAgain} is called
     * before each repeat. Otherwise a lost connection fails the query with an error that says it was not run again,
     * after the lost isolated session was reopened (nothing is sent; a lost transaction is told).
     *
     * @throws Throwable the error of the execution (unwrapped from {@link InvocationTargetException})
     */
    public static void execute(
        @NotNull DBCSession session,
        @NotNull SQLQuery query,
        @NotNull DBRRunnableParametrized<DBCSession> execution,
        @NotNull Runnable ranAgain
    ) throws Throwable {
        DBCExecutionContext context = session.getExecutionContext();
        TransactionRecoveryGuard.Attempt attempt = TransactionRecoveryGuard.Attempt.start(context);
        if (canRunAgain(query) && !attempt.transactionBefore().isOpen()) {
            int[] tries = {0};
            DBExecUtils.tryExecuteRecover(session, session.getDataSource(), param -> {
                if (tries[0]++ > 0) {
                    ranAgain.run();
                }
                execution.run(param);
            });
            return;
        }
        try {
            execution.run(session);
        } catch (Throwable e) {
            Throwable error = e instanceof InvocationTargetException ite && ite.getTargetException() != null ? ite.getTargetException() : e;
            if (!session.getProgressMonitor().isCanceled()
                && DBExecUtils.discoverErrorType(session.getDataSource(), error) == DBPErrorAssistant.ErrorType.CONNECTION_LOST) {
                // Makes the window usable again and tells a lost transaction; sends nothing
                TransactionRecoveryGuard.reopenLostSession(session.getProgressMonitor(), context, error, attempt);
                String outcome;
                if (attempt.transactionBefore().isOpen() && query.getType() != SQLQueryType.COMMIT) {
                    // The server rolled the transaction back with the session (the notice of the reopened session counts
                    // its statements); a COMMIT in flight may or may not have been done
                    outcome = ModelSQLMessages.model_sql_exec_lost_transaction;
                } else if (isSingleStatement(query)) {
                    outcome = ModelSQLMessages.model_sql_exec_lost_outcome_unknown;
                } else {
                    outcome = ModelSQLMessages.model_sql_exec_lost_part_may_have_run;
                }
                throw new DBCException(NLS.bind(ModelSQLMessages.model_sql_exec_lost_not_run_again, outcome, error.getMessage()), error);
            }
            throw error;
        }
    }
}
