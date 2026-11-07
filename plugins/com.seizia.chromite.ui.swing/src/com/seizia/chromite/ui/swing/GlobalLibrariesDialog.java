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
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.connection.DBPDataSourceProviderDescriptor;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.registry.DataSourceProviderRegistry;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.StandardConstants;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;

/**
 * Driver Manager의 Global Libraries… 창(SWT 환경설정 {@code PrefPageDriversClasspath}의 Swing 판): 모든 드라이버의 클래스패스 앞에 놓을
 * jar 목록과, 읽기 전용인 앱 클래스패스.
 *
 * 설계: 모델 설정 {@link ModelPreferences#UI_DRIVERS_GLOBAL_LIBRARIES}(경로를 URL 인코딩해 '|'로 이은 값, SWT와 같은 형식)를 고친다.
 * 모델({@code DriverLoaderDescriptor})은 첫 드라이버를 불러올 때 이 목록으로 모든 드라이버 클래스 로더의 부모를 한 번 만든다.
 * 그래서 이미 불러온 드라이버가 있으면 바뀐 목록은 다시 시작해야 쓰인다. SWT는 이때 재시작을 권하는데, Swing 앱에는 재시작 명령이 없어
 * 저장한 뒤 "다시 시작하면 적용된다"고 알린다. 환경설정 창(Options)이 아니라 Driver Manager에 둔 것은 오프라인 모드 체크 상자와 같은 이유다
 * (Swing 앱에는 드라이버 환경설정 페이지가 없다, docs/chromite/swing-driver-manager.md).
 * 두 번 누르면 파일이 있는 폴더를 연다(SWT와 같음).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-driver-manager.md "전역 라이브러리")
 */
final class GlobalLibrariesDialog extends JDialog {

    private static final Log log = Log.getLog(GlobalLibrariesDialog.class);

    @Nullable
    private static File lastFolder;

    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final JButton removeButton = new JButton(DriverManagerMessages.driverglobal_remove);
    private final String[] initialLibraries;

    private GlobalLibrariesDialog(@NotNull Window owner) {
        super(owner, DriverManagerMessages.driverglobal_title, ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        initialLibraries = DriverDescriptor.getGlobalLibraries();
        for (String library : initialLibraries) {
            model.addElement(library);
        }

        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.addListSelectionListener(e -> removeButton.setEnabled(!list.isSelectionEmpty()));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && list.getSelectedValue() != null) {
                    showInExplorer(new File(list.getSelectedValue()));
                }
            }
        });
        removeButton.setEnabled(false);
        removeButton.addActionListener(e -> {
            for (String library : list.getSelectedValuesList()) {
                model.removeElement(library);
            }
        });
        JButton addButton = new JButton(DriverManagerMessages.driverglobal_add);
        addButton.addActionListener(e -> addFiles());
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(addButton);
        buttons.add(removeButton);
        JPanel east = new JPanel(new BorderLayout());
        east.add(buttons, BorderLayout.NORTH);

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setPreferredSize(new Dimension(520, 140));
        JLabel info = new JLabel(DriverManagerMessages.driverglobal_info);
        info.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel globalPanel = new JPanel(new BorderLayout(8, 6));
        globalPanel.add(new JLabel(DriverManagerMessages.driverglobal_libraries), BorderLayout.NORTH);
        globalPanel.add(listScroll, BorderLayout.CENTER);
        globalPanel.add(east, BorderLayout.EAST);
        globalPanel.add(info, BorderLayout.SOUTH);

        JList<String> systemList = new JList<>(CommonUtils.splitString(
            System.getProperty(StandardConstants.ENV_JAVA_CLASSPATH), File.pathSeparatorChar).toArray(new String[0]));
        JScrollPane systemScroll = new JScrollPane(systemList);
        systemScroll.setPreferredSize(new Dimension(520, 100));
        JPanel systemPanel = new JPanel(new BorderLayout(0, 6));
        systemPanel.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        systemPanel.add(new JLabel(DriverManagerMessages.driverglobal_system), BorderLayout.NORTH);
        systemPanel.add(systemScroll, BorderLayout.CENTER);

        JButton okButton = new JButton(CoreMessages.common_ok);
        okButton.addActionListener(e -> save());
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        south.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        south.add(okButton);
        south.add(cancelButton);

        JPanel center = new JPanel(new BorderLayout());
        center.add(globalPanel, BorderLayout.CENTER);
        center.add(systemPanel, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout());
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(center, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Driver Manager &gt; Global Libraries…. Blocks until closed.
     */
    static void open(@NotNull Window owner) {
        new GlobalLibrariesDialog(owner).setVisible(true);
    }

    private void addFiles() {
        JFileChooser chooser = new JFileChooser(lastFolder);
        chooser.setDialogTitle(DriverManagerMessages.driverglobal_open_title);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter(DriverManagerMessages.driverlib_filter_jars, "jar", "zip")); //$NON-NLS-1$ //$NON-NLS-2$
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        lastFolder = chooser.getCurrentDirectory();
        for (File file : chooser.getSelectedFiles()) {
            String path = file.getAbsolutePath();
            if (!model.contains(path)) {
                model.addElement(path);
            }
        }
    }

    private void save() {
        List<String> libraries = new ArrayList<>();
        StringJoiner value = new StringJoiner("|"); //$NON-NLS-1$
        for (int i = 0; i < model.size(); i++) {
            libraries.add(model.get(i));
            value.add(URLEncoder.encode(model.get(i), StandardCharsets.UTF_8));
        }
        DBPPreferenceStore preferences = DBWorkbench.getPlatform().getPreferenceStore();
        preferences.setValue(ModelPreferences.UI_DRIVERS_GLOBAL_LIBRARIES, value.toString());
        PrefUtils.savePreferenceStore(preferences);
        dispose();
        if (!Arrays.equals(initialLibraries, libraries.toArray(new String[0])) && isAnyDriverLoaded()) {
            JOptionPane.showMessageDialog(getOwner(), DriverManagerMessages.driverglobal_restart, DriverManagerMessages.driverglobal_title,
                JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * The global libraries are the parent class loader of the drivers loaded from now on; loaded drivers keep theirs.
     */
    private static boolean isAnyDriverLoaded() {
        for (DBPDataSourceProviderDescriptor provider : DataSourceProviderRegistry.getInstance().getDataSourceProviders()) {
            for (DBPDriver driver : provider.getDrivers()) {
                if (driver instanceof DriverDescriptor descriptor && descriptor.getDefaultDriverLoader().isLoaded()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void showInExplorer(@NotNull File file) {
        File folder = file.isDirectory() ? file : file.getParentFile();
        if (folder != null && folder.exists() && Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().open(folder);
            } catch (IOException | UnsupportedOperationException e) {
                log.debug("Can't open " + folder, e);
            }
        }
    }
}
