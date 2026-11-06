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
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ErdMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.runtime.DBInterruptedException;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Object Explorer "View Diagram" 문서 탭: 스키마의 테이블 또는 테이블과 관련 테이블의 ER 다이어그램.
 *
 * 설계:
 * <ul>
 *     <li>SWT의 "View Diagram"(ViewDiagramHandler → ERDEditorEmbedded)에 해당한다. SWT는 GEF 편집기라 쓸 수 없어
 *     모델({@link ErdDiagramModel}, model.erd 번들) 위에 Java2D 캔버스({@link ErdCanvas})를 새로 만들었다.</li>
 *     <li>읽기는 작업(Job)에서 하고 그동안 탭에 "로드하는 중"을 보인다. 다 읽으면 저장된 배치({@link ErdDiagramState}, SWT와 같은
 *     가상 모델 속성)가 있으면 그 위치를, 없으면 자동 배치({@link ErdLayout})를 쓰고 창에 맞춘다.
 *     저장된 배치에 없는 테이블(그 뒤 새로 만든 테이블)은 오른쪽에 놓는다.</li>
 *     <li>도구 막대(SSMS 다이어그램 도구 막대와 SWT ERD 도구 막대를 합친 것): 축소·비율·확대·창에 맞추기, 자동 레이아웃,
 *     열 표시(모두/키만/없음), 데이터 형식 표시, 새로 고침(위치 유지), 레이아웃 저장, 내보내기(PNG, SVG, .erd 파일).
 *     열 표시·형식 표시는 모든 다이어그램의 기본값으로 이 번들 설정에 저장한다.</li>
 *     <li>자동 배치 상태에서 열 표시를 바꾸면 상자 크기가 바뀌므로 다시 자동 배치한다. 사용자가 옮겼거나 저장된 배치를 연 경우는 위치를 둔다.</li>
 *     <li>상자 두 번 클릭은 Object Explorer에서 그 테이블을 고르고(SWT의 두 번 클릭은 테이블 편집기를 연다. Swing에는 그 편집기가 없으므로
 *     SSMS처럼 탐색기로 보낸다), 오른쪽 클릭 메뉴는 Object Explorer와 같은 Design·Select Top 1000 Rows다.</li>
 *     <li>PNG는 화면 배율과 관계없이 선명하도록 두 배로 그린다(아주 큰 다이어그램은 한 배). 색은 지금 테마를 따른다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-erd.md)
 */
final class ErdDiagramPanel extends JPanel implements ToolDocument {

    private static final Log log = Log.getLog(ErdDiagramPanel.class);

    private static final String PREF_COLUMNS = "erd.columns"; //$NON-NLS-1$
    private static final String PREF_SHOW_TYPES = "erd.showTypes"; //$NON-NLS-1$
    private static final String CARD_MESSAGE = "message"; //$NON-NLS-1$
    private static final String CARD_DIAGRAM = "diagram"; //$NON-NLS-1$
    private static final int PNG_SCALE = 2;
    /**
     * Above this many pixels the PNG is written at scale 1 (memory).
     */
    private static final long PNG_MAX_PIXELS = 60_000_000L;

    @Nullable
    private static File lastDirectory;

    private final DBSObject root;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final Consumer<DBNNode> revealer;
    private final String title;
    private final ErdRenderer renderer = new ErdRenderer();
    private final ErdCanvas canvas;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JLabel messageLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel();
    private final JComboBox<String> columnsCombo;
    private final JCheckBox typesCheck;
    private final Action zoomInAction;
    private final Action zoomOutAction;
    private final Action zoomActualAction;
    private final Action fitAction;
    private final Action layoutAction;
    private final Action refreshAction;
    private final Action saveAction;
    private final Action exportAction;
    private final Action exportPngAction;
    private final Action exportSvgAction;
    private final Action exportErdAction;

    /**
     * The positions come from the automatic layout (not moved by the user, not a saved layout).
     */
    private boolean autoLayout = true;
    private boolean dirty;
    private boolean disposed;
    @Nullable
    private AbstractJob loadJob;

    /**
     * @param root         schema (all its tables) or table (with related tables)
     * @param queryWindows main window: table designer and query windows
     * @param revealer     selects a node in Object Explorer
     */
    ErdDiagramPanel(@NotNull DBSObject root, @NotNull ObjectExplorerPanel.QueryWindows queryWindows, @NotNull Consumer<DBNNode> revealer) {
        super(new BorderLayout());
        this.root = root;
        this.queryWindows = queryWindows;
        this.revealer = revealer;
        DBSObject parent = root.getParentObject();
        this.title = root instanceof DBSEntity && parent instanceof DBSObjectContainer && parent != root.getDataSource()
            ? parent.getName() + "." + root.getName() : root.getName(); //$NON-NLS-1$

        DBPPreferenceStore preferences = getPreferences();
        renderer.columnMode = CommonUtils.valueOf(ErdRenderer.ColumnMode.class, preferences.getString(PREF_COLUMNS), ErdRenderer.ColumnMode.ALL);
        renderer.showTypes = CommonUtils.isEmpty(preferences.getString(PREF_SHOW_TYPES)) || preferences.getBoolean(PREF_SHOW_TYPES);

        canvas = new ErdCanvas(renderer, new ErdCanvas.Listener() {
            @Override
            public void entityOpened(@NotNull ErdDiagramModel.Entity entity) {
                showInExplorer(entity);
            }

            @Override
            public void popupRequested(@NotNull MouseEvent event, @Nullable ErdDiagramModel.Entity entity) {
                showPopup(event, entity);
            }

            @Override
            public void layoutChanged() {
                autoLayout = false;
                setDirty(true);
            }

            @Override
            public void zoomChanged() {
                updateZoomLabel();
            }
        });

        zoomInAction = createAction(ErdMessages.erd_zoom_in, canvas::zoomIn);
        zoomOutAction = createAction(ErdMessages.erd_zoom_out, canvas::zoomOut);
        zoomActualAction = createAction("100%", () -> canvas.setZoom(1.0, null)); //$NON-NLS-1$
        zoomActualAction.putValue(Action.SHORT_DESCRIPTION, ErdMessages.erd_zoom_actual);
        fitAction = createAction(ErdMessages.erd_zoom_fit, canvas::zoomToFit);
        layoutAction = createAction(ErdMessages.erd_layout, this::autoLayout);
        layoutAction.putValue(Action.SHORT_DESCRIPTION, ErdMessages.erd_layout_tip);
        refreshAction = createAction(ErdMessages.erd_refresh, () -> load(true));
        saveAction = createAction(ErdMessages.erd_save_layout, this::saveLayout);
        saveAction.putValue(Action.SHORT_DESCRIPTION, ErdMessages.erd_save_layout_tip);
        exportPngAction = createAction(ErdMessages.erd_export_png, this::exportPng);
        exportSvgAction = createAction(ErdMessages.erd_export_svg, this::exportSvg);
        exportErdAction = createAction(ErdMessages.erd_export_erd, this::exportErd);
        exportAction = new AbstractAction(ErdMessages.erd_export) {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (e.getSource() instanceof Component source) {
                    JPopupMenu menu = createExportMenu();
                    menu.show(source, 0, source.getHeight());
                }
            }
        };

        columnsCombo = new JComboBox<>(new String[]{
            ErdMessages.erd_columns_all, ErdMessages.erd_columns_keys, ErdMessages.erd_columns_none});
        columnsCombo.setSelectedIndex(renderer.columnMode.ordinal());
        columnsCombo.setMaximumSize(columnsCombo.getPreferredSize());
        columnsCombo.addActionListener(e -> {
            renderer.columnMode = ErdRenderer.ColumnMode.values()[columnsCombo.getSelectedIndex()];
            optionsChanged();
        });
        typesCheck = new JCheckBox(ErdMessages.erd_show_types, renderer.showTypes);
        typesCheck.setOpaque(false);
        typesCheck.setFocusable(false);
        typesCheck.addActionListener(e -> {
            renderer.showTypes = typesCheck.isSelected();
            optionsChanged();
        });

        // The zoom ratio as a text button: resets to 100%
        JButton zoomButton = new JButton(zoomActualAction);
        zoomButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        zoomButton.setFocusable(false);

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setOpaque(false);
        toolBar.add(UiIcons.createActionButton(zoomOutAction, "zoom-out")); //$NON-NLS-1$
        toolBar.add(zoomButton);
        toolBar.add(UiIcons.createActionButton(zoomInAction, "zoom-in")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(fitAction, "zoom-fit")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(layoutAction, "layout")); //$NON-NLS-1$
        toolBar.addSeparator();
        JLabel columnsLabel = new JLabel(ErdMessages.erd_columns);
        columnsLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(4), 0, UIScale.scale(4)));
        toolBar.add(columnsLabel);
        toolBar.add(columnsCombo);
        toolBar.add(Box.createHorizontalStrut(UIScale.scale(8)));
        toolBar.add(typesCheck);
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(saveAction, "save")); //$NON-NLS-1$
        toolBar.add(UiIcons.createToolButton(exportAction, "export")); //$NON-NLS-1$

        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        // Below the diagram, so a narrow tab does not push tool bar buttons out of view
        statusLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(12), UIScale.scale(4), UIScale.scale(12)));
        toolBar.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(3), UIScale.scale(8), UIScale.scale(3), UIScale.scale(8)));
        add(toolBar, BorderLayout.NORTH);
        add(statusLabel, BorderLayout.SOUTH);

        JScrollPane scrollPane = new JScrollPane(canvas);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(UIScale.scale(20));
        scrollPane.getHorizontalScrollBar().setUnitIncrement(UIScale.scale(20));
        messageLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        center.add(messageLabel, CARD_MESSAGE);
        center.add(scrollPane, CARD_DIAGRAM);
        add(center, BorderLayout.CENTER);

        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "zoomIn", zoomInAction); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, InputEvent.CTRL_DOWN_MASK), "zoomIn", zoomInAction); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "zoomOut", zoomOutAction); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, InputEvent.CTRL_DOWN_MASK), "zoomOut", zoomOutAction); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK), "zoomActual", zoomActualAction); //$NON-NLS-1$

        updateZoomLabel();
        load(false);
    }

    /**
     * The schema or table of the diagram (the main window shows an open diagram tab again instead of a second one).
     */
    @NotNull
    DBSObject getRoot() {
        return root;
    }

    //////////////////////////////////////////////////////////
    // ToolDocument

    @NotNull
    @Override
    public String getTitle() {
        return NLS.bind(ErdMessages.erd_tab_title, title);
    }

    @NotNull
    @Override
    public String getToolTip() {
        return NLS.bind(ErdMessages.erd_tab_tooltip, DBUtils.getObjectFullName(root, DBPEvaluationContext.UI),
            root.getDataSource().getContainer().getName());
    }

    @NotNull
    @Override
    public Icon getIcon() {
        return UiIcons.get("diagram"); //$NON-NLS-1$
    }

    @Override
    public void dispose() {
        disposed = true;
        if (loadJob != null) {
            loadJob.cancel();
        }
    }

    //////////////////////////////////////////////////////////
    // Loading

    /**
     * Reads the diagram in a job.
     *
     * @param keepPositions keep the positions of the boxes shown now (Refresh)
     */
    private void load(boolean keepPositions) {
        ErdDiagramModel current = canvas.getModel();
        Map<String, Point2D> shown = keepPositions && current != null ? currentPositions(current) : null;
        messageLabel.setText(ErdMessages.erd_loading);
        cards.show(center, CARD_MESSAGE);
        setActionsEnabled(false);
        loadJob = new AbstractJob("Load ER diagram " + root.getName()) { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    ErdDiagramModel model = ErdDiagramModel.load(monitor, root);
                    Map<String, Point2D> positions = shown;
                    if (positions == null && model.savedState != null) {
                        try {
                            positions = ErdDiagramState.parsePositions(model.savedState);
                        } catch (DBException e) {
                            log.warn("Saved diagram of " + root.getName() + " is not readable, using the automatic layout", e);
                        }
                    }
                    Map<String, Point2D> finalPositions = positions;
                    SwingUtilities.invokeLater(() -> loaded(model, finalPositions, keepPositions));
                } catch (Throwable e) {
                    SwingUtilities.invokeLater(() -> loadFailed(e));
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.schedule();
    }

    private void loaded(@NotNull ErdDiagramModel model, @Nullable Map<String, Point2D> positions, boolean refreshed) {
        if (disposed) {
            return;
        }
        for (String error : model.errors) {
            log.warn("ER diagram of " + root.getName() + ": " + error);
        }
        renderer.measure(model.entities);
        boolean anyPlaced = false;
        if (positions != null) {
            for (ErdDiagramModel.Entity entity : model.entities) {
                Point2D p = positions.get(entity.getLayoutKey());
                if (p != null) {
                    entity.x = p.getX();
                    entity.y = p.getY();
                    entity.placed = true;
                    anyPlaced = true;
                }
            }
        }
        if (anyPlaced) {
            ErdLayout.placeNew(model.entities);
            if (!refreshed) {
                autoLayout = false;
            }
        } else {
            ErdLayout.layout(model.entities, model.links);
            autoLayout = true;
        }
        canvas.setModel(model);
        setActionsEnabled(true);
        if (model.entities.isEmpty()) {
            messageLabel.setText(ErdMessages.erd_empty);
            return;
        }
        cards.show(center, CARD_DIAGRAM);
        updateStatus();
        if (!refreshed) {
            // After the scroll pane got its size
            SwingUtilities.invokeLater(canvas::zoomToFit);
        }
    }

    private void loadFailed(@NotNull Throwable error) {
        if (disposed) {
            return;
        }
        log.error("Error loading ER diagram of " + root.getName(), error);
        messageLabel.setText(ErdMessages.erd_load_failed);
        setActionsEnabled(false);
        refreshAction.setEnabled(true);
        DBWorkbench.getPlatformUI().showError(ErdMessages.erd_title, ErdMessages.erd_load_failed, error);
    }

    @NotNull
    private static Map<String, Point2D> currentPositions(@NotNull ErdDiagramModel model) {
        Map<String, Point2D> positions = new HashMap<>();
        for (ErdDiagramModel.Entity entity : model.entities) {
            positions.put(entity.getLayoutKey(), new Point2D.Double(entity.x, entity.y));
        }
        return positions;
    }

    //////////////////////////////////////////////////////////
    // Commands

    private void autoLayout() {
        ErdDiagramModel model = canvas.getModel();
        if (model == null) {
            return;
        }
        ErdLayout.layout(model.entities, model.links);
        autoLayout = true;
        setDirty(true);
        canvas.diagramResized();
        canvas.zoomToFit();
    }

    private void optionsChanged() {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_COLUMNS, renderer.columnMode.name());
        preferences.setValue(PREF_SHOW_TYPES, renderer.showTypes);
        PrefUtils.savePreferenceStore(preferences);
        ErdDiagramModel model = canvas.getModel();
        if (model == null) {
            return;
        }
        renderer.measure(model.entities);
        if (autoLayout) {
            ErdLayout.layout(model.entities, model.links);
        }
        canvas.diagramResized();
    }

    private void saveLayout() {
        ErdDiagramModel model = canvas.getModel();
        if (model == null) {
            return;
        }
        try {
            ErdDiagramState.save(model);
            setDirty(false);
            statusLabel.setText(ErdMessages.erd_status_saved);
        } catch (Exception e) {
            DBWorkbench.getPlatformUI().showError(ErdMessages.erd_title, ErdMessages.erd_save_layout_failed, e);
        }
    }

    private void showInExplorer(@NotNull ErdDiagramModel.Entity entity) {
        DBNNode node = DBNUtils.getNodeByObject(entity.object);
        if (node == null) {
            try {
                node = ProgressDialog.runTask(ErdMessages.erd_show_in_explorer,
                    monitor -> DBNUtils.getNodeByObject(monitor, entity.object, true));
            } catch (DBException e) {
                log.debug("Can't find the navigator node of " + entity.fullName, e);
            }
        }
        if (node != null) {
            revealer.accept(node);
        }
    }

    private void selectTop(@NotNull DBSEntity table) {
        int rows = ObjectExplorerPanel.SELECT_TOP_ROWS;
        String commandTitle = NLS.bind(CoreMessages.explorer_select_top, rows);
        DBPDataSourceContainer dataSource = table.getDataSource().getContainer();
        try {
            String script = ProgressDialog.runTask(commandTitle, monitor -> ObjectScripts.generateSelect(monitor, table, rows));
            if (script != null) {
                queryWindows.openScript(dataSource, script, rows, true);
            }
        } catch (DBInterruptedException e) {
            // Canceled in the progress dialog
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(commandTitle, CoreMessages.explorer_cant_generate, e);
        }
    }

    private void showPopup(@NotNull MouseEvent event, @Nullable ErdDiagramModel.Entity entity) {
        JPopupMenu menu = new JPopupMenu();
        if (entity != null) {
            menu.add(createAction(ErdMessages.erd_show_in_explorer, () -> showInExplorer(entity)));
            menu.addSeparator();
            Action design = createAction(CoreMessages.explorer_design, () -> queryWindows.designTable(entity.object));
            design.setEnabled(TableDesignSession.canDesign(entity.object));
            menu.add(design);
            Action select = createAction(NLS.bind(CoreMessages.explorer_select_top, ObjectExplorerPanel.SELECT_TOP_ROWS),
                () -> selectTop(entity.object));
            select.setEnabled(ObjectScripts.canSelect(entity.object));
            menu.add(select);
        } else {
            menu.add(layoutAction);
            menu.add(fitAction);
            menu.addSeparator();
            menu.add(exportPngAction);
            menu.add(exportSvgAction);
            menu.add(exportErdAction);
        }
        menu.show(event.getComponent(), event.getX(), event.getY());
    }

    @NotNull
    private JPopupMenu createExportMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(exportPngAction);
        menu.add(exportSvgAction);
        menu.addSeparator();
        menu.add(exportErdAction);
        return menu;
    }

    //////////////////////////////////////////////////////////
    // Export

    private void exportPng() {
        Path file = chooseFile(ErdMessages.erd_export_png, ErdMessages.erd_filter_png, "png", null); //$NON-NLS-1$
        if (file == null) {
            return;
        }
        ErdRenderer.Palette palette = ErdRenderer.Palette.current();
        Rectangle2D bounds = diagramBounds();
        int width = (int) Math.ceil(bounds.getWidth());
        int height = (int) Math.ceil(bounds.getHeight());
        int scale = (long) width * height * PNG_SCALE * PNG_SCALE > PNG_MAX_PIXELS ? 1 : PNG_SCALE;
        try {
            BufferedImage image = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.scale(scale, scale);
                g.setColor(palette.canvas());
                g.fillRect(0, 0, width, height);
                canvas.paintDiagram(new ErdPainter.Java2D(g), palette);
            } finally {
                g.dispose();
            }
            if (!ImageIO.write(image, "png", file.toFile())) { //$NON-NLS-1$
                throw new IOException("No PNG writer");
            }
        } catch (IOException | RuntimeException e) {
            DBWorkbench.getPlatformUI().showError(ErdMessages.erd_title, ErdMessages.erd_export_failed, e);
        }
    }

    private void exportSvg() {
        Path file = chooseFile(ErdMessages.erd_export_svg, ErdMessages.erd_filter_svg, "svg", null); //$NON-NLS-1$
        if (file == null) {
            return;
        }
        ErdRenderer.Palette palette = ErdRenderer.Palette.current();
        Rectangle2D bounds = diagramBounds();
        ErdPainter.Svg svg = new ErdPainter.Svg();
        canvas.paintDiagram(svg, palette);
        try {
            Files.writeString(file, svg.toDocument((int) Math.ceil(bounds.getWidth()), (int) Math.ceil(bounds.getHeight()), palette.canvas()),
                StandardCharsets.UTF_8);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ErdMessages.erd_title, ErdMessages.erd_export_failed, e);
        }
    }

    /**
     * Writes the diagram as an .erd file; the chooser starts in the project's "Diagrams" folder (where the SWT diagram
     * editor keeps its files) if it exists.
     */
    private void exportErd() {
        ErdDiagramModel model = canvas.getModel();
        if (model == null) {
            return;
        }
        File diagramsFolder = null;
        DBPProject project = root.getDataSource().getContainer().getProject();
        Path projectPath = project.getAbsolutePath();
        if (projectPath != null && Files.isDirectory(projectPath.resolve("Diagrams"))) { //$NON-NLS-1$
            diagramsFolder = projectPath.resolve("Diagrams").toFile(); //$NON-NLS-1$
        }
        Path file = chooseFile(ErdMessages.erd_export_erd, ErdMessages.erd_filter_erd, "erd", diagramsFolder); //$NON-NLS-1$
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file, ErdDiagramState.serialize(model, title, false), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(ErdMessages.erd_title, ErdMessages.erd_export_failed, e);
        }
    }

    @NotNull
    private Rectangle2D diagramBounds() {
        ErdDiagramModel model = canvas.getModel();
        return model == null ? new Rectangle2D.Double() : ErdRenderer.diagramBounds(model.entities);
    }

    /**
     * Save dialog with one file type; adds the extension and asks before replacing a file.
     */
    @Nullable
    private Path chooseFile(@NotNull String dialogTitle, @NotNull String filterName, @NotNull String extension, @Nullable File directory) {
        JFileChooser chooser = new JFileChooser(directory != null ? directory : lastDirectory);
        chooser.setDialogTitle(dialogTitle);
        chooser.setFileFilter(new FileNameExtensionFilter(filterName, extension));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setSelectedFile(new File(title.replaceAll("[\\\\/:*?\"<>|]", "_") + "." + extension)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (chooser.showSaveDialog(SwingUtilities.getWindowAncestor(this)) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path file = chooser.getSelectedFile().toPath();
        if (!file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + extension)) { //$NON-NLS-1$
            file = file.resolveSibling(file.getFileName() + "." + extension); //$NON-NLS-1$
        }
        lastDirectory = file.getParent() != null ? file.getParent().toFile() : null;
        if (Files.exists(file) && JOptionPane.showConfirmDialog(this,
            NLS.bind(CoreMessages.main_file_exists, file.getFileName()), dialogTitle,
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return null;
        }
        return file;
    }

    //////////////////////////////////////////////////////////
    // State

    private void setDirty(boolean dirty) {
        this.dirty = dirty;
        saveAction.setEnabled(dirty && canvas.getModel() != null);
        updateStatus();
    }

    private void updateStatus() {
        ErdDiagramModel model = canvas.getModel();
        if (model == null) {
            statusLabel.setText("");
        } else if (dirty) {
            statusLabel.setText(ErdMessages.erd_status_unsaved);
        } else {
            statusLabel.setText(NLS.bind(ErdMessages.erd_status_counts, model.entities.size(), model.links.size()));
        }
    }

    private void updateZoomLabel() {
        zoomActualAction.putValue(Action.NAME, Math.round(canvas.getZoom() * 100) + "%"); //$NON-NLS-1$
    }

    private void setActionsEnabled(boolean enabled) {
        boolean hasDiagram = enabled && canvas.getModel() != null && !canvas.getModel().entities.isEmpty();
        for (Action action : new Action[]{zoomInAction, zoomOutAction, zoomActualAction, fitAction, layoutAction, exportAction,
            exportPngAction, exportSvgAction, exportErdAction}) {
            action.setEnabled(hasDiagram);
        }
        refreshAction.setEnabled(enabled);
        saveAction.setEnabled(hasDiagram && dirty);
        columnsCombo.setEnabled(enabled);
        typesCheck.setEnabled(enabled);
    }

    private void bindKey(@NotNull KeyStroke keyStroke, @NotNull String name, @NotNull Action action) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, name);
        getActionMap().put(name, action);
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull Runnable command) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                command.run();
            }
        };
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }
}
