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

import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.utils.PrefUtils;

import javax.swing.*;

/**
 * 자동 새로 고침 간격 콤보 (Activity Monitor의 세션·잠금 탭).
 *
 * 설계: SWT의 {@code AutoRefreshControl}(새로 고침 단추의 간격 메뉴)에 해당한다. SSMS Activity Monitor처럼 간격을 콤보로 고르고,
 * 기본값은 SSMS와 같은 10초다. 고른 간격은 탭 종류마다 이 번들의 설정 저장소에 둔다.
 * 타이머는 탭이 화면에 보일 때만 새로 고침을 부른다(다른 문서 탭 뒤에 있으면 서버를 읽지 않는다). 읽는 중이면 그 차례는 건너뛴다(호출하는 쪽이 판단).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 */
final class AutoRefreshBox extends JComboBox<AutoRefreshBox.Interval> {

    private static final int DEFAULT_SECONDS = 10;

    record Interval(int seconds) {
        @Override
        public String toString() {
            return seconds == 0 ? ConnectionMessages.auto_refresh_off
                : seconds < 60 ? NLS.bind(ConnectionMessages.auto_refresh_seconds, seconds)
                : NLS.bind(ConnectionMessages.auto_refresh_minutes, seconds / 60);
        }
    }

    private final Timer timer;

    /**
     * @param preferenceKey where the interval is saved
     * @param owner         the refresh runs only while it is showing
     * @param refresh       called on the EDT at each interval
     */
    AutoRefreshBox(@NotNull String preferenceKey, @NotNull JComponent owner, @NotNull Runnable refresh) {
        super(new Interval[] {new Interval(0), new Interval(2), new Interval(5), new Interval(10), new Interval(30), new Interval(60)});
        setToolTipText(ConnectionMessages.auto_refresh_tip);
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        int seconds = preferences.contains(preferenceKey) ? preferences.getInt(preferenceKey) : DEFAULT_SECONDS;
        setSelectedItem(new Interval(seconds));
        if (getSelectedIndex() < 0) {
            setSelectedItem(new Interval(DEFAULT_SECONDS));
        }
        timer = new Timer(1000, e -> {
            if (owner.isShowing()) {
                refresh.run();
            }
        });
        timer.setRepeats(true);
        addActionListener(e -> {
            applyInterval();
            preferences.setValue(preferenceKey, getSeconds());
            PrefUtils.savePreferenceStore(preferences);
        });
        applyInterval();
    }

    private int getSeconds() {
        return getSelectedItem() instanceof Interval interval ? interval.seconds() : 0;
    }

    private void applyInterval() {
        int seconds = getSeconds();
        timer.stop();
        if (seconds > 0) {
            timer.setDelay(seconds * 1000);
            timer.setInitialDelay(seconds * 1000);
            timer.start();
        }
    }

    /**
     * Stops the timer (the window is closing).
     */
    void dispose() {
        timer.stop();
    }
}
