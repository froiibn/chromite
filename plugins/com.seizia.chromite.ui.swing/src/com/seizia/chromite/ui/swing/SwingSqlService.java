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
import com.seizia.chromite.model.DBPContextProvider;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRCreator;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.ui.UIServiceSQL;

import java.util.Map;

/**
 * SQL 서비스({@link UIServiceSQL})의 Swing 구현. 지금은 SQL 실행 확인({@link #confirmQueryExecution})만 한다.
 *
 * 설계: 남은 코드에서 이 서비스를 찾는 곳은 AI 모델의 {@code AIUtils.confirmExecute} 하나다(스크립트의 {@code @ai} 명령이 만든 SQL을
 * 실행하기 전 확인). 서비스가 없으면 그곳은 SQL 없이 "계속할까요"만 묻는 {@code DBPPlatformUI.confirmAction}으로 떨어져, 사용자가 AI가 만든
 * DML·DDL을 보지 않고 승인하게 된다. 그래서 SWT {@code UIServiceSQLImpl}처럼 이 서비스를 등록하고({@code plugin.xml}의
 * {@code com.seizia.chromite.service}) 실행할 SQL을 읽기 전용으로 보여 주는 {@link SqlConfirmDialog}로 묻는다.
 * 나머지 메서드는 SWT 편집기 부품(SQL 보기·편집 대화상자, SQL 콘솔, SWT 패널)을 돌려주는 것이라 Swing에 대응하는 화면이 없고 부르는 곳도 없다.
 * 조용히 아무것도 하지 않거나 빈 값을 돌려주면 호출자가 성공으로 오해할 수 있으므로 {@link UnsupportedOperationException}을 던진다.
 * 그런 호출자를 새로 만들면 이 클래스에 구현을 먼저 더해야 한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: {@code @ai} 명령 실행 확인에 SQL을 보여 줌 (docs/chromite/swing-sql-exec-safety.md)
 * - 2026-11-12 예약 실행({@link UnattendedRuns})에서는 묻지 않고 실행이 실패한다(앱 변경 #104, docs/chromite/task-scheduling.md "무인 실행")
 */
public class SwingSqlService implements UIServiceSQL {

    @Override
    public boolean confirmQueryExecution(
        @NotNull String title,
        @NotNull String message,
        @NotNull String queryText,
        @NotNull DBPContextProvider contextProvider,
        boolean isWarning
    ) {
        UnattendedRuns.refuseQuestion(title, message);
        return SqlConfirmDialog.confirm(null, title, message, queryText, isWarning);
    }

    @Override
    public int openSQLViewer(
        @Nullable DBCExecutionContext context,
        String title,
        @Nullable DBPImage image,
        String text,
        boolean showSaveButton,
        boolean showOpenEditorButton
    ) {
        throw notAvailable("openSQLViewer"); //$NON-NLS-1$
    }

    @Override
    public String openSQLEditor(@Nullable DBPContextProvider contextProvider, String title, @Nullable DBPImage image, String text) {
        throw notAvailable("openSQLEditor"); //$NON-NLS-1$
    }

    @Override
    public int openGeneratedScriptViewer(
        @Nullable DBCExecutionContext context,
        String title,
        @Nullable DBPImage image,
        @NotNull DBRCreator<String, Map<String, Object>> scriptGenerator,
        @NotNull DBPPropertyDescriptor[] properties,
        boolean showSaveButton
    ) {
        throw notAvailable("openGeneratedScriptViewer"); //$NON-NLS-1$
    }

    @Override
    public Object openSQLConsole(
        @NotNull DBPDataSourceContainer dataSourceContainer,
        @Nullable DBCExecutionContext executionContext,
        DBSObject selectedObject,
        String name,
        String sqlText
    ) {
        throw notAvailable("openSQLConsole"); //$NON-NLS-1$
    }

    @Override
    public Object createSQLPanel(
        Object site,
        Object parentControl,
        DBPContextProvider contextProvider,
        String panelName,
        boolean showVerticalBar,
        String sqlText
    ) {
        throw notAvailable("createSQLPanel"); //$NON-NLS-1$
    }

    @Override
    public void setSQLPanelText(Object panelObject, String sqlText) {
        throw notAvailable("setSQLPanelText"); //$NON-NLS-1$
    }

    @Override
    public String getSQLPanelText(Object panelObject) {
        throw notAvailable("getSQLPanelText"); //$NON-NLS-1$
    }

    @Override
    public Object openNewScript(DBSObject forObject) {
        throw notAvailable("openNewScript"); //$NON-NLS-1$
    }

    @Override
    public Object openRecentScript(DBSObject forObject) {
        throw notAvailable("openRecentScript"); //$NON-NLS-1$
    }

    @Override
    public boolean useIsolatedConnections(DBPContextProvider contextProvider) {
        throw notAvailable("useIsolatedConnections"); //$NON-NLS-1$
    }

    @NotNull
    private static UnsupportedOperationException notAvailable(@NotNull String method) {
        return new UnsupportedOperationException("UIServiceSQL." + method + " is not available in the Swing UI"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
