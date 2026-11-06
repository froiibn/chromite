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

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.maintenance.MaintenanceDialect;
import com.seizia.chromite.model.admin.maintenance.MaintenanceTarget;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.ui.swing.internal.MaintenanceViewMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.function.Supplier;

/**
 * Object Explorer 오른쪽 클릭 메뉴의 "유지 관리…" 명령: 테이블·인덱스·스키마·데이터베이스 노드에서 {@link MaintenanceDialog}를 연다.
 *
 * 설계: 여러 작업이 함께 고치는 {@code ObjectExplorerPanel}에는 이 클래스를 부르는 줄만 둔다(만들기, 메뉴에 넣기, 선택에 따라 켜기).
 * 어떤 노드에 할 수 있는지는 모델의 DB별 방언({@link MaintenanceDialect#getTarget})이 정한다(서버를 읽지 않고 노드의 종류만 본다).
 * 대상의 데이터베이스 인스턴스({@link DBUtils#getObjectOwnerInstance})로 실행한다: PostgreSQL은 데이터베이스마다 연결이 다르다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
final class MaintenanceCommands {

    private MaintenanceCommands() {
    }

    /**
     * The "Maintenance…" action for the selected object.
     */
    @NotNull
    static Action createAction(@NotNull Component owner, @NotNull Supplier<DBSObject> selection) {
        Action action = new AbstractAction(MaintenanceViewMessages.maintview_menu) {
            @Override
            public void actionPerformed(ActionEvent e) {
                open(owner, selection.get());
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, MaintenanceViewMessages.maintview_menu_tip);
        return action;
    }

    /**
     * Whether maintenance applies to the object (a table, index, schema or database of a supported database).
     */
    static boolean canMaintain(@Nullable DBSObject object) {
        return target(object) != null;
    }

    @Nullable
    private static MaintenanceTarget target(@Nullable DBSObject object) {
        DBPDataSource dataSource = object == null ? null : object.getDataSource();
        MaintenanceDialect dialect = dataSource == null ? null : MaintenanceDialect.forDataSource(dataSource);
        return dialect == null ? null : dialect.getTarget(object);
    }

    private static void open(@NotNull Component owner, @Nullable DBSObject object) {
        MaintenanceTarget target = target(object);
        DBPDataSource dataSource = object == null ? null : object.getDataSource();
        DBSInstance instance = object == null ? null : DBUtils.getObjectOwnerInstance(object);
        if (target == null || dataSource == null || instance == null) {
            return;
        }
        MaintenanceDialect dialect = MaintenanceDialect.forDataSource(dataSource);
        if (dialect != null) {
            MaintenanceDialog.open(owner, dataSource.getContainer(), instance, dialect, target, null, null);
        }
    }
}
