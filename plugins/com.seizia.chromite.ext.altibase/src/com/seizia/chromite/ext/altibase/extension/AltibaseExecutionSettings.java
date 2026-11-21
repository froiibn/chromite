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

import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * An object of a server extension's tree that the server runs by itself in the background: it is enabled or disabled
 * and has an execution group and order, and maybe a TTL (rows that expire). The object itself implements it; the
 * maintenance commands "Enable", "Disable" and "Execution Settings" and their dialog use it without knowing the server.
 *
 * 설계: 테이블 TTL({@link AltibaseTableTtl})과 같은 좁은 능력이다. 기존 유지 관리 명령의 흐름(설정 대화상자 → 미리 보기 → 열린 트랜잭션
 * 질문 → 격리 실행 → 노드 다시 읽기)을 그대로 쓰고, 지금 값과 문장만 이 인터페이스로 묻는다. 문장은 확장이 만든다(모델·UI 분리, UI는 값만
 * 넘김). 확장의 객체가 직접 구현하므로 어댑터가 필요 없고, 확장이 없는 서버·확장 번들을 뺀 제품에는 이 객체가 없어 명령도 없다.
 * 대안(고르지 않음): 확장 번들이 객체 관리자의 속성 편집으로 값을 바꾸는 길 — 속성 보기의 편집은 절마다 따로 실행해야 하는 문장과 미리
 * 보기를 한 번에 보이지 못하고, 사용 / 사용 안 함은 앱에서 메뉴 명령(트리거, 작업)이다.
 * TTL 단위는 테이블 TTL의 단위 값({@link AltibaseTableTtl.Unit})을 쓴다(같은 서버의 같은 낱말·코드).
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성: 확장 객체의 사용 / 사용 안 함, 실행 그룹·순서·TTL 설정(앱 변경 #156, docs/chromite/altibase.md "확장 객체의 명령 자리")
 */
public interface AltibaseExecutionSettings extends DBSObject {

    /**
     * A TTL: the amount of units after which rows expire.
     */
    record Ttl(long amount, @NotNull AltibaseTableTtl.Unit unit) {
    }

    /**
     * The execution settings, as the catalog holds them or as the user chose them.
     *
     * @param ttl the TTL, or null for none
     */
    record Settings(int group, int order, @Nullable Ttl ttl) {
    }

    /**
     * Whether the server runs the object, as the model read it.
     */
    boolean isEnabled();

    /**
     * The current settings, as the model read them.
     */
    @NotNull
    Settings getExecutionSettings();

    /**
     * The units offered for a TTL, in their order; empty if the object takes no TTL (the dialog then has no TTL).
     */
    @NotNull
    List<AltibaseTableTtl.Unit> getTtlUnits();

    /**
     * The unit chosen first for an object without a TTL; null if the object takes no TTL.
     */
    @Nullable
    AltibaseTableTtl.Unit getDefaultTtlUnit();

    /**
     * The statement that enables or disables the object.
     */
    @NotNull
    String getEnableStatement(boolean enable);

    /**
     * The statements that change the current settings into these, one per changed setting; empty if none changed.
     */
    @NotNull
    List<String> getSettingsStatements(@NotNull Settings settings);

}
