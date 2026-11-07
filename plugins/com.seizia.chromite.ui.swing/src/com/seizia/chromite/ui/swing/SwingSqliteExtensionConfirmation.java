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

import com.seizia.chromite.ext.sqlite.SQLiteExtensionConfirmation;
import com.seizia.chromite.ext.sqlite.SQLiteExtensionSettings;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import org.jkiss.code.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Asks before a SQLite connection loads native extensions ({@link SQLiteExtensionConfirmation}): the
 * {@code enable_load_extension} driver setting, the extension libraries and their initialization SQL.
 *
 * 설계: SWT 제품은 {@code com.seizia.chromite.ext.sqlite.ui}의 {@code SQLiteExtensionConfirmationImpl}이 이 일을 했는데 Swing 제품에는
 * 그 번들이 없어 서비스가 없었다. 모델은 서비스가 없으면 승인 안 됨으로 보고 연결을 거부했다(안전한 쪽으로 실패,
 * docs/chromite/swt-parity/A-platform.md S11, D-db-specific.md K5). 그래서 같은 서비스를 {@code plugin.xml}에 등록한다.
 * <ul>
 *     <li>묻기: {@link ExecutionApprovalDialog}(셸 명령 승인 #99와 같은 모양, 기본 단추는 취소). 내용은 SWT와 같다: 연결, URL,
 *     라이브러리 목록, 초기화 SQL.</li>
 *     <li>기억은 모델이 한다({@code SQLiteExtensionPermissionStore}: 설정의 지문이 같으면 다시 묻지 않고, 설정이 바뀌면 다시 묻는다).
 *     이 클래스는 답만 돌려준다.</li>
 *     <li>취소하면 모델이 연결을 멈춘다("not approved. Connection cancelled"). 확장은 불러오지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-lifecycle.md "초기화 SQL과 SQLite 확장 승인")
 */
public class SwingSqliteExtensionConfirmation implements SQLiteExtensionConfirmation {

    @Override
    public boolean confirmExtensionLoading(@NotNull SQLiteExtensionSettings settings, boolean connecting) {
        Map<String, String> context = new LinkedHashMap<>();
        context.put(LifecycleMessages.approval_connection, settings.connectionName());
        context.put(LifecycleMessages.approval_url, settings.url());
        Map<String, String> execution = new LinkedHashMap<>();
        if (!settings.libraries().isEmpty()) {
            execution.put(LifecycleMessages.sqlite_ext_libraries, String.join("\n", settings.libraries())); //$NON-NLS-1$
        }
        if (!settings.initQueries().isEmpty()) {
            execution.put(LifecycleMessages.sqlite_ext_queries, String.join(";\n", settings.initQueries())); //$NON-NLS-1$
        }
        return ExecutionApprovalDialog.ask(
            LifecycleMessages.sqlite_ext_title,
            LifecycleMessages.sqlite_ext_message,
            connecting ? LifecycleMessages.approval_allow_connect : LifecycleMessages.approval_allow,
            context,
            execution,
            LifecycleMessages.sqlite_ext_remember_note,
            LifecycleMessages.approval_cancel_note);
    }
}
