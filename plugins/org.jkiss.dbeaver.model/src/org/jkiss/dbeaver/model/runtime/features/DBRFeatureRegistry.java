/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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

package org.jkiss.dbeaver.model.runtime.features;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.impl.AbstractDescriptor;
import org.jkiss.dbeaver.model.qm.QMUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * DBeaver feature registry
 *
 * 설계: 기능 사용 기록은 로컬 쿼리 관리자(QM) 처리기에만 전달한다. Chromite는 사용 통계를 수집하거나
 * 외부로 보내지 않으므로 upstream의 추적기(DBRFeatureTracker) 연결 지점을 두지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 사용 통계 기능 제거: DBRFeatureTracker 어댑터 조회, startTracking(), endTracking() 삭제
 *   (docs/chromite/telemetry.md)
 */
public class DBRFeatureRegistry {

    public static final String COMMAND_FEATURE_EXTENSION = "org.jkiss.dbeaver.commandFeature";

    private static final class CommandFeatureDescriptor extends AbstractDescriptor {
        @NotNull
        private final String id;
        @NotNull
        private final String commandId;

        private CommandFeatureDescriptor(@NotNull IConfigurationElement element) {
            super(element);
            id = Objects.requireNonNull(element.getAttribute("id"));
            commandId = Objects.requireNonNull(element.getAttribute("commandId"));
        }

        @NotNull
        private DBRFeature createFeature() {
            return new DBRFeature(id);
        }
    }

    private final Map<String, DBRFeature> commandFeatures = new HashMap<>();

    @Nullable
    private static DBRFeatureRegistry instance = null;

    @NotNull
    public synchronized static DBRFeatureRegistry getInstance() {
        if (instance == null) {
            instance = new DBRFeatureRegistry();
        }
        return instance;
    }

    private DBRFeatureRegistry() {
        for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(COMMAND_FEATURE_EXTENSION)) {
            if (element.getAttribute("id") == null || element.getAttribute("commandId") == null) {
                continue;
            }
            CommandFeatureDescriptor descriptor = new CommandFeatureDescriptor(element);
            commandFeatures.put(descriptor.commandId, descriptor.createFeature());
        }
    }

    @Nullable
    public DBRFeature findCommandFeature(@NotNull String commandId) {
        return commandFeatures.get(commandId);
    }

    public static void useFeature(@NotNull DBRFeature feature, @Nullable Map<String, Object> parameters) {
        QMUtils.getDefaultHandler().handleFeatureUsage(feature, parameters);
    }

}
