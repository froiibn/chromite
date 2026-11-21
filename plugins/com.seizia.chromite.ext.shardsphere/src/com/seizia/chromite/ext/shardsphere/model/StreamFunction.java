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

import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.shardsphere.internal.ShardSphereMessages;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.StringJoiner;

/**
 * A stream function ({@code CREATE STREAM FUNCTION}, {@code SYSTEM_.SYS_STREAM_FUNCTIONS_}) with its runtime row of
 * {@code V$STREAM_FUNCTION}.
 *
 * 설계: 사전에 소유자 열이 없고 매뉴얼은 만든 함수가 모든 사용자에게 공개된다고 한다. 그래서 스키마가 아니라 System Info ▸ ShardSphere
 * 아래 서버의 목록에 둔다. 유형 {@code FUNC_TYPE}은 서버에서 본 넷(LASTNTH, TOPK, DIST, HLL, 2026-11-21 시험용 함수로 확인)을 이름으로
 * 보이고 모르는 값은 그대로 보인다. 인자는 만든 문장의 인자 그대로({@code ARG1}-{@code 4}, 빈 칸은 뺌)다.
 *
 * 변경 이력:
 * - 2026-11-21 최초 작성(앱 변경 #153, docs/chromite/shardsphere.md "스트림 탐색기")
 */
public class StreamFunction implements DBSObject {

    /**
     * Every stream function of the server with its memory and call count.
     */
    static final String QUERY_FUNCTIONS = "SELECT F.FUNC_NAME, F.FUNC_TYPE, F.ARG1, F.ARG2, F.ARG3, F.ARG4, F.CREATED,"
        + " V.ALLOC_SIZE, V.TOUCH_COUNT FROM SYSTEM_.SYS_STREAM_FUNCTIONS_ F"
        + " LEFT OUTER JOIN V$STREAM_FUNCTION V ON V.FUNC_NAME = F.FUNC_NAME ORDER BY F.FUNC_NAME";

    @NotNull
    private final ShardSphereServer server;
    @NotNull
    private final String name;
    @Nullable
    private final String type;
    @NotNull
    private final String arguments;
    @Nullable
    private final Timestamp created;
    @Nullable
    private final Long memorySize;
    @Nullable
    private final Long callCount;

    StreamFunction(@NotNull ShardSphereServer server, @NotNull ResultSet dbResult) {
        this.server = server;
        this.name = String.valueOf(JDBCUtils.safeGetStringTrimmed(dbResult, "FUNC_NAME"));
        this.type = JDBCUtils.safeGetStringTrimmed(dbResult, "FUNC_TYPE");
        StringJoiner joined = new StringJoiner(", "); //$NON-NLS-1$
        for (String column : new String[] {"ARG1", "ARG2", "ARG3", "ARG4"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            String argument = JDBCUtils.safeGetStringTrimmed(dbResult, column);
            if (argument != null && !argument.isEmpty()) {
                joined.add(argument);
            }
        }
        this.arguments = joined.toString();
        this.created = JDBCUtils.safeGetTimestamp(dbResult, "CREATED");
        Integer allocSize = JDBCUtils.safeGetInteger(dbResult, "ALLOC_SIZE");
        Integer touchCount = JDBCUtils.safeGetInteger(dbResult, "TOUCH_COUNT");
        this.memorySize = allocSize == null ? null : allocSize.longValue();
        this.callCount = touchCount == null ? null : touchCount.longValue();
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Nullable
    @Property(viewable = true, order = 2)
    public String getType() {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "LASTNTH" -> ShardSphereMessages.stream_function_lastnth; //$NON-NLS-1$
            case "TOPK" -> ShardSphereMessages.stream_function_topk; //$NON-NLS-1$
            case "DIST" -> ShardSphereMessages.stream_function_dist; //$NON-NLS-1$
            case "HLL" -> ShardSphereMessages.stream_function_hll; //$NON-NLS-1$
            default -> type;
        };
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getArguments() {
        return arguments;
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public Timestamp getCreated() {
        return created;
    }

    /**
     * The memory the function's state uses, in bytes ({@code V$STREAM_FUNCTION.ALLOC_SIZE}).
     */
    @Nullable
    @Property(viewable = true, order = 5)
    public Long getMemorySize() {
        return memorySize;
    }

    @Nullable
    @Property(viewable = true, order = 6)
    public Long getCallCount() {
        return callCount;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public ShardSphereServer getParentObject() {
        return server;
    }

    @NotNull
    @Override
    public AltibaseDataSource getDataSource() {
        return server.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

}
