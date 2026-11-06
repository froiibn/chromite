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

import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.struct.rdb.DBSTableStorageKind;
import com.seizia.chromite.model.struct.rdb.DBSTableWithStorageKind;
import com.seizia.chromite.ui.swing.internal.TableStorageMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;

/**
 * Object Explorer 테이블 이름의 저장 방식 표시: 메모리 테이블은 빨강, 디스크 테이블은 파랑, 휘발성 테이블은 호박색
 * 글자이고, 툴팁이 저장 방식을 말로 알려 준다(색만으로는 알 수 없는 사용자를 위해).
 *
 * 설계:
 * <ul>
 *     <li>모델의 {@link DBSTableWithStorageKind}만 본다. DBMS 이름으로 분기하지 않으므로 다른 DBMS도 그 인터페이스를
 *     구현하면 같은 표시를 얻는다. 값은 테이블 목록과 함께 읽혀 있어 그릴 때 질의가 없다.</li>
 *     <li>색은 테마 토큰({@code Chromite.Tree.storage*Foreground}, ChromiteLight/DarkLaf.properties)이다. 토큰 값은
 *     트리 바탕과 활성/비활성 선택 바탕 모두에서 4.5:1 이상이라 선택한 줄도 같은 색으로 읽힌다.</li>
 *     <li>하이브리드(파티션마다 저장 방식이 다름)는 한 색으로 말할 수 없어 글자색을 바꾸지 않고 툴팁만 둔다.</li>
 *     <li>ObjectExplorerPanel의 렌더러는 이 클래스를 한 줄로 부른다. 그 파일은 여러 작업이 함께 고치므로 바뀌는 줄을
 *     줄이려고 따로 두었다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Altibase 메모리/디스크 테이블 색 구분 (docs/chromite/altibase.md)
 */
final class TableStorageStyle {

    private TableStorageStyle() {
    }

    /**
     * Colors the label of a table node by its storage kind and puts the kind in words in front of its tooltip.
     * Does nothing for other nodes and for tables of unknown kind.
     */
    static void apply(@NotNull JLabel label, @NotNull DBNNode node) {
        if (!(node instanceof DBNDatabaseNode databaseNode)
            || !(databaseNode.getObject() instanceof DBSTableWithStorageKind table)) {
            return;
        }
        DBSTableStorageKind kind = table.getStorageKind();
        if (kind == null) {
            return;
        }
        Color color = getForeground(kind);
        if (color != null) {
            label.setForeground(color);
        }
        String description = label.getToolTipText();
        label.setToolTipText(CommonUtils.isEmpty(description)
            ? NLS.bind(TableStorageMessages.table_storage_tooltip, kind.getName())
            : NLS.bind(TableStorageMessages.table_storage_tooltip_with_description, kind.getName(), description));
    }

    @Nullable
    private static Color getForeground(@NotNull DBSTableStorageKind kind) {
        return switch (kind) {
            case MEMORY -> UIManager.getColor("Chromite.Tree.storageMemoryForeground"); //$NON-NLS-1$
            case DISK -> UIManager.getColor("Chromite.Tree.storageDiskForeground"); //$NON-NLS-1$
            case VOLATILE -> UIManager.getColor("Chromite.Tree.storageVolatileForeground"); //$NON-NLS-1$
            case HYBRID -> null;
        };
    }
}
