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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.data.office.export.DataExporterXLSX;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDContentStorage;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.transfer.stream.IStreamDataExporterSite;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 결과 표 하나의 복사(머리글 포함/미포함)와 CSV·XLSX 내보내기.
 *
 * 설계:
 * <ul>
 *     <li>결과 표가 이미 읽어 둔 행을 내보낸다. 쿼리를 다시 실행하지 않으므로 최대 행 수에서 멈춘 결과는 그만큼만 나가고,
 *     대화상자에 그 사실을 적는다.</li>
 *     <li>값은 표에 보이는 표시 문자열이다. 결과 표는 C단계 설계대로 작업 스레드에서 값을 표시 문자열로 바꾸고 원래 값을 놓아 주므로
 *     ({@link ResultSetGridReceiver}), 원래 값과 열 바인딩, 열린 세션(LOB 읽기)이 필요한 data.transfer의 {@code DataExporterCSV}는
 *     이 데이터로 쓸 수 없다. 그래서 CSV는 RFC 4180 규칙(구분자·큰따옴표·줄바꿈이 있는 값만 큰따옴표로 감싸고 큰따옴표는 두 번,
 *     줄 끝 CRLF)으로 여기서 쓴다. NULL은 빈 칸이다({@code DataExporterCSV}의 기본 NULL 문자열과 같음).</li>
 *     <li>수식 이스케이프: {@code DataExporterCSV}의 기본 동작처럼 {@code = + - @}, 탭, 줄바꿈으로 시작하는 글자 값 앞에 {@code '}를 붙여
 *     스프레드시트가 수식으로 실행하지 않게 한다(CSV injection). 숫자 열은 건드리지 않는다. 대화상자에서 끌 수 있다.</li>
 *     <li>파일 쓰기는 진행 대화상자({@link ProgressDialog}, 취소 가능)의 작업 스레드에서 한다. 취소하거나 실패하면 쓰다 만 파일을 지운다.</li>
 *     <li>XLSX는 모델의 내보내기 엔진 {@link DataExporterXLSX}({@code com.seizia.chromite.data.office}, Apache POI)로 쓴다(SWT Office 확장의 "Open in Excel"과 같은 기본 속성).
 *     숫자·불리언·날짜 칸은 결과 표가 표시 문자열과 함께 남긴 원래 값({@link ResultSetGrid.Data#values()})을 넘겨 숫자·불리언·날짜 셀이 되고,
 *     나머지 값은 표에 보이는 표시 문자열을 그대로 쓴다({@link GridXlsxExporter}). 그래서 세션·결과 집합 없이 쓸 수 있다(LOB 값이 엔진에 가지 않음).
 *     날짜 셀 형식은 {@value #XLSX_DATE_FORMAT}이다(엔진 기본 속성의 빈 형식 대신. 엑셀 셀 형식과 엔진이 1900년 이전 날짜에 쓰는
 *     {@code SimpleDateFormat}에서 같은 뜻이다). NULL은 빈 칸이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 E단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 * - 2026-11-06 F단계: XLSX 내보내기 (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 파일 덮어쓰기 확인은 바꾸기 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 */
final class ResultSetExport {

    private static final String CSV_LINE_END = "\r\n"; //$NON-NLS-1$
    private static final int PROGRESS_STEP = 1000;

    /**
     * Property ids of the XLSX processor ({@code com.seizia.chromite.data.office} plugin.xml, {@code DataExporterXLSX.PROP_*}).
     */
    private static final String XLSX_PROP_HEADER = "header"; //$NON-NLS-1$
    private static final String XLSX_PROP_DATE_FORMAT = "dateFormat"; //$NON-NLS-1$
    private static final String XLSX_HEADER_LABEL = "label"; //$NON-NLS-1$
    private static final String XLSX_HEADER_NONE = "none"; //$NON-NLS-1$
    static final String XLSX_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"; //$NON-NLS-1$

    private static final String[] DELIMITER_LABELS = {EditorMessages.editor_export_delimiter_comma, EditorMessages.editor_export_delimiter_semicolon,
        EditorMessages.editor_export_delimiter_tab};
    private static final char[] DELIMITERS = {',', ';', '\t'};

    // Remembered for the session, like the file dialogs of the editor
    @Nullable
    private static Path lastDirectory;
    private static boolean lastIncludeHeaders = true;
    private static int lastDelimiter;
    private static boolean lastBom = true;
    private static boolean lastEscapeFormulas = true;
    private static boolean lastXlsxHeaders = true;

    private ResultSetExport() {
    }

    /**
     * Tab-separated text of the selected cells (rows and columns of the selection) in the format of the grid's
     * Ctrl+C (lines separated by {@code \n}, no line break at the end), optionally with the column names as the first line.
     */
    @NotNull
    static String copySelection(@NotNull JTable table, @NotNull ResultSetGrid.Data data, boolean withHeaders) {
        int[] rows = table.getSelectedRows();
        int[] columns = table.getSelectedColumns();
        StringBuilder text = new StringBuilder();
        if (withHeaders) {
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    text.append('\t');
                }
                text.append(data.columns()[table.convertColumnIndexToModel(columns[i])]);
            }
        }
        boolean firstLine = !withHeaders;
        for (int row : rows) {
            if (!firstLine) {
                text.append('\n');
            }
            firstLine = false;
            Object[] values = data.rows().get(table.convertRowIndexToModel(row));
            for (int i = 0; i < columns.length; i++) {
                if (i > 0) {
                    text.append('\t');
                }
                text.append(values[table.convertColumnIndexToModel(columns[i])]);
            }
        }
        return text.toString();
    }

    /**
     * Asks for the file and options, then writes the fetched rows as CSV.
     */
    static void exportCsv(@NotNull Component parent, @NotNull ResultSetGrid.Data data) {
        JCheckBox headersCheck = new JCheckBox(EditorMessages.editor_export_include_headers, lastIncludeHeaders);
        JComboBox<String> delimiterCombo = new JComboBox<>(DELIMITER_LABELS);
        delimiterCombo.setSelectedIndex(lastDelimiter);
        JCheckBox bomCheck = new JCheckBox(EditorMessages.editor_export_bom, lastBom);
        JCheckBox formulaCheck = new JCheckBox(EditorMessages.editor_export_escape_formulas, lastEscapeFormulas);
        JPanel options = new JPanel(new GridBagLayout());
        options.setBorder(BorderFactory.createTitledBorder(EditorMessages.editor_export_csv_options));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(2, 6, 2, 6);
        options.add(headersCheck, constraints);
        JPanel delimiterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        delimiterPanel.add(new JLabel(EditorMessages.editor_export_delimiter));
        delimiterPanel.add(delimiterCombo);
        options.add(delimiterPanel, constraints);
        options.add(bomCheck, constraints);
        options.add(formulaCheck, constraints);
        options.add(createFetchedRowsNote(data), constraints);

        Path file = chooseFile(parent, EditorMessages.editor_export_csv_title, options,
            new FileNameExtensionFilter(EditorMessages.editor_export_csv_filter, "csv"), "Results.csv", "csv"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (file == null) {
            return;
        }
        lastIncludeHeaders = headersCheck.isSelected();
        lastDelimiter = delimiterCombo.getSelectedIndex();
        lastBom = bomCheck.isSelected();
        lastEscapeFormulas = formulaCheck.isSelected();

        CsvOptions csvOptions = new CsvOptions(lastIncludeHeaders, DELIMITERS[lastDelimiter], lastBom, lastEscapeFormulas);
        Path target = file;
        try {
            ProgressDialog.runTask(EditorMessages.editor_export_csv_title, monitor -> {
                writeCsv(monitor, target, data, csvOptions);
                return null;
            });
        } catch (DBInterruptedException e) {
            // Canceled: the partial file was removed
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(EditorMessages.editor_export_csv_title, NLS.bind(EditorMessages.editor_export_cant_export, target), e);
        }
    }

    /**
     * Note under the export options: the grid's fetched rows are exported, the query is not run again.
     */
    @NotNull
    private static JLabel createFetchedRowsNote(@NotNull ResultSetGrid.Data data) {
        int rowCount = data.rows().size();
        String note = NLS.bind(rowCount == 1 ? EditorMessages.editor_export_note_one : EditorMessages.editor_export_note_many, rowCount);
        return new JLabel("<html><body style='width: 200px'>" + note //$NON-NLS-1$
            + (data.truncated() ? "<br>" + EditorMessages.editor_export_note_truncated : "") //$NON-NLS-1$ //$NON-NLS-2$
            + "</html>"); //$NON-NLS-1$
    }

    /**
     * Save dialog with the options as accessory; adds the extension when the name has none and confirms replacing
     * an existing file. Returns null when canceled. The folder is remembered for the session.
     */
    @Nullable
    private static Path chooseFile(
        @NotNull Component parent,
        @NotNull String title,
        @NotNull JComponent options,
        @NotNull FileNameExtensionFilter filter,
        @NotNull String defaultName,
        @NotNull String extension
    ) {
        JFileChooser chooser = new JFileChooser(lastDirectory != null ? lastDirectory.toFile() : null);
        chooser.setDialogTitle(title);
        chooser.setFileFilter(filter);
        chooser.setSelectedFile(new java.io.File(defaultName));
        chooser.setAccessory(options);
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path file = chooser.getSelectedFile().toPath();
        if (!file.getFileName().toString().contains(".")) {
            file = file.resolveSibling(file.getFileName() + "." + extension);
        }
        if (Files.exists(file) && !SwingPlatformUI.confirmChange(parent, NLS.bind(EditorMessages.editor_export_replace_confirm, file.getFileName()),
            title, CoreMessages.common_replace, JOptionPane.WARNING_MESSAGE)) {
            return null;
        }
        lastDirectory = file.toAbsolutePath().getParent();
        return file;
    }

    /**
     * Asks for the file and options, then writes the fetched rows as an Excel workbook.
     */
    static void exportXlsx(@NotNull Component parent, @NotNull ResultSetGrid.Data data) {
        JCheckBox headersCheck = new JCheckBox(EditorMessages.editor_export_include_headers, lastXlsxHeaders);
        JPanel options = new JPanel(new GridBagLayout());
        options.setBorder(BorderFactory.createTitledBorder(EditorMessages.editor_export_excel_options));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(2, 6, 2, 6);
        options.add(headersCheck, constraints);
        options.add(createFetchedRowsNote(data), constraints);

        Path file = chooseFile(parent, EditorMessages.editor_export_xlsx_title, options,
            new FileNameExtensionFilter(EditorMessages.editor_export_xlsx_filter, "xlsx"), "Results.xlsx", "xlsx"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (file == null) {
            return;
        }
        lastXlsxHeaders = headersCheck.isSelected();
        boolean headers = lastXlsxHeaders;
        try {
            ProgressDialog.runTask(EditorMessages.editor_export_xlsx_title, monitor -> {
                writeXlsx(monitor, file, data, headers);
                return null;
            });
        } catch (DBInterruptedException e) {
            // Canceled: the partial file was removed
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(EditorMessages.editor_export_xlsx_title, NLS.bind(EditorMessages.editor_export_cant_export, file), e);
        }
    }

    private static void writeXlsx(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Path file,
        @NotNull ResultSetGrid.Data data,
        boolean headers
    ) throws DBException {
        List<Object[]> rows = data.rows();
        monitor.beginTask(NLS.bind(EditorMessages.editor_export_task, rows.size(), file.getFileName()), rows.size());
        Map<String, Object> properties = DataExporterXLSX.getDefaultProperties();
        properties.put(XLSX_PROP_HEADER, headers ? XLSX_HEADER_LABEL : XLSX_HEADER_NONE);
        properties.put(XLSX_PROP_DATE_FORMAT, XLSX_DATE_FORMAT);
        boolean completed = false;
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            GridXlsxExporter exporter = new GridXlsxExporter();
            exporter.init(new XlsxSite(data.bindings(), properties, out, file));
            try {
                // No session: the header has no column descriptions and the values hold no LOB content
                exporter.exportHeader(null);
                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    exporter.exportRow(null, null, getExportValues(data, rowIndex));
                    if ((rowIndex + 1) % PROGRESS_STEP == 0) {
                        if (monitor.isCanceled()) {
                            throw new DBInterruptedException("Export canceled");
                        }
                        monitor.worked(PROGRESS_STEP);
                    }
                }
                exporter.exportFooter(monitor);
            } finally {
                // Writes the workbook to the stream and releases POI's temporary files
                exporter.dispose();
            }
            completed = true;
        } catch (IOException e) {
            throw new DBException(NLS.bind(EditorMessages.editor_export_error_writing, file), e);
        } finally {
            monitor.done();
            if (!completed) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    // Reported by the original error
                }
            }
        }
    }

    /**
     * Values of one row for the XLSX exporter: the kept original value (number, boolean, date) where there is one,
     * null for NULL, otherwise the display string of the grid.
     */
    @NotNull
    private static Object[] getExportValues(@NotNull ResultSetGrid.Data data, int rowIndex) {
        Object[] displayRow = data.rows().get(rowIndex);
        Object[] kept = data.values().get(rowIndex);
        Object[] values = new Object[displayRow.length];
        for (int i = 0; i < displayRow.length; i++) {
            Object display = displayRow[i];
            if (kept != null && kept[i] != null) {
                values[i] = kept[i];
            } else if (display != null && display != ResultSetGrid.NULL_VALUE) {
                values[i] = display.toString();
            }
        }
        return values;
    }

    /**
     * Display strings are written as they are, without going through the column's value handler again.
     */
    private static final class GridXlsxExporter extends DataExporterXLSX {
        @Override
        protected String getValueDisplayString(DBDAttributeBinding column, Object value) {
            return value instanceof String text ? text : super.getValueDisplayString(column, value);
        }
    }

    /**
     * Exporter site of the grid: the result set's bindings, the export properties and the file stream.
     */
    private static final class XlsxSite implements IStreamDataExporterSite {
        private final DBDAttributeBinding[] bindings;
        private final Map<String, Object> properties;
        private final OutputStream out;
        private final Path file;

        XlsxSite(
            @NotNull DBDAttributeBinding[] bindings,
            @NotNull Map<String, Object> properties,
            @NotNull OutputStream out,
            @NotNull Path file
        ) {
            this.bindings = bindings;
            this.properties = properties;
            this.out = out;
            this.file = file;
        }

        @Override
        public DBPNamedObject getSource() {
            // Sheets keep the default names (useDefaultSpreadsheetNames); the source only needs a name
            return () -> "Results"; //$NON-NLS-1$
        }

        @Override
        public DBDDisplayFormat getExportFormat() {
            return DBDDisplayFormat.UI;
        }

        @Override
        public Map<String, Object> getProperties() {
            return properties;
        }

        @Override
        public DBDAttributeBinding[] getAttributes() {
            return bindings;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Nullable
        @Override
        public Path getOutputFile() {
            return file;
        }

        @Override
        public PrintWriter getWriter() {
            throw new UnsupportedOperationException("The XLSX exporter writes binary data"); //$NON-NLS-1$
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void writeBinaryData(@NotNull DBDContentStorage cs) {
            throw new UnsupportedOperationException("The grid holds no content values"); //$NON-NLS-1$
        }

        @NotNull
        @Override
        public String getOutputEncoding() {
            return StandardCharsets.UTF_8.name();
        }
    }

    private record CsvOptions(boolean headers, char delimiter, boolean bom, boolean escapeFormulas) {
    }

    private static void writeCsv(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Path file,
        @NotNull ResultSetGrid.Data data,
        @NotNull CsvOptions options
    ) throws DBException {
        List<Object[]> rows = data.rows();
        monitor.beginTask(NLS.bind(EditorMessages.editor_export_task, rows.size(), file.getFileName()), rows.size());
        boolean completed = false;
        try (OutputStream out = Files.newOutputStream(file);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            if (options.bom()) {
                writer.write('﻿');
            }
            if (options.headers()) {
                String[] columns = data.columns();
                for (int i = 0; i < columns.length; i++) {
                    if (i > 0) {
                        writer.write(options.delimiter());
                    }
                    writer.write(quote(escapeFormula(columns[i], options), options.delimiter()));
                }
                writer.write(CSV_LINE_END);
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Object[] row = rows.get(rowIndex);
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) {
                        writer.write(options.delimiter());
                    }
                    Object value = row[i];
                    if (value == null || value == ResultSetGrid.NULL_VALUE) {
                        continue;
                    }
                    String text = value.toString();
                    if (i >= data.numeric().length || !data.numeric()[i]) {
                        text = escapeFormula(text, options);
                    }
                    writer.write(quote(text, options.delimiter()));
                }
                writer.write(CSV_LINE_END);
                if ((rowIndex + 1) % PROGRESS_STEP == 0) {
                    if (monitor.isCanceled()) {
                        throw new DBInterruptedException("Export canceled");
                    }
                    monitor.worked(PROGRESS_STEP);
                }
            }
            completed = true;
        } catch (IOException e) {
            throw new DBException(NLS.bind(EditorMessages.editor_export_error_writing, file), e);
        } finally {
            monitor.done();
            if (!completed) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    // Reported by the original error
                }
            }
        }
    }

    /**
     * RFC 4180: a field with the delimiter, a double quote or a line break is enclosed in double quotes,
     * and its double quotes are doubled.
     */
    @NotNull
    static String quote(@NotNull String value, char delimiter) {
        boolean needsQuotes = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == delimiter || c == '"' || c == '\r' || c == '\n') {
                needsQuotes = true;
                break;
            }
        }
        return needsQuotes ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    /**
     * Same prefixes as {@code DataExporterCSV#sanitizeFormula} (see OWASP "CSV Injection").
     */
    @NotNull
    private static String escapeFormula(@NotNull String value, @NotNull CsvOptions options) {
        if (!options.escapeFormulas() || value.isEmpty()) {
            return value;
        }
        return switch (value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r', '\n', '＝', '＋', '－', '＠' -> "'" + value;
            default -> value;
        };
    }
}
