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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.connection.DBPNativeClientLocation;
import com.seizia.chromite.model.connection.DBPNativeClientLocationManager;
import com.seizia.chromite.model.connection.LocalNativeClientLocation;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.registry.driver.RemoteNativeClientLocation;
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 드라이버 편집 창의 Local Client 탭(SWT {@code ClientHomesPanel}의 Swing 판): 이 드라이버의 네이티브 클라이언트 설치 목록.
 * 백업·복원({@link NativeBackupRestoreDialog})이 pg_dump·mysqldump 같은 도구를 이 위치에서 실행한다.
 *
 * 설계: 목록은 클라이언트 관리자({@link DBPNativeClientLocationManager#findLocalClientLocations()})가 이 컴퓨터에서 찾은 설치와
 * 사용자가 추가한 폴더(드라이버의 {@code nativeClientHomes}, {@code drivers.xml}에 저장)다. 같은 이름이면 찾은 설치로 본다.
 * 찾은 설치는 지울 수 없고(SWT와 같음), 추가한 폴더만 지운다. 지우기는 확인을 받고 기본 단추는 취소다.
 * 원격 저장소에서 받는 위치({@link RemoteNativeClientLocation})는 넣지 않는다. 고르면 내려받기 때문이고, 백업 대화상자도 넣지 않는다(폐쇄망 우선).
 * 고른 위치의 제품 이름·버전은 클라이언트 관리자가 도구를 실행해 읽을 수 있어 백그라운드({@link SwingWorker})에서 읽는다(SWT는 UI 스레드).
 * 바뀐 목록은 드라이버 편집 창의 OK가 {@code DriverDescriptor.setNativeClientLocations}로 쓴다({@link #getLocalLocations()}).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-driver-manager.md "Local Client")
 */
final class ClientHomesPanel extends JPanel {

    private static final Log log = Log.getLog(ClientHomesPanel.class);

    @Nullable
    private static File lastFolder;

    /**
     * A client location of the list; {@code found} ones come from the client manager and are not saved.
     */
    private record Home(@NotNull DBPNativeClientLocation location, boolean found, boolean isDefault) {
    }

    private final DriverDescriptor driver;
    private final DBPNativeClientLocationManager manager;
    private final DefaultListModel<Home> model = new DefaultListModel<>();
    private final JList<Home> list = new JList<>(model);
    private final JButton removeButton = new JButton(DriverManagerMessages.driverclient_remove);
    private final JTextField idField = createInfoField();
    private final JTextField pathField = createInfoField();
    private final JTextField nameField = createInfoField();
    private final JTextField productField = createInfoField();
    private final JTextField versionField = createInfoField();
    @Nullable
    private SwingWorker<String[], Void> productReader;

    ClientHomesPanel(@NotNull DriverDescriptor driver, @NotNull DBPNativeClientLocationManager manager) {
        super(new BorderLayout(10, 8));
        this.driver = driver;
        this.manager = manager;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new HomeRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showHome(list.getSelectedValue());
            }
        });
        JButton addButton = new JButton(DriverManagerMessages.driverclient_add);
        addButton.setToolTipText(DriverManagerMessages.driverclient_add_tip);
        addButton.addActionListener(e -> addHome());
        removeButton.addActionListener(e -> removeHome());
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(addButton);
        buttons.add(removeButton);
        JPanel east = new JPanel(new BorderLayout());
        east.add(buttons, BorderLayout.NORTH);

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(300, 200));
        JPanel listPanel = new JPanel(new BorderLayout(8, 0));
        listPanel.add(listScroll, BorderLayout.CENTER);
        listPanel.add(east, BorderLayout.EAST);

        JPanel info = new JPanel(new GridBagLayout());
        int row = 0;
        addInfo(info, row++, DriverManagerMessages.driverclient_id, idField);
        addInfo(info, row++, DriverManagerMessages.driverclient_path, pathField);
        addInfo(info, row++, DriverManagerMessages.driverclient_name, nameField);
        addInfo(info, row++, DriverManagerMessages.driverclient_product, productField);
        addInfo(info, row, DriverManagerMessages.driverclient_version, versionField);

        JLabel hint = new JLabel(DriverManagerMessages.driverclient_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.add(listPanel, BorderLayout.CENTER);
        center.add(info, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);
        add(hint, BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        loadHomes();
    }

    @NotNull
    private static JTextField createInfoField() {
        JTextField field = new JTextField();
        field.setEditable(false);
        return field;
    }

    private static void addInfo(@NotNull JPanel panel, int row, @NotNull String label, @NotNull JTextField field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(3, 0, 3, 12);
        JLabel labelComponent = new JLabel(label);
        labelComponent.setLabelFor(field);
        panel.add(labelComponent, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(3, 0, 3, 0);
        panel.add(field, c);
    }

    /**
     * The folders the user added (SWT {@code getLocalLocations}): what the driver saves.
     */
    @NotNull
    List<DBPNativeClientLocation> getLocalLocations() {
        List<DBPNativeClientLocation> locations = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            Home home = model.get(i);
            if (!home.found()) {
                locations.add(home.location());
            }
        }
        return locations;
    }

    private void loadHomes() {
        Map<String, DBPNativeClientLocation> found = new LinkedHashMap<>();
        for (DBPNativeClientLocation location : manager.findLocalClientLocations()) {
            found.putIfAbsent(location.getName(), location);
        }
        DBPNativeClientLocation defaultLocation = manager.getDefaultLocalClientLocation();
        String defaultName = defaultLocation == null ? null : defaultLocation.getName();
        for (DBPNativeClientLocation location : driver.getNativeClientLocations()) {
            if (!(location instanceof RemoteNativeClientLocation) && !found.containsKey(location.getName())) {
                model.addElement(new Home(location, false, location.getName().equals(defaultName)));
            }
        }
        for (DBPNativeClientLocation location : found.values()) {
            model.addElement(new Home(location, true, location.getName().equals(defaultName)));
        }
        Home selected = null;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).isDefault() || selected == null) {
                selected = model.get(i);
            }
        }
        if (selected != null) {
            list.setSelectedValue(selected, true);
        } else {
            showHome(null);
        }
    }

    private void addHome() {
        JFileChooser chooser = new JFileChooser(lastFolder);
        chooser.setDialogTitle(DriverManagerMessages.driverclient_choose);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        File folder = chooser.getSelectedFile();
        lastFolder = folder.getParentFile();
        String path = folder.getAbsolutePath();
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).location().getPath().getAbsoluteFile().equals(folder.getAbsoluteFile())) {
                list.setSelectedIndex(i);
                return;
            }
        }
        // Named by its folder, as SWT and the backup dialog do
        Home home = new Home(new LocalNativeClientLocation(path, path), false, false);
        model.addElement(home);
        list.setSelectedValue(home, true);
    }

    private void removeHome() {
        Home home = list.getSelectedValue();
        if (home == null || home.found()) {
            return;
        }
        if (!SwingPlatformUI.confirmChange(this, NLS.bind(DriverManagerMessages.driverclient_remove_confirm, home.location().getPath().getAbsolutePath()),
            DriverManagerMessages.driverclient_remove_title, DriverManagerMessages.driverclient_remove, JOptionPane.QUESTION_MESSAGE)) {
            return;
        }
        int index = list.getSelectedIndex();
        model.removeElement(home);
        if (!model.isEmpty()) {
            list.setSelectedIndex(Math.min(index, model.size() - 1));
        } else {
            showHome(null);
        }
    }

    private void showHome(@Nullable Home home) {
        removeButton.setEnabled(home != null && !home.found());
        removeButton.setToolTipText(home != null && home.found() ? DriverManagerMessages.driverclient_remove_found_tip : null);
        idField.setText(home == null ? "" : CommonUtils.notEmpty(home.location().getName())); //$NON-NLS-1$
        pathField.setText(home == null ? "" : home.location().getPath().getAbsolutePath()); //$NON-NLS-1$
        nameField.setText(home == null ? "" : CommonUtils.notEmpty(home.location().getDisplayName())); //$NON-NLS-1$
        productField.setText(""); //$NON-NLS-1$
        versionField.setText(""); //$NON-NLS-1$
        if (productReader != null) {
            productReader.cancel(false);
            productReader = null;
        }
        if (home == null) {
            return;
        }
        // The client manager may run the client tool to read its version
        SwingWorker<String[], Void> reader = new SwingWorker<>() {
            @Override
            protected String[] doInBackground() {
                return new String[]{manager.getProductName(home.location()), manager.getProductVersion(home.location())};
            }

            @Override
            protected void done() {
                if (isCancelled() || productReader != this) {
                    return;
                }
                try {
                    String[] product = get();
                    productField.setText(CommonUtils.notEmpty(product[0]));
                    versionField.setText(CommonUtils.notEmpty(product[1]));
                } catch (Exception e) {
                    log.debug("Can't read the client product of " + home.location().getPath(), e);
                }
            }
        };
        productReader = reader;
        reader.execute();
    }

    /**
     * Display name with "found" / "added" and "default".
     */
    private static final class HomeRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Home home) {
                String name = home.location().getDisplayName();
                String path = home.location().getPath().getAbsolutePath();
                StringBuilder text = new StringBuilder(CommonUtils.isEmpty(name) ? path : name);
                text.append("  (").append(home.found() ? DriverManagerMessages.driverclient_found : DriverManagerMessages.driverclient_added); //$NON-NLS-1$
                if (home.isDefault()) {
                    text.append(", ").append(DriverManagerMessages.driverclient_default); //$NON-NLS-1$
                }
                text.append(')');
                setText(text.toString());
                setIcon(UiIcons.get("folder")); //$NON-NLS-1$
                setToolTipText(path);
            }
            return this;
        }
    }
}
