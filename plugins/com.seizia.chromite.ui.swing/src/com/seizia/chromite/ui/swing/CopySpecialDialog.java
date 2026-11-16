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

import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.navigator.DBNDatabaseItem;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Object Explorer Copy Special: copies the selected objects as names, qualified names or DDL, joined as chosen.
 *
 * 설계: SWT Copy Special({@code NavigatorHandlerCopySpecial}, {@code CopyMode.ADVANCED})은 정규화된 이름을 복사했다. Swing에는
 * 이름 복사(Ctrl+C)와 정규화된 이름 복사(Ctrl+Shift+C)가 이미 있으므로, 이 대화상자는 그 둘에 없는 것을 더한다: 구분자(줄마다, 쉼표,
 * 쉼표와 줄바꿈 — IN 목록이나 SELECT 열 목록에 붙이기 좋게)와 DDL(객체마다 View DDL과 같은 CREATE 문, {@link ObjectScripts#generateCreate}).
 * 이름 규칙은 {@link ExplorerObjectCommands}와 같다: 이름은 {@link DBSObject#getName}, 정규화된 이름은 방언 따옴표를 붙인
 * {@link DBUtils#getObjectFullName}({@link DBPEvaluationContext#DML}). DDL은 메타데이터를 읽으므로 진행 대화상자의 작업 스레드에서 만들고,
 * DDL을 만들 수 없는 객체가 있으면 DDL 선택을 끈다. 마지막 선택은 실행 중에 기억한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-explorer-productivity.md "Copy Special")
 */
final class CopySpecialDialog extends JDialog {

    private enum Content {
        NAMES, QUALIFIED_NAMES, DDL
    }

    private enum Separator {
        NEW_LINE(System.lineSeparator()),
        COMMA(", "), //$NON-NLS-1$
        COMMA_NEW_LINE("," + System.lineSeparator()); //$NON-NLS-1$

        private final String text;

        Separator(@NotNull String text) {
            this.text = text;
        }
    }

    private static Content lastContent = Content.QUALIFIED_NAMES;
    private static Separator lastSeparator = Separator.NEW_LINE;

    private final List<DBSObject> objects;
    private final JRadioButton namesButton = new JRadioButton(ObjectExplorerMessages.oe_copy_names);
    private final JRadioButton qualifiedButton = new JRadioButton(ObjectExplorerMessages.oe_copy_qualified);
    private final JRadioButton ddlButton = new JRadioButton(ObjectExplorerMessages.oe_copy_ddl);
    private final JComboBox<String> separatorCombo = new JComboBox<>(new String[]{
        ObjectExplorerMessages.oe_copy_separator_new_line, ObjectExplorerMessages.oe_copy_separator_comma,
        ObjectExplorerMessages.oe_copy_separator_comma_new_line});

    private CopySpecialDialog(@NotNull Component owner, @NotNull List<DBSObject> objects) {
        super(SwingUtilities.getWindowAncestor(owner), ObjectExplorerMessages.oe_copy_special_title, ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        this.objects = objects;

        ButtonGroup group = new ButtonGroup();
        group.add(namesButton);
        group.add(qualifiedButton);
        group.add(ddlButton);
        boolean canDdl = objects.stream().allMatch(ObjectScripts::canScriptCreate);
        ddlButton.setEnabled(canDdl);
        ddlButton.setToolTipText(canDdl ? null : ObjectExplorerMessages.oe_copy_ddl_unavailable);
        Content content = lastContent == Content.DDL && !canDdl ? Content.QUALIFIED_NAMES : lastContent;
        (switch (content) {
            case NAMES -> namesButton;
            case QUALIFIED_NAMES -> qualifiedButton;
            case DDL -> ddlButton;
        }).setSelected(true);
        separatorCombo.setSelectedIndex(lastSeparator.ordinal());
        // DDL scripts are separated by blank lines
        Runnable updateSeparator = () -> separatorCombo.setEnabled(!ddlButton.isSelected());
        namesButton.addActionListener(e -> updateSeparator.run());
        qualifiedButton.addActionListener(e -> updateSeparator.run());
        ddlButton.addActionListener(e -> updateSeparator.run());
        updateSeparator.run();

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 4, 0);
        form.add(new JLabel(objects.size() == 1
            ? ObjectExplorerMessages.oe_copy_what_one
            : NLS.bind(ObjectExplorerMessages.oe_copy_what_many, objects.size())), c);
        for (JRadioButton button : List.of(namesButton, qualifiedButton, ddlButton)) {
            c.gridy++;
            c.insets = new Insets(0, 12, 2, 0);
            form.add(button, c);
        }
        c.gridy++;
        c.gridwidth = 1;
        c.insets = new Insets(10, 0, 0, 6);
        form.add(new JLabel(ObjectExplorerMessages.oe_copy_separator), c);
        c.gridx = 1;
        c.insets = new Insets(10, 0, 0, 0);
        form.add(separatorCombo, c);

        JButton copyButton = new JButton(ObjectExplorerMessages.oe_copy_button);
        copyButton.addActionListener(e -> {
            if (copy()) {
                dispose();
            }
        });
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        buttons.add(copyButton);
        buttons.add(cancelButton);

        JPanel root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(copyButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * True when every node is a database object (Copy Special is enabled).
     */
    static boolean canCopy(@NotNull List<DBNNode> nodes) {
        return getObjects(nodes) != null;
    }

    static void open(@NotNull Component owner, @NotNull List<DBNNode> nodes) {
        List<DBSObject> objects = getObjects(nodes);
        if (objects != null) {
            new CopySpecialDialog(owner, objects).setVisible(true);
        }
    }

    /**
     * Builds the text and puts it on the clipboard.
     *
     * @return false when the DDL could not be made (the dialog stays open)
     */
    private boolean copy() {
        Content content = ddlButton.isSelected() ? Content.DDL : namesButton.isSelected() ? Content.NAMES : Content.QUALIFIED_NAMES;
        Separator separator = Separator.values()[Math.max(0, separatorCombo.getSelectedIndex())];
        lastContent = content;
        lastSeparator = separator;
        String text;
        if (content == Content.DDL) {
            try {
                text = ProgressDialog.runTask(ObjectExplorerMessages.oe_copy_progress, monitor -> {
                    List<String> scripts = new ArrayList<>();
                    for (DBSObject object : objects) {
                        scripts.add(ObjectScripts.generateCreate(monitor, object).strip());
                    }
                    return String.join(System.lineSeparator() + System.lineSeparator(), scripts);
                });
            } catch (DBInterruptedException e) {
                return false;
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError(ObjectExplorerMessages.oe_copy_special_title, ObjectExplorerMessages.oe_copy_ddl_error, e);
                return false;
            }
        } else {
            List<String> names = new ArrayList<>();
            for (DBSObject object : objects) {
                names.add(content == Content.NAMES ? object.getName() : DBUtils.getObjectFullName(object, DBPEvaluationContext.DML));
            }
            text = String.join(separator.text, names);
        }
        DBWorkbench.getPlatformUI().copyTextToClipboard(text, false);
        return true;
    }

    /**
     * The objects of the nodes, or null if a node is not a database object.
     */
    @Nullable
    private static List<DBSObject> getObjects(@NotNull List<DBNNode> nodes) {
        List<DBSObject> objects = new ArrayList<>();
        for (DBNNode node : nodes) {
            if (!(node instanceof DBNDatabaseItem item) || item.getObject() == null) {
                return null;
            }
            objects.add(item.getObject());
        }
        return objects.isEmpty() ? null : objects;
    }
}
