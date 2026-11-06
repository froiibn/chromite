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

import com.seizia.chromite.ui.swing.internal.ErrorMessages;
import org.eclipse.core.runtime.IStatus;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.DBPPlatformUI;
import com.seizia.chromite.runtime.ui.UIServiceSystemAgent;

/**
 * 오래 걸린 작업이 끝났음을 알리는 서비스({@link UIServiceSystemAgent})의 Swing 구현.
 *
 * 설계: 모델 계층(네이티브 도구 처리기 {@code AbstractNativeToolHandler.notifyToolFinish}, 작업 처리기)은 작업이 끝나면
 * {@code DBWorkbench.getService(UIServiceSystemAgent.class)}로 이 서비스를 찾는다. SWT 제품은 {@code com.seizia.chromite.core}의
 * {@code plugin.xml}({@code com.seizia.chromite.service} 확장)로 {@code UIServiceSystemAgentImpl}(트레이 알림)을 등록하는데
 * Swing 제품에는 그 번들이 없다. 서비스가 없으면 {@code DBWorkbench.getService}가 "Service '…UIServiceSystemAgent' not found"를
 * 디버그 로그로 남기고, 이 로그가 작업 출력(백업·복원의 메시지 페이지)에 클래스 이름째로 나타났다. 그래서 같은 확장으로
 * 이 클래스를 등록하고 알림은 기존 알림 창({@link NotificationToast}, {@link DBPPlatformUI#showNotification})으로 보여 준다.
 * 설정 키와 기본값은 SWT와 같다({@code ChromitePreferences.AGENT_LONG_OPERATION_*}, {@code DesktopPreferencesInitializer}):
 * 같은 작업 공간을 SWT 제품과 함께 써도 사용자의 설정을 따른다. 다만 SWT는 트레이가 있는 Windows에서만 기본으로 알리지만,
 * Swing 알림 창은 모든 OS에 있으므로 기본값을 켬으로 했다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-frontend.md "오류 문구")
 */
public class SwingSystemAgent implements UIServiceSystemAgent {

    // Same keys as ChromitePreferences of com.seizia.chromite.core, which the Swing product does not include
    private static final String PREF_LONG_OPERATION_NOTIFY = "agent.long.operation.notify"; //$NON-NLS-1$
    private static final String PREF_LONG_OPERATION_TIMEOUT = "agent.long.operation.timeout"; //$NON-NLS-1$
    // Seconds, as DesktopPreferencesInitializer
    private static final long DEFAULT_LONG_OPERATION_TIMEOUT = 30;

    @Override
    public long getLongOperationTimeout() {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        return store.contains(PREF_LONG_OPERATION_TIMEOUT) ? store.getLong(PREF_LONG_OPERATION_TIMEOUT) : DEFAULT_LONG_OPERATION_TIMEOUT;
    }

    @Override
    public void notifyAgent(String message, int status) {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        if (store.contains(PREF_LONG_OPERATION_NOTIFY) && !store.getBoolean(PREF_LONG_OPERATION_NOTIFY)) {
            return;
        }
        DBPPlatformUI platformUI = DBWorkbench.getPlatformUI();
        if (status == IStatus.WARNING) {
            platformUI.showWarningNotification(ErrorMessages.agent_long_operation_finished, message);
        } else {
            platformUI.showNotification(ErrorMessages.agent_long_operation_finished, message, status == IStatus.ERROR, null);
        }
    }
}
