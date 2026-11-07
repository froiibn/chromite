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
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.preferences.ConfirmedActionsStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceConnectionInit;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.SecurityUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks before a connection runs initialization SQL from its settings before the JDBC connection exists
 * ({@link UIServiceConnectionInit}): the H2 {@code INIT=} URL setting and the H2 connection's bootstrap queries.
 *
 * 설계: SWT 제품은 {@code com.seizia.chromite.core}의 {@code UIServiceConnectionInitImpl}이 이 일을 했는데 Swing 제품에는 그 번들이
 * 없어 서비스가 없었다. 모델은 서비스가 없으면 승인 안 됨으로 보고 연결을 거부했다(안전한 쪽으로 실패, docs/chromite/swt-parity/A-platform.md S10,
 * D-db-specific.md K4). 그래서 같은 서비스를 {@code plugin.xml}의 {@code com.seizia.chromite.service} 확장으로 등록한다.
 * <ul>
 *     <li>묻기: {@link ExecutionApprovalDialog}(셸 명령 승인 #99와 같은 모양, 기본 단추는 취소).</li>
 *     <li>기억: 허용하면 SWT처럼 그 연결(프로젝트·연결 ID·드라이버)·URL·SQL 묶음을 SHA-256 키로 전역 설정 파일
 *     {@value #APPROVALS_FILE_NAME}에 기록한다. 파일 이름과 키 계산이 SWT와 같아 SWT에서 허용한 것은 다시 묻지 않는다.
 *     SQL이나 URL이 하나라도 바뀌면 키가 달라져 다시 묻는다.</li>
 *     <li>취소하면 거짓을 돌려주고 모델이 연결을 멈춘다("not approved. Connection cancelled"). 아무것도 실행되지 않는다.</li>
 *     <li>헤드리스 모드에서는 묻지 않고 거부한다(SWT와 같음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-lifecycle.md "초기화 SQL과 SQLite 확장 승인")
 */
public class SwingConnectionInitService implements UIServiceConnectionInit {

    private static final Log log = Log.getLog(SwingConnectionInitService.class);

    // The same file as the SWT product's UIServiceConnectionInitImpl, so that its approvals still count
    private static final String APPROVALS_FILE_NAME = "confirmed_connection_init_scripts.json"; //$NON-NLS-1$
    private static final Object APPROVALS_LOCK = new Object();

    @Override
    public boolean confirmInitScript(@NotNull DBPDataSourceContainer container, @Nullable String url, @NotNull String script) {
        return confirmInitScripts(container, url, List.of(script));
    }

    @Override
    public boolean confirmInitScripts(@NotNull DBPDataSourceContainer container, @Nullable String url, @NotNull List<String> scripts) {
        List<String> approvedScripts = List.copyOf(scripts);
        if (DBWorkbench.getPlatform().getApplication().isHeadlessMode()) {
            return false;
        }
        ConfirmedActionsStore approvals = new ConfirmedActionsStore(DBWorkbench.getPlatform().getGlobalConfigurationFile(APPROVALS_FILE_NAME));
        String key = approvalKey(container, url, approvedScripts);
        try {
            synchronized (APPROVALS_LOCK) {
                if (approvals.contains(key)) {
                    return true;
                }
            }
        } catch (DBException e) {
            log.error("Cannot read connection initialization approvals", e);
            return false;
        }
        Map<String, String> context = new LinkedHashMap<>();
        context.put(LifecycleMessages.approval_connection, container.getName());
        if (url != null) {
            context.put(LifecycleMessages.approval_url, url);
        }
        Map<String, String> execution = new LinkedHashMap<>();
        for (int i = 0; i < approvedScripts.size(); i++) {
            String label = approvedScripts.size() > 1
                ? LifecycleMessages.init_approval_script + " " + (i + 1) //$NON-NLS-1$
                : LifecycleMessages.init_approval_script;
            execution.put(label, approvedScripts.get(i));
        }
        boolean approved = ExecutionApprovalDialog.ask(
            LifecycleMessages.init_approval_title,
            LifecycleMessages.init_approval_message,
            LifecycleMessages.approval_allow_connect,
            context,
            execution,
            LifecycleMessages.init_approval_remember_note,
            LifecycleMessages.approval_cancel_note);
        if (approved) {
            try {
                synchronized (APPROVALS_LOCK) {
                    approvals.add(key);
                }
            } catch (DBException e) {
                log.error("Cannot save connection initialization approval", e);
                return false;
            }
        }
        return approved;
    }

    /**
     * The approval key of the SWT product's {@code UIServiceConnectionInitImpl}: the same connection, driver, URL and SQL.
     */
    @NotNull
    private static String approvalKey(@NotNull DBPDataSourceContainer container, @Nullable String url, @NotNull List<String> scripts) {
        return SecurityUtils.sha256Hex(JSONUtils.GSON.toJson(new Object[]{
            container.getProject().getAbsolutePath().toAbsolutePath().normalize().toString(),
            container.getId(),
            container.getDriver().getFullId(),
            url,
            scripts
        }));
    }
}
