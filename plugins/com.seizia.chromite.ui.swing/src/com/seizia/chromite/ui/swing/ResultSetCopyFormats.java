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
import java.awt.datatransfer.Transferable;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * 결과 표의 "Copy As" 메뉴와 고급 복사: 고른 칸을 CSV, TSV, Markdown 표, SQL IN 목록, JSON, XML, HTML 표, SQL INSERT로 복사하고,
 * 고급 복사 대화상자에서 머리글·행 번호·구분자·따옴표·NULL 글자를 정해 복사한다(SWT {@code ResultSetHandlerCopyAs}, {@code ResultSetHandlerCopySpecial}).
 *
 * 설계: 표에 보이는 값(저장하지 않은 변경 포함, {@link ResultSetGrid#getCurrentData})과 고른 행·열만 쓴다. 파일 내보내기({@link ResultSetExport})와
 * 달리 클립보드용 글자만 만든다. NULL은 CSV·TSV에서 빈 칸, Markdown에서 {@code NULL}, IN 목록에서 {@code NULL}이다.
 * IN 목록은 고른 칸의 값을 중복 없이 차례대로 쓰고, 숫자 열의 숫자는 그대로, 나머지는 DB 방언의 문자열 따옴표({@link SQLUtils#quoteString})로 감싼다.
 * SWT는 JSON·XML·HTML·INSERT 복사에 데이터 전송 처리기를 다시 실행했다. Swing은 표가 가진 값(숫자·불리언·날짜 원래 값과 표시 문자열)으로
 * 바로 만든다: 쿼리를 다시 실행하지 않고 저장하지 않은 변경도 그대로 복사하기 위해서다. LOB 값은 표에 보이는 글자이되, 칸 글자가 값의 일부인 LOB를 DB 없이 얻을 수 있으면
 * 전문이다({@link ResultSetGrid#getCopyData}: 로컬 임시 파일이나 값 패널이 읽은 글자, 백그라운드에서 읽음). 읽지 않은 LOB는 칸 글자(자리 표시)다.
 * INSERT의 테이블은 결과의 단일 원본 테이블, 없으면 {@code table}이고 이름과 값은 DB 방언으로 쓴다({@link SQLUtils#convertValueToSQL}).
 * 고급 복사 설정은 이 실행 동안 기억한다.
 * <p>
 * 결과 표의 복사(Ctrl+C, 편집 ▸ 복사, Copy)와 머리글 포함 복사도 여기서 만든다({@link #copySelection}, 결과 값을 보이는 다른 표는
 * {@link TableCopyHandler}). Swing 기본 표 복사는 값을 따옴표 없이 탭으로 잇고 HTML 표에 값을 이스케이프 없이 넣어, 줄바꿈·탭·{@code "}가 든
 * 값이 엑셀에서 여러 칸으로 깨지고 {@code <b>} 같은 글자가 표식이 되었다. 그래서 글자는 고급 복사와 같은 코드({@link #appendDelimited})에
 * 탭·"필요할 때" 따옴표·그리드의 NULL 글자를 넘겨 만들고, 엑셀이 붙여넣을 때 글자보다 먼저 읽는 HTML 표는 값을 이스케이프하고 줄바꿈을
 * 칸 안에 남는 {@code <br>}({@code mso-data-placement:same-cell})로 쓴다. 다른 값은 전과 같다(따옴표 없음, 마지막 줄 끝 줄바꿈 없음).
 * 두 글자는 복사할 때 한 번씩 만든다: Windows의 Java 클립보드는 넣는 순간 모든 형식을 만들기 때문에({@code WClipboard.setContentsNative})
 * 나중에 만들어도 시간과 메모리가 줄지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-result-viewer.md "표 편의 기능")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 JSON, XML, HTML, SQL INSERT 형식과 고급 복사 대화상자 (docs/chromite/swing-grid-editing.md "고급 복사")
 * - 2026-11-22 이미 읽은 LOB는 앞부분 대신 전문을 복사, 고급 복사는 설정을 고른 뒤 행을 읽음 (앱 변경 #160 후속, docs/chromite/swing-result-viewer.md "긴 LOB 칸")
 * - 2026-11-28 결과 표의 복사·머리글 포함 복사와 결과 값을 보이는 다른 표의 복사: 고급 복사의 "필요할 때" 따옴표로 만든 글자와 값을 이스케이프한
 *   HTML 표({@link #copySelection}, {@link TableCopyHandler}). 고급 복사의 글자 만들기를 한 {@link StringBuilder}에 쓰는 {@link #appendDelimited}로,
 *   XML 이스케이프를 줄바꿈 표식을 받는 {@link #appendEscaped}로 나눔 (앱 변경 #189, docs/chromite/swing-grid-editing.md "엑셀로 붙여넣는 복사")
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
    /**
     * A line break in a cell of a copied HTML table. Excel ends a cell at a plain {@code <br>} (the rest goes to the next
     * row) and turns a raw line break into a space; with this style it keeps the line break inside the cell.
     */
    private static final String HTML_LINE_BREAK = "<br style=\"mso-data-placement:same-cell\">"; //$NON-NLS-1$
    private static AdvancedOptions lastAdvanced = new AdvancedOptions(true, false, "\\t", 0, ""); //$NON-NLS-1$ //$NON-NLS-2$

    private ResultSetCopyFormats() {
    }

    /**
     * Advanced Copy…: asks for the settings, then copies the selected cells with them. The rows are taken after the
     * settings: reading the whole text of LOB cells may show a progress dialog, and the user may cancel it (nothing copied).
     */
    static void advancedCopy(@NotNull JTable table, @NotNull Supplier<ResultSetGrid.Data> data) {
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
        ResultSetGrid.Data copyData = data.get();
        if (copyData != null) {
            DBWorkbench.getPlatformUI().copyTextToClipboard(advanced(table, copyData, lastAdvanced), false);
        }
    }

    /**
     * The selected cells with the Advanced Copy settings ({@code \t} in the delimiter is a tab).
     */
    @NotNull
    static String advanced(@NotNull JTable table, @NotNull ResultSetGrid.Data data, @NotNull AdvancedOptions options) {
        return appendDelimited(new StringBuilder(), table.getSelectedRows(), toModelColumns(table), column -> data.columns()[column],
            (row, column) -> data.rows().get(row)[column], options).toString();
    }

    /**
     * A cell of a copied selection: its display value, {@link ResultSetGrid#NULL_VALUE} (or null) for NULL.
     */
    @FunctionalInterface
    interface CellValues {
        @Nullable
        Object get(int row, int column);
    }

    /**
     * Delimited lines of the cells with the Advanced Copy settings, each line ending with {@code \n}. Rows and columns are
     * passed to {@code names} and {@code cells} as given. Everything goes into {@code out}: a copy of hundreds of
     * thousands of rows makes no string per cell.
     */
    @NotNull
    private static StringBuilder appendDelimited(
        @NotNull StringBuilder out,
        @NotNull int[] rows,
        @NotNull int[] columns,
        @NotNull IntFunction<String> names,
        @NotNull CellValues cells,
        @NotNull AdvancedOptions options
    ) {
        String delimiter = options.delimiter().replace("\\t", "\t"); //$NON-NLS-1$ //$NON-NLS-2$
        if (options.header()) {
            if (options.rowNumbers()) {
                out.append('#').append(delimiter);
            }
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    out.append(delimiter);
                }
                appendQuoted(out, names.apply(columns[i]), delimiter, options.quote());
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
                Object value = cells.get(row, columns[i]);
                if (value == ResultSetGrid.NULL_VALUE || value == null) {
                    out.append(options.nullText());
                } else {
                    appendQuoted(out, value.toString(), delimiter, options.quote());
                }
            }
            out.append('\n');
        }
        return out;
    }

    /**
     * A value in double quotes with inner {@code "} doubled when the quote setting asks for it (when needed: the value holds
     * the delimiter, a {@code "} or a line break), else as it is.
     */
    private static void appendQuoted(@NotNull StringBuilder out, @NotNull String text, @NotNull String delimiter, int quote) {
        boolean needed = (!delimiter.isEmpty() && text.contains(delimiter)) || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
            || text.indexOf('\r') >= 0;
        if (quote != 1 && (quote != 0 || !needed)) {
            out.append(text);
            return;
        }
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                out.append('"');
            }
            out.append(c);
        }
        out.append('"');
    }

    /**
     * Copy (Ctrl+C, Edit > Copy) and Copy with Headers of the result grid: the selected cells of {@code data} as
     * tab-separated text and as an HTML table ({@link #selection}).
     *
     * @return null without a selection
     */
    @Nullable
    static Transferable copySelection(@NotNull JTable table, @NotNull ResultSetGrid.Data data, boolean header) {
        return selection(table.getSelectedRows(), toModelColumns(table), column -> data.columns()[column],
            (row, column) -> data.rows().get(row)[column], header);
    }

    /**
     * Ctrl+C and Edit > Copy of a table that shows result values outside the grid (record view, aggregates, the items of
     * a value dialog, the reference picker): its selected cells as the grid copies them ({@link #selection}), in place of
     * Swing's table copy. As Swing's, it copies every row (column) when the table does not select rows (columns), and a
     * null cell is empty.
     */
    static class TableCopyHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent component) {
            return COPY;
        }

        @Nullable
        @Override
        protected Transferable createTransferable(JComponent component) {
            if (!(component instanceof JTable table) || (!table.getRowSelectionAllowed() && !table.getColumnSelectionAllowed())) {
                return null;
            }
            int[] rows = table.getRowSelectionAllowed() ? table.getSelectedRows() : allIndexes(table.getRowCount());
            int[] columns = table.getColumnSelectionAllowed() ? table.getSelectedColumns() : allIndexes(table.getColumnCount());
            return selection(rows, columns, table::getColumnName, (row, column) -> {
                Object value = table.getValueAt(row, column);
                return value == null ? "" : value; //$NON-NLS-1$
            }, false);
        }

        @NotNull
        private static int[] allIndexes(int count) {
            int[] indexes = new int[count];
            for (int i = 0; i < count; i++) {
                indexes[i] = i;
            }
            return indexes;
        }
    }

    /**
     * The clipboard contents of a copy of result cells (why: the class comment):
     * <ul>
     *     <li>Text: Advanced Copy with a tab, quotes when needed and the grid's NULL text ({@link #appendDelimited}); the
     *     other values as they are shown, no line break after the last line (one cell pastes as its value alone).</li>
     *     <li>HTML (Excel reads it before the text when it pastes): every value escaped, each line break as a {@code <br>}
     *     that Excel keeps in the cell ({@link #HTML_LINE_BREAK}). Header names are {@code <th>} cells.</li>
     * </ul>
     *
     * @return null when no cell is selected
     */
    @Nullable
    private static Transferable selection(
        @NotNull int[] rows,
        @NotNull int[] columns,
        @NotNull IntFunction<String> names,
        @NotNull CellValues cells,
        boolean header
    ) {
        if (rows.length == 0 || columns.length == 0) {
            return null;
        }
        String nullText = ResultSetGrid.NULL_VALUE.toString();
        StringBuilder text = appendDelimited(new StringBuilder(), rows, columns, names, cells, new AdvancedOptions(header, false, "\\t", 0, nullText)); //$NON-NLS-1$
        text.setLength(text.length() - 1);
        StringBuilder html = new StringBuilder("<html>\n<body>\n<table>\n"); //$NON-NLS-1$
        if (header) {
            html.append("<tr>\n"); //$NON-NLS-1$
            for (int column : columns) {
                appendEscaped(html.append("  <th>"), names.apply(column), HTML_LINE_BREAK).append("</th>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            html.append("</tr>\n"); //$NON-NLS-1$
        }
        for (int row : rows) {
            html.append("<tr>\n"); //$NON-NLS-1$
            for (int column : columns) {
                Object value = cells.get(row, column);
                String cellText = value == ResultSetGrid.NULL_VALUE || value == null ? nullText : value.toString();
                appendEscaped(html.append("  <td>"), cellText, HTML_LINE_BREAK).append("</td>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            html.append("</tr>\n"); //$NON-NLS-1$
        }
        html.append("</table>\n</body>\n</html>"); //$NON-NLS-1$
        return new SqlTextTools.HtmlSelection(html.toString(), text.toString());
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
                ResultSetGrid.Data rows = table.getSelectedRowCount() > 0 && table.getSelectedColumnCount() > 0 ? data.get() : null;
                // Null: no selection, or reading the whole text of LOB cells was canceled
                if (rows != null) {
                    DBWorkbench.getPlatformUI().copyTextToClipboard(format(format, table, rows, dataSource.get()), false);
                }
            }));
        }
        menu.addSeparator();
        menu.add(ResultSetViewerPanel.createAction(GridEditMessages.gridedit_copy_advanced_menu, null, () -> advancedCopy(table, data)));
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
        return appendEscaped(new StringBuilder(text.length()), text, null).toString();
    }

    /**
     * The text with {@code & < > "} as entities and the characters XML 1.0 can't hold left out; with {@code lineBreak},
     * each line break ({@code \r\n}, {@code \n} or {@code \r}) is written as it.
     */
    @NotNull
    private static StringBuilder appendEscaped(@NotNull StringBuilder out, @NotNull String text, @Nullable String lineBreak) {
        // Runs of characters that stay as they are go in at once (a copy escapes millions of cells)
        int plainStart = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean lineEnd = c == '\n' || c == '\r';
            if (c != '&' && c != '<' && c != '>' && c != '"' && !(lineBreak != null && lineEnd) && (c >= 0x20 || c == '\t' || lineEnd)) {
                continue;
            }
            out.append(text, plainStart, i);
            plainStart = i + 1;
            switch (c) {
                case '&' -> out.append("&amp;"); //$NON-NLS-1$
                case '<' -> out.append("&lt;"); //$NON-NLS-1$
                case '>' -> out.append("&gt;"); //$NON-NLS-1$
                case '"' -> out.append("&quot;"); //$NON-NLS-1$
                default -> {
                    if (lineBreak != null && lineEnd) {
                        out.append(lineBreak);
                        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                            i++;
                            plainStart = i + 1;
                        }
                    }
                    // Otherwise a character XML 1.0 can't hold: left out
                }
            }
        }
        // A text with nothing to escape (most cells) goes in whole, which copies faster than a range
        return plainStart == 0 ? out.append(text) : out.append(text, plainStart, text.length());
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
