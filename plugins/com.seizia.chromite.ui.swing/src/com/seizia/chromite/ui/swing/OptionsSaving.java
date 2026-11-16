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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.impl.preferences.AbstractPreferenceStore;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * The save standard of the Options pages (the same as Turbrix's #722, STYLE_GUIDE 5.12): toggles apply and save at once; other
 * values wait for the page's Save button; actions (Test, delete, copy) save nothing and use the values on screen.
 *
 * 설계:
 * <ul>
 *     <li>페이지(왼쪽 목록의 한 항목)는 칸({@link Part})으로 이루어진다. 칸은 이름, 저장된 값과 다른 값의 수, 저장, 되돌리기를 안다.
 *     페이지({@link PageState})는 칸의 수를 더해 아래 줄({@link Footer})에 [저장] [되돌리기]와 바뀐 수를 보이고, [저장]은 바뀐 칸을 차례로
 *     저장한다. 한 칸이 실패하면 그 칸의 값은 화면에 남고 이름을 한 줄로 알리며 다른 칸은 저장한다. 성공 알림은 없다(바뀐 수가 사라짐).</li>
 *     <li>모델 키 페이지({@link SettingsPage}, 약 20개)는 페이지 코드를 거의 고치지 않는다. {@link SettingsPagePart}가 페이지의
 *     {@code save(Writer)}를 실제 저장소 대신 {@link StagingStore}(저장소 위의 기록용 층)에 돌려 바뀔 키를 얻는다. 그 수가 바뀐 수다.
 *     토글(값의 일부가 아닌 체크 상자)을 누르면 직전에 센 바뀐 키와 지금 바뀐 키를 비교해 그 사이에 바뀐 키(누른 토글이 바꾼 키)만 바로 쓰고
 *     저장한다. 같이 바뀌는 상자(이름 형식 둘 중 하나처럼 페이지가 다른 상자를 끄는 경우)도 함께 쓰인다.</li>
 *     <li>다시 세기: 페이지의 값 컨트롤(글 칸, 숫자, 선택 상자, 버튼, 표 모델)에 듣는 것을 달고 바뀌면 EDT 다음 차례에 한 번 센다
 *     ({@link #watch}). 표의 셀을 편집하는 동안은 세지 않는다(데이터 형식 페이지의 저장이 편집을 끝내므로). 편집이 끝나면 표 모델이 알린다.</li>
 *     <li>토글은 칸의 바뀐 수에 들지 않는다. 값의 일부인 체크 상자는 {@link OptionsForm#VALUE_CHECK_BOX}로 표시해 값으로 센다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 확인/취소 대신 저장 표준(앱 변경 #59, docs/chromite/swing-options.md "옵션 화면 모양과 저장 (#59)")
 */
final class OptionsSaving {

    private static final Log log = Log.getLog(OptionsSaving.class);

    private OptionsSaving() {
    }

    /**
     * A block of a page: its values, saved by the page's Save button.
     */
    interface Part {
        /**
         * The block's name as the page shows it (named when it can't be saved).
         */
        @NotNull
        String getName();

        /**
         * Counts again (called after a change on the page, before {@link #getChangeCount}); a toggle may be saved here.
         */
        default void update() {
        }

        /**
         * How many of its values differ from what is saved.
         */
        int getChangeCount();

        /**
         * Writes its changed values; throws when they were not taken (the values stay on screen).
         */
        void save() throws Exception;

        /**
         * Puts the saved values back in its fields.
         */
        void revert();
    }

    /**
     * Saves a part's values; throws when they were not taken.
     */
    interface SaveAction {
        void run() throws Exception;
    }

    /**
     * A part made of functions (the blocks of the pages the dialog builds itself).
     */
    @NotNull
    static Part part(@NotNull String name, @NotNull IntSupplier changes, @NotNull SaveAction save, @NotNull Runnable revert) {
        return new Part() {
            @NotNull
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getChangeCount() {
                return changes.getAsInt();
            }

            @Override
            public void save() throws Exception {
                save.run();
            }

            @Override
            public void revert() {
                revert.run();
            }
        };
    }

    /**
     * 1 when a shown value differs from the saved one, otherwise 0 (numbers by their int value: spinners hold Integer or Double).
     */
    static int differs(@Nullable Object shown, @Nullable Object saved) {
        if (shown instanceof Number a && saved instanceof Number b) {
            return a.longValue() == b.longValue() ? 0 : 1;
        }
        return Objects.equals(shown, saved) ? 0 : 1;
    }

    /**
     * The parts of one page, the names of the parts the last Save could not save, and the one listener (the footer).
     */
    static final class PageState {
        private final String title;
        private final List<Part> parts = new ArrayList<>();
        private List<String> failed = List.of();
        private String failure;
        private Runnable listener = () -> {
        };

        PageState(@NotNull String title) {
            this.title = title;
        }

        @NotNull
        String getTitle() {
            return title;
        }

        void add(@NotNull Part part) {
            parts.add(part);
        }

        void addAll(@NotNull List<? extends Part> list) {
            parts.addAll(list);
        }

        boolean hasParts() {
            return !parts.isEmpty();
        }

        void setListener(@NotNull Runnable listener) {
            this.listener = listener;
        }

        /**
         * Counts the parts again and tells the footer.
         */
        void update() {
            for (Part part : parts) {
                try {
                    part.update();
                } catch (RuntimeException e) {
                    log.error("Can't count the changes of " + part.getName(), e); //$NON-NLS-1$
                }
            }
            listener.run();
        }

        int getChangeCount() {
            int count = 0;
            for (Part part : parts) {
                count += part.getChangeCount();
            }
            return count;
        }

        /**
         * Saves every part with changes, one after the other; a part that fails keeps its values and is named.
         *
         * @return whether every part was saved
         */
        boolean save() {
            List<String> names = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            for (Part part : parts) {
                if (part.getChangeCount() == 0) {
                    continue;
                }
                try {
                    part.save();
                } catch (Exception e) {
                    log.error("Can't save the options of " + part.getName(), e); //$NON-NLS-1$
                    names.add(part.getName());
                    reasons.add(part.getName() + ": " + ErrorText.describeLine(e)); //$NON-NLS-1$
                }
            }
            setFailed(names, String.join("\n", reasons)); //$NON-NLS-1$
            update();
            return names.isEmpty();
        }

        /**
         * The saved values back in every part (Revert, Discard).
         */
        void revert() {
            for (Part part : parts) {
                try {
                    part.revert();
                } catch (RuntimeException e) {
                    log.error("Can't revert the options of " + part.getName(), e); //$NON-NLS-1$
                }
            }
            setFailed(List.of(), null);
            update();
        }

        /**
         * A toggle that could not be saved at once is named like a part.
         */
        void toggleFailed(@NotNull String name, @NotNull Throwable error) {
            log.error("Can't save the option " + name, error); //$NON-NLS-1$
            setFailed(List.of(name), name + ": " + ErrorText.describeLine(error)); //$NON-NLS-1$
            listener.run();
        }

        private void setFailed(@NotNull List<String> names, @Nullable String reasons) {
            failed = names;
            failure = reasons;
        }

        @NotNull
        List<String> getFailed() {
            return failed;
        }

        @Nullable
        String getFailure() {
            return failure;
        }
    }

    /**
     * Calls {@code changed} after a value of the component tree changes: text, spinner, combo box, button, table model. Components
     * added later (a table's cell editor) are not watched; the table model tells about the edit when it ends.
     */
    static void watch(@NotNull Component component, @NotNull Runnable changed) {
        switch (component) {
            case JTextComponent text -> text.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    changed.run();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    changed.run();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }
            });
            case JSpinner spinner -> spinner.addChangeListener(e -> changed.run());
            case JComboBox<?> combo -> combo.addActionListener(e -> changed.run());
            case AbstractButton button -> button.addActionListener(e -> changed.run());
            case JTable table -> table.getModel().addTableModelListener(e -> changed.run());
            default -> {
            }
        }
        // A spinner's and a combo box's own editors are watched through them; a table's renderers are not settings
        if (component instanceof Container container && !(component instanceof JSpinner) && !(component instanceof JComboBox)
            && !(component instanceof JTable) && !(component instanceof CellRendererPane)) {
            for (Component child : container.getComponents()) {
                watch(child, changed);
            }
        }
    }

    /**
     * The toggles of a component tree: check boxes and toggle buttons that are not part of a value ({@link OptionsForm#VALUE_CHECK_BOX})
     * and not in a table.
     */
    static void findToggles(@NotNull Component component, @NotNull List<AbstractButton> toggles) {
        if (component instanceof JTable || component instanceof CellRendererPane) {
            return;
        }
        if ((component instanceof JCheckBox || component.getClass() == JToggleButton.class)
            && !Boolean.TRUE.equals(((JComponent) component).getClientProperty(OptionsForm.VALUE_CHECK_BOX))) {
            toggles.add((AbstractButton) component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                findToggles(child, toggles);
            }
        }
    }

    /**
     * Whether a table of the tree is editing a cell (counting would end the edit on pages whose save stops it).
     */
    static boolean isEditingCell(@NotNull Component component) {
        if (component instanceof JTable table) {
            return table.isEditing();
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (isEditingCell(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The part of a model key page: the keys its {@code save(Writer)} would change, and what it saves outside its store
     * ({@link SettingsPage#getOtherChangeCount}). A toggle's keys are written and saved at once.
     */
    static final class SettingsPagePart implements Part {
        private final SettingsPage page;
        private final PageState state;
        // The keys that would change, with their values, at the last count (toggle keys left out once saved)
        private Map<String, Object> pending = Map.of();
        private boolean toggled;
        private String toggleName;

        SettingsPagePart(@NotNull SettingsPage page, @NotNull PageState state) {
            this.page = page;
            this.state = state;
            List<AbstractButton> toggles = new ArrayList<>();
            findToggles(page, toggles);
            for (AbstractButton toggle : toggles) {
                toggle.addActionListener(e -> {
                    toggled = true;
                    toggleName = getToggleText(toggle);
                });
            }
            pending = stage();
        }

        @NotNull
        @Override
        public String getName() {
            return page.getTitle();
        }

        @Override
        public void update() {
            Map<String, Object> now = stage();
            if (toggled) {
                toggled = false;
                Map<String, Object> toggleKeys = new LinkedHashMap<>();
                Set<String> keys = new HashSet<>(now.keySet());
                keys.addAll(pending.keySet());
                for (String key : keys) {
                    if (!Objects.equals(now.get(key), pending.get(key)) && now.containsKey(key)) {
                        toggleKeys.put(key, now.get(key));
                    }
                }
                if (!toggleKeys.isEmpty()) {
                    try {
                        DBPPreferenceStore store = page.getGlobalStore();
                        toggleKeys.forEach((key, value) -> StagingStore.write(store, key, value));
                        PrefUtils.savePreferenceStore(store);
                    } catch (RuntimeException e) {
                        state.toggleFailed(Objects.requireNonNullElse(toggleName, getName()), e);
                    }
                    now = stage();
                }
            }
            pending = now;
        }

        @Override
        public int getChangeCount() {
            return pending.size() + page.getOtherChangeCount();
        }

        @Override
        public void save() throws Exception {
            page.apply();
            pending = stage();
        }

        @Override
        public void revert() {
            page.load(page.getGlobalStore());
            page.revertOther();
            page.updateEnablement();
            pending = stage();
        }

        /**
         * The keys the page's save would change in its global store, with their values ({@link StagingStore#RESET}: back to the default).
         */
        @NotNull
        private Map<String, Object> stage() {
            StagingStore staging = new StagingStore(page.getGlobalStore());
            page.save(new SettingsPage.Writer(staging, false));
            return staging.getChanges();
        }
    }

    /**
     * The text of a toggle for an error line (its accessible name when its text moved to the row name).
     */
    @NotNull
    private static String getToggleText(@NotNull AbstractButton button) {
        String text = button.getText();
        if (text == null || text.isEmpty()) {
            text = button.getAccessibleContext().getAccessibleName();
        }
        return text == null ? "" : text; //$NON-NLS-1$
    }

    /**
     * A layer over a store that records writes instead of making them: reads see the recorded values, the store is not changed.
     */
    static final class StagingStore extends AbstractPreferenceStore {
        // Recorded instead of a value: the key goes back to its default
        static final Object RESET = new Object();

        private final DBPPreferenceStore base;
        private final Map<String, Object> values = new HashMap<>();

        StagingStore(@NotNull DBPPreferenceStore base) {
            this.base = base;
        }

        /**
         * The recorded keys whose value differs from the store's.
         */
        @NotNull
        Map<String, Object> getChanges() {
            Map<String, Object> changes = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                boolean changed = value == RESET ? !base.isDefault(key) && !Objects.equals(base.getString(key), base.getDefaultString(key))
                    : !String.valueOf(value).equals(base.getString(key));
                if (changed) {
                    changes.put(key, value);
                }
            });
            return changes;
        }

        /**
         * Writes a recorded value to a store, with the setter of its type (listeners get the type they expect).
         */
        static void write(@NotNull DBPPreferenceStore store, @NotNull String key, @Nullable Object value) {
            switch (value) {
                case Boolean b -> store.setValue(key, b);
                case Integer i -> store.setValue(key, i);
                case Long l -> store.setValue(key, l);
                case Double d -> store.setValue(key, d);
                case Float f -> store.setValue(key, f);
                case String s -> store.setValue(key, s);
                case null -> store.setValue(key, (String) null);
                default -> store.setToDefault(key);
            }
        }

        @Nullable
        private String get(@NotNull String name) {
            Object value = values.get(name);
            if (value == RESET) {
                return base.getDefaultString(name);
            }
            return value == null ? null : String.valueOf(value);
        }

        @Override
        public boolean contains(@NotNull String name) {
            return values.containsKey(name) ? values.get(name) != RESET : base.contains(name);
        }

        @Override
        public boolean getBoolean(@NotNull String name) {
            return values.containsKey(name) ? toBoolean(get(name)) : base.getBoolean(name);
        }

        @Override
        public double getDouble(@NotNull String name) {
            return values.containsKey(name) ? toDouble(get(name)) : base.getDouble(name);
        }

        @Override
        public float getFloat(@NotNull String name) {
            return values.containsKey(name) ? toFloat(get(name)) : base.getFloat(name);
        }

        @Override
        public int getInt(@NotNull String name) {
            return values.containsKey(name) ? toInt(get(name)) : base.getInt(name);
        }

        @Override
        public long getLong(@NotNull String name) {
            return values.containsKey(name) ? toLong(get(name)) : base.getLong(name);
        }

        @Nullable
        @Override
        public String getString(@NotNull String name) {
            return values.containsKey(name) ? get(name) : base.getString(name);
        }

        @Override
        public boolean getDefaultBoolean(@NotNull String name) {
            return base.getDefaultBoolean(name);
        }

        @Override
        public double getDefaultDouble(@NotNull String name) {
            return base.getDefaultDouble(name);
        }

        @Override
        public float getDefaultFloat(@NotNull String name) {
            return base.getDefaultFloat(name);
        }

        @Override
        public int getDefaultInt(@NotNull String name) {
            return base.getDefaultInt(name);
        }

        @Override
        public long getDefaultLong(@NotNull String name) {
            return base.getDefaultLong(name);
        }

        @Nullable
        @Override
        public String getDefaultString(@NotNull String name) {
            return base.getDefaultString(name);
        }

        @Override
        public boolean isDefault(@NotNull String name) {
            return values.containsKey(name) ? values.get(name) == RESET : base.isDefault(name);
        }

        @Override
        public boolean needsSaving() {
            return !values.isEmpty();
        }

        // Defaults belong to the store's owner, not to a page: not recorded
        @Override
        public void setDefault(@NotNull String name, double value) {
        }

        @Override
        public void setDefault(@NotNull String name, float value) {
        }

        @Override
        public void setDefault(@NotNull String name, int value) {
        }

        @Override
        public void setDefault(@NotNull String name, long value) {
        }

        @Override
        public void setDefault(@NotNull String name, @Nullable String defaultObject) {
        }

        @Override
        public void setDefault(@NotNull String name, boolean value) {
        }

        @Override
        public void setToDefault(@NotNull String name) {
            values.put(name, RESET);
        }

        @Override
        public void setValue(@NotNull String name, double value) {
            values.put(name, value);
        }

        @Override
        public void setValue(@NotNull String name, float value) {
            values.put(name, value);
        }

        @Override
        public void setValue(@NotNull String name, int value) {
            values.put(name, value);
        }

        @Override
        public void setValue(@NotNull String name, long value) {
            values.put(name, value);
        }

        @Override
        public void setValue(@NotNull String name, @Nullable String value) {
            values.put(name, value);
        }

        @Override
        public void setValue(@NotNull String name, boolean value) {
            values.put(name, value);
        }

        @Override
        public void save() {
        }
    }

    /**
     * The fixed line under a page: the failure line (when the last Save could not save a part), then [Save] (primary) [Revert] and the
     * number of changes. Both buttons are on only while something is changed.
     */
    static final class Footer extends JPanel {
        // The primary button look of the theme (Button.default.*) without being the root pane's default button: Enter in a field
        // does not save
        private static final String PRIMARY_BUTTON_STYLE = "background: $Button.default.background; foreground: $Button.default.foreground;" //$NON-NLS-1$
            + " borderColor: $Button.default.borderColor; hoverBackground: $Button.default.hoverBackground;" //$NON-NLS-1$
            + " pressedBackground: $Button.default.pressedBackground; focusedBackground: $Button.default.focusedBackground"; //$NON-NLS-1$
        private final JButton saveButton = new JButton(CoreMessages.common_save);
        private final JButton revertButton = new JButton(CoreMessages.options_revert);
        private final JLabel countLabel = new JLabel();
        private final JLabel failedLabel = new JLabel();
        private PageState state;

        Footer() {
            super(new BorderLayout(0, UIScale.scale(6)));
            setBorder(OptionsForm.createDividerBorder(SwingConstants.TOP,
                new Insets(UIScale.scale(10), UIScale.scale(28), UIScale.scale(10), UIScale.scale(28))));
            saveButton.putClientProperty(FlatClientProperties.STYLE, PRIMARY_BUTTON_STYLE);
            countLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            failedLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
            failedLabel.setVisible(false);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            buttons.add(saveButton);
            buttons.add(Box.createHorizontalStrut(UIScale.scale(6)));
            buttons.add(revertButton);
            buttons.add(Box.createHorizontalStrut(UIScale.scale(12)));
            buttons.add(countLabel);
            add(failedLabel, BorderLayout.NORTH);
            add(buttons, BorderLayout.CENTER);
            saveButton.addActionListener(e -> {
                if (state != null) {
                    state.save();
                }
            });
            revertButton.addActionListener(e -> {
                if (state != null) {
                    state.revert();
                }
            });
        }

        @NotNull
        JButton getSaveButton() {
            return saveButton;
        }

        /**
         * Shows a page's state (hidden for a page without values: toggles and actions only).
         */
        void setPage(@Nullable PageState pageState) {
            state = pageState;
            setVisible(pageState != null && pageState.hasParts());
            refresh();
        }

        void refresh() {
            int count = state == null ? 0 : state.getChangeCount();
            saveButton.setEnabled(count > 0);
            revertButton.setEnabled(count > 0);
            countLabel.setText(count == 0 ? "" : count == 1 ? CoreMessages.options_changes_one //$NON-NLS-1$
                : NLS.bind(CoreMessages.options_changes, count));
            List<String> failed = state == null ? List.of() : state.getFailed();
            failedLabel.setVisible(!failed.isEmpty());
            failedLabel.setText(failed.isEmpty() ? "" : NLS.bind(CoreMessages.options_not_saved, String.join(", ", failed))); //$NON-NLS-1$ //$NON-NLS-2$
            failedLabel.setToolTipText(state == null ? null : state.getFailure());
            revalidate();
            repaint();
        }
    }

    /**
     * The answer to the question before a page with changes is left or the dialog closed.
     */
    enum LeaveAnswer {
        SAVE, DISCARD, CANCEL
    }

    /**
     * Asks Save / Discard / Cancel about the page's unsaved changes; Esc and the window's close are Cancel.
     */
    @NotNull
    static LeaveAnswer askLeave(@NotNull Component parent, @NotNull PageState state) {
        Object[] options = {CoreMessages.common_save, CoreMessages.options_discard, CoreMessages.common_cancel};
        int answer = JOptionPane.showOptionDialog(parent, NLS.bind(CoreMessages.options_leave_message, state.getTitle()),
            CoreMessages.options_leave_title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        return switch (answer) {
            case 0 -> LeaveAnswer.SAVE;
            case 1 -> LeaveAnswer.DISCARD;
            default -> LeaveAnswer.CANCEL;
        };
    }

}
