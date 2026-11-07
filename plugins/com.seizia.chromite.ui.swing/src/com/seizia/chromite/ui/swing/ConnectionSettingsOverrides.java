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

import com.formdev.flatlaf.FlatClientProperties;
import com.seizia.chromite.ext.altibase.AltibaseDataSourceProvider;
import com.seizia.chromite.ext.cubrid.CubridDataSourceProvider;
import com.seizia.chromite.ext.oracle.OracleDataSourceProvider;
import com.seizia.chromite.model.DBPDataSourceProvider;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Connection dialog, Options &gt; Settings: the Tools &gt; Options pages whose model settings one connection can set
 * differently (SWT: the connection's property pages built on {@code TargetPrefPage}).
 *
 * 설계:
 * <ul>
 *     <li>페이지는 Options와 같은 {@link SettingsPage} 클래스다(Query Execution, Query Results, Binary and LOB, Data Formats, Errors and Timeouts,
 *     Metadata, Client Identification, Code Completion, Data Transfer, Oracle·Altibase·CUBRID 연결이면 그 데이터베이스 페이지). 페이지마다 위에
 *     "이 연결에 다른 설정 사용" 확인란이 있다(SWT의 "Enable datasource-specific settings"). 끄면 페이지는 Options의 값을 보이고 고칠 수 없다.
 *     Options의 값은 페이지의 전역 저장소({@link SettingsPage#getGlobalStore})에서 읽는다.</li>
 *     <li>처음 상태: 연결의 설정 저장소가 페이지의 키 중 하나라도 자기 값으로 가지면 켜짐(SWT {@code hasDataSourceSpecificOptions}와 같은 규칙).</li>
 *     <li>저장({@link #saveSettings}): 켠 페이지는 모든 값을 연결의 값으로 쓰고({@link SettingsPage.Writer} 연결별 모드), 끈 페이지는 연결의 값을 지운다.
 *     값은 연결 설정 파일의 {@code custom-properties}에 저장된다(모델의 직렬화가 기본값과 다른 값만 씀).</li>
 *     <li>다시 연결: 모델이 작업마다 읽는 페이지의 키는 {@link #getKeysAppliedWithoutReconnect}로 알려 연결 대화상자가 다시 연결을 묻지 않게 한다.
 *     세션을 열 때 읽는 페이지(Client Identification, Oracle)는 기존 "다시 연결할까요" 질문을 거치고, 다시 연결은 열린 트랜잭션을 먼저 묻는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "연결별 설정")
 * - 2026-11-08 Binary and LOB, Code Completion, Data Transfer 페이지와 Altibase·CUBRID 연결의 페이지, 끈 페이지는 페이지의 전역 저장소 값을 보임
 *   (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class ConnectionSettingsOverrides extends JPanel {

    private final List<Entry> entries = new ArrayList<>();
    private final JTabbedPane tabs = new JTabbedPane(SwingConstants.LEFT);

    /**
     * @param dataSource the edited (or new) connection; it is only read here
     */
    ConnectionSettingsOverrides(@NotNull DataSourceDescriptor dataSource) {
        super(new BorderLayout());
        List<SettingsPage> pages = new ArrayList<>(List.of(
            new SqlExecutionOptionsPage(false), new ResultSetOptionsPage(false), new ContentOptionsPage(false),
            new DataFormatOptionsPage(false), new ConnectionErrorsOptionsPage(false), new MetadataOptionsPage(false),
            new ClientIdentificationOptionsPage(false), new CodeCompletionOptionsPage(false), new DataTransferOptionsPage(false)));
        DBPDataSourceProvider<?> provider = dataSource.getDriver().getDataSourceProvider();
        if (provider instanceof OracleDataSourceProvider) {
            pages.add(new OracleOptionsPage(false));
        } else if (provider instanceof AltibaseDataSourceProvider) {
            pages.add(new AltibaseOptionsPage(false));
        } else if (provider instanceof CubridDataSourceProvider) {
            pages.add(new CubridOptionsPage(false));
        }
        DBPPreferenceStore store = dataSource.getPreferenceStore();
        for (SettingsPage page : pages) {
            Entry entry = new Entry(page);
            entries.add(entry);
            // The Options tabs of the connection dialog are low and narrower than the Options dialog: pages scroll
            // (the hints right of the fields would be cut off otherwise)
            JScrollPane scrollPane = new JScrollPane(entry.panel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.getVerticalScrollBar().setUnitIncrement(16);
            tabs.addTab(page.getTitle(), scrollPane);
            boolean override = page.getKeys().stream().anyMatch(store::contains);
            // The connection's values, or the global ones for the keys it does not set
            page.load(store);
            entry.overrideBox.setSelected(override);
            entry.overrideBox.addActionListener(e -> overrideChanged(entry));
            entry.updateState();
        }
        add(tabs, BorderLayout.CENTER);
        updateTabIcons();
    }

    /**
     * Writes the settings into a connection: the values of the pages it overrides, none for the others.
     */
    void saveSettings(@NotNull DataSourceDescriptor target) {
        DBPPreferenceStore store = target.getPreferenceStore();
        for (Entry entry : entries) {
            if (entry.overrideBox.isSelected()) {
                entry.page.save(new SettingsPage.Writer(store, true));
            } else {
                entry.page.clear(store);
            }
        }
    }

    /**
     * The keys of the pages the model reads at each operation: a change of them needs no reconnect.
     */
    @NotNull
    Set<String> getKeysAppliedWithoutReconnect() {
        Set<String> keys = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry.page.isAppliedWithoutReconnect()) {
                keys.addAll(entry.page.getKeys());
            }
        }
        return keys;
    }

    private void overrideChanged(@NotNull Entry entry) {
        if (!entry.overrideBox.isSelected()) {
            // Back to the global settings: show what applies now
            entry.page.load(entry.page.getGlobalStore());
        }
        entry.updateState();
        updateTabIcons();
    }

    /**
     * A check mark on the pages the connection overrides, as on the network handler tabs.
     */
    private void updateTabIcons() {
        for (int i = 0; i < entries.size(); i++) {
            tabs.setIconAt(i, entries.get(i).overrideBox.isSelected() ? UiIcons.get("check") : null); //$NON-NLS-1$
        }
    }

    private static void setEnabledDeep(@NotNull Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setEnabledDeep(child, enabled);
            }
        }
    }

    /**
     * A page with its "use different settings for this connection" check box.
     */
    private static final class Entry {
        final SettingsPage page;
        final JCheckBox overrideBox = new JCheckBox(SettingsMessages.overrides_use_connection);
        final JPanel panel = new JPanel(new BorderLayout(0, 6));

        Entry(@NotNull SettingsPage page) {
            this.page = page;
            JLabel hint = new JLabel(SettingsMessages.overrides_hint);
            hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            JPanel header = new JPanel(new GridLayout(0, 1, 0, 2));
            header.add(overrideBox);
            header.add(hint);
            panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            panel.add(header, BorderLayout.NORTH);
            panel.add(page, BorderLayout.CENTER);
        }

        void updateState() {
            setEnabledDeep(page, overrideBox.isSelected());
            if (overrideBox.isSelected()) {
                page.updateEnablement();
            }
        }
    }
}
