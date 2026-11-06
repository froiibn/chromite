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

import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

import javax.swing.*;
import java.awt.*;

/**
 * Object Explorer "View DDL" 문서 탭: 객체의 DDL을 읽기 전용으로 보여 준다.
 *
 * 설계: SQL 편집기와 같은 {@link SqlEditorPanel.SqlTextArea}(구문 강조, 테마 색)를 편집할 수 없게 해서 쓴다.
 * 실행·저장 대상이 아니므로 {@link SqlEditorPanel}(연결, 결과, 파일)을 쓰지 않는다. 고쳐서 실행하려면 "Script as > CREATE To"로 편집기를 연다.
 *
 * 변경 이력:
 * - 2026-11-06 E단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 * - 2026-11-06 P1: Edit &gt; Find가 쓰는 편집기 접근자 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 객체의 연결을 받아 그 SQL 방언으로 강조 (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 */
final class DdlViewerPanel extends JPanel {

    private final String title;
    private final SqlEditorPanel.SqlTextArea textArea = new SqlEditorPanel.SqlTextArea();

    /**
     * @param dataSource connection of the object, for the keywords and functions of its SQL dialect
     * @param objectName full name of the object (tab title and header)
     * @param ddl        object definition
     */
    DdlViewerPanel(@Nullable DBPDataSourceContainer dataSource, @NotNull String objectName, @NotNull String ddl) {
        super(new BorderLayout());
        this.title = objectName + " [DDL]";
        textArea.setDataSourceContainer(dataSource);
        textArea.setText(ddl);
        textArea.setCaretPosition(0);
        textArea.setEditable(false);
        textArea.discardAllEdits();
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        // The gutter takes its colors from the theme too, so the theme is applied once the scroll pane exists
        textArea.applyTheme();

        JLabel header = new JLabel(NLS.bind(CoreMessages.ddl_header, objectName));
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(header, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
    }

    @NotNull
    String getTitle() {
        return title;
    }

    /**
     * The read-only text, for Edit &gt; Find.
     */
    @NotNull
    SqlEditorPanel.SqlTextArea getTextArea() {
        return textArea;
    }
}
