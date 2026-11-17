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
package com.seizia.chromite.ext.altibase.extension;

import com.seizia.chromite.ext.altibase.model.AltibaseTable;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.List;

/**
 * A table expiry (TTL) capability of a server of the Altibase family: rows older than an amount of time in one date
 * column of a table expire. A server extension that knows the server's TTL catalog and statements offers it as an
 * adapter of the connection's data source ({@link AltibaseServerExtensionState#getAdapter}); the maintenance command
 * "TTL" and its dialog use it without knowing the server.
 *
 * 설계: 계획의 S6/S7(객체 명령 확장 지점과 쓰기 대화상자 API) 가운데 이 기능에 필요한 가장 좁은 중립 자리다. 일반 "객체 명령" 틀은
 * 메뉴 항목·대화상자 칸·문장·새로 고침을 모두 기여로 받아야 해서 크고, 쓰는 곳이 하나뿐이다. 그래서 기존 유지 관리 명령의 흐름
 * (설정 대화상자 → 미리 보기 → 열린 트랜잭션 질문 → 격리 실행 → 노드 다시 읽기)을 그대로 쓰고, 서버마다 다른 것(카탈로그 읽기,
 * 단위 낱말, 문장, 해당하는 테이블)만 이 인터페이스로 묻는다. 문장은 확장이 만든다(모델·UI 분리, UI는 값만 넘김). 제품 이름이 없고
 * 어댑터가 없으면 명령도 없다(그 확장이 없는 서버, 확장 번들을 뺀 제품). 메서드는 모델의 일반 타입({@link DBSTable},
 * {@link DBSEntityAttribute})만 받는다: Swing 번들은 Generic 플러그인에 기대지 않는다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 기존 테이블의 TTL 설정·변경·해제(앱 변경 #152, docs/chromite/altibase.md "테이블 TTL 자리")
 */
public interface AltibaseTableTtl {

    /**
     * A unit of the TTL amount: the word of the statement, the code the catalog stores and the name shown.
     */
    record Unit(@NotNull String keyword, @NotNull String code, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * A table's TTL as the catalog holds it.
     *
     * @param column   the column name
     * @param amount   the amount of units
     * @param unitCode the unit code of the catalog
     */
    record Setting(@NotNull String column, long amount, @NotNull String unitCode) {
    }

    /**
     * The command's settings: a TTL to set (column, amount, unit), or the removal of the table's TTL (column null).
     */
    record Change(@Nullable DBSEntityAttribute column, long amount, @Nullable Unit unit) {
        @NotNull
        public static Change remove() {
            return new Change(null, 0, null);
        }
    }

    /**
     * The capability of the object's connection when the object is an Altibase-family table it applies to; null on
     * another server, object or table. In memory only (detection result of the connection).
     */
    @Nullable
    static AltibaseTableTtl forTable(@Nullable DBSObject object) {
        if (!(object instanceof AltibaseTable table)) {
            return null;
        }
        AltibaseTableTtl ttl = DBUtils.getAdapter(AltibaseTableTtl.class, table.getDataSource());
        return ttl != null && ttl.appliesTo(table) ? ttl : null;
    }

    /**
     * Whether the table can take a TTL on this server (no database access).
     */
    boolean appliesTo(@NotNull DBSTable table);

    /**
     * The units offered for a new or changed TTL, in their order.
     */
    @NotNull
    List<Unit> getUnits();

    /**
     * The unit chosen first for a table without a TTL (one of {@link #getUnits()}).
     */
    @NotNull
    Unit getDefaultUnit();

    /**
     * The unit of a catalog code, also one not offered for a new TTL; null if the code is unknown.
     */
    @Nullable
    Unit getUnit(@NotNull String code);

    /**
     * Whether a column is offered as the TTL column.
     */
    boolean isTtlColumn(@NotNull DBSEntityAttribute column);

    /**
     * The table's TTL as the catalog holds it now; null if it has none.
     */
    @Nullable
    Setting readSetting(@NotNull DBRProgressMonitor monitor, @NotNull DBSTable table) throws DBException;

    /**
     * The statement of the change (setting a TTL sets a new one or changes the existing one).
     */
    @NotNull
    String getStatement(@NotNull DBSTable table, @NotNull Change change);

}
