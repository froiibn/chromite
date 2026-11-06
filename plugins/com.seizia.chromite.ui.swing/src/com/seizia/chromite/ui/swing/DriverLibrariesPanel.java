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
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.connection.DBPDriverLibrary;
import com.seizia.chromite.registry.driver.DriverClassFindJob;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.registry.driver.DriverFileInfo;
import com.seizia.chromite.registry.driver.DriverLibraryAbstract;
import com.seizia.chromite.registry.driver.DriverLibraryBundle;
import com.seizia.chromite.registry.driver.DriverUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 드라이버 편집 대화상자의 Libraries 탭: 드라이버를 로드할 라이브러리 목록, 로컬 파일·폴더 추가, 제거, 다운로드, 드라이버 클래스 찾기.
 *
 * 설계: SWT {@code DriverEditDialog.createLibrariesTab}(ui.editors.connection, DriverEditDialog.java:433-658)을 모델 API 그대로 옮겼다.
 * 목록은 대화상자가 가진 라이브러리 사본({@link #libraries})이고, OK를 누를 때 대화상자가 {@code DriverDescriptor.setDriverLibraries}로 쓴다.
 * <ul>
 *     <li>파일 추가·폴더 추가: {@link DriverLibraryAbstract#createFromPath}(SWT {@code addLibraryFiles}/{@code addLibraryFolder}와 같음).
 *     파일 형식은 {@link DBPDriverLibrary.FileType#getFileTypeByFileName}(jar·zip → jar, 나머지 → 네이티브 lib)으로 정한다. 폴더는 모델이 바로 아래의
 *     jar·zip만 클래스패스에 넣는다. 폐쇄망에서 드라이버를 쓰는 기본 방법이라 탭 아래에 안내를 둔다. 이미 목록에 있는 경로는 다시 넣지 않는다(SWT는 넣음).</li>
 *     <li>상태: 라이브러리마다 내장(설치 폴더 안의 파일, {@code bundle:}), 로컬 파일, 다운로드됨, 다운로드 안 됨, 없음을 보여 준다
 *     ({@link #getState}). SWT는 같은 판단을 글자색(오류색 = 파일 없음, 링크색 = 아직 내려받지 않음)으로만 보여 준다.
 *     내려받은 Maven 라이브러리는 SWT처럼 실제 파일({@link DriverFileInfo})을 자식으로 보인다.</li>
 *     <li>다운로드/업데이트: SWT와 같이 목록을 드라이버에 쓰고 {@code DriverLoaderDescriptor.updateFiles(true)}(최신 버전으로 다시 받기)를 부른다.
 *     실제 확인과 진행 대화상자는 {@link SwingDriversService}다. 오프라인 모드({@link DriverUtils#isOfflineMode()})에서는 단추를 끄고
 *     눌려도 내려받지 않는다고 알린다. 모델도 오프라인 모드에서 원격 다운로드를 막는다(docs/chromite/drivers.md).</li>
 *     <li>클래스 찾기: SWT처럼 현재 폼 값의 임시 드라이버({@link Host#createTestDriver()})로 모델의 {@link DriverClassFindJob}을 돌려
 *     {@link java.sql.Driver}를 구현한 클래스를 찾고, 고르면 Class name 칸에 넣는다. 진행 대화상자({@link ProgressDialog})에서 돌린다.</li>
 * </ul>
 * SWT에 있던 Maven 아티팩트 추가·편집({@code EditMavenArtifactDialog}), 라이브러리 정보 창, 전역 클래스패스, 내보내기는 없다(설계 문서 "남긴 것").
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-driver-manager.md)
 */
final class DriverLibrariesPanel extends JPanel {

    private static final Log log = Log.getLog(DriverLibrariesPanel.class);

    /**
     * Where a library's files come from, as far as the user is concerned.
     */
    enum State {
        BUNDLED, LOCAL, DOWNLOADED, NOT_DOWNLOADED, MISSING
    }

    /**
     * The dialog side the panel needs.
     */
    interface Host {
        @NotNull
        DriverDescriptor getDriver();

        /**
         * A throwaway driver with the current form values and libraries (SWT: {@code new DriverDescriptor(provider, "test", driver)}).
         */
        @NotNull
        DriverDescriptor createTestDriver();

        boolean isManageable();

        void setDriverClassName(@NotNull String className);
    }

    // Shared by all driver dialogs of the session, as SWT's curFolder per dialog but remembered across dialogs
    @Nullable
    private static File lastFolder;
    @Nullable
    private static Path installFolder;

    private final Host host;
    private final List<DBPDriverLibrary> libraries;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private final JButton removeButton = new JButton(DriverManagerMessages.driverlib_remove);
    private final JButton downloadButton = new JButton(DriverManagerMessages.driverlib_download);
    private final JComboBox<String> classCombo = new JComboBox<>();
    private final JButton findClassButton = new JButton(DriverManagerMessages.driverlib_find_class);
    private boolean fillingClasses;

    DriverLibrariesPanel(@NotNull Host host, @NotNull List<DBPDriverLibrary> libraries) {
        super(new BorderLayout(10, 8));
        this.host = host;
        this.libraries = libraries;

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new LibraryRenderer());
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.getSelectionModel().addTreeSelectionListener(e -> updateButtons());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    showSelectedInExplorer();
                }
            }
        });

        JButton addFileButton = new JButton(DriverManagerMessages.driverlib_add_file);
        addFileButton.setToolTipText(DriverManagerMessages.driverlib_add_file_tip);
        addFileButton.addActionListener(e -> addFiles());
        JButton addFolderButton = new JButton(DriverManagerMessages.driverlib_add_folder);
        addFolderButton.setToolTipText(DriverManagerMessages.driverlib_add_folder_tip);
        addFolderButton.addActionListener(e -> addFolder());
        removeButton.addActionListener(e -> removeSelected());
        downloadButton.addActionListener(e -> download());

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.add(addFileButton);
        buttons.add(addFolderButton);
        buttons.add(removeButton);
        buttons.add(new JSeparator());
        buttons.add(downloadButton);
        JPanel east = new JPanel(new BorderLayout());
        east.add(buttons, BorderLayout.NORTH);

        classCombo.setPrototypeDisplayValue("org.example.jdbc.ExampleDriverClass"); //$NON-NLS-1$
        classCombo.addActionListener(e -> {
            Object selected = classCombo.getSelectedItem();
            if (!fillingClasses && selected != null) {
                host.setDriverClassName(selected.toString());
            }
        });
        findClassButton.setToolTipText(DriverManagerMessages.driverlib_find_class_tip);
        findClassButton.addActionListener(e -> findClasses());
        JPanel classRow = new JPanel(new BorderLayout(6, 0));
        classRow.add(new JLabel(DriverManagerMessages.driverlib_driver_class), BorderLayout.WEST);
        classRow.add(classCombo, BorderLayout.CENTER);
        classRow.add(findClassButton, BorderLayout.EAST);

        JLabel hint = new JLabel(DriverManagerMessages.driverlib_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.add(classRow, BorderLayout.NORTH);
        south.add(hint, BorderLayout.SOUTH);

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setPreferredSize(new Dimension(520, 240));
        add(scrollPane, BorderLayout.CENTER);
        add(east, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        reload();
    }

    /**
     * Rebuilds the tree from the library list (after the list or the resolved files changed).
     */
    void reload() {
        root.removeAllChildren();
        DriverDescriptor driver = host.getDriver();
        for (DBPDriverLibrary library : libraries) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(library);
            Collection<DriverFileInfo> files = driver.getDefaultDriverLoader().getLibraryFiles(library);
            if (files != null) {
                for (DriverFileInfo file : files) {
                    node.add(new DefaultMutableTreeNode(file));
                }
            }
            root.add(node);
        }
        treeModel.reload();
        updateButtons();
    }

    private void updateButtons() {
        boolean manageable = host.isManageable();
        boolean hasFiles = false;
        boolean hasDownloads = false;
        DriverDescriptor driver = host.getDriver();
        for (DBPDriverLibrary library : libraries) {
            State state = getState(driver, library);
            hasFiles |= state == State.BUNDLED || state == State.LOCAL || state == State.DOWNLOADED;
            hasDownloads |= library.isDownloadable();
        }
        // Same conditions as DriverEditDialog.changeLibContent / changeLibSelection
        findClassButton.setEnabled(manageable && hasFiles);
        classCombo.setEnabled(manageable && hasFiles);
        boolean offline = DriverUtils.isOfflineMode();
        downloadButton.setEnabled(hasDownloads && !offline);
        downloadButton.setToolTipText(offline ? DriverManagerMessages.driverlib_download_offline
            : hasDownloads ? DriverManagerMessages.driverlib_download_tip : DriverManagerMessages.driverlib_download_none);
        removeButton.setEnabled(!getSelectedLibraries().isEmpty());
    }

    @NotNull
    private List<DBPDriverLibrary> getSelectedLibraries() {
        List<DBPDriverLibrary> selected = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths != null) {
            for (TreePath path : paths) {
                Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (value instanceof DBPDriverLibrary library) {
                    selected.add(library);
                }
            }
        }
        return selected;
    }

    private void addFiles() {
        JFileChooser chooser = new JFileChooser(lastFolder);
        chooser.setDialogTitle(DriverManagerMessages.driverlib_open_files_title);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        FileNameExtensionFilter jarFilter = new FileNameExtensionFilter(DriverManagerMessages.driverlib_filter_jars, "jar", "zip"); //$NON-NLS-1$ //$NON-NLS-2$
        chooser.addChoosableFileFilter(jarFilter);
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(DriverManagerMessages.driverlib_filter_native, "dll", "so", "dylib")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        chooser.setFileFilter(jarFilter);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] files = chooser.getSelectedFiles();
        if (files.length == 0 && chooser.getSelectedFile() != null) {
            files = new File[]{chooser.getSelectedFile()};
        }
        lastFolder = chooser.getCurrentDirectory();
        for (File file : files) {
            addLibrary(DBPDriverLibrary.FileType.getFileTypeByFileName(file.getName()), file.getAbsolutePath());
        }
        reload();
    }

    private void addFolder() {
        JFileChooser chooser = new JFileChooser(lastFolder);
        chooser.setDialogTitle(DriverManagerMessages.driverlib_open_folder_title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        File folder = chooser.getSelectedFile();
        lastFolder = folder.getParentFile();
        addLibrary(DBPDriverLibrary.FileType.jar, folder.getAbsolutePath());
        reload();
    }

    private void addLibrary(@NotNull DBPDriverLibrary.FileType type, @NotNull String path) {
        for (DBPDriverLibrary library : libraries) {
            if (path.equals(library.getPath())) {
                return;
            }
        }
        libraries.add(DriverLibraryAbstract.createFromPath(host.getDriver(), type, path, null));
    }

    private void removeSelected() {
        List<DBPDriverLibrary> selected = getSelectedLibraries();
        if (selected.isEmpty()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this, DriverManagerMessages.driverlib_remove_confirm,
            DriverManagerMessages.driverlib_remove_title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        // The class loader holds the removed files open until it is dropped (SWT does the same)
        host.getDriver().resetDriverInstance();
        libraries.removeAll(selected);
        reload();
    }

    private void download() {
        if (DriverUtils.isOfflineMode()) {
            JOptionPane.showMessageDialog(this, DriverManagerMessages.driverlib_download_offline, DriverManagerMessages.driverlib_download,
                JOptionPane.INFORMATION_MESSAGE);
            updateButtons();
            return;
        }
        // Same as SWT's Download/Update: the list goes to the driver and its downloadable files are fetched again
        // in their latest versions; SwingDriversService asks and shows the progress
        DriverDescriptor driver = host.getDriver();
        driver.setDriverLibraries(libraries);
        driver.getDefaultDriverLoader().updateFiles(true);
        reload();
    }

    private void findClasses() {
        DriverDescriptor testDriver = host.createTestDriver();
        DriverClassFindJob finder = new DriverClassFindJob(testDriver, java.sql.Driver.class.getName(), true);
        List<String> classNames;
        try {
            classNames = ProgressDialog.runTask(DriverManagerMessages.driverlib_find_class_task, monitor -> {
                finder.run(monitor);
                return finder.getDriverClassNames();
            });
        } catch (DBException e) {
            log.debug("Driver class search failed", e);
            JOptionPane.showMessageDialog(this, NLS.bind(DriverManagerMessages.driverlib_find_class_error, e.getMessage()),
                DriverManagerMessages.driverlib_find_class, JOptionPane.ERROR_MESSAGE);
            return;
        }
        fillingClasses = true;
        try {
            classCombo.removeAllItems();
            if (classNames != null) {
                for (String className : classNames) {
                    classCombo.addItem(className);
                }
            }
            classCombo.setSelectedIndex(-1);
        } finally {
            fillingClasses = false;
        }
        if (classCombo.getItemCount() == 0) {
            JOptionPane.showMessageDialog(this, DriverManagerMessages.driverlib_find_class_none, DriverManagerMessages.driverlib_find_class,
                JOptionPane.INFORMATION_MESSAGE);
        } else if (classCombo.isShowing()) {
            classCombo.showPopup();
        }
    }

    private void showSelectedInExplorer() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return;
        }
        Path file = getFile(((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject());
        if (file != null && Files.exists(file) && Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().open(Files.isDirectory(file) ? file.toFile() : file.getParent().toFile());
            } catch (IOException | UnsupportedOperationException e) {
                log.debug("Can't open " + file, e);
            }
        }
    }

    @Nullable
    private static Path getFile(@Nullable Object value) {
        if (value instanceof DBPDriverLibrary library) {
            return library.getLocalFile();
        } else if (value instanceof DriverFileInfo fileInfo) {
            return fileInfo.getFile();
        }
        return null;
    }

    /**
     * The state of a library for the user. Downloadable libraries (Maven, URL) are "downloaded" when the loader has
     * resolved files for them that exist; local ones are "bundled" when they live in the installation folder
     * (the public edition's {@code drivers/}, docs/chromite/drivers.md) or in a plugin ({@code bundle:}).
     */
    @NotNull
    static State getState(@NotNull DriverDescriptor driver, @NotNull DBPDriverLibrary library) {
        if (library.isDownloadable()) {
            Collection<DriverFileInfo> files = driver.getDefaultDriverLoader().getLibraryFiles(library);
            if (CommonUtils.isEmpty(files)) {
                return State.NOT_DOWNLOADED;
            }
            for (DriverFileInfo file : files) {
                if (file.getFile() == null || !Files.exists(file.getFile())) {
                    return State.NOT_DOWNLOADED;
                }
            }
            return State.DOWNLOADED;
        }
        Path localFile = library.getLocalFile();
        if (localFile == null || !Files.exists(localFile)) {
            return State.MISSING;
        }
        if (library instanceof DriverLibraryBundle) {
            return State.BUNDLED;
        }
        Path install = getInstallFolder();
        return install != null && localFile.toAbsolutePath().normalize().startsWith(install) ? State.BUNDLED : State.LOCAL;
    }

    @NotNull
    static String getStateText(@NotNull State state) {
        return switch (state) {
            case BUNDLED -> DriverManagerMessages.driverlib_state_bundled;
            case LOCAL -> DriverManagerMessages.driverlib_state_local;
            case DOWNLOADED -> DriverManagerMessages.driverlib_state_downloaded;
            case NOT_DOWNLOADED -> DriverUtils.isOfflineMode()
                ? DriverManagerMessages.driverlib_state_not_downloaded_offline : DriverManagerMessages.driverlib_state_not_downloaded;
            case MISSING -> DriverManagerMessages.driverlib_state_missing;
        };
    }

    @Nullable
    private static Path getInstallFolder() {
        if (installFolder == null && Platform.getInstallLocation() != null) {
            try {
                installFolder = RuntimeUtils.getLocalPathFromURL(Platform.getInstallLocation().getURL()).toAbsolutePath().normalize();
            } catch (IOException e) {
                log.debug("Can't get the installation folder", e);
            }
        }
        return installFolder;
    }

    /**
     * Library icon, name, version and state (the state dimmed, an error color when the file is missing);
     * the tool tip is the local path.
     */
    private final class LibraryRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
            Path file = getFile(userObject);
            if (userObject instanceof DBPDriverLibrary library) {
                setIcon(NodeIcons.getIcon(library.getIcon()));
                StringBuilder name = new StringBuilder(library.getDisplayName());
                if (library.getPreferredVersion() != null) {
                    name.append(" [").append(library.getPreferredVersion()).append(']'); //$NON-NLS-1$
                }
                State state = getState(host.getDriver(), library);
                String stateText = getStateText(state);
                if (library.isOptional()) {
                    stateText += ", " + DriverManagerMessages.driverlib_optional; //$NON-NLS-1$
                }
                Color stateColor = selected ? null : state == State.MISSING
                    ? ConnectionDialog.getThemeColor("Chromite.status.error", "Component.error.focusedBorderColor", Color.RED) //$NON-NLS-1$ //$NON-NLS-2$
                    : UIManager.getColor("Label.disabledForeground"); //$NON-NLS-1$
                String stateHtml = stateColor == null ? escape(stateText)
                    : "<font color='" + String.format("#%06x", stateColor.getRGB() & 0xffffff) + "'>" + escape(stateText) + "</font>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                setText("<html>" + escape(name.toString()) + "&nbsp;&nbsp;" + stateHtml + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            } else if (userObject instanceof DriverFileInfo fileInfo) {
                setIcon(NodeIcons.getIcon(fileInfo.getType() == DBPDriverLibrary.FileType.license ? DBIcon.TYPE_TEXT : DBIcon.JAR));
                setText(fileInfo.toString());
            }
            setToolTipText(file == null ? null : file.toAbsolutePath().toString());
            return this;
        }

        @NotNull
        private static String escape(@NotNull String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        }
    }
}
