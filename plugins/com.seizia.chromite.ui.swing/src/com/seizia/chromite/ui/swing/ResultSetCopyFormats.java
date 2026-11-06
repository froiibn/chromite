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

import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import javax.swing.*;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 결과 표의 "Copy As" 메뉴: 고른 칸을 CSV, TSV, Markdown 표, SQL IN 목록으로 복사한다(SWT "Advanced Copy"에 해당).
 *
 * 설계: 표에 보이는 값(저장하지 않은 변경 포함, {@link ResultSetGrid#getCurrentData})과 고른 행·열만 쓴다. 파일 내보내기({@link ResultSetExport})와
 * 달리 클립보드용 글자만 만든다. NULL은 CSV·TSV에서 빈 칸, Markdown에서 {@code NULL}, IN 목록에서 {@code NULL}이다.
 * IN 목록은 고른 칸의 값을 중복 없이 차례대로 쓰고, 숫자 열의 숫자는 그대로, 나머지는 DB 방언의 문자열 따옴표({@link SQLUtils#quoteString})로 감싼다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-result-viewer.md "표 편의 기능")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 */
final class ResultSetCopyFormats {

    enum Format {
        CSV("CSV"),
        TSV(EditorMessages.editor_copy_tsv),
        MARKDOWN(EditorMessages.editor_copy_markdown),
        SQL_IN(EditorMessages.editor_copy_sql_in);

        private final String label;

        Format(@NotNull String label) {
            this.label = label;
        }
    }

    private ResultSetCopyFormats() {
    }

    @NotNull
    static JMenu createMenu(
        @NotNull JTable table,
        @NotNull Supplier<ResultSetGrid.Data> data,
        @NotNull Supplier<DBPDataSource> dataSource
    ) {
        JMenu menu = new JMenu(EditorMessages.editor_copy_as);
        for (Format format : Format.values()) {
            menu.add(ResultSetViewerPanel.createAction(format.label, null, () -> {
                if (table.getSelectedRowCount() > 0 && table.getSelectedColumnCount() > 0) {
                    DBWorkbench.getPlatformUI().copyTextToClipboard(format(format, table, data.get(), dataSource.get()), false);
                }
            }));
        }
        return menu;
    }

    @NotNull
    static String format(@NotNull Format format, @NotNull JTable table, @NotNull ResultSetGrid.Data data, @Nullable DBPDataSource dataSource) {
        int[] rows = table.getSelectedRows();
        int[] viewColumns = table.getSelectedColumns();
        int[] columns = new int[viewColumns.length];
        for (int i = 0; i < viewColumns.length; i++) {
            columns[i] = table.convertColumnIndexToModel(viewColumns[i]);
        }
        return switch (format) {
            case CSV -> delimited(data, rows, columns, ',');
            case TSV -> delimited(data, rows, columns, '\t');
            case MARKDOWN -> markdown(data, rows, columns);
            case SQL_IN -> inList(data, rows, columns, dataSource);
        };
    }

    @NotNull
    private static String delimited(@NotNull ResultSetGrid.Data data, @NotNull int[] rows, @NotNull int[] columns, char delimiter) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                out.append(delimiter);
            }
            out.append(delimitedValue(data.columns()[columns[i]], delimiter));
        }
        out.append('\n');
        for (int row : rows) {
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    out.append(delimiter);
                }
                Object value = data.rows().get(row)[columns[i]];
                out.append(value == ResultSetGrid.NULL_VALUE || value == null ? "" : delimitedValue(value.toString(), delimiter));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * CSV: RFC 4180 quoting. TSV: tabs and line breaks become spaces (TSV has no quoting).
     */
    @NotNull
    private static String delimitedValue(@NotNull String text, char delimiter) {
        if (delimiter == '\t') {
            return text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        }
        if (text.indexOf(delimiter) >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"'; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return text;
    }

    @NotNull
    private static String markdown(@NotNull ResultSetGrid.Data data, @NotNull int[] rows, @NotNull int[] columns) {
        StringBuilder out = new StringBuilder("|");
        for (int column : columns) {
            out.append(' ').append(markdownValue(data.columns()[column])).append(" |");
        }
        out.append("\n|");
        for (int column : columns) {
            out.append(data.numeric()[column] ? " ---: |" : " --- |");
        }
        out.append('\n');
        for (int row : rows) {
            out.append('|');
            for (int column : columns) {
                Object value = data.rows().get(row)[column];
                out.append(' ').append(markdownValue(value == null ? "" : value.toString())).append(" |");
            }
            out.append('\n');
        }
        return out.toString();
    }

    @NotNull
    private static String markdownValue(@NotNull String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @NotNull
    private static String inList(@NotNull ResultSetGrid.Data data, @NotNull int[] rows, @NotNull int[] columns, @Nullable DBPDataSource dataSource) {
        Set<String> items = new LinkedHashSet<>();
        for (int row : rows) {
            Object[] kept = data.values().get(row);
            for (int column : columns) {
                Object value = data.rows().get(row)[column];
                if (value == ResultSetGrid.NULL_VALUE || value == null) {
                    items.add("NULL"); //$NON-NLS-1$
                } else if (data.numeric()[column] && kept != null && kept[column] instanceof Number number) {
                    items.add(number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString());
                } else {
                    String text = value.toString();
                    items.add(dataSource != null ? SQLUtils.quoteString(dataSource, text) : "'" + text.replace("'", "''") + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
            }
        }
        return "(" + String.join(", ", items) + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
