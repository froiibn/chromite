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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mysql.MySQLConstants;
import com.seizia.chromite.ext.mysql.model.MySQLTableColumn;
import com.seizia.chromite.ext.postgresql.model.PostgreDataType;
import com.seizia.chromite.ext.postgresql.model.PostgreTypeCategory;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSTypedObjectEx;

import java.util.List;

/**
 * 결과 표의 ENUM 열이 가질 수 있는 값(편집 콤보 상자용). 지금은 PostgreSQL ENUM 형식과 MySQL/MariaDB {@code ENUM} 열이다.
 *
 * 설계: SWT는 DB 확장의 값 관리자({@code PostgreEnumValueManager}, {@code MySQLEnumValueManager}, 각 {@code ext.*.ui} 번들)가
 * 콤보 편집기를 만들었다. Swing에는 값 관리자 확장점이 없고 Swing 번들이 이미 PostgreSQL·MySQL 모델 번들에 의존하므로, 두 관리자가 읽던
 * 모델 값({@link PostgreDataType#getEnumValues}, {@link MySQLTableColumn#getEnumValues})을 여기 한 곳에서 읽는다. DB별 코드를 표 코드에
 * 섞지 않으려고 따로 둔 클래스다. PostgreSQL 값 읽기는 형식 캐시가 비어 있으면 질의하므로, 수신기가 결과를 받는 Job 스레드에서
 * 부른다({@link ResultSetGridReceiver}). MySQL {@code SET}은 여러 값을 고르는 형식이라 콤보로 다루지 않고 글자 편집으로 둔다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: ENUM 열 콤보 편집 (docs/chromite/swing-grid-editing.md "ENUM 콤보")
 */
final class ResultSetEnumValues {

    private static final Log log = Log.getLog(ResultSetEnumValues.class);

    private ResultSetEnumValues() {
    }

    /**
     * The allowed values of each column, or null for a column that is not an ENUM (job thread: may query the
     * database).
     */
    @NotNull
    static String[][] read(@NotNull DBRProgressMonitor monitor, @NotNull DBDAttributeBinding[] bindings) {
        String[][] result = new String[bindings.length][];
        for (int i = 0; i < bindings.length; i++) {
            try {
                result[i] = readColumn(monitor, bindings[i].getEntityAttribute());
            } catch (Exception e) {
                // The column stays a text cell
                log.debug("Can't read the enum values of " + bindings[i].getName(), e);
            }
        }
        return result;
    }

    @Nullable
    private static String[] readColumn(@NotNull DBRProgressMonitor monitor, @Nullable DBSEntityAttribute attribute) {
        if (attribute instanceof MySQLTableColumn column) {
            List<String> values = column.getEnumValues();
            if (MySQLConstants.TYPE_NAME_ENUM.equalsIgnoreCase(column.getTypeName()) && values != null && !values.isEmpty()) {
                return values.toArray(new String[0]);
            }
            return null;
        }
        if (attribute instanceof DBSTypedObjectEx typed && typed.getDataType() instanceof PostgreDataType dataType
            && dataType.getTypeCategory() == PostgreTypeCategory.E) {
            Object[] values = dataType.getEnumValues(monitor);
            if (values == null || values.length == 0) {
                return null;
            }
            String[] labels = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                if (!(values[i] instanceof String label)) {
                    // PostgreDataType reports a failed read as {0}
                    return null;
                }
                labels[i] = label;
            }
            return labels;
        }
        return null;
    }
}
