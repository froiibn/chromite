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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import java.awt.*;

/**
 * Query &gt; Auto-Commit and the commit mode button of the SQL Editor tool bar: switches the commit mode of the active
 * query window's session (auto-commit or manual commit), without changing the connection's saved default.
 *
 * 설계:
 * <ul>
 *     <li>사용자 결정(2026-11-08, B-S18): 실행 중 커밋 방식 전환은 Query 메뉴와 SQL Editor 도구 막대에 둔다. 상태 줄은 방식을 보이기만 하고
 *     바꾸지 않는다(B-S18a). SWT의 {@code DataSourceAutoCommitHandler}처럼 활성 편집기의 실행 문맥 하나에
 *     {@link DBCTransactionManager#setAutoCommit}을 부른다. 연결 설정의 기본값({@code isDefaultAutoCommit})은 바꾸지 않으므로 다시 연결하면
 *     설정의 방식으로 돌아간다(무효화·다시 연결은 문맥의 지금 방식을 이어받는다, {@code JDBCExecutionContext.invalidateContext}).</li>
 *     <li>트랜잭션 안전 규칙: JDBC는 {@code setAutoCommit(true)}에서 열린 트랜잭션을 묻지 않고 커밋한다. SWT는 그대로 바꿨다. 여기서는 수동 커밋에서
 *     자동 커밋으로 바꿀 때 세션에 커밋하지 않은 문장이 있으면(상태 줄의 열린 트랜잭션 {@link QMTransactionState#isOpen}, 또는 데이터를 바꾼 실행
 *     {@link QMUtils#isTransactionActive}) 커밋하고 전환 / 롤백하고 전환 / 취소를 묻고 취소가 기본이다. 커밋·롤백은 전환 전에 명시적으로 한다.
 *     자동 커밋에서 수동 커밋으로 바꾸기는 잃을 것이 없어 묻지 않는다.</li>
 *     <li>실행 중에는 바꾸지 않는다: 쿼리 창은 실행·계획·결과 저장 중에 명령을 끄고({@link SqlEditorPanel#canSwitchCommitMode}), 작업도 바꾸기 직전에
 *     그 세션에서 도는 문장이나 {@code DataSourceJob}을 다시 확인해 있으면 아무것도 바꾸지 않고 알린다(주 문맥을 함께 쓰는 다른 창의 실행).
 *     질문 뒤 트랜잭션이 바뀌었으면(다른 창이 문장을 더 실행) 묻지 않은 트랜잭션을 끝내지 않도록 멈춘다.</li>
 *     <li>세션을 함께 쓰는 창(쿼리 창마다 별도 연결이 꺼져 있거나 "단일 연결" 연결): 바뀌는 것은 연결의 주 문맥이라 같은 연결의 모든 창(과 결과 편집)에
 *     적용된다. 그래서 그 창 목록을 보이고 확인을 받는다(취소가 기본). 열린 트랜잭션 질문이 있으면 같은 질문에 적는다.</li>
 *     <li>JDBC 호출은 {@link ProgressDialog}의 작업 스레드에서 한다. 짧은 호출이라 결과를 기다리는 모달 진행 창이 다른 명령과 섞이지 않게 한다.</li>
 *     <li>스마트 커밋({@link SmartCommit})과 맞물림: 스마트 커밋이 수동 커밋으로 바꾼 세션을 여기서 자동 커밋으로 되돌리면 모델이 알리는
 *     {@code handleTransactionAutocommit}으로 스마트 커밋의 되돌릴 목록에서 빠진다. 여기서 수동 커밋으로 바꾼 세션은 스마트 커밋이 바꾼 것이 아니라
 *     커밋·롤백 뒤에도 수동 커밋으로 남는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/transactions.md "커밋 방식 전환")
 */
final class CommitModeSwitch {

    private static final Log log = Log.getLog(CommitModeSwitch.class);

    private enum Outcome {
        SWITCHED, RUNNING, CHANGED
    }

    private CommitModeSwitch() {
    }

    /**
     * Switches the session to the other commit mode after the questions the transaction rule asks. Call in the EDT.
     *
     * @param context    the window's execution context (its own session, or the connection's main context it shares)
     * @param autoCommit the new mode
     * @param shared     the window shares the connection's main context with the other windows of the connection
     * @param windowName the window's file name
     * @return true when the mode was switched
     */
    static boolean switchMode(@NotNull Component parent, @NotNull DBCExecutionContext context, boolean autoCommit, boolean shared,
                              @NotNull String windowName) {
        String connection = context.getDataSource().getContainer().getName();
        String modeName = autoCommit ? LifecycleMessages.commit_mode_auto : LifecycleMessages.commit_mode_manual;
        String sharedNote = null;
        if (shared) {
            String windows = SwingIdleTransactionService.describeQueryWindows(context);
            sharedNote = NLS.bind(LifecycleMessages.commit_mode_shared_note, connection, windows != null ? windows : windowName);
        }
        // Only switching to auto-commit ends a transaction (the driver commits it)
        QMTransactionState transaction = QMUtils.getTransactionState(context);
        boolean open = autoCommit && (transaction.isOpen() || QMUtils.isTransactionActive(context));
        Boolean commit = null;
        if (open) {
            String message = NLS.bind(LifecycleMessages.commit_mode_txn_message, windowName, connection);
            if (sharedNote != null) {
                message += "\n\n" + sharedNote; //$NON-NLS-1$
            }
            message += "\n\n" + LifecycleMessages.commit_mode_txn_cancel_note; //$NON-NLS-1$
            String[] options = {LifecycleMessages.commit_mode_commit_switch, LifecycleMessages.commit_mode_rollback_switch, CoreMessages.common_cancel};
            int answer = JOptionPane.showOptionDialog(parent, message, LifecycleMessages.commit_mode_title,
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
            if (answer != 0 && answer != 1) {
                return false;
            }
            commit = answer == 0;
        } else if (sharedNote != null) {
            String[] options = {NLS.bind(LifecycleMessages.commit_mode_switch_to, modeName), CoreMessages.common_cancel};
            int answer = JOptionPane.showOptionDialog(parent, sharedNote, LifecycleMessages.commit_mode_title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
            if (answer != 0) {
                return false;
            }
        }
        Boolean endTransaction = commit;
        long transactionStart = transaction.getTransactionStartTime();
        Outcome outcome;
        try {
            outcome = ProgressDialog.runTask(LifecycleMessages.commit_mode_progress, monitor -> {
                DBCTransactionManager txnManager = DBUtils.getTransactionManager(context);
                if (txnManager == null) {
                    throw new DBException("The session has no transaction manager"); //$NON-NLS-1$
                }
                QMTransactionState now = QMUtils.getTransactionState(context);
                if (now.isExecuting() || Job.getJobManager().find(context).length > 0) {
                    return Outcome.RUNNING;
                }
                boolean openNow = autoCommit && (now.isOpen() || QMUtils.isTransactionActive(context));
                if (openNow && (endTransaction == null || now.getTransactionStartTime() != transactionStart)) {
                    // Not the transaction the user answered for: switching would commit it without asking
                    return Outcome.CHANGED;
                }
                if (openNow) {
                    try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL,
                        endTransaction ? "Commit before auto-commit" : "Roll back before auto-commit")) { //$NON-NLS-1$ //$NON-NLS-2$
                        if (endTransaction) {
                            txnManager.commit(session);
                        } else {
                            txnManager.rollback(session, null);
                        }
                    }
                }
                txnManager.setAutoCommit(monitor, autoCommit);
                return Outcome.SWITCHED;
            });
        } catch (DBException e) {
            log.warn("Cannot switch the commit mode of " + connection + " (" + context.getContextName() + ")", e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ErrorDialog.show(parent, LifecycleMessages.commit_mode_title, NLS.bind(LifecycleMessages.commit_mode_error, windowName, connection), e);
            return false;
        }
        if (outcome == Outcome.RUNNING || outcome == Outcome.CHANGED) {
            JOptionPane.showMessageDialog(parent,
                outcome == Outcome.RUNNING ? LifecycleMessages.commit_mode_running : LifecycleMessages.commit_mode_changed,
                LifecycleMessages.commit_mode_title, JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        log.info("Switched " + connection + " (" + context.getContextName() + ") to " + (autoCommit ? "auto-commit" : "manual commit") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + (endTransaction == null ? "" : endTransaction ? " after a commit" : " after a rollback")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return true;
    }

    /**
     * The tool tip of the command for the active window's commit mode, or for no mode (not connected, running).
     */
    @NotNull
    static String describe(@Nullable Boolean autoCommit, boolean shared) {
        if (autoCommit == null) {
            return LifecycleMessages.commit_mode_tip_unavailable;
        }
        String tip = autoCommit ? LifecycleMessages.commit_mode_tip_auto : LifecycleMessages.commit_mode_tip_manual;
        return shared ? tip + " " + LifecycleMessages.commit_mode_tip_shared : tip; //$NON-NLS-1$
    }
}
