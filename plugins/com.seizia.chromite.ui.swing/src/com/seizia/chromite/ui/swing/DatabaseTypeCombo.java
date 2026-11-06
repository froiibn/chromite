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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Connect 대화상자 첫 줄의 "Database type" 콤보.
 *
 * 설계: SSMS의 "Server type" 콤보처럼 자주 쓰는 종류만 바로 고르게 하고, 나머지는 콤보 끝의 "More database types…"가 여는
 * 검색 창({@link DriverSelectionPanel}: 검색, 범주, 전체 드라이버)에서 고른다(docs/chromite/design-spec.md "11. 대화상자").
 * 드라이버가 100개가 넘어 콤보 하나에 모두 넣으면 찾기 어렵고, 편집 가능한 콤보로 걸러 내면 공급자 아이콘을 편집 칸에 보일 수 없기 때문이다.
 * <ul>
 *     <li>맨 위: 공개판에 드라이버가 내장된 주요 DB({@link #FAVORITES}: SQL Server, PostgreSQL, MySQL, MariaDB, Oracle, SQLite) 중 이 제품에 있는 것.</li>
 *     <li>그다음: 최근에 연결을 만든 다른 드라이버({@link ConnectionHistory#getRecentDrivers()}), 더 찾기에서 고른 드라이버.</li>
 *     <li>콤보에 포커스가 있을 때 글자를 치면 이름이 그 글자들로 시작하는 항목으로 간다(1초 안에 친 글자를 이어 붙임).</li>
 * </ul>
 * 묶음 사이에는 1px 구분선을 그린다(색은 테마 키 {@code Chromite.dividerColor}, 없으면 {@code Separator.foreground}).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: SSMS식 Connect 대화상자 (docs/chromite/swing-frontend.md "Connect 대화상자 (SSMS식)")
 */
final class DatabaseTypeCombo extends JComboBox<Object> {

    /**
     * Full driver IDs ({@code provider:driver}) shown first, in this order.
     */
    private static final List<String> FAVORITES = List.of(
        "sqlserver:microsoft", //$NON-NLS-1$
        "postgresql:postgres-jdbc", //$NON-NLS-1$
        "mysql:mysql8", //$NON-NLS-1$
        "mysql:mariaDB", //$NON-NLS-1$
        "oracle:oracle_thin", //$NON-NLS-1$
        "sqlite:sqlite_jdbc"); //$NON-NLS-1$

    private static final Object MORE_ITEM = new Object() {
        @Override
        public String toString() {
            return "More database types…";
        }
    };

    private final DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
    private final Consumer<DBPDriver> onDriverSelected;
    private int favoriteCount;
    @Nullable
    private DBPDriver selectedDriver;
    private boolean updating;

    /**
     * @param initial          the driver to select
     * @param onDriverSelected called when the user selects another driver
     */
    DatabaseTypeCombo(@NotNull DBPDriver initial, @NotNull Consumer<DBPDriver> onDriverSelected) {
        this.onDriverSelected = onDriverSelected;
        setModel(model);
        setRenderer(new DriverRenderer());
        setMaximumRowCount(16);
        setKeySelectionManager(new PrefixKeySelectionManager());

        Map<String, DBPDriver> connectable = new LinkedHashMap<>();
        for (DBPDriver driver : DriverSelectionPanel.getConnectableDrivers()) {
            connectable.put(driver.getFullId(), driver);
        }
        for (String id : FAVORITES) {
            DBPDriver driver = connectable.get(id);
            if (driver != null) {
                model.addElement(driver);
            }
        }
        favoriteCount = model.getSize();
        for (String id : ConnectionHistory.getRecentDrivers()) {
            DBPDriver driver = connectable.get(id);
            if (driver != null && model.getIndexOf(driver) < 0) {
                model.addElement(driver);
            }
        }
        model.addElement(MORE_ITEM);
        selectDriver(initial);

        addActionListener(e -> itemChosen());
        addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // The list is still visible while the clicked item is selected: apply the choice after it closes
                SwingUtilities.invokeLater(() -> itemChosen());
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
    }

    /**
     * The first driver of the list (SQL Server), for a new connection.
     */
    @Nullable
    static DBPDriver getDefaultDriver() {
        List<DBPDriver> drivers = DriverSelectionPanel.getConnectableDrivers();
        for (String id : FAVORITES) {
            for (DBPDriver driver : drivers) {
                if (driver.getFullId().equals(id)) {
                    return driver;
                }
            }
        }
        return drivers.isEmpty() ? null : drivers.getFirst();
    }

    @Nullable
    DBPDriver getSelectedDriver() {
        return selectedDriver;
    }

    private void selectDriver(@NotNull DBPDriver driver) {
        updating = true;
        try {
            if (model.getIndexOf(driver) < 0) {
                // Chosen in "More database types…": shown after the favorites
                model.insertElementAt(driver, favoriteCount);
            }
            model.setSelectedItem(driver);
            selectedDriver = driver;
        } finally {
            updating = false;
        }
    }

    private void itemChosen() {
        if (updating) {
            return;
        }
        if (isPopupVisible()) {
            // Moving through the open list (keyboard) or clicking an item: chosen when the list closes
            return;
        }
        Object item = getSelectedItem();
        if (item == MORE_ITEM) {
            DBPDriver previous = selectedDriver;
            DBPDriver chosen = chooseDriver();
            selectDriver(chosen != null ? chosen : previous);
            if (chosen != null && chosen != previous) {
                onDriverSelected.accept(chosen);
            }
        } else if (item instanceof DBPDriver driver && driver != selectedDriver) {
            selectedDriver = driver;
            onDriverSelected.accept(driver);
        }
    }

    /**
     * Opens the searchable list of all drivers.
     */
    @Nullable
    private DBPDriver chooseDriver() {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Database Type", Dialog.ModalityType.APPLICATION_MODAL);
        DBPDriver[] result = new DBPDriver[1];
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        DriverSelectionPanel[] panel = new DriverSelectionPanel[1];
        Runnable choose = () -> {
            result[0] = panel[0].getSelectedDriver();
            if (result[0] != null) {
                dialog.dispose();
            }
        };
        panel[0] = new DriverSelectionPanel(() -> okButton.setEnabled(panel[0] != null && panel[0].getSelectedDriver() != null), choose);
        okButton.addActionListener(e -> choose.run());
        cancelButton.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(panel[0], BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setSize(520, 560);
        dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
        okButton.setEnabled(panel[0].getSelectedDriver() != null);
        SwingUtilities.invokeLater(panel[0]::focusSearch);
        dialog.setVisible(true);
        return result[0];
    }

    @NotNull
    private static String normalize(@Nullable String text) {
        return CommonUtils.notEmpty(text).toLowerCase(Locale.ENGLISH);
    }

    /**
     * Selects the first item whose name starts with the characters typed within a second.
     */
    private final class PrefixKeySelectionManager implements KeySelectionManager {
        private final StringBuilder prefix = new StringBuilder();
        private long lastKeyTime;

        @Override
        public int selectionForKey(char key, ComboBoxModel<?> comboModel) {
            long now = System.currentTimeMillis();
            if (now - lastKeyTime > 1000) {
                prefix.setLength(0);
            }
            lastKeyTime = now;
            prefix.append(Character.toLowerCase(key));
            String text = prefix.toString();
            for (int i = 0; i < comboModel.getSize(); i++) {
                Object item = comboModel.getElementAt(i);
                if (item instanceof DBPDriver driver && normalize(driver.getName()).startsWith(text)) {
                    return i;
                }
            }
            return -1;
        }
    }

    /**
     * Provider icon and driver name; a divider above the first non-favorite item and above "More database types…".
     */
    private final class DriverRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DBPDriver driver) {
                setIcon(NodeIcons.getIcon(driver.getIcon()));
                setText(driver.getName());
                setToolTipText(CommonUtils.isEmpty(driver.getDescription()) ? driver.getFullName() : driver.getDescription());
            } else {
                setIcon(null);
                setText(String.valueOf(value));
                setToolTipText("Search all database types and drivers");
            }
            setIconTextGap(6);
            boolean divider = index > 0 && (index == favoriteCount || value == MORE_ITEM);
            if (divider) {
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, ConnectionDialog.getThemeColor("Chromite.dividerColor", "Separator.foreground", Color.LIGHT_GRAY)), //$NON-NLS-1$ //$NON-NLS-2$
                    getBorder()));
            }
            return this;
        }
    }
}
