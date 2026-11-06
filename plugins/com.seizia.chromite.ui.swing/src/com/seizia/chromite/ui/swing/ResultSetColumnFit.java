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

import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;

/**
 * 결과 표의 열 너비를 내용에 맞추기(머리글 오른쪽 클릭 "Fit Column Width", 머리글 경계 두 번 누르기).
 *
 * 설계: 처음 열 너비는 표가 앞쪽 200행과 최대 320px로 정한다({@link ResultSetGrid}). 여기서는 사용자가 요청할 때 표의 실제 렌더러로
 * 머리글과 행({@link #MEASURED_ROWS}행까지)을 재어 더 넓게(최대 {@link #MAX_WIDTH}px) 맞춘다. 렌더러로 재므로 글꼴, 셀 여백, NULL 표시가 그대로 들어간다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-result-viewer.md "표 편의 기능")
 */
final class ResultSetColumnFit {

    private static final int MEASURED_ROWS = 5000;
    private static final int MIN_WIDTH = 40;
    private static final int MAX_WIDTH = 900;

    private ResultSetColumnFit() {
    }

    static void fitAllColumns(@NotNull JTable table) {
        for (int column = 0; column < table.getColumnCount(); column++) {
            fitColumn(table, column);
        }
    }

    /**
     * @param column view column index
     */
    static void fitColumn(@NotNull JTable table, int column) {
        if (column < 0 || column >= table.getColumnCount()) {
            return;
        }
        TableColumn tableColumn = table.getColumnModel().getColumn(column);
        TableCellRenderer headerRenderer = tableColumn.getHeaderRenderer() != null
            ? tableColumn.getHeaderRenderer() : table.getTableHeader().getDefaultRenderer();
        int width = headerRenderer.getTableCellRendererComponent(table, tableColumn.getHeaderValue(), false, false, -1, column)
            .getPreferredSize().width;
        int rows = Math.min(table.getRowCount(), MEASURED_ROWS);
        for (int row = 0; row < rows; row++) {
            Component cell = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
            width = Math.max(width, cell.getPreferredSize().width);
        }
        width += table.getIntercellSpacing().width + 2;
        tableColumn.setPreferredWidth(Math.clamp(width, UIScale.scale(MIN_WIDTH), UIScale.scale(MAX_WIDTH)));
    }
}
