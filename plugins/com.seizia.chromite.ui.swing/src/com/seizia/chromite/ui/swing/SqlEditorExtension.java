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
import com.seizia.chromite.model.DBPDataSourceContainer;

import javax.swing.*;

/**
 * 쿼리 창({@link SqlEditorPanel})을 다른 종류의 문서로 쓰기 위한 확장(지금은 Query Library 쿼리, {@link QueryLibraryEditor}).
 *
 * 설계: 쿼리 창의 편집기·실행·결과(GO 배치, 여러 결과 집합, Messages)를 그대로 쓰고, 문서 종류에 따라 다른 부분만 이 인터페이스로 바꾼다.
 * 메인 창은 결과·상태 줄·저장 확인을 {@link SqlEditorPanel}에 대해 하므로, 쿼리 창을 감싸는 새 컴포넌트를 만들지 않고
 * 쿼리 창에 확장을 붙였다(메인 창과 쿼리 창의 변경을 작은 연결 지점으로 줄이기 위해서).
 * 모든 메서드는 EDT에서 불린다.
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md)
 */
interface SqlEditorExtension {

    /**
     * Component shown below the query window's tool bar, or null.
     */
    @Nullable
    JComponent getHeader();

    /**
     * Document tab icon, or null for the query window's own icon.
     */
    @Nullable
    Icon getTabIcon();

    /**
     * Saves the window instead of writing its text to a file (File &gt; Save, closing a modified window).
     *
     * @return false if it was not saved (canceled or failed)
     */
    boolean save(@NotNull SqlEditorPanel editor);

    /**
     * Called before a run: asks for parameter values.
     *
     * @param container connection the query runs on
     * @param sql       the text that runs (the selection, or the whole text)
     * @return the binding of the run ({@link SqlParameterBinding#NONE} without parameters), or null to cancel the run
     */
    @Nullable
    SqlParameterBinding prepareExecution(@NotNull SqlEditorPanel editor, @NotNull DBPDataSourceContainer container, @NotNull String sql);
}
