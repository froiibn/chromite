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
package com.seizia.chromite.model.admin.maintenance;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 대화상자를 열 때 서버에서 한 번 읽은 것: 대상에 할 수 있는 동작, 로그인 권한으로 할 수 없는 동작과 그 이유, 대상의 현재 상태.
 *
 * 설계: 권한을 먼저 본다(DB 관리 축 공통 규칙). 대화상자는 동작을 고를 때마다 {@link #getDeniedReason}을 보고 실행 단추를 끄고 이유를 보인다.
 * 문장 미리 보기({@link MaintenanceDialect#buildCommand})는 서버를 읽지 않고 이 계획과 옵션만으로 만든다(옵션을 바꿀 때마다 바로 바뀌게).
 * 스키마 대상의 테이블 목록, 인덱스 목록, 서버 에디션처럼 문장에 필요한 것은 방언이 {@link #getData}에 담는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: DB 관리 축 4 유지 관리 (docs/chromite/swing-admin-tools.md)
 */
public final class MaintenancePlan {

    @NotNull
    private final MaintenanceTarget target;
    @NotNull
    private final List<MaintenanceOperation> operations = new ArrayList<>();
    @NotNull
    private final Map<String, String> deniedReasons = new LinkedHashMap<>();
    @NotNull
    private final List<String> facts = new ArrayList<>();
    @Nullable
    private String suggestedOperation;
    @Nullable
    private Object data;

    public MaintenancePlan(@NotNull MaintenanceTarget target) {
        this.target = target;
    }

    @NotNull
    public MaintenanceTarget getTarget() {
        return target;
    }

    @NotNull
    public List<MaintenanceOperation> getOperations() {
        return operations;
    }

    public void addOperation(@NotNull MaintenanceOperation operation) {
        operations.add(operation);
    }

    @Nullable
    public MaintenanceOperation getOperation(@NotNull String id) {
        for (MaintenanceOperation operation : operations) {
            if (operation.id().equals(id)) {
                return operation;
            }
        }
        return null;
    }

    /**
     * Why the login may not run the operation (localized), or null if it may.
     */
    @Nullable
    public String getDeniedReason(@NotNull String operationId) {
        return deniedReasons.get(operationId);
    }

    public void deny(@NotNull String operationId, @NotNull String reason) {
        deniedReasons.put(operationId, reason);
    }

    /**
     * Current state of the target worth knowing before running (e.g. fragmentation and the suggestion), localized lines.
     */
    @NotNull
    public List<String> getFacts() {
        return facts;
    }

    public void addFact(@NotNull String fact) {
        facts.add(fact);
    }

    /**
     * Operation to select first (e.g. Rebuild for a heavily fragmented index), or null for the first one.
     */
    @Nullable
    public String getSuggestedOperation() {
        return suggestedOperation;
    }

    public void setSuggestedOperation(@Nullable String suggestedOperation) {
        this.suggestedOperation = suggestedOperation;
    }

    /**
     * What the dialect read to build statements (table list, edition...); only the dialect knows its type.
     */
    @Nullable
    public Object getData() {
        return data;
    }

    public void setData(@Nullable Object data) {
        this.data = data;
    }
}
