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
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.ui.swing.internal.GridFilterMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.sql.SQLQueryContainer;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.data.SQLQueryColumnValues;
import com.seizia.chromite.runtime.jobs.DataSourceJob;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 결과 그리드 열 하나의 필터 팝업: 값 목록(검색, 확인란, 행 수, NULL)과 조건(연산자와 값), 적용·열 필터 지우기·모든 필터 지우기.
 *
 * 설계:
 * <ul>
 *     <li>필터는 서버에서 건다. 팝업은 결과 뷰어의 {@link DBDDataFilter} 사본에서 이 열의 {@link DBDAttributeConstraint}만 바꿔
 *     {@link Host#applyFilter}로 넘기고, 뷰어가 예전 필터 줄처럼 쿼리를 다시 읽는다(SWT {@code ResultSetViewer.setDataFilter}).
 *     읽어 둔 행만 거르지 않는다.</li>
 *     <li>열마다 조건은 하나다(모델의 열 제약이 하나라서). 그래서 조건 콤보의 첫 항목 "선택한 값"이면 값 목록이, 다른 연산자면 값 칸이 필터가 되고,
 *     값 목록의 확인란을 누르면 콤보가 "선택한 값"으로 돌아간다. 고른 값은 {@code IN}(NULL을 고르면 모델이 {@code IS NULL OR … IN}을 만든다),
 *     아무것도 고르지 않으면 이 열의 필터가 없다. 목록이 다 읽혔고 모든 값을 골랐으면 걸 필요가 없으므로 필터를 두지 않는다.</li>
 *     <li>값은 모델이 방언으로 바꾼다: 연산자 조건(=, ≠, &gt;, ≥, &lt;, ≤, LIKE, IN)은 모델이 값 처리기와 방언 따옴표로 SQL 글자를 만들고
 *     ({@code SQLUtils.convertValueToSQL}), 모델 연산자로 나타낼 수 없는 조건(포함·시작·끝, 범위)은 같은 함수와 방언의
 *     {@code getQuotedString}만으로 조건 글자를 만든다. 사용자가 쓴 글자가 그대로 SQL에 들어가지 않는다.
 *     포함·시작·끝은 {@code %}, {@code _}, {@code [}(SQL Server)를 글자 그대로 찾도록 {@code ESCAPE '!'}로 이스케이프한다. 모델의 BETWEEN 연산자는
 *     두 자리에 같은 값을 넣으므로 범위는 조건 글자로 만든다.</li>
 *     <li>팝업에서 고른 연산자와 입력한 글자는 열 제약의 옵션({@link #OPTION_CONDITION})에 둔다. 조건 글자만으로는 포함·시작·끝을 다시
 *     알아낼 수 없기 때문이다. 옵션은 SQL에 쓰이지 않는다.</li>
 *     <li>값 목록은 서버에서 읽는다({@link SQLQueryColumnValues}: 지금 쿼리를 감싼 {@code GROUP BY}, 다른 열의 필터를 따름, 행이 많은 값 1,000개).
 *     더 있으면 "더 읽기"로 1,000개씩 늘린다. 문자열 열의 검색은 서버에서 다시 읽고(LIKE, 300ms 뒤), 다른 형식은 읽은 값의 표시 글자에서 찾는다.
 *     NULL은 처음 읽을 때 있었으면 맨 위에 늘 둔다. 읽기는 결과의 실행 문맥에서 {@link DataSourceJob}으로 하며 팝업을 닫으면 취소한다.</li>
 *     <li>LOB·이진·구조 형식과 이름이 겹치는 열은 묶을 수 없거나 바깥 쿼리에서 가리킬 수 없으므로 값 목록 대신 이유를 보이고 조건만 쓴다.</li>
 *     <li>창은 꾸밈 없는 모달리스 {@link JDialog}다. 팝업 메뉴 안의 콤보는 목록을 고를 때 메뉴가 닫히는 문제가 있어서다. 앱 안의 다른 곳을
 *     누르거나 Esc를 누르면 닫히고, 다른 앱으로 갔다 와도 남는다. 한 번에 하나만 연다.</li>
 * </ul>
 * EDT에서만 쓴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 결과 필터 줄을 열별 필터로 바꿈 (docs/chromite/swing-grid-column-filters.md)
 */
final class ResultSetColumnFilterPopup {

    private static final Log log = Log.getLog(ResultSetColumnFilterPopup.class);

    /**
     * Constraint option with the {@link Condition} the popup set.
     */
    static final String OPTION_CONDITION = "chromite.columnFilter"; //$NON-NLS-1$
    /**
     * Distinct values read at once; also the most values an IN list may have (Oracle allows 1,000 expressions).
     */
    static final int VALUE_LIMIT = 1000;

    private static final int SEARCH_DELAY = 300;
    private static final int POPUP_WIDTH = 320;
    private static final int VISIBLE_ROWS = 8;
    /**
     * A click on the funnel of the column whose popup just closed (by that click) does not open it again.
     */
    private static final long REOPEN_GUARD_MILLIS = 300;
    private static final char LIKE_ESCAPE = '!';
    private static final String NULL_TEXT = "NULL"; //$NON-NLS-1$

    /**
     * Filter operators of the popup.
     */
    enum Op {
        VALUES(0, null),
        EQUALS(1, DBCLogicalOperator.EQUALS),
        NOT_EQUALS(1, DBCLogicalOperator.NOT_EQUALS),
        CONTAINS(1, null),
        STARTS_WITH(1, null),
        ENDS_WITH(1, null),
        LIKE(1, DBCLogicalOperator.LIKE),
        GREATER(1, DBCLogicalOperator.GREATER),
        GREATER_EQUALS(1, DBCLogicalOperator.GREATER_EQUALS),
        LESS(1, DBCLogicalOperator.LESS),
        LESS_EQUALS(1, DBCLogicalOperator.LESS_EQUALS),
        BETWEEN(2, null),
        IS_NULL(0, DBCLogicalOperator.IS_NULL),
        IS_NOT_NULL(0, DBCLogicalOperator.IS_NOT_NULL);

        private final int arguments;
        @Nullable
        private final DBCLogicalOperator operator;

        Op(int arguments, @Nullable DBCLogicalOperator operator) {
            this.arguments = arguments;
            this.operator = operator;
        }

        @NotNull
        String getLabel() {
            return switch (this) {
                case VALUES -> GridFilterMessages.gridfilter_op_values;
                case EQUALS -> "="; //$NON-NLS-1$
                case NOT_EQUALS -> "≠"; //$NON-NLS-1$
                case CONTAINS -> GridFilterMessages.gridfilter_op_contains;
                case STARTS_WITH -> GridFilterMessages.gridfilter_op_starts_with;
                case ENDS_WITH -> GridFilterMessages.gridfilter_op_ends_with;
                case LIKE -> "LIKE"; //$NON-NLS-1$
                case GREATER -> ">"; //$NON-NLS-1$
                case GREATER_EQUALS -> "≥"; //$NON-NLS-1$
                case LESS -> "<"; //$NON-NLS-1$
                case LESS_EQUALS -> "≤"; //$NON-NLS-1$
                case BETWEEN -> GridFilterMessages.gridfilter_op_between;
                case IS_NULL -> "IS NULL"; //$NON-NLS-1$
                case IS_NOT_NULL -> "IS NOT NULL"; //$NON-NLS-1$
            };
        }

        @Override
        public String toString() {
            return getLabel();
        }
    }

    /**
     * The operator and the texts the popup set on a column, kept in the column's constraint.
     */
    record Condition(@NotNull Op op, @NotNull String text1, @NotNull String text2) {
    }

    /**
     * The result viewer side.
     */
    interface Host {
        /**
         * Reads the result again with the filter (on the server).
         */
        void applyFilter(@NotNull DBDDataFilter filter);

        void clearAllFilters();
    }

    /**
     * One entry of the value list.
     */
    private record Item(@Nullable Object value, @NotNull String display, long count) {
        boolean isNull() {
            return value == null;
        }
    }

    @Nullable
    private static ResultSetColumnFilterPopup current;
    @Nullable
    private static DBDAttributeBinding lastClosedColumn;
    private static long lastClosedAt;

    private final DBDAttributeBinding binding;
    private final DBDAttributeBinding[] bindings;
    private final DBDDataFilter filter;
    private final ResultSetSource source;
    private final DBCExecutionContext context;
    private final Host host;
    private final JDialog dialog;
    @Nullable
    private final String valuesUnavailable;
    private final boolean serverSearch;

    private final JTextField searchField = new JTextField();
    private final DefaultListModel<Item> listModel = new DefaultListModel<>();
    private final JList<Item> list = new JList<>(listModel);
    private final JLabel valuesStatus = new JLabel();
    private final JButton loadMoreButton;
    private final JButton selectAllButton;
    private final JButton selectNoneButton;
    private final JComboBox<Op> opCombo = new JComboBox<>();
    private final JTextField valueField1 = new JTextField();
    private final JTextField valueField2 = new JTextField();
    private final JLabel andLabel = new JLabel(GridFilterMessages.gridfilter_between_and);
    private final JLabel errorLabel = new JLabel();
    private final Timer searchTimer;

    private final List<Object> checkedValues = new ArrayList<>();
    private boolean nullChecked;
    /**
     * The values of the last read without a search text, and whether they are all values of the column.
     */
    private List<Item> unsearchedItems = List.of();
    private boolean unsearchedComplete;
    @Nullable
    private Item nullItem;
    /**
     * The values of the last read (with the search text of string columns).
     */
    private List<Item> loadedItems = List.of();
    private int limit = VALUE_LIMIT;
    private int generation;
    @Nullable
    private DataSourceJob valuesJob;
    private boolean closed;

    private ResultSetColumnFilterPopup(
        @NotNull Window owner,
        @NotNull DBDAttributeBinding binding,
        @NotNull DBDAttributeBinding[] bindings,
        @NotNull DBDDataFilter filter,
        @NotNull ResultSetSource source,
        @NotNull DBCExecutionContext context,
        @NotNull Host host
    ) {
        this.binding = binding;
        this.bindings = bindings;
        this.filter = filter;
        this.source = source;
        this.context = context;
        this.host = host;
        this.valuesUnavailable = getValuesUnavailableReason();
        this.serverSearch = binding.getDataKind() == DBPDataKind.STRING;
        dialog = new JDialog(owner, Dialog.ModalityType.MODELESS);
        dialog.setUndecorated(true);
        searchTimer = new Timer(SEARCH_DELAY, e -> searchChanged());
        searchTimer.setRepeats(false);
        loadMoreButton = createLinkButton(GridFilterMessages.gridfilter_values_load_more, () -> {
            limit += VALUE_LIMIT;
            loadValues();
        });
        selectAllButton = createLinkButton(GridFilterMessages.gridfilter_select_all, () -> setVisibleChecked(true));
        selectNoneButton = createLinkButton(GridFilterMessages.gridfilter_select_none, () -> setVisibleChecked(false));
        dialog.setContentPane(createContent());
        initState();
        installClosing();
    }

    /**
     * Opens the filter popup of a column below its header cell.
     *
     * @param invoker the table header
     * @param cell    the header cell, in the invoker's coordinates
     * @param filter  a copy of the viewer's filter (the popup changes it)
     */
    static void show(
        @NotNull JComponent invoker,
        @NotNull Rectangle cell,
        @NotNull DBDAttributeBinding binding,
        @NotNull DBDAttributeBinding[] bindings,
        @NotNull DBDDataFilter filter,
        @NotNull ResultSetSource source,
        @NotNull Host host
    ) {
        if (lastClosedColumn == binding && System.currentTimeMillis() - lastClosedAt < REOPEN_GUARD_MILLIS) {
            // The click that closed this column's popup (by taking the focus from it) would open it again
            lastClosedColumn = null;
            return;
        }
        if (current != null) {
            current.close();
        }
        Window owner = SwingUtilities.getWindowAncestor(invoker);
        DBCExecutionContext context = source.context();
        if (owner == null || context == null) {
            return;
        }
        ResultSetColumnFilterPopup popup = new ResultSetColumnFilterPopup(owner, binding, bindings, filter, source, context, host);
        current = popup;
        popup.open(invoker, cell);
    }

    /**
     * Removes the condition of a column and what the popup kept about it.
     */
    static void clearCondition(@NotNull DBDAttributeConstraint constraint) {
        constraint.setCriteria(null);
        constraint.removeOption(OPTION_CONDITION);
    }

    //////////////////////////////////////////////////////////
    // Layout

    @NotNull
    private JComponent createContent() {
        int gap = UIScale.scale(6);
        JPanel content = new JPanel(new BorderLayout(0, gap));
        Insets padding = UIScale.scale(new Insets(8, 8, 8, 8));
        content.setBorder(BorderFactory.createCompoundBorder(UIManager.getBorder("PopupMenu.border"), //$NON-NLS-1$
            BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, padding.right)));

        JPanel top = new JPanel(new BorderLayout(0, gap));
        top.setOpaque(false);
        JLabel title = new JLabel(binding.getLabel(), UiIcons.get("column"), SwingConstants.LEADING); //$NON-NLS-1$
        title.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        JLabel type = new JLabel(binding.getFullTypeName());
        type.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel titleRow = new JPanel(new BorderLayout(gap, 0));
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(type, BorderLayout.EAST);
        top.add(titleRow, BorderLayout.NORTH);
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, GridFilterMessages.gridfilter_search_hint);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, Boolean.TRUE);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                searchTimer.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                searchTimer.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                searchTimer.restart();
            }
        });
        top.add(searchField, BorderLayout.SOUTH);

        list.setCellRenderer(new ItemRenderer());
        list.setVisibleRowCount(VISIBLE_ROWS);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && list.isEnabled() && index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) {
                    toggle(listModel.get(index));
                }
            }
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "chromite.toggleValue"); //$NON-NLS-1$
        list.getActionMap().put("chromite.toggleValue", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(ActionEvent e) {
                Item item = list.getSelectedValue();
                if (item != null) {
                    toggle(item);
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(list);
        JPanel values = new JPanel(new BorderLayout(0, UIScale.scale(2)));
        values.setOpaque(false);
        values.add(listScroll, BorderLayout.CENTER);
        JPanel valuesFooter = new JPanel(new BorderLayout());
        valuesFooter.setOpaque(false);
        JPanel selectButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        selectButtons.setOpaque(false);
        selectButtons.add(selectAllButton);
        selectButtons.add(selectNoneButton);
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        statusPanel.setOpaque(false);
        valuesStatus.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        statusPanel.add(valuesStatus);
        statusPanel.add(loadMoreButton);
        valuesFooter.add(selectButtons, BorderLayout.WEST);
        valuesFooter.add(statusPanel, BorderLayout.CENTER);
        values.add(valuesFooter, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout(0, gap));
        bottom.setOpaque(false);
        bottom.add(new JSeparator(), BorderLayout.NORTH);
        JPanel condition = new JPanel(new GridBagLayout());
        condition.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 0, gap);
        condition.add(new JLabel(GridFilterMessages.gridfilter_condition), c);
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = 3;
        c.insets = new Insets(0, 0, 0, 0);
        condition.add(opCombo, c);
        c.gridy = 1;
        c.gridx = 1;
        c.gridwidth = 1;
        c.insets = new Insets(gap, 0, 0, 0);
        condition.add(valueField1, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(gap, gap, 0, gap);
        condition.add(andLabel, c);
        c.gridx = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(gap, 0, 0, 0);
        condition.add(valueField2, c);
        valueField1.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, GridFilterMessages.gridfilter_value_hint);
        valueField2.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, GridFilterMessages.gridfilter_value_hint);
        opCombo.addActionListener(e -> opChanged());
        JPanel conditionArea = new JPanel(new BorderLayout(0, gap));
        conditionArea.setOpaque(false);
        conditionArea.add(condition, BorderLayout.NORTH);
        errorLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
        errorLabel.setVisible(false);
        conditionArea.add(errorLabel, BorderLayout.SOUTH);
        bottom.add(conditionArea, BorderLayout.CENTER);

        JButton applyButton = new JButton(GridFilterMessages.gridfilter_apply);
        applyButton.addActionListener(e -> apply());
        JButton clearColumnButton = new JButton(GridFilterMessages.gridfilter_clear_column);
        clearColumnButton.addActionListener(e -> clearColumn());
        JButton clearAllButton = createLinkButton(GridFilterMessages.gridfilter_clear_all, () -> {
            close();
            host.clearAllFilters();
        });
        clearAllButton.setEnabled(filter.hasConditions());
        clearColumnButton.setEnabled(hasColumnCondition());
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, gap, 0));
        right.setOpaque(false);
        right.add(clearColumnButton);
        right.add(applyButton);
        buttons.add(clearAllButton, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);
        bottom.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(applyButton);

        content.add(top, BorderLayout.NORTH);
        content.add(values, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        content.setBackground(UIManager.getColor("PopupMenu.background")); //$NON-NLS-1$

        if (valuesUnavailable != null) {
            searchField.setEnabled(false);
            list.setEnabled(false);
            selectAllButton.setVisible(false);
            selectNoneButton.setVisible(false);
            loadMoreButton.setVisible(false);
            valuesStatus.setText(valuesUnavailable);
            valuesStatus.setToolTipText(valuesUnavailable);
        }
        return content;
    }

    @NotNull
    private static JButton createLinkButton(@NotNull String text, @NotNull Runnable task) {
        JButton button = new JButton(text);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        button.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.linkColor; margin: 0,2,0,2"); //$NON-NLS-1$
        button.setFocusable(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> task.run());
        return button;
    }

    private void installClosing() {
        dialog.getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                // A click elsewhere in the application (the combo's list is a window of this dialog and does not take
                // the focus). Switching to another application keeps the popup, so the values are still there on return
                if (!closed && e.getOppositeWindow() != null) {
                    lastClosedColumn = binding;
                    lastClosedAt = System.currentTimeMillis();
                    close();
                }
            }
        });
    }

    private void open(@NotNull JComponent invoker, @NotNull Rectangle cell) {
        dialog.pack();
        Dimension size = new Dimension(Math.max(UIScale.scale(POPUP_WIDTH), dialog.getPreferredSize().width), dialog.getPreferredSize().height);
        dialog.setSize(size);
        Point below = new Point(cell.x + cell.width - size.width, cell.y + cell.height);
        Point above = new Point(below.x, cell.y - size.height);
        SwingUtilities.convertPointToScreen(below, invoker);
        SwingUtilities.convertPointToScreen(above, invoker);
        Rectangle screen = invoker.getGraphicsConfiguration().getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(invoker.getGraphicsConfiguration());
        int minX = screen.x + screenInsets.left;
        int maxX = screen.x + screen.width - screenInsets.right - size.width;
        int maxY = screen.y + screen.height - screenInsets.bottom - size.height;
        Point location = below.y <= maxY ? below : above;
        location.x = Math.max(minX, Math.min(location.x, maxX));
        location.y = Math.max(screen.y + screenInsets.top, location.y);
        dialog.setLocation(location);
        dialog.setVisible(true);
        if (valuesUnavailable == null) {
            searchField.requestFocusInWindow();
            loadValues();
        } else {
            opCombo.requestFocusInWindow();
        }
    }

    private void close() {
        if (closed) {
            return;
        }
        closed = true;
        searchTimer.stop();
        cancelValuesJob();
        dialog.dispose();
        if (current == this) {
            current = null;
        }
    }

    //////////////////////////////////////////////////////////
    // State

    /**
     * Operators for the column's data kind, as far as its value handler supports them (like the SWT filter menu); numbers
     * and dates that compare get all comparisons and the range, and every column gets IS NULL and IS NOT NULL.
     */
    @NotNull
    private List<Op> getOperators() {
        DBCLogicalOperator[] supported = binding.getValueHandler().getSupportedOperators(binding);
        DBPDataKind kind = binding.getDataKind();
        List<Op> ops = new ArrayList<>();
        if (valuesUnavailable == null) {
            ops.add(Op.VALUES);
        }
        addIfSupported(ops, supported, Op.EQUALS, Op.NOT_EQUALS);
        if (kind == DBPDataKind.STRING && ArrayUtils.contains(supported, DBCLogicalOperator.LIKE)) {
            ops.add(Op.CONTAINS);
            ops.add(Op.STARTS_WITH);
            ops.add(Op.ENDS_WITH);
            ops.add(Op.LIKE);
        }
        if ((kind == DBPDataKind.NUMERIC || kind == DBPDataKind.DATETIME)
            && (ArrayUtils.contains(supported, DBCLogicalOperator.GREATER) || ArrayUtils.contains(supported, DBCLogicalOperator.LESS))) {
            // Value handlers list only > and < (e.g. the JDBC number handler); a column that compares also takes ≥, ≤ and BETWEEN
            ops.add(Op.GREATER);
            ops.add(Op.GREATER_EQUALS);
            ops.add(Op.LESS);
            ops.add(Op.LESS_EQUALS);
            ops.add(Op.BETWEEN);
        }
        // Value handlers don't list the NULL tests; every column can be tested for NULL
        ops.add(Op.IS_NULL);
        ops.add(Op.IS_NOT_NULL);
        return ops;
    }

    private static void addIfSupported(@NotNull List<Op> ops, @NotNull DBCLogicalOperator[] supported, @NotNull Op... candidates) {
        for (Op op : candidates) {
            if (ArrayUtils.contains(supported, op.operator)) {
                ops.add(op);
            }
        }
    }

    /**
     * Shows the column's condition: the popup's own (constraint option), an IN list as checked values, or a single
     * operator (the grid's "Filter by Value").
     */
    private void initState() {
        List<Op> ops = getOperators();
        for (Op op : ops) {
            opCombo.addItem(op);
        }
        Op op = ops.isEmpty() ? null : ops.getFirst();
        DBDAttributeConstraint constraint = filter.getConstraint(binding);
        Condition condition = constraint != null ? constraint.getOption(OPTION_CONDITION) : null;
        if (condition != null) {
            op = condition.op();
            valueField1.setText(condition.text1());
            valueField2.setText(condition.text2());
        } else if (constraint != null && constraint.getOperator() == DBCLogicalOperator.IN && constraint.getValue() instanceof Object[] array) {
            op = Op.VALUES;
            for (Object value : array) {
                if (value == null) {
                    nullChecked = true;
                } else {
                    checkedValues.add(value);
                }
            }
        } else if (constraint != null && constraint.getOperator() != null && !constraint.isReverseOperator()) {
            for (Op candidate : ops) {
                if (candidate.operator == constraint.getOperator()) {
                    op = candidate;
                    if (constraint.getValue() != null) {
                        valueField1.setText(binding.getValueHandler().getValueDisplayString(binding, constraint.getValue(), DBDDisplayFormat.EDIT));
                    }
                }
            }
        }
        if (op != null && ops.contains(op)) {
            opCombo.setSelectedItem(op);
        }
        opChanged();
    }

    private boolean hasColumnCondition() {
        DBDAttributeConstraint constraint = filter.getConstraint(binding);
        return constraint != null && constraint.hasCondition();
    }

    @Nullable
    private Op getOp() {
        return (Op) opCombo.getSelectedItem();
    }

    private void opChanged() {
        Op op = getOp();
        int arguments = op == null ? 0 : op.arguments;
        valueField1.setVisible(arguments >= 1);
        andLabel.setVisible(arguments == 2);
        valueField2.setVisible(arguments == 2);
        boolean valuesMode = op == Op.VALUES;
        list.setEnabled(valuesMode && valuesUnavailable == null);
        list.repaint();
        errorLabel.setVisible(false);
        if (dialog.isVisible()) {
            dialog.getContentPane().revalidate();
            if (arguments >= 1) {
                valueField1.requestFocusInWindow();
            }
        }
    }

    @Nullable
    private String getValuesUnavailableReason() {
        if (!(source.dataContainer() instanceof SQLQueryContainer)) {
            return GridFilterMessages.gridfilter_values_unavailable_query;
        }
        DBPDataKind kind = binding.getDataKind();
        if (kind != DBPDataKind.STRING && kind != DBPDataKind.NUMERIC && kind != DBPDataKind.DATETIME && kind != DBPDataKind.BOOLEAN) {
            return GridFilterMessages.gridfilter_values_unavailable_type;
        }
        for (DBDAttributeBinding other : bindings) {
            if (other != binding && other.getLabel().equalsIgnoreCase(binding.getLabel())) {
                return GridFilterMessages.gridfilter_values_unavailable_name;
            }
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Values

    private void searchChanged() {
        if (closed || valuesUnavailable != null) {
            return;
        }
        if (serverSearch) {
            limit = VALUE_LIMIT;
            loadValues();
        } else {
            showItems();
        }
    }

    @NotNull
    private String getSearchText() {
        return searchField.getText().trim();
    }

    /**
     * Reads the values in the background: the query of the result with the other columns' filters.
     */
    private void loadValues() {
        cancelValuesJob();
        int readGeneration = ++generation;
        String search = serverSearch && !getSearchText().isEmpty() ? getSearchText() : null;
        int readLimit = limit;
        DBDDataFilter otherFilter = new DBDDataFilter(filter);
        DBDAttributeConstraint own = otherFilter.getConstraint(binding);
        if (own != null) {
            clearCondition(own);
        }
        SQLQueryContainer queryContainer = (SQLQueryContainer) source.dataContainer();
        valuesStatus.setText(GridFilterMessages.gridfilter_values_loading);
        valuesStatus.setToolTipText(null);
        loadMoreButton.setVisible(false);
        DBDValueHandler valueHandler = binding.getValueHandler();
        valuesJob = new DataSourceJob("Read column values", context) { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    SQLQueryColumnValues.Result result = SQLQueryColumnValues.read(monitor, context, queryContainer, binding,
                        otherFilter, search, readLimit);
                    List<Item> items = new ArrayList<>(result.values().size());
                    for (SQLQueryColumnValues.ValueCount valueCount : result.values()) {
                        Object value = valueCount.value();
                        String display = value == null ? NULL_TEXT : valueHandler.getValueDisplayString(binding, value, DBDDisplayFormat.UI);
                        items.add(new Item(value, display, valueCount.count()));
                    }
                    // Listed in value order; the server read the most frequent ones
                    items.sort((item1, item2) -> {
                        if (item1.isNull() || item2.isNull()) {
                            return Boolean.compare(!item1.isNull(), !item2.isNull());
                        }
                        if (item1.value() instanceof String text1 && item2.value() instanceof String text2) {
                            return text1.compareToIgnoreCase(text2);
                        }
                        return DBUtils.compareDataValues(item1.value(), item2.value());
                    });
                    SwingUtilities.invokeLater(() -> valuesRead(readGeneration, search, items, result.truncated()));
                } catch (Throwable e) {
                    if (!monitor.isCanceled()) {
                        SwingUtilities.invokeLater(() -> valuesFailed(readGeneration, e));
                    }
                }
                return Status.OK_STATUS;
            }
        };
        valuesJob.schedule();
    }

    private void cancelValuesJob() {
        if (valuesJob != null) {
            valuesJob.cancel();
            valuesJob = null;
        }
    }

    private void valuesRead(int readGeneration, @Nullable String search, @NotNull List<Item> items, boolean truncated) {
        if (closed || readGeneration != generation) {
            return;
        }
        valuesJob = null;
        List<Item> nonNull = new ArrayList<>(items.size());
        for (Item item : items) {
            if (item.isNull()) {
                nullItem = item;
            } else {
                nonNull.add(item);
            }
        }
        if (search == null) {
            unsearchedItems = nonNull;
            unsearchedComplete = !truncated;
        }
        loadedItems = nonNull;
        showItems();
        if (truncated) {
            valuesStatus.setText(NLS.bind(GridFilterMessages.gridfilter_values_more, String.format("%,d", nonNull.size()))); //$NON-NLS-1$
        } else {
            int count = nonNull.size() + (nullItem != null && search == null ? 1 : 0);
            valuesStatus.setText(count == 0 ? GridFilterMessages.gridfilter_values_none
                : count == 1 ? GridFilterMessages.gridfilter_values_one
                : NLS.bind(GridFilterMessages.gridfilter_values_many, String.format("%,d", count))); //$NON-NLS-1$
        }
        valuesStatus.setToolTipText(null);
        loadMoreButton.setVisible(truncated && limit < Integer.MAX_VALUE - VALUE_LIMIT);
    }

    private void valuesFailed(int readGeneration, @NotNull Throwable error) {
        if (closed || readGeneration != generation) {
            return;
        }
        valuesJob = null;
        String message = ErrorText.describe(error);
        valuesStatus.setText(NLS.bind(GridFilterMessages.gridfilter_values_failed, CommonUtils.truncateString(message.replace('\n', ' '), 80)));
        valuesStatus.setToolTipText(message);
        loadMoreButton.setVisible(false);
    }

    /**
     * Fills the list: NULL first, then the read values (for non-string columns filtered here by the search text).
     */
    private void showItems() {
        String search = getSearchText().toLowerCase(Locale.ROOT);
        listModel.clear();
        if (nullItem != null && (search.isEmpty() || NULL_TEXT.toLowerCase(Locale.ROOT).contains(search))) {
            listModel.addElement(nullItem);
        }
        for (Item item : loadedItems) {
            if (serverSearch || search.isEmpty() || item.display().toLowerCase(Locale.ROOT).contains(search)) {
                listModel.addElement(item);
            }
        }
    }

    private boolean isChecked(@NotNull Item item) {
        return item.isNull() ? nullChecked : indexOfValue(item.value()) >= 0;
    }

    private int indexOfValue(@Nullable Object value) {
        for (int i = 0; i < checkedValues.size(); i++) {
            Object checked = checkedValues.get(i);
            if (Objects.equals(checked, value)
                || (checked != null && value != null && checked.getClass() == value.getClass() && DBUtils.compareDataValues(checked, value) == 0)) {
                return i;
            }
        }
        return -1;
    }

    private void setChecked(@NotNull Item item, boolean checked) {
        if (item.isNull()) {
            nullChecked = checked;
            return;
        }
        int index = indexOfValue(item.value());
        if (checked && index < 0) {
            checkedValues.add(item.value());
        } else if (!checked && index >= 0) {
            checkedValues.remove(index);
        }
    }

    private void toggle(@NotNull Item item) {
        setChecked(item, !isChecked(item));
        valuesChosen();
    }

    private void setVisibleChecked(boolean checked) {
        if (valuesUnavailable != null) {
            return;
        }
        for (int i = 0; i < listModel.size(); i++) {
            setChecked(listModel.get(i), checked);
        }
        valuesChosen();
    }

    /**
     * A value was checked or unchecked: the value list is the filter now.
     */
    private void valuesChosen() {
        if (getOp() != Op.VALUES) {
            opCombo.setSelectedItem(Op.VALUES);
        }
        errorLabel.setVisible(false);
        list.repaint();
    }

    /**
     * All values of the column are checked (then there is nothing to filter).
     */
    private boolean isAllChecked() {
        if (!unsearchedComplete || (nullItem != null && !nullChecked)) {
            return false;
        }
        for (Item item : unsearchedItems) {
            if (!isChecked(item)) {
                return false;
            }
        }
        return true;
    }

    //////////////////////////////////////////////////////////
    // Apply

    private void apply() {
        Op op = getOp();
        DBDDataFilter newFilter = new DBDDataFilter(filter);
        DBDAttributeConstraint constraint = newFilter.getConstraint(binding);
        if (op == null || constraint == null) {
            return;
        }
        clearCondition(constraint);
        try {
            setCondition(constraint, op);
        } catch (InvalidInput e) {
            errorLabel.setText(e.getMessage());
            errorLabel.setToolTipText(e.getMessage());
            errorLabel.setVisible(true);
            // Keep the width (a long message is cut, the tool tip has it); the error line adds height
            dialog.setSize(dialog.getWidth(), dialog.getPreferredSize().height);
            return;
        }
        close();
        host.applyFilter(newFilter);
    }

    private void clearColumn() {
        DBDDataFilter newFilter = new DBDDataFilter(filter);
        DBDAttributeConstraint constraint = newFilter.getConstraint(binding);
        if (constraint != null) {
            clearCondition(constraint);
        }
        close();
        host.applyFilter(newFilter);
    }

    private void setCondition(@NotNull DBDAttributeConstraint constraint, @NotNull Op op) throws InvalidInput {
        String text1 = valueField1.getText();
        String text2 = valueField2.getText();
        switch (op) {
            case VALUES -> {
                if ((checkedValues.isEmpty() && !nullChecked) || isAllChecked()) {
                    return;
                }
                if (checkedValues.size() > VALUE_LIMIT) {
                    throw new InvalidInput(NLS.bind(GridFilterMessages.gridfilter_values_too_many, String.format("%,d", VALUE_LIMIT))); //$NON-NLS-1$
                }
                List<Object> values = new ArrayList<>(checkedValues);
                if (nullChecked) {
                    values.add(null);
                }
                constraint.setOperator(DBCLogicalOperator.IN);
                constraint.setValue(values.toArray());
                return;
            }
            case IS_NULL, IS_NOT_NULL -> constraint.setOperator(op.operator);
            case LIKE -> {
                constraint.setOperator(op.operator);
                constraint.setValue(requireText(text1));
            }
            case EQUALS, NOT_EQUALS, GREATER, GREATER_EQUALS, LESS, LESS_EQUALS -> {
                constraint.setOperator(op.operator);
                constraint.setValue(parseValue(requireText(text1)));
            }
            case CONTAINS -> constraint.setCriteria(likeCriteria("%" + escapeLike(requireText(text1)) + "%")); //$NON-NLS-1$ //$NON-NLS-2$
            case STARTS_WITH -> constraint.setCriteria(likeCriteria(escapeLike(requireText(text1)) + "%")); //$NON-NLS-1$
            case ENDS_WITH -> constraint.setCriteria(likeCriteria("%" + escapeLike(requireText(text1)))); //$NON-NLS-1$
            case BETWEEN -> {
                String low = toSqlLiteral(parseValue(requireText(text1)));
                String high = toSqlLiteral(parseValue(requireText(text2)));
                constraint.setCriteria("BETWEEN " + low + " AND " + high); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        constraint.setOption(OPTION_CONDITION, new Condition(op, text1, op.arguments == 2 ? text2 : "")); //$NON-NLS-1$
    }

    @NotNull
    private static String requireText(@NotNull String text) throws InvalidInput {
        if (text.isBlank()) {
            throw new InvalidInput(GridFilterMessages.gridfilter_value_required);
        }
        return text;
    }

    /**
     * The value of a text as the column's value handler makes it (as the grid's cell editor does).
     */
    @NotNull
    private Object parseValue(@NotNull String text) throws InvalidInput {
        Object value;
        try (DBCSession session = context.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Make filter value")) { //$NON-NLS-1$
            value = binding.getValueHandler().getValueFromObject(session, binding, text.trim(), false, true);
        } catch (DBCException | RuntimeException e) {
            // The value handler's message is technical ("Can't parse numeric value … using formatter"): name the input instead
            log.debug("Invalid filter value " + text, e);
            throw new InvalidInput(NLS.bind(GridFilterMessages.gridfilter_value_invalid, text.trim()));
        }
        if (DBUtils.isNullValue(value)) {
            throw new InvalidInput(NLS.bind(GridFilterMessages.gridfilter_value_invalid, text));
        }
        return value;
    }

    /**
     * A value as an SQL literal of the dialect (the model's conversion for filter conditions).
     */
    @NotNull
    private String toSqlLiteral(@NotNull Object value) {
        DBPDataSource dataSource = context.getDataSource();
        return SQLUtils.convertValueToSQL(dataSource, binding, binding.getValueHandler(), value, DBDDisplayFormat.NATIVE, true);
    }

    /**
     * A LIKE condition with a literal pattern: case-insensitive where the column supports ILIKE (PostgreSQL), like the
     * value search.
     */
    @NotNull
    private String likeCriteria(@NotNull String pattern) {
        boolean caseInsensitive = ArrayUtils.contains(binding.getValueHandler().getSupportedOperators(binding), DBCLogicalOperator.ILIKE);
        return (caseInsensitive ? DBCLogicalOperator.ILIKE : DBCLogicalOperator.LIKE).getExpression() + " " //$NON-NLS-1$
            + context.getDataSource().getSQLDialect().getQuotedString(pattern)
            + " ESCAPE '" + LIKE_ESCAPE + "'"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Makes the LIKE wildcards of a text literal: %, _ and [ (a character class in SQL Server) and the escape character.
     */
    @NotNull
    static String escapeLike(@NotNull String text) {
        StringBuilder result = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == LIKE_ESCAPE || c == '%' || c == '_' || c == '[') {
                result.append(LIKE_ESCAPE);
            }
            result.append(c);
        }
        return result.toString();
    }

    /**
     * Input the popup can't make a condition of; its message is shown in the popup.
     */
    private static final class InvalidInput extends Exception {
        InvalidInput(@NotNull String message) {
            super(message);
        }
    }

    /**
     * A value with a check box and its row count on the right.
     */
    private final class ItemRenderer implements ListCellRenderer<Item> {
        private final JPanel panel = new JPanel(new BorderLayout(UIScale.scale(6), 0));
        private final JCheckBox checkBox = new JCheckBox();
        private final JLabel countLabel = new JLabel();

        ItemRenderer() {
            checkBox.setOpaque(false);
            countLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            panel.add(checkBox, BorderLayout.CENTER);
            panel.add(countLabel, BorderLayout.EAST);
            panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, UIScale.scale(4)));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Item> jList, Item item, int index, boolean isSelected, boolean cellHasFocus) {
            if (panel.getFont() != jList.getFont()) {
                // A look and feel switch replaced the list's font and colors
                SwingUtilities.updateComponentTreeUI(panel);
                checkBox.setOpaque(false);
            }
            checkBox.setText(item.display());
            checkBox.setSelected(isChecked(item));
            checkBox.setEnabled(jList.isEnabled());
            checkBox.setFont(jList.getFont());
            checkBox.setForeground(item.isNull() ? UIManager.getColor("Chromite.secondaryForeground") //$NON-NLS-1$
                : isSelected ? jList.getSelectionForeground() : jList.getForeground());
            countLabel.setText(String.format("%,d", item.count())); //$NON-NLS-1$
            panel.setBackground(isSelected && jList.isEnabled() ? jList.getSelectionBackground() : jList.getBackground());
            return panel;
        }
    }
}
