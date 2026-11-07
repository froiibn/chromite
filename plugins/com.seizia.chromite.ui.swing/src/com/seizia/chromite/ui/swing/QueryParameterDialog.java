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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.DBCStatementType;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실행할 때 매개변수 값을 묻는 대화상자. 쿼리 창의 모든 매개변수 입력이 이 대화상자 하나를 쓴다: Query Library 쿼리와 기록에서 다시 연
 * 바인딩 실행({@link #open}), 평범한 쿼리 창의 스크립트가 실행 중에 만난 매개변수({@link #openForScript}).
 *
 * 설계:
 * <ul>
 *     <li>매개변수마다 한 줄: 표시 이름(필수면 {@code *}), 형식에 맞는 입력(글자·숫자·날짜는 입력란, 참/거짓·선택 목록은 콤보,
 *     조회 쿼리는 편집할 수 있는 콤보), 형식 힌트. 값이 바인딩되지 않고 SQL에 그대로 들어가는 줄({@link Field#sqlText}: {@code ${name}} 변수,
 *     평범한 쿼리 창의 매개변수)은 "SQL text"로 표시한다. 이름의 도구 설명은 SQL에 쓴 표식({@code :name}, {@code ${name}}, {@code ?})이다.</li>
 *     <li>처음 값은 부르는 쪽이 정한다: 라이브러리 쿼리는 이 PC의 마지막 입력값({@link QueryLibraryState}), 없으면 정의의 기본값,
 *     평범한 쿼리 창은 DBeaver의 매개변수 기록({@code SQLQueryParameterRegistry}), 기록에서 연 실행은 기록된 값이다.</li>
 *     <li>조회 쿼리 목록은 대상 연결에서 작업 스레드로 읽는다(필요하면 연결한다). 읽는 동안에도 값을 입력할 수 있다.</li>
 *     <li>실행 단추를 누르면 형식·필수 검사({@link QueryLibraryParameter#validate})를 하고, 틀리면 대화상자 안에 오류를 보여 준다.</li>
 *     <li>스크립트 모드({@link #openForScript})는 엔진이 문장 하나에서 값을 물을 때라 그 문장을 위에 보이고, SWT와 같은 Ignore 단추
 *     (이 스크립트 끝까지 매개변수를 무시)를 둔다. 처음에는 쿼리 창에 별도 대화상자({@code SqlParameterDialog}, 이름·값 표)가 있었으나
 *     이 대화상자와 하나로 합쳤다: 입력 칸, 오류 표시, Enter = 실행이 모든 쿼리 창에서 같다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "매개변수")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-08 쿼리 창의 매개변수 대화상자({@code SqlParameterDialog})를 합침: 스크립트 모드(문장 미리 보기, Ignore), 줄마다 SQL 글자 여부와
 *   표식, 열 때 첫 입력 칸에 초점 (docs/chromite/swing-query-library.md "매개변수 대화상자와 바인딩 하나로")
 */
final class QueryParameterDialog extends JDialog {

    private static final Log log = Log.getLog(QueryParameterDialog.class);
    private static final int MAX_LOOKUP_VALUES = 1000;
    private static final int MAX_ROWS_WITHOUT_SCROLLING = 10;

    /**
     * One parameter to ask for.
     *
     * @param marker  how the parameter is written in the SQL ({@code :name}, {@code ${name}}, {@code ?}), the label's tool tip
     * @param sqlText the value goes into the SQL text as it is instead of being bound
     */
    record Field(@NotNull QueryLibraryParameter parameter, @NotNull String marker, boolean sqlText) {
    }

    enum Action {
        RUN, IGNORE, CANCEL
    }

    /**
     * @param values values by parameter name (empty unless {@link Action#RUN})
     */
    record Answer(@NotNull Action action, @NotNull Map<String, String> values) {
    }

    private record Row(@NotNull QueryLibraryParameter parameter, @NotNull JComponent input) {
        @NotNull
        String getValue() {
            if (input instanceof JComboBox<?> combo) {
                Object value = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
                return CommonUtils.toString(value).trim();
            }
            return ((JTextField) input).getText().trim();
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final JLabel errorLabel = new JLabel(" ");
    private Action action = Action.CANCEL;
    @Nullable
    private Map<String, String> result;

    /**
     * @param runText   text of the button that runs with the values
     * @param statement the statement the values are for, shown above the fields (script mode), or null
     * @param footer    a line below the fields, or null
     * @param ignorable whether there is an Ignore button (script mode)
     */
    private QueryParameterDialog(
        @NotNull Window owner,
        @NotNull String title,
        @NotNull String runText,
        @NotNull List<Field> fields,
        @NotNull Map<String, String> initialValues,
        @Nullable DBPDataSourceContainer container,
        @Nullable String statement,
        @Nullable String footer,
        boolean ignorable
    ) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel form = new JPanel(new GridBagLayout());
        int y = 0;
        for (Field field : fields) {
            QueryLibraryParameter parameter = field.parameter();
            String initial = initialValues.getOrDefault(parameter.getName(), CommonUtils.notEmpty(parameter.getDefaultValue()));
            JComponent input = createInput(parameter, initial);
            rows.add(new Row(parameter, input));

            JLabel label = new JLabel(parameter.getDisplayLabel() + (parameter.isRequired() ? " *" : ""));
            boolean textSubstitution = field.sqlText();
            label.setToolTipText(field.marker());
            label.setLabelFor(input);
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = y;
            c.gridx = 0;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(0, 0, 6, 8);
            form.add(label, c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            form.add(input, c);
            String hint = getHint(parameter, textSubstitution);
            if (hint != null) {
                JLabel hintLabel = new JLabel(hint);
                hintLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
                if (textSubstitution) {
                    hintLabel.setToolTipText(ToolsMessages.param_dialog_text_tip);
                }
                c.gridx = 2;
                c.fill = GridBagConstraints.NONE;
                c.weightx = 0;
                form.add(hintLabel, c);
            }
            if (parameter.getType() == QueryLibraryParameter.Type.LOOKUP && parameter.getLookupSql() != null && container != null
                && input instanceof JComboBox<?> combo) {
                @SuppressWarnings("unchecked")
                JComboBox<String> lookupCombo = (JComboBox<String>) combo;
                loadLookupValues(container, parameter.getLookupSql(), lookupCombo, initial);
            }
            y++;
        }
        errorLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$

        JButton runButton = new JButton(runText);
        runButton.addActionListener(e -> accept());
        JButton cancelButton = new JButton(ToolsMessages.button_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(runButton);
        if (ignorable) {
            JButton ignoreButton = new JButton(EditorMessages.editor_param_ignore);
            ignoreButton.setToolTipText(EditorMessages.editor_param_ignore_tip);
            ignoreButton.addActionListener(e -> {
                action = Action.IGNORE;
                dispose();
            });
            buttons.add(ignoreButton);
        }
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout(0, 10));
        south.add(errorLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(0, 6));
        if (fields.size() > MAX_ROWS_WITHOUT_SCROLLING) {
            JScrollPane scrollPane = new JScrollPane(form);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            scrollPane.setPreferredSize(new Dimension(form.getPreferredSize().width + 24, 420));
            center.add(scrollPane, BorderLayout.CENTER);
        } else {
            center.add(form, BorderLayout.CENTER);
        }
        if (footer != null) {
            JLabel footerLabel = new JLabel(footer);
            footerLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            center.add(footerLabel, BorderLayout.SOUTH);
        }

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        if (statement != null) {
            SqlEditorPanel.SqlTextArea statementText = new SqlEditorPanel.SqlTextArea();
            statementText.setText(statement);
            statementText.setEditable(false);
            statementText.setCaretPosition(0);
            JScrollPane statementScroll = new JScrollPane(statementText);
            statementText.applyTheme();
            statementScroll.setPreferredSize(new Dimension(480, 110));
            JPanel north = new JPanel(new BorderLayout());
            north.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
            north.add(statementScroll, BorderLayout.CENTER);
            content.add(north, BorderLayout.NORTH);
        }
        content.add(center, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(runButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setSize(Math.max(getWidth(), 460), getHeight());
        setLocationRelativeTo(owner);
        // The first value, not the statement above it (script mode)
        SwingUtilities.invokeLater(() -> {
            if (!rows.isEmpty()) {
                rows.getFirst().input().requestFocusInWindow();
            }
        });
    }

    /**
     * Asks for the values of the parameters of a query before it runs (Query Library, a bound run reopened from the
     * query history).
     *
     * @param kinds     parameter name -> true if bound, false if inserted as SQL text ({@link QueryParameterBinder#detect})
     * @param container connection for lookup queries, or null
     * @return values by parameter name, or null if canceled
     */
    @Nullable
    static Map<String, String> open(
        @NotNull Component parent,
        @NotNull String queryName,
        @NotNull List<QueryLibraryParameter> parameters,
        @NotNull Map<String, Boolean> kinds,
        @NotNull Map<String, String> initialValues,
        @Nullable DBPDataSourceContainer container
    ) {
        List<Field> fields = new ArrayList<>();
        for (QueryLibraryParameter parameter : parameters) {
            boolean sqlText = Boolean.FALSE.equals(kinds.get(parameter.getName()));
            fields.add(new Field(parameter, sqlText ? "${" + parameter.getName() + "}" : ":" + parameter.getName(), sqlText)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        Window owner = SwingUtilities.getWindowAncestor(parent);
        QueryParameterDialog dialog = new QueryParameterDialog(owner != null ? owner : JOptionPane.getRootFrame(),
            NLS.bind(ToolsMessages.param_dialog_title, queryName), ToolsMessages.library_action_run, fields, initialValues, container,
            null, null, false);
        dialog.setVisible(true);
        return dialog.result;
    }

    /**
     * Asks for the values of the parameters of one statement while a query window's script runs (the engine found
     * parameters without a value): the statement is shown, the values go into its text as SQL, and Ignore runs the rest
     * of the script without parameters.
     *
     * @param parent        the query window, or null
     * @param fields        the statement's parameters
     * @param initialValues values by parameter name
     */
    @NotNull
    static Answer openForScript(
        @Nullable Component parent,
        @NotNull String statement,
        @NotNull List<Field> fields,
        @NotNull Map<String, String> initialValues
    ) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        QueryParameterDialog dialog = new QueryParameterDialog(owner != null ? owner : JOptionPane.getRootFrame(),
            EditorMessages.editor_param_title, EditorMessages.editor_common_execute, fields, initialValues, null,
            statement, EditorMessages.editor_param_hint, true);
        dialog.setVisible(true);
        return new Answer(dialog.action, dialog.result != null ? dialog.result : Map.of());
    }

    @NotNull
    private static JComponent createInput(@NotNull QueryLibraryParameter parameter, @NotNull String initial) {
        switch (parameter.getType()) {
            case BOOLEAN -> {
                JComboBox<String> combo = new JComboBox<>(new String[]{"", "true", "false"}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                combo.setSelectedItem(initial.toLowerCase());
                return combo;
            }
            case CHOICE -> {
                JComboBox<String> combo = new JComboBox<>();
                if (!parameter.isRequired()) {
                    combo.addItem(""); //$NON-NLS-1$
                }
                parameter.getChoices().forEach(combo::addItem);
                if (!initial.isEmpty() && !parameter.getChoices().contains(initial)) {
                    combo.addItem(initial);
                }
                combo.setSelectedItem(initial);
                return combo;
            }
            case LOOKUP -> {
                JComboBox<String> combo = new JComboBox<>();
                combo.setEditable(true);
                combo.setSelectedItem(initial);
                return combo;
            }
            default -> {
                JTextField field = new JTextField(initial, 24);
                if (parameter.getType() == QueryLibraryParameter.Type.DATE) {
                    field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "yyyy-mm-dd"); //$NON-NLS-1$
                } else if (parameter.getType() == QueryLibraryParameter.Type.DATETIME) {
                    field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "yyyy-mm-dd hh:mm:ss"); //$NON-NLS-1$
                }
                return field;
            }
        }
    }

    @Nullable
    private static String getHint(@NotNull QueryLibraryParameter parameter, boolean textSubstitution) {
        if (textSubstitution) {
            return ToolsMessages.param_dialog_hint_text;
        }
        return switch (parameter.getType()) {
            case NUMBER -> ToolsMessages.param_dialog_hint_number;
            case DATE -> ToolsMessages.param_dialog_hint_date;
            case DATETIME -> ToolsMessages.param_dialog_hint_datetime;
            default -> null;
        };
    }

    private void accept() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Row row : rows) {
            String value = row.getValue();
            String error = row.parameter().validate(value);
            if (error != null) {
                errorLabel.setText(error);
                row.input().requestFocusInWindow();
                return;
            }
            values.put(row.parameter().getName(), value);
        }
        result = values;
        action = Action.RUN;
        dispose();
    }

    /**
     * Fills a lookup combo with the first column of the lookup query, read in a job (connects if needed).
     */
    private void loadLookupValues(
        @NotNull DBPDataSourceContainer container,
        @NotNull String lookupSql,
        @NotNull JComboBox<String> combo,
        @NotNull String initial
    ) {
        combo.setToolTipText(ToolsMessages.param_dialog_loading);
        new AbstractJob("Read parameter values") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<String> values = new ArrayList<>();
                String error = null;
                try {
                    if (!container.isConnected()) {
                        DBUtils.initDataSource(monitor, container, null);
                    }
                    DBPDataSource dataSource = container.getDataSource();
                    DBCExecutionContext context = dataSource == null ? null : DBUtils.getDefaultContext(dataSource.getDefaultInstance(), true);
                    if (context == null) {
                        error = ToolsMessages.library_not_connected;
                    } else {
                        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Read parameter values");
                             DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, lookupSql, false, false, false)) {
                            if (statement.executeStatement()) {
                                try (DBCResultSet resultSet = statement.openResultSet()) {
                                    while (resultSet != null && resultSet.nextRow() && values.size() < MAX_LOOKUP_VALUES) {
                                        values.add(CommonUtils.toString(resultSet.getAttributeValue(0)));
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    log.debug("Lookup query failed", e);
                    error = ErrorText.describe(e);
                }
                String finalError = error;
                SwingUtilities.invokeLater(() -> {
                    if (finalError != null) {
                        combo.setToolTipText(NLS.bind(ToolsMessages.param_dialog_error_values, finalError));
                        return;
                    }
                    Object typed = combo.getEditor().getItem();
                    combo.removeAllItems();
                    values.forEach(combo::addItem);
                    combo.setSelectedItem(typed != null && !CommonUtils.toString(typed).isEmpty() ? typed : initial);
                    combo.setToolTipText(NLS.bind(ToolsMessages.param_dialog_values, values.size()));
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }
}
