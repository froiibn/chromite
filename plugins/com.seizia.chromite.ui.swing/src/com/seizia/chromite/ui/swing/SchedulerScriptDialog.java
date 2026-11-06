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

import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.ui.swing.internal.SchedulerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Common part of the job scheduler dialogs (Oracle Scheduler job, pg_cron job, MySQL event): OK shows the script, then
 * runs it in an isolated connection and reads the explorer node again.
 *
 * 설계: SQL Server 에이전트의 {@link AgentObjectDialog}와 같은 흐름이다(그 클래스는 SQL Server 에이전트 모델에 묶여 있어 그대로 두고,
 * DB마다 다른 부분은 {@link SchedulerCommands.Backend}로 받는다). 확인을 누르면 실행할 스크립트를 먼저 보여 주고
 * ({@link ScriptPreviewDialog}: 실행 / 쿼리 창으로 열기 / 복사 / 취소) 실행을 고르면 모델이 격리된 연결에서 실행한다. 성공하면 창을 닫고
 * 탐색기 노드를 다시 읽는다. 실패하면 메시지 페이지에 오류와 실패한 문을 남기고 창은 열어 둔다(고쳐서 다시 실행).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
abstract class SchedulerScriptDialog extends DatabaseTaskDialog {

    private final SchedulerCommands.Backend backend;
    @Nullable
    private final DBNDatabaseNode refreshNode;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    @Nullable
    private List<String> statements;

    /**
     * @param refreshNode explorer node read again after a successful run (the folder or the scheduler node), or null
     */
    SchedulerScriptDialog(
        @NotNull Component owner,
        @NotNull String title,
        @NotNull SchedulerCommands.Backend backend,
        @Nullable DBNDatabaseNode refreshNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, title, backend.container());
        this.backend = backend;
        this.refreshNode = refreshNode;
        this.queryWindows = queryWindows;
    }

    /**
     * The statements that make the change from the current input (empty when nothing changed).
     */
    @NotNull
    protected abstract List<String> buildStatements();

    /**
     * Why the login may not make this change (a missing privilege), or null; checked before the script is shown.
     */
    @Nullable
    protected String getDeniedReason() {
        return null;
    }

    @Override
    protected boolean confirmRun() {
        List<String> built = buildStatements();
        if (built.isEmpty()) {
            // Nothing changed: OK just closes, as in the property dialogs this one follows
            dispose();
            return false;
        }
        if (!SchedulerCommands.checkEditAllowed(this, backend.container(), getTitle())) {
            return false;
        }
        String denied = getDeniedReason();
        if (denied != null) {
            JOptionPane.showMessageDialog(this, denied, getTitle(), JOptionPane.WARNING_MESSAGE);
            return false;
        }
        String script = backend.formatScript(built);
        ScriptPreviewDialog.Result choice = ScriptPreviewDialog.show(this, NLS.bind(SchedulerMessages.scheduler_script_title, getTitle()), script, true);
        if (choice == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            queryWindows.openScript(backend.container(), script, 0, false);
            dispose();
            return false;
        }
        if (choice != ScriptPreviewDialog.Result.EXECUTE) {
            return false;
        }
        statements = built;
        return true;
    }

    @NotNull
    @Override
    protected TaskRun prepareRun() {
        List<String> run = statements == null ? buildStatements() : statements;
        return monitor -> backend.execute(monitor, run, this::appendMessage);
    }

    @NotNull
    @Override
    protected String getSuccessText() {
        return SchedulerMessages.scheduler_success;
    }

    @Override
    protected void runSucceeded() {
        backend.changed();
        SchedulerCommands.refreshNode(refreshNode);
        dispose();
    }

    @NotNull
    @Override
    protected String getErrorText(@NotNull Throwable error) {
        String text = super.getErrorText(error);
        String statement = SchedulerCommands.getFailedStatement(error);
        return statement == null ? text : text + "\n\n" + NLS.bind(SchedulerMessages.scheduler_failed_statement, statement); //$NON-NLS-1$
    }

    @NotNull
    @Override
    protected List<Action> getScriptActions() {
        return createSqlScriptActions(queryWindows, () -> {
            List<String> built = buildStatements();
            return built.isEmpty() ? "-- " + SchedulerMessages.scheduler_no_changes + "\n" : backend.formatScript(built); //$NON-NLS-1$ //$NON-NLS-2$
        });
    }
}
