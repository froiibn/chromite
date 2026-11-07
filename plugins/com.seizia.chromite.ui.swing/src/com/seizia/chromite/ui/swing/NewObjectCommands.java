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

import com.seizia.chromite.ext.mysql.model.MySQLEvent;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectMaker;
import com.seizia.chromite.model.edit.DBEObjectManager;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.meta.DBXTreeItem;
import com.seizia.chromite.model.struct.DBSAlias;
import com.seizia.chromite.model.struct.DBSDataType;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSPackage;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;
import com.seizia.chromite.model.struct.rdb.DBSTrigger;
import com.seizia.chromite.model.struct.rdb.DBSView;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.CreateObjectMessages;
import com.seizia.chromite.ui.swing.internal.NavigatorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Object Explorer 폴더의 "New &lt;종류&gt;…" 명령: 폴더가 담는 종류의 새 개체를 만든다(SWT 탐색기의 "Create New …").
 *
 * 설계: SWT {@code NavigatorHandlerObjectCreateNew}는 폴더의 자식 종류에 객체 관리자({@link DBEObjectMaker})가 있으면 메뉴를 보이고,
 * 드라이버 UI 플러그인의 구성기 대화상자를 띄운 뒤 개체 편집기를 열었다. Swing에는 그 구성기와 개체 편집기가 없으므로 종류에 따라
 * 네 길로 나눈다(메뉴는 한 항목이고 이름은 폴더의 종류를 따른다, 예: "New Sequence…").
 * <ol>
 *     <li>틀(Template): 시퀀스·트리거·패키지·형식·이벤트 트리거·정책·큐는 본문을 사용자가 써야 하므로 실행할 수 있는 CREATE 틀을 새 쿼리 창에
 *     연다({@link SourceScripts#getTemplateKind}, New View와 같은 길). 실행은 쿼리 창이 하고, 창은 실행할 때마다 폴더를 다시 읽는다.</li>
 *     <li>디자이너: 테이블의 열·키·외래 키·인덱스·CHECK 폴더는 테이블 디자이너를 연다(SSMS "New Column…"처럼). 그 폴더의 관리자는 테이블을
 *     컨테이너로 받으므로 다른 폴더(스키마의 모든 인덱스)에서는 보이지 않는다.</li>
 *     <li>대화상자: 데이터베이스(PostgreSQL, SQL Server, MySQL/MariaDB)와 PostgreSQL 스키마·테이블스페이스·확장은 설정 대화상자
 *     ({@link NewObjectDialogs})로 값을 받는다.</li>
 *     <li>이름만(Generic): 그 밖에 객체 관리자가 있는 종류는 이름만 받는다(틀이 없는 드라이버의 시퀀스처럼 관리자 기본값으로 문장이
 *     되는 것). 본문이나 대상이 필요한 종류(뷰, 루틴, 테이블, 트리거, 패키지, 형식, 별칭)와 다른 명령이 맡는 종류(New Table·New View·
 *     보안 주체·MySQL 이벤트, Oracle 스키마 = 사용자)는 여기서 빼서, 이름만으로 틀린 문장이 나오지 않게 한다.</li>
 * </ol>
 * 대화상자와 이름만 길은 모델의 객체 관리자로 새 객체를 만들고({@link DBEObjectMaker#createNewObject}, 구성기 없이
 * {@link SQLObjectEditor#OPTION_SKIP_CONFIGURATION}), 삭제·이름 바꾸기와 같은 길({@link ExplorerObjectCommands#runChange})로 실행한다:
 * 원자적 명령 묶음이라 저장 전에는 모델과 탐색기가 바뀌지 않고, 스크립트를 먼저 보여 주며(Cancel이 기본 단추), 실행 직전에 연결의 열린
 * 트랜잭션을 묻는다(사용자 규칙 "트랜잭션 안전", DDL을 바로 커밋하는 MySQL·Oracle은 머리글에 적음). 저장 뒤 모델이 객체 추가 이벤트를 보내
 * 탐색기에 노드가 생긴다. 메뉴 항목은 해당하는 폴더에서만 보인다(SQL Server 에이전트 메뉴와 같은 방식): 폴더마다 종류가 달라
 * 꺼진 항목을 늘 두면 메뉴가 길어지기 때문이다. 탐색기({@link ObjectExplorerPanel})는 항목을 메뉴에 넣고 {@link #update}만 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-create-objects.md)
 */
final class NewObjectCommands {

    private enum Route {
        TEMPLATE,
        DESIGNER,
        DIALOG
    }

    /**
     * What "New …" does for a folder.
     *
     * @param kind the template kind ({@link Route#TEMPLATE} only)
     */
    private record Target(
        @NotNull Route route,
        @NotNull DBNDatabaseFolder folder,
        @NotNull DBSObject container,
        @NotNull Class<? extends DBSObject> childClass,
        @NotNull String typeLabel,
        @Nullable SourceScripts.NewObjectKind kind
    ) {
    }

    private final JComponent owner;
    private final Supplier<DBNNode> selection;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final ExplorerObjectCommands objectCommands;
    private final JMenuItem item;

    NewObjectCommands(
        @NotNull JComponent owner,
        @NotNull Supplier<DBNNode> selection,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows,
        @NotNull ExplorerObjectCommands objectCommands
    ) {
        this.owner = owner;
        this.selection = selection;
        this.queryWindows = queryWindows;
        this.objectCommands = objectCommands;
        item = new JMenuItem(new AbstractAction(NLS.bind(CreateObjectMessages.create_new_object, "")) { //$NON-NLS-1$
            @Override
            public void actionPerformed(ActionEvent e) {
                run();
            }
        });
        item.setVisible(false);
    }

    /**
     * The menu item; visible only on folders it applies to.
     */
    @NotNull
    JMenuItem getItem() {
        return item;
    }

    /**
     * Shows the item for the node and names it after the folder's type (EDT, no database access).
     */
    void update(@Nullable DBNNode node) {
        Target target = getTarget(node);
        item.setVisible(target != null);
        if (target != null) {
            item.setText(NLS.bind(CreateObjectMessages.create_new_object, target.typeLabel()));
            item.setToolTipText(NLS.bind(switch (target.route()) {
                case TEMPLATE -> CreateObjectMessages.create_new_template_tip;
                case DESIGNER -> CreateObjectMessages.create_new_designer_tip;
                case DIALOG -> CreateObjectMessages.create_new_object_tip;
            }, target.typeLabel()));
        }
    }

    @Nullable
    private static Target getTarget(@Nullable DBNNode node) {
        if (!(node instanceof DBNDatabaseFolder folder) || !(folder.getValueObject() instanceof DBSObject container)
            || container.getDataSource() == null) {
            return null;
        }
        Class<? extends DBSObject> childClass = folder.getChildrenClass();
        if (childClass == null
            // Folders with their own "New" items
            || TableDesignSession.canCreateTable(folder) || !SourceScripts.getNewKinds(folder).isEmpty()
            || SecurityCommands.getNewPrincipalKind(folder) != null) {
            return null;
        }
        String typeLabel = getTypeLabel(folder, container.getDataSource());
        SourceScripts.NewObjectKind kind = SourceScripts.getTemplateKind(folder);
        if (kind != null) {
            return new Target(Route.TEMPLATE, folder, container, childClass, typeLabel, kind);
        }
        if (DBSEntityAttribute.class.isAssignableFrom(childClass) || DBSEntityConstraint.class.isAssignableFrom(childClass)
            || DBSTableIndex.class.isAssignableFrom(childClass)) {
            return container instanceof DBSEntity && TableDesignSession.canDesign(container)
                ? new Target(Route.DESIGNER, folder, container, childClass, typeLabel, null) : null;
        }
        if (!NewObjectDialogs.hasDialog(childClass) && needsMoreThanName(childClass)) {
            return null;
        }
        DBEObjectMaker<DBSObject, Object> maker = getMaker(childClass);
        return maker != null && maker.canCreateObject(container)
            ? new Target(Route.DIALOG, folder, container, childClass, typeLabel, null) : null;
    }

    /**
     * Types whose statement needs more than a name (a body, a target, columns), or that other commands create.
     */
    private static boolean needsMoreThanName(@NotNull Class<?> childClass) {
        for (Class<?> type : List.of(DBSView.class, DBSProcedure.class, DBSTable.class, DBSTrigger.class, DBSPackage.class, DBSDataType.class,
            DBSAlias.class, MySQLEvent.class, OracleSchema.class)) {
            if (type.isAssignableFrom(childClass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The singular type name of the folder's items ("Sequence", "Database"), localized by the driver bundle.
     */
    @NotNull
    private static String getTypeLabel(@NotNull DBNDatabaseFolder folder, @NotNull DBPDataSource dataSource) {
        DBXTreeItem items = folder.getItemsMeta();
        String label = items == null ? null : items.getNodeTypeLabel(dataSource, null);
        return CommonUtils.isEmpty(label) ? folder.getNodeDisplayName() : label;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static DBEObjectMaker<DBSObject, Object> getMaker(@NotNull Class<?> objectClass) {
        return DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(objectClass, DBEObjectMaker.class);
    }

    //////////////////////////////////////////////////////////
    // Run

    private void run() {
        Target target = getTarget(selection.get());
        if (target == null) {
            return;
        }
        switch (target.route()) {
            case TEMPLATE -> openTemplate(target);
            case DESIGNER -> queryWindows.designTable((DBSEntity) target.container());
            case DIALOG -> create(target);
        }
    }

    /**
     * Opens the CREATE template in a new query window that reads the folder again after each run.
     */
    private void openTemplate(@NotNull Target target) {
        String title = NLS.bind(CreateObjectMessages.create_title, target.typeLabel());
        String script;
        try {
            script = ProgressDialog.runTask(title, monitor -> SourceScripts.generateNew(monitor, target.folder(), target.kind()));
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, CoreMessages.explorer_cant_generate, e);
            return;
        }
        queryWindows.openSourceScript(target.folder().getDataSourceContainer(), script, target.folder(), null);
    }

    /**
     * Asks the settings, then builds the object with its manager and runs it through the Delete/Rename path (script
     * preview, open transaction question, save).
     */
    private void create(@NotNull Target target) {
        DBSObject container = target.container();
        DBPDataSourceContainer dataSourceContainer = container.getDataSource().getContainer();
        String title = NLS.bind(CreateObjectMessages.create_title, target.typeLabel());
        if (dataSourceContainer.isConnectionReadOnly() || DBUtils.isReadOnly(container)) {
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(owner),
                SwingPlatformUI.createMessageComponent(NLS.bind(NavigatorMessages.navigator_change_restricted, dataSourceContainer.getName())),
                title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        NewObjectDialogs.Configurer configurer = NewObjectDialogs.ask(owner, title, container, target.childClass());
        if (configurer == null) {
            return;
        }
        objectCommands.runChange(title, dataSourceContainer, CreateObjectMessages.create_error_create, (monitor, changes) -> {
            DBEObjectMaker<DBSObject, Object> maker = getMaker(target.childClass());
            if (maker == null) {
                throw new DBException("No object manager for " + target.childClass().getName());
            }
            DBECommandContext context = ExplorerObjectCommands.createCommandContext(container);
            Map<String, Object> options = new HashMap<>();
            // The SWT configuration dialogs are not available; the dialog's values are put in by the configurer
            options.put(SQLObjectEditor.OPTION_SKIP_CONFIGURATION, true);
            options.put(DBEObjectManager.OPTION_CONTAINER, target.folder());
            options.put(DBEObjectManager.OPTION_OBJECT_TYPE, target.childClass());
            DBSObject object = maker.createNewObject(monitor, context, container, null, options);
            if (object == null) {
                context.resetChanges(true);
                throw new DBException("The " + target.childClass().getSimpleName() + " was not created");
            }
            changes.add(new ExplorerObjectCommands.Change(object, context, new HashMap<>()));
            configurer.configure(monitor, object, context);
        });
    }
}
