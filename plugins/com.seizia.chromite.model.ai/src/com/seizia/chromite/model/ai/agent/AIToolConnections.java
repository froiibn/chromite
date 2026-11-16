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
package com.seizia.chromite.model.ai.agent;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Finds the connection a tool argument names.
 *
 * 설계: 모델은 연결 이름을 기억하기 쉽고, ID는 {@code listConnections} 결과에서 정확히 옮길 수 있다. 둘 다 받되 ID를 먼저 보고,
 * 이름이 여러 연결에 같으면 추측하지 않고 후보 ID와 함께 오류를 낸다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "공통 규칙")
 */
public final class AIToolConnections {

    public static final String PARAM_CONNECTION = "connection"; //$NON-NLS-1$

    private AIToolConnections() {
    }

    /**
     * All connections of all projects of the workspace.
     */
    @NotNull
    public static List<DBPDataSourceContainer> getAll() {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            result.addAll(registry.getDataSources());
        }
        return result;
    }

    /**
     * A connection by ID or name (case-insensitive).
     */
    @NotNull
    public static DBPDataSourceContainer find(@NotNull String idOrName) throws AIToolArgumentException {
        String key = idOrName.trim();
        List<DBPDataSourceContainer> all = getAll();
        for (DBPDataSourceContainer container : all) {
            if (container.getId().equals(key)) {
                return container;
            }
        }
        List<DBPDataSourceContainer> byName = all.stream()
            .filter(c -> c.getName().equalsIgnoreCase(key))
            .toList();
        if (byName.size() == 1) {
            return byName.getFirst();
        }
        if (byName.isEmpty()) {
            throw new AIToolArgumentException("Connection '" + key + "' not found. Call listConnections for the IDs.");
        }
        throw new AIToolArgumentException("Several connections are named '" + key + "'; pass one of the IDs: " +
            byName.stream().map(DBPDataSourceContainer::getId).collect(Collectors.joining(", ")));
    }
}
