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
package com.seizia.chromite.ext.shardsphere.model;

import com.seizia.chromite.ext.altibase.extension.AltibaseExecutionSettings;
import com.seizia.chromite.ext.altibase.model.AltibaseSchema;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The statements of the stream commands on a ShardSphere server: enable / disable and settings of continuous tables and
 * queries, the drop of each stream object and the CREATE templates.
 *
 * 설계: 일반 서버 5.1.9에서 시험용 개체({@code CHR_STW_*})로 확인한 모양만 쓴다(docs/chromite/shardsphere.md "스트림 명령").
 * <ul>
 *     <li>연속 테이블과 연속 스트림 테이블은 {@code ALTER|DROP CONTINUOUS TABLE}, 연속 쿼리는 {@code ALTER|DROP CONTINUOUS QUERY}다.
 *     다른 종류의 문장은 서버가 거절한다(테이블에 QUERY 201860, 쿼리에 TABLE 200753, {@code CONTINUOUS STREAM TABLE} 200705).</li>
 *     <li>{@code SET}은 절 하나씩이다(둘을 한 문장에 쓰면 200705): 실행 그룹, 실행 순서, TTL은 바뀐 것마다 한 문장.</li>
 *     <li>TTL은 연속 테이블에만 {@code USING TTL n 단위} / {@code USING NOTTL}이다(SET 없음). 연속 스트림 테이블은 201849로 거절한다.</li>
 *     <li>스트림 테이블은 {@code DROP TABLE}, 스트림 함수는 소유자 없이 {@code DROP STREAM FUNCTION}이다(함수에는 소유자가 없다:
 *     {@code CREATE STREAM FUNCTION 소유자.f}는 200705).</li>
 *     <li>이름은 방언의 규칙대로 따옴표를 붙인 {@code 소유자.이름}(서버가 받음, 따옴표 이름도 받음).</li>
 *     <li>틀은 기본값(사용 안 함, 그룹·순서 0)을 드러내 쓴 실행할 수 있는 모양이다. 고칠 자리는 기존 틀처럼 대문자 자리 표시
 *     이름({@code SOURCE_STREAM}, {@code TABLE_NAME})이다. 서로나 새 개체 이름을 품지 않아 바꾸기로 하나씩 고칠 수 있다. 고치지 않고
 *     실행하면 서버가 거절한다(없는 테이블). {@code OR REPLACE}는 쓰지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성(앱 변경 #156, docs/chromite/shardsphere.md "스트림 명령")
 */
public final class StreamDDL {

    // Template kinds (AltibaseObjectTemplates.Template.kind)
    static final String KIND_STREAM_TABLE = "streamTable"; //$NON-NLS-1$
    static final String KIND_CONTINUOUS_TABLE = "continuousTable"; //$NON-NLS-1$
    static final String KIND_CONTINUOUS_STREAM_TABLE = "continuousStreamTable"; //$NON-NLS-1$
    static final String KIND_CONTINUOUS_QUERY = "continuousQuery"; //$NON-NLS-1$
    static final String KIND_STREAM_FUNCTION = "streamFunction"; //$NON-NLS-1$

    // The placeholders the user replaces, named as the existing templates name theirs
    private static final String PLACEHOLDER_STREAM_TABLE = "SOURCE_STREAM"; //$NON-NLS-1$
    private static final String PLACEHOLDER_TABLE = "TABLE_NAME"; //$NON-NLS-1$

    private StreamDDL() {
    }

    @NotNull
    static String enable(@NotNull ContinuousObject object, boolean enable) {
        return alter(object) + (enable ? " SET ENABLE" : " SET DISABLE"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * One statement per changed setting, in the order group, order, TTL.
     */
    @NotNull
    static List<String> changeSettings(
        @NotNull ContinuousObject object,
        @NotNull AltibaseExecutionSettings.Settings current,
        @NotNull AltibaseExecutionSettings.Settings settings
    ) {
        List<String> statements = new ArrayList<>();
        if (settings.group() != current.group()) {
            statements.add(alter(object) + " SET EXEC GROUP " + settings.group()); //$NON-NLS-1$
        }
        if (settings.order() != current.order()) {
            statements.add(alter(object) + " SET EXEC ORDER " + settings.order()); //$NON-NLS-1$
        }
        AltibaseExecutionSettings.Ttl ttl = settings.ttl();
        if (!Objects.equals(ttl, current.ttl())) {
            statements.add(alter(object) + (ttl == null ? " USING NOTTL" //$NON-NLS-1$
                : " USING TTL " + ttl.amount() + " " + ttl.unit().keyword())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return statements;
    }

    /**
     * The statement that drops a stream table, continuous table, continuous stream table, continuous query or stream function.
     */
    @NotNull
    public static String drop(@NotNull DBSObject object) {
        if (object instanceof ContinuousObject continuous) {
            return "DROP " + kindWords(continuous) + " " + name(continuous); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (object instanceof StreamFunction function) {
            return "DROP STREAM FUNCTION " + DBUtils.getQuotedIdentifier(function); //$NON-NLS-1$
        }
        return "DROP TABLE " + name(object); //$NON-NLS-1$
    }

    /**
     * The CREATE template of a kind.
     *
     * @param schema the owner of the new object (null for a stream function, which has none)
     * @param name   the new object's name
     * @param ttl    whether the server has the TTL dictionary (the continuous table's template shows the TTL option)
     */
    @NotNull
    static String template(
        @NotNull String kind,
        @NotNull DBPDataSource dataSource,
        @Nullable AltibaseSchema schema,
        @NotNull String name,
        boolean ttl
    ) {
        String objectName = qualified(schema, DBUtils.getQuotedIdentifier(dataSource, name));
        String source = qualified(schema, PLACEHOLDER_STREAM_TABLE);
        String options = "    EXEC GROUP 0\n    EXEC ORDER 0\n    DISABLE\nAS\n"; //$NON-NLS-1$
        return switch (kind) {
            case KIND_STREAM_TABLE -> "CREATE STREAM TABLE " + objectName + " (\n    ID INTEGER,\n    NAME VARCHAR(100)\n);\n";
            case KIND_CONTINUOUS_STREAM_TABLE -> "CREATE CONTINUOUS STREAM TABLE " + objectName + "\n" + options
                + "SELECT ID, NAME\nFROM " + source + "\nWHERE ID > 0;\n";
            case KIND_CONTINUOUS_TABLE -> "CREATE CONTINUOUS TABLE " + objectName + "\n    WITH WINDOW 1 HOUR\n"
                + (ttl ? "    USING TTL 1 DAY\n" : "") + options
                + "SELECT ID, COUNT(*) AS CNT\nFROM " + source + "\nGROUP BY ID;\n";
            case KIND_CONTINUOUS_QUERY -> "CREATE CONTINUOUS QUERY " + objectName + "\n" + options
                + "INSERT INTO " + qualified(schema, PLACEHOLDER_TABLE) + " (ID, NAME)\nSELECT ID, NAME\nFROM " + source + ";\n";
            case KIND_STREAM_FUNCTION -> "CREATE STREAM FUNCTION " + objectName + " IS LASTNTH(10);\n";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @NotNull
    private static String alter(@NotNull ContinuousObject object) {
        return "ALTER " + kindWords(object) + " " + name(object); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * CONTINUOUS QUERY for a continuous query, CONTINUOUS TABLE for a continuous (stream) table.
     */
    @NotNull
    private static String kindWords(@NotNull ContinuousObject object) {
        return object.isQuery() ? "CONTINUOUS QUERY" : "CONTINUOUS TABLE"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private static String name(@NotNull DBSObject object) {
        return DBUtils.getObjectFullName(object, DBPEvaluationContext.DDL);
    }

    @NotNull
    private static String qualified(@Nullable AltibaseSchema schema, @NotNull String name) {
        return schema == null ? name : DBUtils.getQuotedIdentifier(schema) + "." + name; //$NON-NLS-1$
    }
}
