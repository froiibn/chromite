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
import com.seizia.chromite.ui.swing.internal.GridVirtualMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPEvaluationContext;
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
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.virtual.DBVEntity;
import com.seizia.chromite.model.virtual.DBVUtils;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 결과의 저장한 필터와 최근 필터(SWT {@code DataFilterRegistry}의 "필터 저장"과 {@code ResultSetFilterPanel}의 필터 기록에 해당).
 *
 * 설계:
 * <ul>
 *     <li><b>저장 위치:</b> 결과 테이블의 가상 모델({@link DBVEntity})의 속성 두 개({@link #PROP_SAVED}, {@link #PROP_RECENT})에 목록으로 두고
 *     연결 설정(data-sources.json의 가상 메타데이터)에 쓴다. 가상 키({@link ResultSetVirtualKeys})와 같은 곳이라 연결을 내보내거나 옮기면 함께 간다.
 *     SWT는 작업 공간의 {@code saved-data-filter.xml}에 테이블마다 기본 필터 하나만 두었다(다른 형식이라 읽지 않는다). DB는 바꾸지 않는다.</li>
 *     <li><b>저장한 필터:</b> 이름이 있고 열 필터(조건)와 정렬을 함께 담는다. 적용하면 결과의 조건과 정렬을 그 필터로 바꾼다.</li>
 *     <li><b>최근 필터:</b> 조건이 있는 필터로 결과를 다시 읽을 때마다 조건만 남긴다(정렬은 머리글을 누를 때마다 바뀌어 기록이 넘치므로 뺀다).
 *     같은 조건은 맨 위로 올리고, {@value #RECENT_LIMIT}개까지 둔다(없앤 WHERE 필터 줄의 기록 수와 같음). 적용하면 조건만 바꾸고 정렬은 그대로다.</li>
 *     <li><b>열 조건 하나의 형식:</b> 열 이름, 조건 글자(criteria) 또는 연산자·뒤집기·값, 열 필터 팝업의 상태(연산자와 입력한 글자,
 *     {@link ResultSetColumnFilterPopup#OPTION_CONDITION}), 정렬 위치·방향. 조건 글자는 저장할 때 방언으로 만든 SQL 조각이다(같은 연결에서만 쓰임).</li>
 *     <li><b>값:</b> JSON에 넣을 수 있게 종류와 글자로 둔다. 글자·불리언은 그대로, 숫자는 정밀도를 잃지 않게 10진 글자({@link BigDecimal}),
 *     날짜 같은 그 밖의 값은 열 값 처리기의 편집 형식 글자다. 적용할 때 열 값 처리기로 다시 값을 만든다(셀 편집기·필터 팝업과 같은 변환).
 *     값을 만들 수 없으면 글자 그대로 쓴다. IN 값 목록(팝업의 값 고르기)은 목록이고 NULL을 담을 수 있다.</li>
 *     <li><b>열 맞추기:</b> 적용할 때 열 이름으로 결과의 열을 찾는다(같은 테이블의 다른 쿼리 결과에도 쓰게). 결과에 없는 열의 조건은 빼고
 *     그 열 이름을 알린다. 가상 열에는 조건과 서버 정렬을 두지 않는다({@link ResultSetVirtualColumns}).</li>
 *     <li>저장·지우기 대화상자는 연결 설정을 바꾸므로 기본 단추가 Cancel이다. 필터를 적용하는 것은 열 필터 팝업과 같이 묻지 않는다
 *     (저장하지 않은 편집이 있으면 결과 뷰어가 먼저 묻는다).</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다(값 변환은 필터 팝업처럼 UTIL 세션을 열 뿐 DB를 읽지 않는다).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-saved-filters-virtual-columns.md "저장한 필터와 최근 필터")
 */
final class ResultSetSavedFilters {

    private static final Log log = Log.getLog(ResultSetSavedFilters.class);

    /**
     * Virtual model property of a table: its saved filters.
     */
    static final String PROP_SAVED = "chromite.savedFilters"; //$NON-NLS-1$
    /**
     * Virtual model property of a table: the conditions of its recent filters, the latest first.
     */
    static final String PROP_RECENT = "chromite.recentFilters"; //$NON-NLS-1$
    static final int RECENT_LIMIT = 20;

    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_TEXT = "text"; //$NON-NLS-1$
    private static final String KEY_CONSTRAINTS = "constraints"; //$NON-NLS-1$
    private static final String KEY_COLUMN = "column"; //$NON-NLS-1$
    private static final String KEY_CRITERIA = "criteria"; //$NON-NLS-1$
    private static final String KEY_OPERATOR = "operator"; //$NON-NLS-1$
    private static final String KEY_REVERSE = "reverse"; //$NON-NLS-1$
    private static final String KEY_VALUE = "value"; //$NON-NLS-1$
    private static final String KEY_ORDER = "order"; //$NON-NLS-1$
    private static final String KEY_DESCENDING = "descending"; //$NON-NLS-1$
    private static final String KEY_POPUP = "popup"; //$NON-NLS-1$
    private static final String KEY_POPUP_OP = "op"; //$NON-NLS-1$
    private static final String KEY_POPUP_TEXT1 = "text1"; //$NON-NLS-1$
    private static final String KEY_POPUP_TEXT2 = "text2"; //$NON-NLS-1$
    private static final String KEY_LIST = "list"; //$NON-NLS-1$
    private static final String KEY_KIND = "kind"; //$NON-NLS-1$
    private static final String KIND_STRING = "string"; //$NON-NLS-1$
    private static final String KIND_BOOLEAN = "boolean"; //$NON-NLS-1$
    private static final String KIND_NUMBER = "number"; //$NON-NLS-1$
    private static final String KIND_TEXT = "text"; //$NON-NLS-1$

    /**
     * A saved or recent filter.
     *
     * @param name        the name (a recent filter has none)
     * @param text        the conditions (and sort) as SQL, for menus
     * @param constraints the column conditions as stored
     */
    record Entry(@NotNull String name, @NotNull String text, @NotNull List<?> constraints) {
    }

    /**
     * A filter made from an entry.
     *
     * @param missingColumns the columns of the entry that the result does not have (their conditions were left out)
     */
    record Restored(@NotNull DBDDataFilter filter, @NotNull List<String> missingColumns) {
    }

    private ResultSetSavedFilters() {
    }

    //////////////////////////////////////////////////////////
    // Reading

    @NotNull
    static List<Entry> getSaved(@NotNull DBSEntity entity) {
        return readEntries(entity, PROP_SAVED);
    }

    @NotNull
    static List<Entry> getRecent(@NotNull DBSEntity entity) {
        return readEntries(entity, PROP_RECENT);
    }

    /**
     * Whether a filter has column conditions or a sort that can be saved (not on virtual columns).
     */
    static boolean hasSavable(@NotNull DBDDataFilter filter) {
        for (DBDAttributeConstraint constraint : filter.getConstraints()) {
            if (!ResultSetVirtualColumns.isVirtual(constraint) && (constraint.hasCondition() || constraint.getOrderPosition() > 0)) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private static List<Entry> readEntries(@NotNull DBSEntity entity, @NotNull String property) {
        DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, false);
        Object stored = vEntity == null ? null : vEntity.getProperty(property);
        List<Entry> entries = new ArrayList<>();
        if (stored instanceof Collection<?> list) {
            for (Object item : list) {
                // Read back from the connection settings as plain JSON maps and lists
                if (item instanceof Map<?, ?> map && map.get(KEY_CONSTRAINTS) instanceof List<?> constraints) {
                    entries.add(new Entry(CommonUtils.toString(map.get(KEY_NAME)), CommonUtils.toString(map.get(KEY_TEXT)), constraints));
                }
            }
        }
        return entries;
    }

    //////////////////////////////////////////////////////////
    // Saving

    /**
     * Save Current Filter: asks a name and keeps the conditions and the sort of the filter for the table. A name that
     * exists is replaced after a confirmation.
     *
     * @param text the filter as SQL (shown in the menus)
     * @return the saved name, or null
     */
    @Nullable
    static String save(@NotNull Component parent, @NotNull DBSEntity entity, @NotNull DBDDataFilter filter, @NotNull String text) {
        List<Map<String, Object>> constraints = encodeConstraints(filter, true);
        if (constraints.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(GridVirtualMessages.gridvirt_filters_save_title, GridVirtualMessages.gridvirt_filters_nothing, false);
            return null;
        }
        List<Entry> saved = getSaved(entity);
        JTextField nameField = new JTextField(makeDefaultName(saved), 28);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(NLS.bind(GridVirtualMessages.gridvirt_filters_save_intro, escape(DBUtils.getObjectFullName(entity, DBPEvaluationContext.UI)))),
            BorderLayout.NORTH);
        JPanel nameRow = new JPanel(new BorderLayout(6, 0));
        nameRow.add(new JLabel(GridVirtualMessages.gridvirt_filters_name), BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        panel.add(nameRow, BorderLayout.CENTER);
        JLabel textLabel = new JLabel(shorten(text, 120));
        textLabel.setToolTipText(text);
        textLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        panel.add(textLabel, BorderLayout.SOUTH);
        String[] options = {GridVirtualMessages.gridvirt_filters_save_button, ToolsMessages.button_cancel};
        Window owner = SwingUtilities.getWindowAncestor(parent);
        while (true) {
            // Cancel is the default button: the dialog changes the connection's settings
            int answer = JOptionPane.showOptionDialog(owner, panel, GridVirtualMessages.gridvirt_filters_save_title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[1]);
            if (answer != 0) {
                return null;
            }
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                DBWorkbench.getPlatformUI().showMessageBox(GridVirtualMessages.gridvirt_filters_save_title, GridVirtualMessages.gridvirt_filters_name_required, true);
                continue;
            }
            boolean exists = saved.stream().anyMatch(entry -> entry.name().equals(name));
            if (exists) {
                String[] replaceOptions = {GridVirtualMessages.gridvirt_filters_replace, ToolsMessages.button_cancel};
                int replace = JOptionPane.showOptionDialog(owner, NLS.bind(GridVirtualMessages.gridvirt_filters_replace_confirm, name),
                    GridVirtualMessages.gridvirt_filters_save_title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    replaceOptions, replaceOptions[1]);
                if (replace != 0) {
                    continue;
                }
            }
            List<Object> entries = new ArrayList<>();
            Map<String, Object> newEntry = makeEntry(name, text, constraints);
            boolean replaced = false;
            for (Entry entry : saved) {
                if (entry.name().equals(name)) {
                    entries.add(newEntry);
                    replaced = true;
                } else {
                    entries.add(makeEntry(entry.name(), entry.text(), entry.constraints()));
                }
            }
            if (!replaced) {
                entries.add(newEntry);
            }
            writeEntries(entity, PROP_SAVED, entries);
            return name;
        }
    }

    /**
     * Keeps the conditions of a filter the result was read with in the table's recent filters (the latest first).
     */
    static void remember(@NotNull DBSEntity entity, @NotNull DBDDataFilter filter, @NotNull String text) {
        List<Map<String, Object>> constraints = encodeConstraints(filter, false);
        if (constraints.isEmpty()) {
            return;
        }
        List<Entry> recent = getRecent(entity);
        if (!recent.isEmpty() && Objects.equals(recent.getFirst().constraints(), constraints)) {
            // The same conditions again (a sort or a new read): nothing changes
            return;
        }
        List<Object> entries = new ArrayList<>();
        entries.add(makeEntry("", text, constraints)); //$NON-NLS-1$
        for (Entry entry : recent) {
            if (entries.size() >= RECENT_LIMIT) {
                break;
            }
            if (!Objects.equals(entry.constraints(), constraints)) {
                entries.add(makeEntry("", entry.text(), entry.constraints())); //$NON-NLS-1$
            }
        }
        writeEntries(entity, PROP_RECENT, entries);
    }

    /**
     * Deletes a saved filter after a confirmation (Cancel is the default).
     */
    static boolean delete(@NotNull Component parent, @NotNull DBSEntity entity, @NotNull Entry entry) {
        String[] options = {GridVirtualMessages.gridvirt_filters_delete_button, ToolsMessages.button_cancel};
        int answer = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(parent),
            NLS.bind(GridVirtualMessages.gridvirt_filters_delete_confirm, entry.name(), DBUtils.getObjectFullName(entity, DBPEvaluationContext.UI)),
            GridVirtualMessages.gridvirt_filters_delete_title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (answer != 0) {
            return false;
        }
        List<Object> entries = new ArrayList<>();
        for (Entry other : getSaved(entity)) {
            if (!other.name().equals(entry.name())) {
                entries.add(makeEntry(other.name(), other.text(), other.constraints()));
            }
        }
        writeEntries(entity, PROP_SAVED, entries);
        return true;
    }

    /**
     * Clears the recent filters of a table after a confirmation (Cancel is the default).
     */
    static boolean clearRecent(@NotNull Component parent, @NotNull DBSEntity entity) {
        int count = getRecent(entity).size();
        String[] options = {GridVirtualMessages.gridvirt_filters_clear_button, ToolsMessages.button_cancel};
        int answer = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(parent),
            NLS.bind(GridVirtualMessages.gridvirt_filters_history_clear_confirm, count, DBUtils.getObjectFullName(entity, DBPEvaluationContext.UI)),
            GridVirtualMessages.gridvirt_filters_history, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (answer != 0) {
            return false;
        }
        writeEntries(entity, PROP_RECENT, List.of());
        return true;
    }

    @NotNull
    private static Map<String, Object> makeEntry(@NotNull String name, @NotNull String text, @NotNull List<?> constraints) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (!name.isEmpty()) {
            entry.put(KEY_NAME, name);
        }
        entry.put(KEY_TEXT, text);
        entry.put(KEY_CONSTRAINTS, constraints);
        return entry;
    }

    private static void writeEntries(@NotNull DBSEntity entity, @NotNull String property, @NotNull List<Object> entries) {
        DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, !entries.isEmpty());
        if (vEntity == null) {
            return;
        }
        vEntity.setProperty(property, entries.isEmpty() ? null : entries);
        if (!entity.getDataSource().getContainer().persistConfiguration()) {
            log.warn("Can't save the filters of " + entity.getName());
        }
    }

    @NotNull
    private static String makeDefaultName(@NotNull List<Entry> saved) {
        for (int index = saved.size() + 1; ; index++) {
            String name = NLS.bind(GridVirtualMessages.gridvirt_filters_default_name, index);
            if (saved.stream().noneMatch(entry -> entry.name().equals(name))) {
                return name;
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Constraints

    /**
     * The column conditions (and the sort) of a filter as JSON values, not those of virtual columns.
     */
    @NotNull
    private static List<Map<String, Object>> encodeConstraints(@NotNull DBDDataFilter filter, boolean withOrder) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DBDAttributeConstraint constraint : filter.getConstraints()) {
            if (ResultSetVirtualColumns.isVirtual(constraint)) {
                continue;
            }
            boolean condition = constraint.hasCondition();
            boolean order = withOrder && constraint.getOrderPosition() > 0;
            if (!condition && !order) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(KEY_COLUMN, constraint.getAttributeName());
            if (condition) {
                if (!CommonUtils.isEmpty(constraint.getCriteria())) {
                    item.put(KEY_CRITERIA, constraint.getCriteria());
                } else if (constraint.getOperator() != null) {
                    item.put(KEY_OPERATOR, constraint.getOperator().name());
                    if (constraint.isReverseOperator()) {
                        item.put(KEY_REVERSE, true);
                    }
                    if (constraint.getValue() != null && constraint.getAttribute() instanceof DBDAttributeBinding binding) {
                        item.put(KEY_VALUE, encodeValue(binding, constraint.getValue()));
                    }
                }
                ResultSetColumnFilterPopup.Condition popup = constraint.getOption(ResultSetColumnFilterPopup.OPTION_CONDITION);
                if (popup != null) {
                    Map<String, Object> state = new LinkedHashMap<>();
                    state.put(KEY_POPUP_OP, popup.op().name());
                    // Empty texts are left out as the settings file leaves them out, so a recent filter read back from
                    // the file compares equal to the same filter made now
                    putIfNotEmpty(state, KEY_POPUP_TEXT1, popup.text1());
                    putIfNotEmpty(state, KEY_POPUP_TEXT2, popup.text2());
                    item.put(KEY_POPUP, state);
                }
            }
            if (order) {
                item.put(KEY_ORDER, constraint.getOrderPosition());
                if (constraint.isOrderDescending()) {
                    item.put(KEY_DESCENDING, true);
                }
            }
            result.add(item);
        }
        return result;
    }

    /**
     * Puts the conditions (and, for a saved filter, the sort) of an entry into a filter of the result's columns.
     *
     * @param target    a filter of the result's columns with no conditions (and no sort, to take the entry's sort)
     * @param withOrder take the entry's sort too
     */
    @NotNull
    static Restored restore(
        @NotNull Entry entry,
        @NotNull DBDDataFilter target,
        @NotNull DBDAttributeBinding[] bindings,
        @NotNull DBCExecutionContext context,
        boolean withOrder
    ) {
        List<String> missing = new ArrayList<>();
        // Sorted columns by their saved position, renumbered from 1 (columns may be missing)
        List<Object[]> orders = new ArrayList<>();
        try (DBCSession session = context.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Restore saved filter")) { //$NON-NLS-1$
            for (Object item : entry.constraints()) {
                if (!(item instanceof Map<?, ?> stored)) {
                    continue;
                }
                String column = CommonUtils.toString(stored.get(KEY_COLUMN));
                DBDAttributeBinding binding = findBinding(bindings, column);
                DBDAttributeConstraint constraint = binding == null ? null : target.getConstraint(binding);
                if (constraint == null) {
                    missing.add(column);
                    continue;
                }
                restoreCondition(session, binding, constraint, stored);
                int position = CommonUtils.toInt(stored.get(KEY_ORDER));
                if (withOrder && position > 0) {
                    orders.add(new Object[]{constraint, position, CommonUtils.toBoolean(stored.get(KEY_DESCENDING))});
                }
            }
        }
        orders.sort(Comparator.comparingInt(order -> (Integer) order[1]));
        for (int i = 0; i < orders.size(); i++) {
            DBDAttributeConstraint constraint = (DBDAttributeConstraint) orders.get(i)[0];
            constraint.setOrderPosition(i + 1);
            constraint.setOrderDescending((Boolean) orders.get(i)[2]);
        }
        if (withOrder) {
            // The saved sort replaces the query's own ORDER BY text, as a header sort does
            target.setOrder(null);
        }
        return new Restored(target, missing);
    }

    /**
     * The result column of a stored column name (the same name, else the same name in another case); virtual columns
     * take no conditions.
     */
    @Nullable
    private static DBDAttributeBinding findBinding(@NotNull DBDAttributeBinding[] bindings, @NotNull String name) {
        DBDAttributeBinding other = null;
        for (DBDAttributeBinding binding : bindings) {
            if (binding.isCustom()) {
                continue;
            }
            if (binding.getName().equals(name)) {
                return binding;
            }
            if (other == null && binding.getName().equalsIgnoreCase(name)) {
                other = binding;
            }
        }
        return other;
    }

    private static void restoreCondition(
        @NotNull DBCSession session,
        @NotNull DBDAttributeBinding binding,
        @NotNull DBDAttributeConstraint constraint,
        @NotNull Map<?, ?> stored
    ) {
        String criteria = CommonUtils.toString(stored.get(KEY_CRITERIA), null);
        String operatorName = CommonUtils.toString(stored.get(KEY_OPERATOR), null);
        if (!CommonUtils.isEmpty(criteria)) {
            constraint.setCriteria(criteria);
        } else if (operatorName != null) {
            DBCLogicalOperator operator = CommonUtils.valueOf(DBCLogicalOperator.class, operatorName, null);
            if (operator == null) {
                return;
            }
            constraint.setOperator(operator);
            constraint.setReverseOperator(CommonUtils.toBoolean(stored.get(KEY_REVERSE)));
            constraint.setValue(decodeValue(session, binding, stored.get(KEY_VALUE)));
        } else {
            return;
        }
        if (stored.get(KEY_POPUP) instanceof Map<?, ?> popup) {
            ResultSetColumnFilterPopup.Op op = CommonUtils.valueOf(ResultSetColumnFilterPopup.Op.class,
                CommonUtils.toString(popup.get(KEY_POPUP_OP)), null);
            if (op != null) {
                constraint.setOption(ResultSetColumnFilterPopup.OPTION_CONDITION, new ResultSetColumnFilterPopup.Condition(op,
                    CommonUtils.toString(popup.get(KEY_POPUP_TEXT1)), CommonUtils.toString(popup.get(KEY_POPUP_TEXT2))));
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Values

    @Nullable
    private static Object encodeValue(@NotNull DBDAttributeBinding binding, @Nullable Object value) {
        if (value instanceof Object[] array) {
            // An IN list of the column filter popup; NULL may be among the values
            List<Object> list = new ArrayList<>(array.length);
            for (Object item : array) {
                list.add(encodeScalar(binding, item));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(KEY_LIST, list);
            return result;
        }
        return encodeScalar(binding, value);
    }

    @Nullable
    private static Map<String, Object> encodeScalar(@NotNull DBDAttributeBinding binding, @Nullable Object value) {
        if (value == null) {
            return null;
        }
        String kind;
        String text;
        if (value instanceof String string) {
            kind = KIND_STRING;
            text = string;
        } else if (value instanceof Boolean bool) {
            kind = KIND_BOOLEAN;
            text = bool.toString();
        } else if (value instanceof Number number) {
            kind = KIND_NUMBER;
            text = number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString();
        } else {
            // Dates and other values: the text the column's value handler makes and reads back (as the cell editor)
            kind = KIND_TEXT;
            text = binding.getValueHandler().getValueDisplayString(binding, value, DBDDisplayFormat.EDIT);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(KEY_KIND, kind);
        putIfNotEmpty(result, KEY_TEXT, text);
        return result;
    }

    private static void putIfNotEmpty(@NotNull Map<String, Object> map, @NotNull String key, @Nullable String text) {
        if (!CommonUtils.isEmpty(text)) {
            map.put(key, text);
        }
    }

    @Nullable
    private static Object decodeValue(@NotNull DBCSession session, @NotNull DBDAttributeBinding binding, @Nullable Object stored) {
        if (stored instanceof Map<?, ?> map && map.get(KEY_LIST) instanceof List<?> list) {
            Object[] values = new Object[list.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = decodeScalar(session, binding, list.get(i));
            }
            return values;
        }
        return decodeScalar(session, binding, stored);
    }

    @Nullable
    private static Object decodeScalar(@NotNull DBCSession session, @NotNull DBDAttributeBinding binding, @Nullable Object stored) {
        if (!(stored instanceof Map<?, ?> map)) {
            return null;
        }
        // An empty text is not written to the settings file
        String text = CommonUtils.toString(map.get(KEY_TEXT));
        String kind = CommonUtils.toString(map.get(KEY_KIND));
        DBDValueHandler handler = binding.getValueHandler();
        try {
            return switch (kind) {
                case KIND_STRING -> text;
                case KIND_BOOLEAN -> Boolean.valueOf(text);
                case KIND_NUMBER -> {
                    Object number;
                    try {
                        number = new BigDecimal(text);
                    } catch (NumberFormatException e) {
                        // NaN, Infinity
                        number = text;
                    }
                    yield handler.getValueFromObject(session, binding, number, false, true);
                }
                default -> handler.getValueFromObject(session, binding, text, false, true);
            };
        } catch (DBCException | RuntimeException e) {
            // The database converts the text, if it can
            log.debug("Can't make the filter value of " + binding.getName() + " from '" + text + "'", e);
            return text;
        }
    }

    /**
     * A filter text on one line for menus and labels, cut with an ellipsis.
     */
    @NotNull
    static String shorten(@NotNull String text, int max) {
        String line = text.replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
        return line.length() <= max ? line : line.substring(0, max - 1) + "…"; //$NON-NLS-1$
    }

    @NotNull
    private static String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
}
