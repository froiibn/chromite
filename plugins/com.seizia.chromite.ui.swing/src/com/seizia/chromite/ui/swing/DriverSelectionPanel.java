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
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverWithLicense;
import org.jkiss.dbeaver.registry.driver.DriverUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 드라이버 고르기: 검색 칸, 범주 선택, 아이콘이 있는 드라이버 목록. Connect 대화상자의 "Database type" 콤보에서
 * "More database types…"를 고르면 열리는 창의 내용이다({@link DatabaseTypeCombo}).
 *
 * 설계: 목록은 모델 계층의 {@link DriverUtils#getAllDrivers()}(활성 공급자의 사용 가능한 드라이버: 꺼짐·대체됨·이 OS 미지원 제외,
 * 이름순)에서 연결을 만들 수 없는 것을 뺀다.
 * <ul>
 *     <li>뺀 것: 임시 드라이버({@code isTemporary}), 폐기된 드라이버 자리표시({@code getDriverStub}),
 *     상용 드라이버 자리표시({@link DBPDriverWithLicense}, SWT도 "상용 드라이버 보기"를 켜야 보임),
 *     JDK 내장 ODBC 브리지({@code isInternalDriver}, 현재 Java에는 없음).</li>
 *     <li>검색은 SWT {@code DataSourceTypeViewer}와 같이 대소문자·공백을 무시하고 이름, 전체 이름, ID, 설명, 범주에서 찾는다.
 *     이름이 검색어로 시작하는 드라이버를 먼저 보여 준다(SWT의 인기 점수 정렬 대신 쓰는 간단한 규칙).</li>
 *     <li>범주는 드라이버의 {@code getCategories()}를 모은 것이다({@code getCategory()}는 폐기 예정 API라 쓰지 않는다). SWT의 범주 탭 대신 콤보 하나로 거른다.</li>
 *     <li>아이콘은 탐색기와 같은 {@link NodeIcons}로 그린다(드라이버 아이콘도 {@code DBPImage}).</li>
 * </ul>
 * 두 번 누르거나 Enter를 누르면 고른 것으로 한다({@code onChoose}).
 *
 * 변경 이력:
 * - 2026-11-06 D단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 SSMS식 Connect 대화상자: 새 연결의 1단계 화면에서 "Database type" 콤보의 전체 드라이버 검색 창으로 바꿈.
 *   콤보도 같은 드라이버 목록을 쓰도록 {@link #getConnectableDrivers()}를 패키지에 공개
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 */
final class DriverSelectionPanel extends JPanel {

    private static final String ALL_CATEGORIES = ConnectionMessages.driver_select_all_categories;

    private final List<DBPDriver> allDrivers;
    private final DefaultListModel<DBPDriver> listModel = new DefaultListModel<>();
    private final JList<DBPDriver> driverList = new JList<>(listModel);
    private final JTextField searchField = new JTextField();
    private final JComboBox<String> categoryCombo;

    /**
     * @param onSelectionChanged called when the selected driver changes
     * @param onChoose           called on double click or Enter (the driver is chosen)
     */
    DriverSelectionPanel(@NotNull Runnable onSelectionChanged, @NotNull Runnable onChoose) {
        super(new BorderLayout(0, 8));
        allDrivers = getConnectableDrivers();

        TreeSet<String> categories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (DBPDriver driver : allDrivers) {
            for (String category : driver.getCategories()) {
                if (!CommonUtils.isEmpty(category)) {
                    categories.add(category);
                }
            }
        }
        List<String> categoryItems = new ArrayList<>();
        categoryItems.add(ALL_CATEGORIES);
        categoryItems.addAll(categories);
        categoryCombo = new JComboBox<>(categoryItems.toArray(new String[0]));
        categoryCombo.addActionListener(e -> applyFilter());

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ConnectionMessages.driver_select_search);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        // Arrow keys in the search field move through the list, so the keyboard never has to leave the field
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "chromite.nextDriver"); //$NON-NLS-1$
        searchField.getActionMap().put("chromite.nextDriver", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                moveSelection(1);
            }
        });
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "chromite.previousDriver"); //$NON-NLS-1$
        searchField.getActionMap().put("chromite.previousDriver", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                moveSelection(-1);
            }
        });
        searchField.addActionListener(e -> {
            if (getSelectedDriver() != null) {
                onChoose.run();
            }
        });

        driverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        driverList.setCellRenderer(new DriverRenderer());
        driverList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onSelectionChanged.run();
            }
        });
        driverList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && getSelectedDriver() != null) {
                    onChoose.run();
                }
            }
        });
        driverList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.chooseDriver"); //$NON-NLS-1$
        driverList.getActionMap().put("chromite.chooseDriver", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (getSelectedDriver() != null) {
                    onChoose.run();
                }
            }
        });

        JPanel filterPanel = new JPanel(new BorderLayout(8, 0));
        filterPanel.add(searchField, BorderLayout.CENTER);
        filterPanel.add(categoryCombo, BorderLayout.EAST);

        JLabel title = new JLabel(ConnectionMessages.driver_select_title);
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(title, BorderLayout.NORTH);
        top.add(filterPanel, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(driverList), BorderLayout.CENTER);
        applyFilter();
    }

    @Nullable
    DBPDriver getSelectedDriver() {
        return driverList.getSelectedValue();
    }

    void focusSearch() {
        searchField.requestFocusInWindow();
    }

    /**
     * Drivers a connection can be created with (see the class comment for what is left out).
     */
    @NotNull
    static List<DBPDriver> getConnectableDrivers() {
        List<DBPDriver> drivers = new ArrayList<>();
        for (DBPDriver driver : DriverUtils.getAllDrivers()) {
            if (driver.isTemporary() || driver.getDriverStub() != null || driver instanceof DBPDriverWithLicense || driver.isInternalDriver()) {
                continue;
            }
            drivers.add(driver);
        }
        return drivers;
    }

    private void applyFilter() {
        DBPDriver selected = driverList.getSelectedValue();
        String pattern = normalize(searchField.getText());
        String category = (String) categoryCombo.getSelectedItem();
        boolean allCategories = category == null || ALL_CATEGORIES.equals(category);

        List<DBPDriver> matching = new ArrayList<>();
        for (DBPDriver driver : allDrivers) {
            if (!allCategories && driver.getCategories().stream().noneMatch(category::equalsIgnoreCase)) {
                continue;
            }
            if (!pattern.isEmpty() && !matches(driver, pattern)) {
                continue;
            }
            matching.add(driver);
        }
        if (!pattern.isEmpty()) {
            // Names that start with the search text first ("postgres" → PostgreSQL before Google Cloud SQL - PostgreSQL);
            // the sort is stable, so the name order stays within both groups
            matching.sort(Comparator.comparing((DBPDriver driver) -> !normalize(driver.getName()).startsWith(pattern)));
        }
        listModel.clear();
        listModel.addAll(matching);
        if (selected != null && listModel.contains(selected)) {
            driverList.setSelectedValue(selected, true);
        } else if (!listModel.isEmpty()) {
            driverList.setSelectedIndex(0);
            driverList.ensureIndexIsVisible(0);
        }
    }

    private static boolean matches(@NotNull DBPDriver driver, @NotNull String pattern) {
        if (normalize(driver.getName()).contains(pattern) || normalize(driver.getFullName()).contains(pattern)
            || normalize(driver.getId()).contains(pattern) || normalize(driver.getDescription()).contains(pattern)) {
            return true;
        }
        return driver.getCategories().stream().anyMatch(category -> normalize(category).contains(pattern));
    }

    @NotNull
    private static String normalize(@Nullable String text) {
        return CommonUtils.notEmpty(text).toLowerCase(Locale.ENGLISH).replaceAll("\\s+", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void moveSelection(int delta) {
        int size = listModel.getSize();
        if (size == 0) {
            return;
        }
        int index = Math.max(0, Math.min(size - 1, driverList.getSelectedIndex() + delta));
        driverList.setSelectedIndex(index);
        driverList.ensureIndexIsVisible(index);
    }

    /**
     * Driver icon and name, with the category in a dimmed color.
     */
    private static final class DriverRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DBPDriver driver) {
                setIcon(NodeIcons.getIcon(driver.getIcon()));
                String category = String.join(", ", driver.getCategories()); //$NON-NLS-1$
                if (CommonUtils.isEmpty(category)) {
                    setText(driver.getName());
                } else {
                    // The selected row keeps the selection foreground for both parts
                    Color dimmed = isSelected ? null : UIManager.getColor("Label.disabledForeground"); //$NON-NLS-1$
                    String categoryText = dimmed == null ? escape(category)
                        : "<font color='" + String.format("#%06x", dimmed.getRGB() & 0xffffff) + "'>" + escape(category) + "</font>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    setText("<html>" + escape(driver.getName()) + "&nbsp;&nbsp;" + categoryText + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                setToolTipText(CommonUtils.isEmpty(driver.getDescription()) ? driver.getFullName() : driver.getDescription());
                setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
            }
            return this;
        }

        @NotNull
        private static String escape(@NotNull String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        }
    }
}
