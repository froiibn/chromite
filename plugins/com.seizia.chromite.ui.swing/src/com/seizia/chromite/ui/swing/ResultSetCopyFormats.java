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

import com.google.gson.stream.JsonWriter;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.GridEditMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.runtime.DBWorkbench;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 결과 표의 "Copy As" 메뉴와 고급 복사: 고른 칸을 CSV, TSV, Markdown 표, SQL IN 목록, JSON, XML, HTML 표, SQL INSERT로 복사하고,
 * 고급 복사 대화상자에서 머리글·행 번호·구분자·따옴표·NULL 글자를 정해 복사한다(SWT {@code ResultSetHandlerCopyAs}, {@code ResultSetHandlerCopySpecial}).
 *
 * 설계: 표에 보이는 값(저장하지 않은 변경 포함, {@link ResultSetGrid#getCurrentData})과 고른 행·열만 쓴다. 파일 내보내기({@link ResultSetExport})와
 * 달리 클립보드용 글자만 만든다. NULL은 CSV·TSV에서 빈 칸, Markdown에서 {@code NULL}, IN 목록에서 {@code NULL}이다.
 * IN 목록은 고른 칸의 값을 중복 없이 차례대로 쓰고, 숫자 열의 숫자는 그대로, 나머지는 DB 방언의 문자열 따옴표({@link SQLUtils#quoteString})로 감싼다.
 * SWT는 JSON·XML·HTML·INSERT 복사에 데이터 전송 처리기를 다시 실행했다. Swing은 표가 가진 값(숫자·불리언·날짜 원래 값과 표시 문자열)으로
 * 바로 만든다: 쿼리를 다시 실행하지 않고 저장하지 않은 변경도 그대로 복사하기 위해서다. 그래서 LOB 값은 표에 보이는 글자다(저장 미리 보기와 같은 한계).
 * INSERT의 테이블은 결과의 단일 원본 테이블, 없으면 {@code table}이고 이름과 값은 DB 방언으로 쓴다({@link SQLUtils#convertValueToSQL}).
 * 고급 복사 설정은 이 실행 동안 기억한다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-result-viewer.md "표 편의 기능")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 JSON, XML, HTML, SQL INSERT 형식과 고급 복사 대화상자 (docs/chromite/swing-grid-editing.md "고급 복사")
 */
final class ResultSetCopyFormats {

    enum Format {
        CSV("CSV"),
        TSV(EditorMessages.editor_copy_tsv),
        MARKDOWN(EditorMessages.editor_copy_markdown),
        SQL_IN(EditorMessages.editor_copy_sql_in),
        JSON("JSON"), //$NON-NLS-1$
        XML("XML"), //$NON-NLS-1$
        HTML(GridEditMessages.gridedit_copy_html),
        SQL_INSERT(GridEditMessages.gridedit_copy_sql_insert);

        private final String label;

        Format(@NotNull String label) {
            this.label = label;
        }
    }

    /**
     * Advanced Copy settings (SWT {@code ResultSetCopySettings}).
     *
     * @param header     column names as the first line
     * @param rowNumbers the row number as the first value of each line
     * @param delimiter  between values
     * @param quote      0: when needed, 1: always, 2: never
     * @param nullText   the text of NULL
     */
    record AdvancedOptions(boolean header, boolean rowNumbers, @NotNull String delimiter, int quote, @NotNull String nullText) {
    }

    private static final String[] DELIMITERS = {"\\t", ",", ";", "|"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    private static AdvancedOptions lastAdvanced = new AdvancedOptions(true, false, "\\t", 0, ""); //$NON-NLS-1$ //$NON-NLS-2$

    private ResultSetCopyFormats() {
    }

    /**
     * Advanced Copy…: asks for the settings, then copies the selected cells with them.
     */
    static void advancedCopy(@NotNull JTable table, @NotNull ResultSetGrid.Data data) {
        if (table.getSelectedRowCount() == 0 || table.getSelectedColumnCount() == 0) {
            return;
        }
        JCheckBox headerCheck = new JCheckBox(GridEditMessages.gridedit_copy_header, lastAdvanced.header());
        JCheckBox rowNumbersCheck = new JCheckBox(GridEditMessages.gridedit_copy_row_numbers, lastAdvanced.rowNumbers());
        JComboBox<String> delimiterCombo = new JComboBox<>(DELIMITERS);
        delimiterCombo.setEditable(true);
        delimiterCombo.setSelectedItem(lastAdvanced.delimiter());
        JComboBox<String> quoteCombo = new JComboBox<>(new String[]{GridEditMessages.gridedit_copy_quote_needed,
            GridEditMessages.gridedit_copy_quote_always, GridEditMessages.gridedit_copy_quote_never});
        quoteCombo.setSelectedIndex(lastAdvanced.quote());
        JTextField nullField = new JTextField(lastAdvanced.nullText(), 8);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        panel.add(headerCheck, c);
        c.gridy++;
        panel.add(rowNumbersCheck, c);
        c.gridwidth = 1;
        Object[][] rows = {
            {GridEditMessages.gridedit_copy_delimiter, delimiterCombo},
            {GridEditMessages.gridedit_copy_quote, quoteCombo},
            {GridEditMessages.gridedit_copy_null, nullField}};
        for (Object[] row : rows) {
            c.gridy++;
            c.gridx = 0;
            panel.add(new JLabel((String) row[0]), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            panel.add((Component) row[1], c);
            c.fill = GridBagConstraints.NONE;
        }
        // Copying changes nothing: OK may be the default
        if (JOptionPane.showConfirmDialog(table, panel, GridEditMessages.gridedit_copy_advanced, JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Object delimiter = delimiterCombo.getEditor().getItem();
        lastAdvanced = new AdvancedOptions(headerCheck.isSelected(), rowNumbersCheck.isSelected(),
            delimiter == null ? "" : delimiter.toString(), quoteCombo.getSelectedIndex(), nullField.getText()); //$NON-NLS-1$
        DBWorkbench.getPlatformUI().copyTextToClipboard(advanced(table, data, lastAdvanced), false);
    }

    /**
     * The selected cells with the Advanced Copy settings ({@code \t} in the delimiter is a tab).
     */
    @NotNull
    static String advanced(@NotNull JTable table, @NotNull ResultSetGrid.Data data, @NotNull AdvancedOptions options) {
        String delimiter = options.delimiter().replace("\\t", "\t"); //$NON-NLS-1$ //$NON-NLS-2$
        int[] rows = table.getSelectedRows();
        int[] columns = toModelColumns(table);
        StringBuilder out = new StringBuilder();
        if (options.header()) {
            if (options.rowNumbers()) {
                out.append('#').append(delimiter);
            }
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    out.append(delimiter);
                }
                out.append(quoteAdvanced(data.columns()[columns[i]], delimiter, options.quote()));
            }
            out.append('\n');
        }
        for (int row : rows) {
            if (options.rowNumbers()) {
                out.append(row + 1).append(delimiter);
            }
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    out.append(delimiter);
                }
                Object value = data.rows().get(row)[columns[i]];
                out.append(value == ResultSetGrid.NULL_VALUE || value == null
                    ? options.nullText() : quoteAdvanced(value.toString(), delimiter, options.quote()));
            }
            out.append('\n');
        }
        return out.toString();
    }

    @NotNull
    private static String quoteAdvanced(@NotNull String text, @NotNull String delimiter, int quote) {
        boolean needed = (!delimiter.isEmpty() && text.contains(delimiter)) || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
            || text.indexOf('\r') >= 0;
        if (quote == 1 || (quote == 0 && needed)) {
            return '"' + text.replace("\"", "\"\"") + '"'; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return text;
    }

    @NotNull
    private static int[] toModelColumns(@NotNull JTable table) {
        int[] viewColumns = table.getSelectedColumns();
        int[] columns = new int[viewColumns.length];
        for (int i = 0; i < viewColumns.length; i++) {
            columns[i] = table.convertColumnIndexToModel(viewColumns[i]);
        }
        return columns;
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
        menu.addSeparator();
        menu.add(ResultSetViewerPanel.createAction(GridEditMessages.gridedit_copy_advanced_menu, null, () -> advancedCopy(table, data.get())));
        return menu;
    }

    @NotNull
    static String format(@NotNull Format format, @NotNull JTable table, @NotNull ResultSetGrid.Data data, @Nullable DBPDataSource dataSource) {
        int[] rows = table.getSelectedRows();
        int[] columns = toModelColumns(table);
        return switch (format) {
            case CSV -> delimited(data, rows, columns, ',');
            case TSV -> delimited(data, rows, columns, '\t');
            case MARKDOWN -> markdown(data, rows, columns);
            case SQL_IN -> inList(data, rows, columns, dataSource);
            case JSON -> json(data, rows, columns);
            case XML -> xml(data, rows, columns);
            case HTML -> html(data, rows, columns);
            case SQL_INSERT -> insert(data, rows, columns, dataSource);
        };
    }

    /**
     * The value of a cell for typed formats: the kept number, boolean or date, else the display string; null for NULL.
     */
    @Nullable
    private static Object typedValue(@NotNull ResultSetGrid.Data data, int row, int column) {
        Object shown = data.rows().get(row)[column];
        if (shown == ResultSetGrid.NULL_VALUE || shown == null) {
            return null;
        }
        Object[] kept = data.values().get(row);
        return kept != null && kept[column] != null ? kept[column] : shown.toString();
    }

    /**
     * JSON: an array of objects, numbers and booleans as JSON values, dates and the rest as their grid text.
     */
    @NotNull
    private static String json(@NotNull ResultSetGrid.Data data, @NotNull int[] rows, @NotNull int[] columns) {
        StringWriter out = new StringWriter();
        try (JsonWriter writer = new JsonWriter(out)) {
            writer.setIndent("  "); //$NON-NLS-1$
            writer.setHtmlSafe(false);
            writer.beginArray();
            for (int row : rows) {
                writer.beginObject();
                for (int column : columns) {
                    writer.name(data.columns()[column]);
                    Object value = typedValue(data, row, column);
                    if (value == null) {
                        writer.nullValue();
                    } else if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                        writer.value(number);
                    } else if (value instanceof Boolean flag) {
                        writer.value(flag);
                    } else {
                        writer.value(data.rows().get(row)[column].toString());
                    }
                }
                writer.endObject();
            }
            writer.endArray();
        } catch (IOException e) {
            // A StringWriter does not fail
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    @NotNull
    private static String xml(@NotNull ResultSetGrid.Data data, @NotNull int[] rows, @NotNull int[] columns) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rows>\n"); //$NON-NLS-1$
        for (int row : rows) {
            out.append("  <row>\n"); //$NON-NLS-1$
            for (int column : columns) {
                Object value = data.rows().get(row)[column];
                out.append("    <column name=\"").append(escapeXml(data.columns()[column])).append('"'); //$NON-NLS-1$
                if (value == ResultSetGrid.NULL_VALUE || value == null) {
                    out.append(" null=\"true\"/>\n"); //$NON-NLS-1$
                } else {
                    out.append('>').append(escapeXml(value.toString())).append("</column>\n"); //$NON-NLS-1$
                }
            }
            out.append("  </row>\n"); //$NON-NLS-1$
        }
        return out.append("</rows>\n").toString(); //$NON-NLS-1$
    }

    @NotNull
    private static String html(@NotNull ResultSetGrid.Data data, @NotNull int[] rows, @NotNull int[] columns) {
        StringBuilder out = new StringBuilder("<table>\n  <tr>"); //$NON-NLS-1$
        for (int column : columns) {
            out.append("<th>").append(escapeXml(data.columns()[column])).append("</th>"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        out.append("</tr>\n"); //$NON-NLS-1$
        for (int row : rows) {
            out.append("  <tr>"); //$NON-NLS-1$
            for (int column : columns) {
                Object value = data.rows().get(row)[column];
                String text = value == ResultSetGrid.NULL_VALUE || value == null ? "" : escapeXml(value.toString()); //$NON-NLS-1$
                out.append(data.numeric()[column] ? "<td align=\"right\">" : "<td>").append(text).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            out.append("</tr>\n"); //$NON-NLS-1$
        }
        return out.append("</table>\n").toString(); //$NON-NLS-1$
    }

    /**
     * SQL INSERT statements into the result's source table (or {@code table}), names and values in the dialect.
     */
    @NotNull
    private static String insert(@NotNull ResultSetGrid.Data data, @NotNull int[] rows, @NotNull int[] columns, @Nullable DBPDataSource dataSource) {
        DBSEntity source = DBExecUtils.detectSingleSourceTable(data.bindings());
        String tableName = source != null ? DBUtils.getObjectFullName(source, DBPEvaluationContext.DML) : "table"; //$NON-NLS-1$
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) {
                names.append(", "); //$NON-NLS-1$
            }
            String name = data.columns()[columns[i]];
            names.append(dataSource != null ? DBUtils.getQuotedIdentifier(dataSource, name) : name);
        }
        StringBuilder out = new StringBuilder();
        for (int row : rows) {
            out.append("INSERT INTO ").append(tableName).append(" (").append(names).append(") VALUES ("); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    out.append(", "); //$NON-NLS-1$
                }
                Object value = typedValue(data, row, columns[i]);
                if (value == null) {
                    out.append("NULL"); //$NON-NLS-1$
                } else if (value instanceof Number number && data.numeric()[columns[i]]) {
                    out.append(number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString());
                } else if (!(value instanceof String) && dataSource != null) {
                    out.append(SQLUtils.convertValueToSQL(dataSource, data.bindings()[columns[i]], value));
                } else {
                    String text = value.toString();
                    out.append(dataSource != null ? SQLUtils.quoteString(dataSource, text) : "'" + text.replace("'", "''") + "'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
            }
            out.append(");\n"); //$NON-NLS-1$
        }
        return out.toString();
    }

    @NotNull
    private static String escapeXml(@NotNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;"); //$NON-NLS-1$
                case '<' -> out.append("&lt;"); //$NON-NLS-1$
                case '>' -> out.append("&gt;"); //$NON-NLS-1$
                case '"' -> out.append("&quot;"); //$NON-NLS-1$
                default -> {
                    // Characters XML 1.0 can't hold are left out
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
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
