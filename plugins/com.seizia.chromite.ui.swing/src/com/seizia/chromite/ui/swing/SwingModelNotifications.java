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
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPMessageType;
import com.seizia.chromite.runtime.ChromiteNotifications;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Shows the model's notifications ({@link ChromiteNotifications}: idle connection closed, connection recovered or not
 * recovered after a lost connection) in the main window's notification area.
 *
 * 설계: SWT 앱은 {@code CoreApplicationActivator}가 처리기를 등록해 알림 팝업으로 보였다. Swing은 등록하지 않아 기본 처리기가
 * {@code System.out}에만 썼다. 같은 알림 창({@link NotificationToast})을 쓰고 알림의 종류(정보·경고·오류)를 그대로 옮긴다.
 * <ul>
 *     <li>제목은 연결 이름(데이터 소스가 있는 알림) 또는 보낸 쪽의 제목이다(SWT {@code NotificationUtils}와 같음).</li>
 *     <li>경고와 오류는 닫을 때까지 남는다. 이 알림들은 백그라운드에서, 자리를 비운 사이(유휴 연결 닫기) 생기므로 시간이 지나 사라지면
 *     돌아온 사용자가 볼 수 없다. 유휴 트랜잭션 알림과 같은 이유다. 정보(다시 연결 성공)는 잠시 뒤 사라진다.</li>
 *     <li>누르면 보낸 쪽의 동작(다시 연결 실패: 연결 편집)을 실행한다.</li>
 *     <li>메인 창이 없으면(시작, 종료 중) 로그에만 남긴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/idle-transactions.md "모델 알림")
 */
final class SwingModelNotifications implements ChromiteNotifications.NotificationHandler {

    private static final Log log = Log.getLog(SwingModelNotifications.class);

    @Override
    public void sendNotification(
        @NotNull DBPDataSource dataSource,
        @NotNull String id,
        @NotNull String text,
        @Nullable DBPMessageType messageType,
        @Nullable Runnable feedback
    ) {
        sendNotification(id, dataSource.getContainer().getName(), text, messageType, feedback);
    }

    @Override
    public void sendNotification(
        @NotNull String id,
        @NotNull String title,
        @NotNull String text,
        @Nullable DBPMessageType messageType,
        @Nullable Runnable feedback
    ) {
        NotificationToast.Kind kind = messageType == DBPMessageType.ERROR ? NotificationToast.Kind.ERROR
            : messageType == DBPMessageType.WARNING ? NotificationToast.Kind.WARNING
            : NotificationToast.Kind.INFO;
        String logText = title + ": " + text + " (" + id + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        switch (kind) {
            case ERROR -> log.error(logText);
            case WARNING -> log.warn(logText);
            default -> log.info(logText);
        }
        String message = ErrorText.clean(text);
        SwingUtilities.invokeLater(() -> {
            for (Frame frame : Frame.getFrames()) {
                if (frame instanceof MainFrame mainFrame && mainFrame.isDisplayable()) {
                    if (kind == NotificationToast.Kind.INFO) {
                        NotificationToast.show(mainFrame, title, message, kind, feedback);
                    } else {
                        NotificationToast.showUntilClosed(mainFrame, title, message, kind, feedback);
                    }
                    return;
                }
            }
        });
    }
}
