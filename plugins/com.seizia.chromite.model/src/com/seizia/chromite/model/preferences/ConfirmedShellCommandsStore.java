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
package com.seizia.chromite.model.preferences;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.runtime.DBRShellCommand;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceShellCommands;
import org.jkiss.utils.CommonUtils;

import java.util.List;

/**
 * Shell commands the user chose to "Always allow", per approval scope (see {@link UIServiceShellCommands}).
 *
 * 설계: SWT와 같은 파일({@value #CONFIRMED_COMMANDS_FILE_NAME}, 전역 설정 폴더의 JSON 문자열 배열)에 둔다.
 * 프로젝트 폴더 밖이라서 가져오거나 공유한 연결 설정이 승인을 함께 들여올 수 없다.
 * SWT는 명령 문자열만 기억해 어느 연결에서든 같은 명령을 묻지 않고 실행했다. Chromite는 항목 하나를
 * [승인 범위, 작업 폴더, 명령] JSON 배열 문자열로 만든다. 그래서 다른 연결, 바뀐 명령, 바뀐 작업 폴더
 * (같은 상대 경로 명령이 다른 파일을 가리킬 수 있다)는 다시 묻는다. JSON 배열이라 어떤 문자가 들어 있어도 부분끼리 섞이지 않는다.
 * SWT가 남긴 명령만 있는 항목은 이 형식과 맞지 않아 쓰이지 않고 파일에 그대로 남는다(전역 승인을 이어받지 않는다).
 * 읽고 쓰기는 {@link ConfirmedActionsStore}(SQLite 확장 승인과 같은 저장 방식, 임시 파일 뒤 교체)가 한다.
 *
 * 변경 이력:
 * - 2026-11-07 명령만 기억하던 전역 승인을 승인 범위·작업 폴더·명령으로 묶은 승인으로 바꿈. 직접 쓰던 파일 읽기·쓰기를
 *   {@link ConfirmedActionsStore}로 바꾸고, 호출부가 없는 {@code prepareFile} 삭제(docs/chromite/shell-command-approval.md)
 */
public final class ConfirmedShellCommandsStore {

    public static final String CONFIRMED_COMMANDS_FILE_NAME = "confirmed_shell_commands.json";

    private static ConfirmedShellCommandsStore instance;

    private final ConfirmedActionsStore actions;

    private ConfirmedShellCommandsStore() {
        actions = new ConfirmedActionsStore(DBWorkbench.getPlatform().getGlobalConfigurationFile(CONFIRMED_COMMANDS_FILE_NAME));
    }

    @NotNull
    public static synchronized ConfirmedShellCommandsStore getInstance() {
        if (instance == null) {
            instance = new ConfirmedShellCommandsStore();
        }
        return instance;
    }

    public boolean isApproved(@NotNull String approvalScope, @NotNull DBRShellCommand command) throws DBException {
        return actions.contains(approvalKey(approvalScope, command));
    }

    public void approve(@NotNull String approvalScope, @NotNull DBRShellCommand command) throws DBException {
        actions.add(approvalKey(approvalScope, command));
    }

    @NotNull
    private static String approvalKey(@NotNull String approvalScope, @NotNull DBRShellCommand command) {
        return JSONUtils.GSON.toJson(List.of(
            approvalScope,
            CommonUtils.notEmpty(command.getWorkingDirectory()),
            UIServiceShellCommands.getCommandText(command)));
    }
}
