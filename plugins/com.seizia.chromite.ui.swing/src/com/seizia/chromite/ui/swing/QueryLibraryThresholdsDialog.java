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
import com.seizia.chromite.ui.swing.internal.AiMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Edits the {@code @threshold} entries of a Query Library query (Thresholds… in the query's header), which the AI agent's
 * monitoring checks use to judge the result (docs/chromite/ai-agent.md "Query Library 기준값").
 *
 * 설계:
 * <ul>
 *     <li>기준값은 Query Library 파서({@link QueryLibraryFile})가 모르는 머리말이라 {@link QueryLibraryItem#getExtraHeader()}에 그대로 보관된다.
 *     이 대화상자는 그 줄들에서 {@code @threshold} 블록만 읽고({@link #parse}), 고친 블록으로 바꾼다({@link #replace}). 다른 모르는 머리말 줄은
 *     건드리지 않는다. 파서·항목 클래스에 필드를 더하지 않은 이유: 형식의 주인은 에이전트 도구(모델 번들의 {@code LibraryReader})이고, 저장소가
 *     모델 번들로 옮겨질 때까지 Swing 파서가 이 형식을 따로 알 필요가 없다.</li>
 *     <li>조건은 {@code <연산자> <값>}만 받는다(평가기 {@code ThresholdEvaluator}와 같은 연산자). 틀린 조건은 저장하지 않고 알린다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 */
final class QueryLibraryThresholdsDialog extends JDialog {

    private static final String KEY_LINE = "-- @threshold "; //$NON-NLS-1$
    private static final String KEY_PREFIX = "-- @"; //$NON-NLS-1$
    private static final String SUB_PREFIX = "--   "; //$NON-NLS-1$
    private static final List<String> SCOPES = List.of("any", "all", "max", "min", "sum", "count"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    /**
     * The condition syntax of the evaluator ({@code ThresholdEvaluator.CONDITION}).
     */
    private static final Pattern CONDITION = Pattern.compile("^\\s*(>=|<=|!=|<>|=|>|<|not-contains|contains)\\s*(.+?)\\s*$"); //$NON-NLS-1$

    /**
     * One {@code @threshold} block.
     */
    static final class Threshold {
        String column = ""; //$NON-NLS-1$
        String warn = ""; //$NON-NLS-1$
        String critical = ""; //$NON-NLS-1$
        String scope = "any"; //$NON-NLS-1$
        String label = ""; //$NON-NLS-1$
    }

    private final List<Threshold> thresholds = new ArrayList<>();
    private final ThresholdModel model = new ThresholdModel();
    private final JTable table = new JTable(model);
    @Nullable
    private List<Threshold> result;

    private QueryLibraryThresholdsDialog(@NotNull Component owner, @NotNull String queryName, @NotNull List<Threshold> current) {
        super(SwingUtilities.getWindowAncestor(owner), NLS.bind(AiMessages.ai_thresholds_title, queryName), ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        for (Threshold t : current) {
            Threshold copy = new Threshold();
            copy.column = t.column;
            copy.warn = t.warn;
            copy.critical = t.critical;
            copy.scope = t.scope;
            copy.label = t.label;
            thresholds.add(copy);
        }
        table.setDefaultEditor(Object.class, new DefaultCellEditor(new JTextField()));
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(SCOPES.toArray(String[]::new))));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(4).setPreferredWidth(160);

        JLabel help = new JLabel("<html>" + CommonUtils.escapeHtml(AiMessages.ai_thresholds_help).replace("\n", "<br>") + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        help.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JButton addButton = new JButton(AiMessages.ai_thresholds_add);
        addButton.addActionListener(e -> {
            stopEditing();
            thresholds.add(new Threshold());
            model.fireTableDataChanged();
            int row = thresholds.size() - 1;
            table.setRowSelectionInterval(row, row);
            table.editCellAt(row, 0);
        });
        JButton removeButton = new JButton(AiMessages.ai_thresholds_remove);
        removeButton.addActionListener(e -> {
            stopEditing();
            int row = table.getSelectedRow();
            if (row >= 0) {
                thresholds.remove(row);
                model.fireTableDataChanged();
            }
        });
        JPanel edit = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        edit.add(addButton);
        edit.add(removeButton);

        JButton okButton = new JButton(AiMessages.ai_thresholds_ok);
        okButton.addActionListener(e -> ok());
        JButton cancelButton = new JButton(AiMessages.ai_thresholds_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(edit, BorderLayout.WEST);
        bottom.add(buttons, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(help, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(680, 200));
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the dialog; returns the edited thresholds, or null if canceled.
     */
    @Nullable
    static List<Threshold> open(@NotNull Component owner, @NotNull String queryName, @NotNull List<Threshold> current) {
        QueryLibraryThresholdsDialog dialog = new QueryLibraryThresholdsDialog(owner, queryName, current);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private void ok() {
        stopEditing();
        List<Threshold> valid = new ArrayList<>();
        for (Threshold t : thresholds) {
            t.column = t.column.trim();
            t.warn = t.warn.trim();
            t.critical = t.critical.trim();
            t.label = t.label.trim();
            if (t.column.isEmpty() && t.warn.isEmpty() && t.critical.isEmpty()) {
                continue;
            }
            String problem = t.column.isEmpty() || t.column.contains(" ") ? AiMessages.ai_thresholds_bad_column //$NON-NLS-1$
                : t.warn.isEmpty() && t.critical.isEmpty() ? AiMessages.ai_thresholds_no_condition
                : !isCondition(t.warn) || !isCondition(t.critical) ? AiMessages.ai_thresholds_bad_condition : null;
            if (problem != null) {
                JOptionPane.showMessageDialog(this, NLS.bind(problem, t.column), getTitle(), JOptionPane.WARNING_MESSAGE);
                return;
            }
            valid.add(t);
        }
        result = valid;
        dispose();
    }

    private static boolean isCondition(@NotNull String condition) {
        return condition.isEmpty() || CONDITION.matcher(condition).matches();
    }

    //////////////////////////////////////////////////////////
    // Header lines

    /**
     * The {@code @threshold} blocks among the header lines a query keeps unchanged.
     */
    @NotNull
    static List<Threshold> parse(@NotNull List<String> headerLines) {
        List<Threshold> result = new ArrayList<>();
        Threshold current = null;
        for (String line : headerLines) {
            if (line.startsWith(KEY_LINE)) {
                current = new Threshold();
                current.column = line.substring(KEY_LINE.length()).trim();
                result.add(current);
            } else if (line.startsWith(KEY_PREFIX)) {
                current = null;
            } else if (current != null && line.startsWith(SUB_PREFIX)) {
                String entry = line.substring(SUB_PREFIX.length());
                int colon = entry.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String value = entry.substring(colon + 1).trim();
                switch (entry.substring(0, colon).trim().toLowerCase(Locale.ROOT)) {
                    case "warn" -> current.warn = value; //$NON-NLS-1$
                    case "critical" -> current.critical = value; //$NON-NLS-1$
                    case "scope" -> current.scope = value.toLowerCase(Locale.ROOT); //$NON-NLS-1$
                    case "label" -> current.label = value; //$NON-NLS-1$
                    default -> {
                        // Unknown sub-key of a newer version: dropped with the block on replace (the block is rewritten)
                    }
                }
            }
        }
        return result;
    }

    /**
     * The header lines with the {@code @threshold} blocks replaced (other lines unchanged, in their order).
     */
    @NotNull
    static List<String> replace(@NotNull List<String> headerLines, @NotNull List<Threshold> thresholds) {
        List<String> result = new ArrayList<>();
        boolean inThreshold = false;
        for (String line : headerLines) {
            if (line.startsWith(KEY_LINE)) {
                inThreshold = true;
            } else if (line.startsWith(KEY_PREFIX)) {
                inThreshold = false;
            } else if (inThreshold && line.startsWith(SUB_PREFIX)) {
                continue;
            }
            if (!inThreshold) {
                result.add(line);
            }
        }
        for (Threshold t : thresholds) {
            result.add(KEY_LINE + t.column);
            addSub(result, "warn", t.warn); //$NON-NLS-1$
            addSub(result, "critical", t.critical); //$NON-NLS-1$
            if (!t.scope.isEmpty() && !t.scope.equals("any")) { //$NON-NLS-1$
                addSub(result, "scope", t.scope); //$NON-NLS-1$
            }
            addSub(result, "label", t.label); //$NON-NLS-1$
        }
        return result;
    }

    private static void addSub(@NotNull List<String> lines, @NotNull String key, @NotNull String value) {
        if (!value.isEmpty()) {
            // One line per value: a line break in a label would end the header
            lines.add(SUB_PREFIX + key + ": " + value.replace('\n', ' ').replace('\r', ' ')); //$NON-NLS-1$
        }
    }

    private final class ThresholdModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return thresholds.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> AiMessages.ai_thresholds_column;
                case 1 -> AiMessages.ai_thresholds_warn;
                case 2 -> AiMessages.ai_thresholds_critical;
                case 3 -> AiMessages.ai_thresholds_scope;
                default -> AiMessages.ai_thresholds_label;
            };
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Threshold t = thresholds.get(row);
            return switch (column) {
                case 0 -> t.column;
                case 1 -> t.warn;
                case 2 -> t.critical;
                case 3 -> t.scope;
                default -> t.label;
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            Threshold t = thresholds.get(row);
            String text = value == null ? "" : value.toString(); //$NON-NLS-1$
            switch (column) {
                case 0 -> t.column = text;
                case 1 -> t.warn = text;
                case 2 -> t.critical = text;
                case 3 -> t.scope = text;
                default -> t.label = text;
            }
        }
    }
}
