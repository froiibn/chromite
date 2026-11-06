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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;

/**
 * Opens SQL text in a new query window of the running application (tool {@code openInEditor}).
 * Implemented by the Swing frontend (phase 2) and registered with {@link AIAgentServices#setEditorBridge}.
 *
 * 설계: 도구는 모델 번들에 있고 쿼리 창은 Swing에 있다. 모델이 Swing에 의존하지 않도록 인터페이스만 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "편집기 연결")
 */
public interface AIEditorBridge {

    /**
     * Opens a new query window with the text; does not execute it. May be called on any thread.
     *
     * @param connection connection of the new window, or null for none
     * @param title      window title, or null for the default
     */
    void openSqlEditor(
        @Nullable DBPDataSourceContainer connection,
        @NotNull String sql,
        @Nullable String title
    ) throws DBException;
}
