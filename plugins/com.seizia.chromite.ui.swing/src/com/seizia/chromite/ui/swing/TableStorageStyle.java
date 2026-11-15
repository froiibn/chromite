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

/**
 * Object Explorer 테이블의 저장 방식 표시: 메모리 테이블은 빨간 테이블 아이콘, 휘발성 테이블은 주황빨강 테이블 아이콘이고,
 * 툴팁이 저장 방식을 말로 알려 준다(색만으로는 알 수 없는 사용자를 위해). 이름 글자색은 바꾸지 않는다.
 *
 * 설계:
 * <ul>
 *     <li>모델의 {@link DBSTableWithStorageKind}만 본다. DBMS 이름으로 분기하지 않으므로 다른 DBMS도 그 인터페이스를
 *     구현하면 같은 표시를 얻는다. 값은 테이블 목록과 함께 읽혀 있어 그릴 때 질의가 없다.</li>
 *     <li>디스크 테이블은 보통 테이블이다(사용자 요청 2026-11-15): 팩의 테이블 아이콘, 보통 글자, 저장 방식 툴팁 없음.
 *     저장 방식은 Properties의 "Storage Type"이 보여 준다.</li>
 *     <li>아이콘 색은 테마 토큰({@code Chromite.Tree.storage*IconColor}, ChromiteLight/DarkLaf.properties)이다. 선택한 줄의
 *     아이콘을 바꾸지 않는 팩의 규칙에 맞춰, 토큰 값은 트리 바탕과 활성/비활성 선택 바탕 모두에서 그래픽 대비 3:1 이상이다.
 *     팩 아이콘의 선과 강조 채움만 칠하므로({@link UiIcons#inLineColor}) 테이블 모양은 보통 테이블 아이콘과 같다.
 *     아이콘은 처음 쓸 때 한 번 만들어 둔다(그릴 때마다 만들지 않음).</li>
 *     <li>하이브리드(파티션마다 저장 방식이 다름)는 한 색으로 말할 수 없어 아이콘을 바꾸지 않고 툴팁만 둔다.</li>
 *     <li>ObjectExplorerPanel의 렌더러는 이 클래스를 한 줄로 부른다. 그 파일은 여러 작업이 함께 고치므로 바뀌는 줄을
 *     줄이려고 따로 두었다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Altibase 메모리/디스크 테이블 색 구분 (docs/chromite/altibase.md)
 * - 2026-11-15 글자색 대신 테이블 아이콘 색: 메모리 빨강, 휘발성 주황빨강, 디스크는 보통 테이블(툴팁 없음)
 *   (앱 변경 #130, 사용자 요청, docs/chromite/altibase.md)
 */
final class TableStorageStyle {

    private static final String TABLE_ICON = "table"; //$NON-NLS-1$
    // A disabled tree shows the icon in text.disabled, not in a bright red
    private static final String DISABLED_COLOR_KEY = "Label.disabledForeground"; //$NON-NLS-1$

    @Nullable
    private static Icon memoryIcon;
    @Nullable
    private static Icon volatileIcon;

    private TableStorageStyle() {
    }

    /**
     * Gives a memory or volatile table node the table icon in its storage color and puts the kind in words in front of
     * its tooltip; a hybrid table gets the tooltip only. Does nothing for disk tables, other nodes and tables of unknown
     * kind. Called on the EDT (the icons are kept without synchronization).
     */
    static void apply(@NotNull JLabel label, @NotNull DBNNode node) {
        if (!(node instanceof DBNDatabaseNode databaseNode)
            || !(databaseNode.getObject() instanceof DBSTableWithStorageKind table)) {
            return;
        }
        DBSTableStorageKind kind = table.getStorageKind();
        if (kind == null || kind == DBSTableStorageKind.DISK) {
            return;
        }
        Icon icon = getIcon(kind);
        if (icon != null) {
            label.setIcon(icon);
        }
        String description = label.getToolTipText();
        label.setToolTipText(CommonUtils.isEmpty(description)
            ? NLS.bind(TableStorageMessages.table_storage_tooltip, kind.getName())
            : NLS.bind(TableStorageMessages.table_storage_tooltip_with_description, kind.getName(), description));
    }

    @Nullable
    private static Icon getIcon(@NotNull DBSTableStorageKind kind) {
        return switch (kind) {
            case MEMORY -> {
                if (memoryIcon == null) {
                    memoryIcon = UiIcons.inLineColor(TABLE_ICON, "Chromite.Tree.storageMemoryIconColor", DISABLED_COLOR_KEY); //$NON-NLS-1$
                }
                yield memoryIcon;
            }
            case VOLATILE -> {
                if (volatileIcon == null) {
                    volatileIcon = UiIcons.inLineColor(TABLE_ICON, "Chromite.Tree.storageVolatileIconColor", DISABLED_COLOR_KEY); //$NON-NLS-1$
                }
                yield volatileIcon;
            }
            case DISK, HYBRID -> null;
        };
    }
}
