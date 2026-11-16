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
package com.seizia.chromite.runtime.ui;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.runtime.DBRShellCommand;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import java.nio.file.Path;
import java.util.Map;

/**
 * Asks the user before Chromite runs a shell command that comes from saved settings: connection events
 * (before/after connect and disconnect), the "password from shell command" auth model and the data transfer
 * "execute process" step.
 *
 * 설계: 이 명령들은 {@code data-sources.json}이나 가져온·공유한 연결 설정, 저장된 작업에 들어 있을 수 있어서
 * 사용자가 방금 입력한 것이 아니다. 그래서 실행하기 전에 정확한 명령을 보여 주고 승인을 받는다.
 * 호출부는 서비스를 직접 찾지 않고 {@link #validate}를 부른다. 서비스가 등록되지 않은 앱(헤드리스 테스트 앱 등)에서는
 * 물을 수 없으므로 실행하지 않는다(fail closed). 전에는 세 호출부가 각자 {@code if (service != null)}로 확인을 건너뛰어
 * Swing 제품에서 승인 없이 실행됐다(docs/chromite/swt-parity/A-platform.md S4).
 * "항상 허용" 기록은 승인 범위({@link #getConnectionScope}: 프로젝트 경로 + 연결 ID)마다 정확한 명령 문자열과 작업 폴더로 묶는다.
 * 범위가 없으면(null) 기억하지 않고 매번 묻는다.
 *
 * 변경 이력:
 * - 2026-11-07 승인 범위 인자와 fail-closed {@link #validate} 추가. 호출부가 없는 {@code isShellCommandExecutionEnabled},
 *   {@code addConfirmedCommand}, {@code FEATURE_CONNECTION_SHELL_COMMANDS} 삭제(SWT 연결 이벤트 편집 화면만 썼다)
 *   (docs/chromite/shell-command-approval.md)
 * - 2026-11-08 {@link #getTaskScope}: 저장된 네이티브 도구 작업(백업, 복원, 스크립트 실행)의 명령줄 승인 범위
 *   (docs/chromite/shell-command-approval.md "저장된 네이티브 도구 작업")
 */
public interface UIServiceShellCommands {

    /**
     * Returns when the user approves the command, now or earlier for the same scope; throws when the user declines.
     *
     * @param approvalScope   what a remembered approval is bound to ({@link #getConnectionScope}); {@code null}: the approval
     *                        is not remembered and the user is asked every time
     * @param approvalContext label and value lines shown with the command: project, connection, triggering event
     */
    void validateByUser(
        @NotNull DBRShellCommand command,
        @Nullable String approvalScope,
        @NotNull Map<String, String> approvalContext
    ) throws DBException;

    /**
     * Asks the registered service. Without one nobody can approve the command, so it is refused (fail closed).
     */
    static void validate(
        @NotNull DBRShellCommand command,
        @Nullable String approvalScope,
        @NotNull Map<String, String> approvalContext
    ) throws DBException {
        UIServiceShellCommands service = DBWorkbench.getService(UIServiceShellCommands.class);
        if (service == null) {
            throw new DBException("Shell command was not run because this application cannot ask for approval: "
                + getCommandText(command));
        }
        service.validateByUser(command, approvalScope, approvalContext);
    }

    /**
     * Approval scope of a connection: its project folder and ID. A test connection (temporary copy) uses the connection
     * it was made from, so an approval given while testing applies to the saved connection.
     *
     * @return {@code null} when the connection has no stable identity
     */
    @Nullable
    static String getConnectionScope(@NotNull DBPDataSourceContainer container) {
        DBPDataSourceContainer identity = container;
        if (container.isTemporary()
            && container.getConnectionConfiguration().getRuntimeAttribute(DBPConnectionConfiguration.ATTRIBUTE_TEST_CONNECTION_SOURCE)
            instanceof DBPDataSourceContainer original) {
            identity = original;
        }
        Path projectPath = identity.getProject() == null ? null : identity.getProject().getAbsolutePath();
        String id = identity.getId();
        if (projectPath == null || CommonUtils.isEmpty(id)) {
            return null;
        }
        return "connection:" + projectPath.toAbsolutePath().normalize() + "|" + id;
    }

    /**
     * Approval scope of a saved task: its project folder and ID. An approval given for one task does not apply to another
     * task that runs the same command, nor to a task imported from another workspace.
     *
     * @return {@code null} when the task has no stable identity (a temporary task)
     */
    @Nullable
    static String getTaskScope(@NotNull DBTTask task) {
        Path projectPath = task.getProject().getAbsolutePath();
        if (task.isTemporary() || projectPath == null) {
            return null;
        }
        return "task:" + projectPath.toAbsolutePath().normalize() + "|" + task.getId();
    }

    /**
     * The command as it is stored and shown: the command line, or its parameters when the command was built from them.
     */
    @NotNull
    static String getCommandText(@NotNull DBRShellCommand command) {
        if (!command.isBlank()) {
            return command.getCommand();
        }
        return command.getCommandParams() == null ? "" : String.join(" ", command.getCommandParams());
    }
}
