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

import com.seizia.chromite.model.admin.security.PermissionGrid;
import com.seizia.chromite.model.admin.security.Securable;
import com.seizia.chromite.model.admin.security.SecurityDialect;
import com.seizia.chromite.ui.swing.internal.SecurityMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.runtime.DBWorkbench;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Permissions of one table, view, sequence or routine by user and role (SSMS table properties, Permissions page).
 *
 * 설계: 위에 권한이 있는 사용자·역할, 아래에 고른 사용자의 권한 종류별 허용·권한 부여·거부 확인란이다({@link PermissionEditorPanel}).
 * SWT의 객체 권한 편집기({@code ObjectPermissionsEditor}, ext.postgresql.ui)는 역할 트리에서 고르는 방식이고 PostgreSQL에만 있다.
 * 여기서는 {@link SecurityDialect}가 DB별 카탈로그를 읽어 SQL Server·PostgreSQL·MySQL/MariaDB를 같은 화면으로 다룬다.
 * OK는 원본과 비교한 GRANT·REVOKE·DENY 스크립트를 보여 주고 실행한다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 */
final class ObjectPermissionsDialog extends JDialog {

    /**
     * What the dialog edits: the object and its permissions as read.
     */
    record Model(@NotNull Securable securable, @NotNull PermissionGrid<String> permissions) {
    }

    private final SecurityDialect dialect;
    private final Securable securable;
    private final PermissionGrid<String> original;
    private final PermissionGrid<String> edited;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final ObjectExplorerPanel explorer;
    private final PermissionEditorPanel<String> editor;

    private ObjectPermissionsDialog(
        @NotNull ObjectExplorerPanel owner,
        @NotNull SecurityDialect dialect,
        @NotNull Model model,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(SwingUtilities.getWindowAncestor(owner),
            NLS.bind(SecurityMessages.security_title_object_permissions, model.securable().getDisplayName()), ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        this.dialect = dialect;
        this.securable = model.securable();
        this.original = model.permissions();
        this.edited = model.permissions().copy();
        this.queryWindows = queryWindows;
        this.explorer = owner;

        editor = new PermissionEditorPanel<>(edited, SecurityMessages.security_users_and_roles,
            grantee -> grantee, null, grantee -> dialect.getPrivileges(securable.kind()), dialect.supportsDeny(),
            SecurityMessages.security_add_grantees, this::addGrantees);

        JLabel header = new JLabel(NLS.bind(SecurityMessages.security_object_header,
            SecurityLabels.getKindLabel(securable.kind()), securable.getDisplayName()));

        JButton scriptButton = new JButton(SecurityMessages.security_button_script);
        scriptButton.addActionListener(e -> SecurityCommands.showScript(this, getTitle(), dialect,
            dialect.generateObjectScript(securable, original, edited), queryWindows));
        JButton okButton = new JButton(ToolsMessages.button_ok);
        okButton.addActionListener(e -> apply());
        JButton cancelButton = new JButton(ToolsMessages.button_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(scriptButton);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(okButton);
        right.add(cancelButton);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(header, BorderLayout.NORTH);
        content.add(editor, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(640, 560);
        setLocationRelativeTo(owner);
    }

    static void show(
        @NotNull ObjectExplorerPanel owner,
        @NotNull SecurityDialect dialect,
        @NotNull Model model,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        new ObjectPermissionsDialog(owner, dialect, model, queryWindows).setVisible(true);
    }

    private void addGrantees() {
        List<String> grantees;
        try {
            grantees = ProgressDialog.runTask(SecurityMessages.security_pick_grantees_title,
                monitor -> dialect.listGrantees(monitor, securable));
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(SecurityMessages.security_pick_grantees_title, null, e);
            return;
        }
        List<String> candidates = new ArrayList<>();
        for (String grantee : grantees) {
            if (!edited.hasRow(grantee)) {
                candidates.add(grantee);
            }
        }
        List<String> picked = SecurityPickerDialog.pick(this, SecurityMessages.security_pick_grantees_title, candidates, grantee -> grantee, null);
        for (String grantee : picked) {
            edited.addRow(grantee);
        }
        if (!picked.isEmpty()) {
            editor.refresh();
            editor.select(picked.getFirst());
        }
    }

    private void apply() {
        if (SecurityCommands.applyScript(this, getTitle(), dialect, dialect.generateObjectScript(securable, original, edited),
            queryWindows, explorer, null, null) == SecurityCommands.ApplyResult.EXECUTED) {
            dispose();
        }
    }
}
