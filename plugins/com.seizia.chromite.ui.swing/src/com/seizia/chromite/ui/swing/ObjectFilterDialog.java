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
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.meta.DBXTreeItem;
import com.seizia.chromite.model.struct.DBSObjectFilter;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Object Explorer Filter: include and exclude name masks of a folder (SWT {@code EditObjectFilterDialog}).
 *
 * 설계: 탐색기 모델의 객체 필터({@link DBSObjectFilter})를 그대로 고친다. 필터는 연결 설정({@code data-sources.json})에 저장되고
 * 모델이 자식을 읽을 때 적용하므로 SWT에서 만든 필터와 같다.
 * <ul>
 *     <li>범위: "이 폴더만"은 폴더의 부모 객체에 붙은 필터({@code DBNDatabaseNode#setNodeFilter}), "이 연결의 모든 같은 폴더"는
 *     그 객체 형식의 전역 필터({@link DBPDataSourceContainer#setObjectFilter}의 부모 null)다. 모델은 폴더 자신의 필터가 적용 가능하면
 *     그것을, 아니면 전역 필터를 쓴다({@code FilterMapping#getFilter}). 그래서 전역으로 저장할 때는 이 폴더 자신의 필터를 지워 전역을 따르게 한다.
 *     SWT는 전역 필터를 따로 여는 대화상자였지만 범위 선택 하나로 합쳤다.</li>
 *     <li>대화상자를 열면 폴더 자신의 필터가 있으면 그것을, 없고 전역 필터가 있으면 전역을 범위와 함께 보인다.</li>
 *     <li>마스크는 줄마다 하나다. 모델 규칙대로 {@code %}는 아무 글자들, {@code _}는 한 글자이고, 와일드카드가 없으면 이름 전체가 같아야 한다
 *     (대소문자 구분은 연결 방언을 따름). 포함 마스크는 하나라도 맞으면, 제외 마스크는 하나라도 맞으면 뺀다. 전에 있던 한 줄 입력 창은
 *     {@code %}가 없으면 앞뒤에 붙였지만, 여러 마스크에서는 SWT와 같이 적은 그대로 저장한다.</li>
 *     <li>포함·제외가 모두 비면 필터를 지운다. "필터 사용"을 끄면 마스크는 남기고 적용하지 않는다(모델의 {@code isEnabled}).</li>
 *     <li>설정만 바꾸는 대화상자라 OK가 기본 단추다(DB를 바꾸지 않음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 한 줄 포함 마스크 입력 창을 대신함 (docs/chromite/swing-explorer-productivity.md "필터 대화상자")
 * - 2026-11-13 마스크 안내를 HTML 라벨 대신 {@link WrappingNote}로: Swing 줄 바꿈이 한국어 낱말 가운데서 끊었다(앱 변경 #90,
 *   docs/chromite/swing-options.md "HTML 라벨 안내")
 */
final class ObjectFilterDialog extends JDialog {

    private final DBNDatabaseFolder folder;
    private final DBXTreeItem itemsMeta;
    private final Class<?> childrenClass;
    private final JCheckBox enabledBox = new JCheckBox(ObjectExplorerMessages.oe_filter_enabled, true);
    private final JTextArea includeArea = createMaskArea();
    private final JTextArea excludeArea = createMaskArea();
    private final JRadioButton folderScope;
    private final JRadioButton globalScope;
    /**
     * The scope whose filter changed (true: all folders of the type), or null when nothing was saved.
     */
    @Nullable
    private Boolean changedScope;

    private ObjectFilterDialog(@NotNull Component owner, @NotNull DBNDatabaseFolder folder, @NotNull DBXTreeItem itemsMeta,
                               @NotNull Class<?> childrenClass) {
        super(SwingUtilities.getWindowAncestor(owner), NLS.bind(ObjectExplorerMessages.oe_filter_title, folder.getNodeDisplayName()),
            ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        this.folder = folder;
        this.itemsMeta = itemsMeta;
        this.childrenClass = childrenClass;
        DBPDataSourceContainer container = folder.getDataSourceContainer();

        String parentName = folder.getParentNode() == null ? container.getName() : folder.getParentNode().getNodeDisplayName();
        folderScope = new JRadioButton(NLS.bind(ObjectExplorerMessages.oe_filter_scope_folder, parentName + " / " + folder.getNodeDisplayName())); //$NON-NLS-1$
        globalScope = new JRadioButton(NLS.bind(ObjectExplorerMessages.oe_filter_scope_global, folder.getNodeDisplayName(), container.getName()));
        ButtonGroup scopeGroup = new ButtonGroup();
        scopeGroup.add(folderScope);
        scopeGroup.add(globalScope);

        // The folder's own filter first, else the connection-wide one (as the model applies them)
        DBSObjectFilter own = folder.getNodeFilter(itemsMeta, true);
        DBSObjectFilter global = container.getObjectFilter(childrenClass, null, false);
        boolean showGlobal = own == null && global != null;
        DBSObjectFilter shown = showGlobal ? global : own;
        (showGlobal ? globalScope : folderScope).setSelected(true);
        if (shown != null) {
            enabledBox.setSelected(shown.isEnabled());
            includeArea.setText(String.join("\n", CommonUtils.safeList(shown.getInclude()))); //$NON-NLS-1$
            excludeArea.setText(String.join("\n", CommonUtils.safeList(shown.getExclude()))); //$NON-NLS-1$
        }
        // Switching the scope shows that scope's masks
        folderScope.addActionListener(e -> showFilter(folder.getNodeFilter(itemsMeta, true)));
        globalScope.addActionListener(e -> showFilter(container.getObjectFilter(childrenClass, null, false)));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 6, 0);
        form.add(enabledBox, c);
        c.gridy++;
        c.gridwidth = 1;
        c.weightx = 0.5;
        c.insets = new Insets(4, 0, 4, 6);
        form.add(new JLabel(ObjectExplorerMessages.oe_filter_include), c);
        c.gridx = 1;
        c.insets = new Insets(4, 6, 4, 0);
        form.add(new JLabel(ObjectExplorerMessages.oe_filter_exclude), c);
        c.gridy++;
        c.gridx = 0;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 6, 6);
        form.add(new JScrollPane(includeArea), c);
        c.gridx = 1;
        c.insets = new Insets(0, 6, 6, 0);
        form.add(new JScrollPane(excludeArea), c);
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 10, 0);
        // Wraps at spaces only (an HTML label broke Korean words) and reports the height of its lines at the dialog's width
        JTextArea hint = new WrappingNote(ObjectExplorerMessages.oe_filter_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        form.add(hint, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 2, 0);
        form.add(new JLabel(ObjectExplorerMessages.oe_filter_scope), c);
        c.gridy++;
        form.add(folderScope, c);
        c.gridy++;
        form.add(globalScope, c);

        JButton okButton = new JButton(CoreMessages.common_ok);
        okButton.addActionListener(e -> {
            save(buildFilter());
            dispose();
        });
        JButton clearButton = new JButton(ObjectExplorerMessages.oe_filter_clear);
        clearButton.setToolTipText(ObjectExplorerMessages.oe_filter_clear_tip);
        clearButton.addActionListener(e -> {
            save(null);
            dispose();
        });
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        buttons.add(clearButton);
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(560, 420);
        setLocationRelativeTo(owner);
    }

    /**
     * Edits the filter of a folder of database objects.
     *
     * @return null if nothing changed, else true when the connection-wide filter of the folder's object type changed
     * (every loaded folder of that type reads its children again), false when only this folder's filter did
     */
    @Nullable
    static Boolean open(@NotNull Component owner, @NotNull DBNDatabaseFolder folder) {
        DBXTreeItem itemsMeta = folder.getItemsMeta();
        Class<?> childrenClass = folder.getChildrenClass();
        if (itemsMeta == null || childrenClass == null) {
            return null;
        }
        ObjectFilterDialog dialog = new ObjectFilterDialog(owner, folder, itemsMeta, childrenClass);
        dialog.setVisible(true);
        return dialog.changedScope;
    }

    private void showFilter(@Nullable DBSObjectFilter filter) {
        enabledBox.setSelected(filter == null || filter.isEnabled());
        includeArea.setText(filter == null ? "" : String.join("\n", CommonUtils.safeList(filter.getInclude()))); //$NON-NLS-1$ //$NON-NLS-2$
        excludeArea.setText(filter == null ? "" : String.join("\n", CommonUtils.safeList(filter.getExclude()))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The filter of the dialog's masks, or null when there is no mask (the filter is removed).
     */
    @Nullable
    private DBSObjectFilter buildFilter() {
        List<String> include = readMasks(includeArea);
        List<String> exclude = readMasks(excludeArea);
        if (include.isEmpty() && exclude.isEmpty()) {
            return null;
        }
        DBSObjectFilter filter = new DBSObjectFilter();
        filter.setInclude(include.isEmpty() ? null : include);
        filter.setExclude(exclude.isEmpty() ? null : exclude);
        filter.setEnabled(enabledBox.isSelected());
        return filter;
    }

    /**
     * Saves the filter (null removes it) in the chosen scope and the connection configuration.
     */
    private void save(@Nullable DBSObjectFilter filter) {
        DBPDataSourceContainer container = folder.getDataSourceContainer();
        boolean global = globalScope.isSelected();
        if (global) {
            container.setObjectFilter(childrenClass, null, filter);
            // The folder's own filter would win over the connection-wide one: this folder follows it now
            if (filter != null && folder.getNodeFilter(itemsMeta, true) != null) {
                folder.setNodeFilter(itemsMeta, null, false);
            }
            container.persistConfiguration();
        } else {
            folder.setNodeFilter(itemsMeta, filter, true);
        }
        changedScope = global;
    }

    @NotNull
    private static List<String> readMasks(@NotNull JTextArea area) {
        List<String> masks = new ArrayList<>();
        for (String line : area.getText().split("\\R")) { //$NON-NLS-1$
            String mask = line.trim();
            if (!mask.isEmpty() && !masks.contains(mask)) {
                masks.add(mask);
            }
        }
        return masks;
    }

    @NotNull
    private static JTextArea createMaskArea() {
        JTextArea area = new JTextArea(8, 20);
        area.setFont(EditorFont.get());
        return area;
    }
}
