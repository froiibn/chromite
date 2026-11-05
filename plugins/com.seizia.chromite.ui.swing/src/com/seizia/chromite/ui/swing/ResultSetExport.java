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
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBInterruptedException;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 결과 표 하나의 복사(머리글 포함/미포함)와 CSV 내보내기.
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
 *     <li>XLSX 내보내기는 아직 없다. 모델의 XLSX 내보내기({@code org.jkiss.dbeaver.data.office})와 Apache POI 번들이 Swing 제품 구성에
 *     들어 있지 않다(docs/chromite/swing-frontend.md "구현 현황 — E단계").</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 E단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 */
final class ResultSetExport {

    private static final String CSV_LINE_END = "\r\n"; //$NON-NLS-1$
    private static final int PROGRESS_STEP = 1000;

    private static final String[] DELIMITER_LABELS = {"Comma (,)", "Semicolon (;)", "Tab"};
    private static final char[] DELIMITERS = {',', ';', '\t'};

    // Remembered for the session, like the file dialogs of the editor
    @Nullable
    private static Path lastDirectory;
    private static boolean lastIncludeHeaders = true;
    private static int lastDelimiter;
    private static boolean lastBom = true;
    private static boolean lastEscapeFormulas = true;

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
        JCheckBox headersCheck = new JCheckBox("Include column headers", lastIncludeHeaders);
        JComboBox<String> delimiterCombo = new JComboBox<>(DELIMITER_LABELS);
        delimiterCombo.setSelectedIndex(lastDelimiter);
        JCheckBox bomCheck = new JCheckBox("UTF-8 BOM (for Excel)", lastBom);
        JCheckBox formulaCheck = new JCheckBox("Escape formulas (= + - @)", lastEscapeFormulas);
        int rowCount = data.rows().size();
        String rowsText = rowCount + (rowCount == 1 ? " row" : " rows");
        JLabel noteLabel = new JLabel("<html><body style='width: 200px'>Exports the " + rowsText + " already fetched in this grid. The query is not run again."
            + (data.truncated() ? "<br><b>Fetching stopped at the maximum row count</b>; rows after it are not exported." : "")
            + "</html>");

        JPanel options = new JPanel(new GridBagLayout());
        options.setBorder(BorderFactory.createTitledBorder("CSV options"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(2, 6, 2, 6);
        options.add(headersCheck, constraints);
        JPanel delimiterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        delimiterPanel.add(new JLabel("Delimiter: "));
        delimiterPanel.add(delimiterCombo);
        options.add(delimiterPanel, constraints);
        options.add(bomCheck, constraints);
        options.add(formulaCheck, constraints);
        options.add(noteLabel, constraints);

        JFileChooser chooser = new JFileChooser(lastDirectory != null ? lastDirectory.toFile() : null);
        chooser.setDialogTitle("Export to CSV");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv")); //$NON-NLS-1$
        chooser.setSelectedFile(new java.io.File("Results.csv")); //$NON-NLS-1$
        chooser.setAccessory(options);
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        if (!file.getFileName().toString().contains(".")) {
            file = file.resolveSibling(file.getFileName() + ".csv"); //$NON-NLS-1$
        }
        if (Files.exists(file) && JOptionPane.showConfirmDialog(parent, file.getFileName() + " already exists.\nDo you want to replace it?",
            "Export to CSV", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        lastDirectory = file.toAbsolutePath().getParent();
        lastIncludeHeaders = headersCheck.isSelected();
        lastDelimiter = delimiterCombo.getSelectedIndex();
        lastBom = bomCheck.isSelected();
        lastEscapeFormulas = formulaCheck.isSelected();

        CsvOptions csvOptions = new CsvOptions(lastIncludeHeaders, DELIMITERS[lastDelimiter], lastBom, lastEscapeFormulas);
        Path target = file;
        try {
            ProgressDialog.runTask("Export to CSV", monitor -> {
                writeCsv(monitor, target, data, csvOptions);
                return null;
            });
        } catch (DBInterruptedException e) {
            // Canceled: the partial file was removed
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError("Export to CSV", "Can't export to " + target, e);
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
        monitor.beginTask("Export " + rows.size() + " rows to " + file.getFileName(), rows.size());
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
            throw new DBException("Error writing " + file, e);
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
