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

import com.formdev.flatlaf.FlatClientProperties;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.exec.plan.DBCPlanSourceFormat;
import com.seizia.chromite.model.impl.local.CachedResultSet;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.PlanMessages;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The raw view of the plan tab: the plan exactly as the server returned it, read-only, with Copy and Save As.
 *
 * 설계:
 * <ul>
 *     <li>글자는 계획 모델이 남긴 원문({@link DBCPlan#getPlanSourceData})을 바꾸지 않고 보인다: SQL Server showplan XML,
 *     PostgreSQL {@code EXPLAIN (FORMAT XML)} 결과(DBeaver 계획기가 XML 형식으로 요청한다), MySQL/MariaDB {@code EXPLAIN FORMAT=JSON},
 *     Oracle {@code DBMS_XPLAN.DISPLAY} 글자. 원문이 결과 집합(MySQL 예전 EXPLAIN, DBMS_XPLAN을 읽지 못한 Oracle)이면 열을 맞춘 표 글자로 보인다.
 *     서식을 다시 맞추지 않으므로 SQL Server XML은 한 줄이다. 아주 긴 줄이 있으면 화면에서만 줄을 접는다(복사·저장은 원문 그대로).</li>
 *     <li>구문 색은 RSyntaxTextArea가 아는 XML·JSON만 쓴다. 테마·글꼴은 값 패널과 같이 SQL 편집기 테마 파일과 고정폭 글꼴이다.
 *     SQL 테마는 XML 토큰을 모두 글자색으로 두므로 태그 이름·구분자·속성 값에 같은 테마의 키워드·연산자·문자열 색을 옮겨 쓴다.</li>
 *     <li>계획마다 원문이 따로라서(실제 계획은 문장마다 XML 하나) 여럿이면 고르는 목록({@link #getSelector})을 계획 탭 도구 막대에 보인다.</li>
 *     <li>저장: 형식에 맞는 확장자 하나로 저장한다. SQL Server showplan은 계획 파일 확장자 {@code .sqlplan}이다(다른 도구에서 열 수 있게).</li>
 * </ul>
 * EDT에서만 쓴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sql-editor-plus.md "실행 계획 세 보기")
 * - 2026-11-08 파일 덮어쓰기 확인은 바꾸기 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 */
final class PlanRawView {

    private static final Log log = Log.getLog(PlanRawView.class);

    /**
     * Lines longer than this are wrapped on screen (a SQL Server showplan is a single line).
     */
    private static final int WRAP_LINE_LENGTH = 2000;
    private static final int MAX_COLUMN_WIDTH = 80;
    private static final String SHOWPLAN_ROOT = "<ShowPlanXML"; //$NON-NLS-1$

    @Nullable
    private static File lastDirectory;

    /**
     * The raw plan of one entry.
     *
     * @param text the plan text, or null when the plan has none
     */
    private record Source(@NotNull String label, @Nullable String text, @NotNull DBCPlanSourceFormat format) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final JPanel component = new JPanel(new BorderLayout());
    private final PlanTextArea textArea = new PlanTextArea();
    private final JComboBox<Source> selector = new JComboBox<>();
    private final JLabel messageLabel = new JLabel("", SwingConstants.CENTER); //$NON-NLS-1$
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);

    PlanRawView() {
        textArea.setEditable(false);
        textArea.setCodeFoldingEnabled(false);
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        // The gutter takes its colors from the theme too, so the theme is applied once the scroll pane exists
        textArea.applyTheme();
        messageLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        center.add(scrollPane, "text"); //$NON-NLS-1$
        center.add(messageLabel, "message"); //$NON-NLS-1$
        component.add(center, BorderLayout.CENTER);
        selector.setFocusable(false);
        selector.addActionListener(e -> showSelected());
        selector.setVisible(false);
    }

    @NotNull
    JComponent getComponent() {
        return component;
    }

    /**
     * Statement picker, shown when the tab has several plans.
     */
    @NotNull
    JComboBox<?> getSelector() {
        return selector;
    }

    void setEntries(@NotNull List<ExecutionPlanView.Entry> entries) {
        Source previous = (Source) selector.getSelectedItem();
        List<Source> sources = new ArrayList<>();
        int number = 1;
        for (ExecutionPlanView.Entry entry : entries) {
            int statements = entry.plan() == null ? 1 : Math.max(1, ExecutionPlanView.getRootNodes(entry.plan()).size());
            String label = statements == 1 ? NLS.bind(PlanMessages.plan_raw_query, number)
                : NLS.bind(PlanMessages.plan_raw_queries, number, number + statements - 1);
            number += statements;
            sources.add(createSource(label, entry.plan()));
        }
        selector.setModel(new DefaultComboBoxModel<>(sources.toArray(new Source[0])));
        selector.setVisible(sources.size() > 1);
        // Keep showing the same statement when more plans arrive
        int index = previous == null ? 0 : Math.min(sources.size() - 1, indexOf(sources, previous.label()));
        if (!sources.isEmpty()) {
            selector.setSelectedIndex(Math.max(0, index));
        }
        showSelected();
    }

    private static int indexOf(@NotNull List<Source> sources, @NotNull String label) {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).label().equals(label)) {
                return i;
            }
        }
        return 0;
    }

    @NotNull
    private static Source createSource(@NotNull String label, @Nullable DBCPlan plan) {
        if (plan == null) {
            return new Source(label, null, DBCPlanSourceFormat.NONE);
        }
        Object data;
        DBCPlanSourceFormat format;
        try {
            data = plan.getPlanSourceData();
            format = plan.getPlanSourceDataFormat();
        } catch (Exception e) {
            log.debug("Can't read the plan source", e);
            return new Source(label, null, DBCPlanSourceFormat.NONE);
        }
        if (data instanceof CachedResultSet resultSet) {
            return new Source(label, formatResultSet(resultSet), DBCPlanSourceFormat.TEXT);
        }
        String text = data == null ? null : CommonUtils.nullIfEmpty(data.toString());
        return new Source(label, text, text == null ? DBCPlanSourceFormat.NONE : format);
    }

    /**
     * A result set source (plan table rows) as a text table with aligned columns.
     */
    @NotNull
    private static String formatResultSet(@NotNull CachedResultSet resultSet) {
        String[] names = resultSet.getColumnNames();
        List<String[]> rows = new ArrayList<>();
        int[] widths = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            widths[i] = Math.min(MAX_COLUMN_WIDTH, names[i].length());
        }
        for (Object[] row : resultSet.getRows()) {
            String[] cells = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                Object value = i < row.length ? row[i] : null;
                cells[i] = CommonUtils.truncateString(value == null ? "" : value.toString().replace('\n', ' '), MAX_COLUMN_WIDTH); //$NON-NLS-1$
                widths[i] = Math.max(widths[i], cells[i].length());
            }
            rows.add(cells);
        }
        StringBuilder text = new StringBuilder();
        appendRow(text, names, widths);
        for (int i = 0; i < names.length; i++) {
            text.append("-".repeat(widths[i])).append(i < names.length - 1 ? "  " : "\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        for (String[] row : rows) {
            appendRow(text, row, widths);
        }
        return text.toString();
    }

    private static void appendRow(@NotNull StringBuilder text, @NotNull String[] cells, int[] widths) {
        for (int i = 0; i < cells.length; i++) {
            String cell = CommonUtils.truncateString(cells[i], widths[i]);
            text.append(cell);
            if (i < cells.length - 1) {
                text.append(" ".repeat(widths[i] - cell.length() + 2)); //$NON-NLS-1$
            }
        }
        text.append('\n');
    }

    private void showSelected() {
        Source source = (Source) selector.getSelectedItem();
        if (source == null || source.text() == null) {
            textArea.setText(""); //$NON-NLS-1$
            messageLabel.setText(source == null ? "" : PlanMessages.plan_raw_none); //$NON-NLS-1$
            cards.show(center, "message"); //$NON-NLS-1$
            return;
        }
        textArea.setSyntaxEditingStyle(switch (source.format()) {
            case XML -> SyntaxConstants.SYNTAX_STYLE_XML;
            case JSON -> SyntaxConstants.SYNTAX_STYLE_JSON;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        });
        textArea.setLineWrap(source.text().lines().anyMatch(line -> line.length() > WRAP_LINE_LENGTH));
        textArea.setText(source.text());
        textArea.setCaretPosition(0);
        cards.show(center, "text"); //$NON-NLS-1$
    }

    boolean hasText() {
        Source source = (Source) selector.getSelectedItem();
        return source != null && source.text() != null;
    }

    void copy() {
        Source source = (Source) selector.getSelectedItem();
        if (source != null && source.text() != null) {
            StringSelection selection = new StringSelection(source.text());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        }
    }

    void saveAs() {
        Source source = (Source) selector.getSelectedItem();
        if (source == null || source.text() == null) {
            return;
        }
        String filterName;
        String extension;
        if (source.format() == DBCPlanSourceFormat.XML && source.text().stripLeading().startsWith(SHOWPLAN_ROOT)) {
            filterName = PlanMessages.plan_filter_sqlplan;
            extension = "sqlplan"; //$NON-NLS-1$
        } else if (source.format() == DBCPlanSourceFormat.XML) {
            filterName = PlanMessages.plan_filter_xml;
            extension = "xml"; //$NON-NLS-1$
        } else if (source.format() == DBCPlanSourceFormat.JSON) {
            filterName = PlanMessages.plan_filter_json;
            extension = "json"; //$NON-NLS-1$
        } else {
            filterName = PlanMessages.plan_filter_text;
            extension = "txt"; //$NON-NLS-1$
        }
        JFileChooser chooser = new JFileChooser(lastDirectory);
        chooser.setDialogTitle(PlanMessages.plan_raw_save_tip);
        chooser.setFileFilter(new FileNameExtensionFilter(filterName, extension));
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setSelectedFile(new File("execution-plan." + extension)); //$NON-NLS-1$
        if (chooser.showSaveDialog(SwingUtilities.getWindowAncestor(component)) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + extension)) { //$NON-NLS-1$
            file = file.resolveSibling(file.getFileName() + "." + extension); //$NON-NLS-1$
        }
        lastDirectory = file.getParent() != null ? file.getParent().toFile() : null;
        if (Files.exists(file) && !SwingPlatformUI.confirmChange(component,
            NLS.bind(CoreMessages.main_file_exists, file.getFileName()), PlanMessages.plan_raw_save_tip,
            CoreMessages.common_replace, JOptionPane.WARNING_MESSAGE)) {
            return;
        }
        try {
            Files.writeString(file, source.text(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(PlanMessages.plan_raw_save_tip, PlanMessages.plan_raw_save_failed, e);
        }
    }

    /**
     * Read-only text area themed like the SQL editor (the theme is applied again after a look and feel switch).
     */
    private static final class PlanTextArea extends RSyntaxTextArea {
        @Override
        public void updateUI() {
            super.updateUI();
            if (getParent() != null) {
                SwingUtilities.invokeLater(this::applyTheme);
            }
        }

        void applyTheme() {
            String resource = ChromiteTheme.getCurrent() == ChromiteTheme.DARK ? "SqlEditorDark.xml" : "SqlEditorLight.xml"; //$NON-NLS-1$ //$NON-NLS-2$
            try (InputStream in = SqlEditorPanel.class.getResourceAsStream(resource)) {
                if (in != null) {
                    Theme.load(in).apply(this);
                }
            } catch (IOException e) {
                log.debug("Can't load the raw plan theme " + resource, e);
            }
            Font codeFont = UIManager.getFont("monospaced.font"); //$NON-NLS-1$
            if (codeFont != null) {
                setFont(codeFont);
            }
            // The SQL theme leaves XML plain (all markup tokens in the text color): color tags and values with the SQL
            // keyword, operator and string colors of the same theme, so both themes stay readable
            SyntaxScheme scheme = getSyntaxScheme();
            copyStyle(scheme, TokenTypes.RESERVED_WORD, TokenTypes.MARKUP_TAG_NAME);
            copyStyle(scheme, TokenTypes.OPERATOR, TokenTypes.MARKUP_TAG_DELIMITER);
            copyStyle(scheme, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE);
            setSyntaxScheme(scheme);
            setHighlightCurrentLine(false);
        }

        private static void copyStyle(@NotNull SyntaxScheme scheme, int from, int to) {
            Style style = scheme.getStyle(from);
            if (style != null) {
                scheme.setStyle(to, (Style) style.clone());
            }
        }
    }
}
