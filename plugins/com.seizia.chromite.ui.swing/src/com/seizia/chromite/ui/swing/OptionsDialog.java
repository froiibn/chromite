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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPIdentifierCase;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.PrefUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tools &gt; Options (SSMS): 기능별 설정을 한곳에서 바꾸는 대화상자.
 *
 * 설계: 이 대화상자는 설정을 따로 저장하지 않고, 각 기능이 가진 설정의 저장 위치와 적용 방법을 그대로 쓴다.
 * <ul>
 *     <li>Theme, Icon style: {@link ChromiteTheme#apply()}, {@link UiIconStyle#apply()}(View &gt; Theme 메뉴와 같음, 실행 중 바로 바뀌고 저장).</li>
 *     <li>Auto-complete while typing, Completion delay: {@link SqlCompletionPopup}의 설정(Query &gt; Auto-Complete While Typing과 같은 키).</li>
 *     <li>Maximum rows: 실행 엔진이 읽는 {@link ModelPreferences#RESULT_SET_MAX_ROWS}. 연결의 설정 저장소는 플랫폼 설정 저장소를 부모로 가지므로
 *     ({@code DataSourcePreferenceStore}), 플랫폼 저장소에 쓰면 연결별 값을 따로 두지 않은 모든 연결에 적용된다. 0은 제한 없음.</li>
 *     <li>Editor 탭: SQL 편집기 글꼴과 크기({@link EditorFont}). Editor Colors 탭: 테마마다 구문·편집기 색({@link EditorColors}),
 *     SWT 제품의 Colors and Fonts &gt; SQL Editor에 해당한다. 두 탭의 미리 보기는 저장하지 않은 값(한글 포함)을 바로 보여 주고,
 *     OK를 누르면 저장해서 열린 모든 편집기와 DDL 탭에 적용한다.</li>
 *     <li>Editor 탭의 Formatting: SQL 서식기 종류와 키워드 대소문자({@link SqlEditCommands}, SWT 서식 설정과 같은 플랫폼 설정 키).
 *     General 탭의 Query History: 쿼리 기록 보관 일수({@link QueryHistory}). Templates 탭: SQL 템플릿({@link SqlTemplatesPage}).</li>
 *     <li>Query Library 기록 보존(실행 수, 일수): {@link QueryLibraryHistory}의 설정. 다음 기록을 덧붙일 때 줄인다.</li>
 * </ul>
 * OK를 누를 때 바뀐 값만 적용한다. 읽기는 열 때 한 번 한다.
 *
 * 변경 이력:
 * - 2026-11-06 P1: 최초 작성 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 General·Editor·Editor Colors 탭: 편집기 글꼴·크기와 테마별 편집기 색, 미리 보기 (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 * - 2026-11-06 화면 문구에 다른 제품 이름을 쓰지 않음: "Reset to Defaults" (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 * - 2026-11-06 SQL 서식(서식기, 키워드 대소문자), 쿼리 기록 보관 일수, Templates 탭 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 Query Library ③: 예약 실행 기록 보존 설정 (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 자동완성에 프로시저·함수 제안 설정 (docs/chromite/swing-followup-fixes.md "자동완성 프로시저")
 * - 2026-11-07 날짜/시간 값을 데이터베이스 형식 그대로 보이는 설정(SWT "Disable date/time formatting") (docs/chromite/swing-followup-fixes.md "날짜/시간 소수 초")
 */
final class OptionsDialog extends JDialog {

    private static final int MAX_DELAY = 5000;
    private static final String PREVIEW_TEXT = """
        -- 한글 주석: editor preview
        DECLARE @cnt INT = 0;
        SELECT a.AlbumId, COUNT(*) AS cnt, UPPER(a.Title), DATEDIFF(SECOND, a.d1, a.d2)
        FROM Album a
        WHERE a.Title LIKE N'%한글%' AND a.AlbumId > 42.5 OR a.Note IS NULL;"""; //$NON-NLS-1$

    private final JComboBox<ChromiteTheme> themeCombo = new JComboBox<>(ChromiteTheme.values());
    private final JComboBox<UiIconStyle> iconStyleCombo = new JComboBox<>(UiIconStyle.values());
    private final JCheckBox autoCompleteBox = new JCheckBox(CoreMessages.options_auto_complete);
    private final JCheckBox showProceduresBox = new JCheckBox(CoreMessages.options_show_procedures);
    private final JSpinner delaySpinner;
    private final JSpinner maxRowsSpinner;
    private final JCheckBox nativeDateTimeBox = new JCheckBox(CoreMessages.options_native_datetime);
    private final JSpinner historyDaysSpinner = new JSpinner(new SpinnerNumberModel(
        Math.min(QueryHistory.getRetentionDays(), QueryHistory.MAX_RETENTION_DAYS), 0, QueryHistory.MAX_RETENTION_DAYS, 1));
    private final JComboBox<String> formatterCombo = new JComboBox<>(new String[]{CoreMessages.options_formatter_default, CoreMessages.options_formatter_compact});
    private final JComboBox<String> keywordCaseCombo = new JComboBox<>(new String[]{
        CoreMessages.options_case_database, CoreMessages.options_case_upper, CoreMessages.options_case_lower, CoreMessages.options_case_as_typed});
    private final SqlTemplatesPage templatesPage = new SqlTemplatesPage();

    // Editor tab
    private final JComboBox<String> fontFamilyCombo;
    private final JSpinner fontSizeSpinner;
    private final SqlEditorPanel.SqlTextArea fontPreview = createPreview();

    // Editor Colors tab: edited colors of both themes, saved on OK
    private final Map<ChromiteTheme, Map<EditorColors.Item, EditorColors.Setting>> colors = new EnumMap<>(ChromiteTheme.class);
    private final JComboBox<ChromiteTheme> colorThemeCombo = new JComboBox<>(ChromiteTheme.values());
    private final JList<EditorColors.Item> colorItemList = new JList<>(EditorColors.Item.values());
    private final JButton colorButton = new JButton(CoreMessages.options_color);
    private final JCheckBox boldBox = new JCheckBox(CoreMessages.options_bold);
    private final JCheckBox italicBox = new JCheckBox(CoreMessages.options_italic);
    private final SqlEditorPanel.SqlTextArea colorPreview = createPreview();
    private boolean updatingColorControls;
    private final JSpinner libraryHistoryRunsSpinner;
    private final JSpinner libraryHistoryDaysSpinner;

    private OptionsDialog(@NotNull Frame owner) {
        super(owner, CoreMessages.options_title, true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        themeCombo.setSelectedItem(ChromiteTheme.getCurrent());
        themeCombo.setRenderer(new LabelRenderer());
        iconStyleCombo.setSelectedItem(UiIconStyle.getCurrent());
        iconStyleCombo.setRenderer(new LabelRenderer());
        autoCompleteBox.setSelected(SqlCompletionPopup.isAutoActivateOnLetters());
        showProceduresBox.setSelected(SqlCompletionPopup.isShowProcedures());
        showProceduresBox.setToolTipText(CoreMessages.options_show_procedures_tip);
        delaySpinner = new JSpinner(new SpinnerNumberModel(
            Math.clamp(SqlCompletionPopup.getAutoActivationDelay(), 0, MAX_DELAY), 0, MAX_DELAY, 50));
        maxRowsSpinner = new JSpinner(new SpinnerNumberModel(
            Math.max(0, getPlatformPreferences().getInt(ModelPreferences.RESULT_SET_MAX_ROWS)), 0, Integer.MAX_VALUE, 100));

        fontFamilyCombo = new JComboBox<>(EditorFont.getMonospacedFamilies().toArray(String[]::new));
        fontFamilyCombo.setSelectedItem(EditorFont.getSavedFamily());
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(EditorFont.getSavedSize(), EditorFont.MIN_SIZE, EditorFont.MAX_SIZE, 1));
        for (ChromiteTheme theme : ChromiteTheme.values()) {
            colors.put(theme, EditorColors.getSaved(theme));
        }
        libraryHistoryRunsSpinner = new JSpinner(new SpinnerNumberModel(QueryLibraryHistory.getMaxRuns(), 1, 100000, 10));
        libraryHistoryDaysSpinner = new JSpinner(new SpinnerNumberModel(QueryLibraryHistory.getMaxDays(), 1, 3650, 1));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = addSection(form, row, CoreMessages.options_environment);
        row = addRow(form, row, CoreMessages.options_theme, themeCombo, null);
        row = addRow(form, row, CoreMessages.options_icon_style, iconStyleCombo, null);
        row = addSection(form, row, CoreMessages.options_query_editor);
        row = addRow(form, row, null, autoCompleteBox, null);
        row = addRow(form, row, CoreMessages.options_completion_delay, delaySpinner, CoreMessages.options_completion_delay_hint);
        row = addRow(form, row, null, showProceduresBox, null);
        row = addSection(form, row, CoreMessages.options_query_results);
        row = addRow(form, row, CoreMessages.options_max_rows, maxRowsSpinner, CoreMessages.options_max_rows_hint);
        // The model setting of the SWT "Disable date/time formatting" (Data Formats page); connections without their own
        // value inherit it. The default data formatter profile prints milliseconds only (yyyy-MM-dd HH:mm:ss.SSS), so the
        // grid and the cell editor drop the micro- and nanoseconds of timestamp values unless the driver's text is used.
        nativeDateTimeBox.setSelected(getPlatformPreferences().getBoolean(ModelPreferences.RESULT_NATIVE_DATETIME_FORMAT));
        nativeDateTimeBox.setToolTipText(CoreMessages.options_native_datetime_tip);
        row = addRow(form, row, null, nativeDateTimeBox, null);
        row = addSection(form, row, CoreMessages.options_query_history);
        row = addRow(form, row, CoreMessages.options_keep_history, historyDaysSpinner, CoreMessages.options_keep_history_hint);
        row = addSection(form, row, CoreMessages.options_query_library);
        row = addRow(form, row, CoreMessages.options_keep_runs, libraryHistoryRunsSpinner, CoreMessages.options_keep_runs_hint);
        addRow(form, row, CoreMessages.options_keep_days, libraryHistoryDaysSpinner, CoreMessages.options_keep_days_hint);

        JButton okButton = new JButton(CoreMessages.common_ok);
        okButton.addActionListener(e -> {
            apply();
            dispose();
        });
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, 20));
        // Dialog insets 10/12, groups 20 apart (design-spec space tokens)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(CoreMessages.options_general, wrapPage(form));
        tabs.addTab(CoreMessages.options_editor, createEditorPage());
        tabs.addTab(CoreMessages.options_editor_colors, createColorsPage());
        tabs.addTab(CoreMessages.options_templates, templatesPage);
        content.add(tabs, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    static void open(@NotNull Frame owner) {
        new OptionsDialog(owner).setVisible(true);
    }

    /**
     * A tab page: the form at the top, below the tabs.
     */
    @NotNull
    private static JComponent wrapPage(@NotNull JComponent form) {
        JPanel page = new JPanel(new BorderLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        page.add(form, BorderLayout.NORTH);
        return page;
    }

    @NotNull
    private JComponent createEditorPage() {
        fontFamilyCombo.addActionListener(e -> updatePreviews());
        fontSizeSpinner.addChangeListener(e -> updatePreviews());
        JPanel form = new JPanel(new GridBagLayout());
        int row = addSection(form, 0, CoreMessages.options_font);
        row = addRow(form, row, CoreMessages.options_family, fontFamilyCombo, NLS.bind(CoreMessages.options_family_hint, EditorFont.DEFAULT_FAMILY));
        row = addRow(form, row, CoreMessages.options_size, fontSizeSpinner, NLS.bind(CoreMessages.options_size_hint, EditorFont.DEFAULT_SIZE));
        formatterCombo.setSelectedIndex(SqlEditCommands.FORMATTER_COMPACT.equals(SqlEditCommands.getFormatterId()) ? 1 : 0);
        DBPIdentifierCase keywordCase = SqlEditCommands.getKeywordCase();
        keywordCaseCombo.setSelectedIndex(keywordCase == null ? 0 : switch (keywordCase) {
            case UPPER -> 1;
            case LOWER -> 2;
            case MIXED -> 3;
        });
        row = addSection(form, row, CoreMessages.options_formatting);
        row = addRow(form, row, CoreMessages.options_style, formatterCombo, CoreMessages.options_style_hint);
        row = addRow(form, row, CoreMessages.options_keyword_case, keywordCaseCombo, CoreMessages.options_keyword_case_hint);
        addPreview(form, row, fontPreview);
        updatePreviews();
        return wrapPage(form);
    }

    @NotNull
    private JComponent createColorsPage() {
        colorThemeCombo.setSelectedItem(ChromiteTheme.getCurrent());
        colorThemeCombo.setRenderer(new LabelRenderer());
        colorThemeCombo.addActionListener(e -> {
            colorItemList.repaint();
            updateColorControls();
            updatePreviews();
        });
        JButton resetButton = new JButton(CoreMessages.options_reset_defaults);
        resetButton.setToolTipText(CoreMessages.options_reset_defaults_tip);
        resetButton.addActionListener(e -> {
            colors.put(getColorTheme(), EditorColors.getDefaults(getColorTheme()));
            colorItemList.repaint();
            updateColorControls();
            updatePreviews();
        });

        colorItemList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        colorItemList.setVisibleRowCount(EditorColors.Item.values().length);
        colorItemList.setCellRenderer(new ColorItemRenderer());
        colorItemList.addListSelectionListener(e -> updateColorControls());
        colorItemList.setSelectedIndex(0);
        colorButton.addActionListener(e -> chooseColor());
        boldBox.addActionListener(e -> updateFontStyle());
        italicBox.addActionListener(e -> updateFontStyle());

        JPanel themeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        themeRow.add(new JLabel(CoreMessages.options_theme));
        themeRow.add(colorThemeCombo);
        themeRow.add(resetButton);

        JPanel itemControls = new JPanel(new GridLayout(0, 1, 0, 6));
        itemControls.add(colorButton);
        itemControls.add(boldBox);
        itemControls.add(italicBox);
        JPanel itemControlsTop = new JPanel(new BorderLayout());
        itemControlsTop.add(itemControls, BorderLayout.NORTH);

        JPanel items = new JPanel(new BorderLayout(12, 0));
        items.add(new JScrollPane(colorItemList), BorderLayout.CENTER);
        items.add(itemControlsTop, BorderLayout.EAST);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 6, 0);
        form.add(themeRow, c);
        c.gridy = 1;
        form.add(items, c);
        addPreview(form, 2, colorPreview);
        updateColorControls();
        updatePreviews();
        return wrapPage(form);
    }

    /**
     * Preview section: a read-only SQL text area with Hangul, keywords, functions, operators, strings and numbers.
     */
    private static void addPreview(@NotNull JPanel form, int row, @NotNull SqlEditorPanel.SqlTextArea preview) {
        row = addSection(form, row, CoreMessages.options_preview);
        JScrollPane scrollPane = new JScrollPane(preview);
        scrollPane.setPreferredSize(UIScale.scale(new Dimension(640, 130)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        form.add(scrollPane, c);
    }

    @NotNull
    private static SqlEditorPanel.SqlTextArea createPreview() {
        SqlEditorPanel.SqlTextArea preview = new SqlEditorPanel.SqlTextArea();
        preview.setText(PREVIEW_TEXT);
        preview.setEditable(false);
        // Current line and selection colors are visible: "Album" selected, the caret on its line
        int albumStart = PREVIEW_TEXT.indexOf("Album a"); //$NON-NLS-1$
        preview.select(albumStart, albumStart + "Album".length()); //$NON-NLS-1$
        return preview;
    }

    /**
     * Shows the unsaved font and colors: the Editor tab in the current theme, the Editor Colors tab in the theme being edited.
     */
    private void updatePreviews() {
        String family = Objects.requireNonNullElse((String) fontFamilyCombo.getSelectedItem(), EditorFont.getDefaultFamily());
        Font font = EditorFont.create(family, ((Number) fontSizeSpinner.getValue()).intValue());
        ChromiteTheme current = ChromiteTheme.getCurrent();
        fontPreview.applyTheme(EditorColors.createTheme(current, colors.get(current), font), font);
        ChromiteTheme edited = getColorTheme();
        Theme theme = EditorColors.createTheme(edited, colors.get(edited), font);
        colorPreview.applyTheme(theme, font);
        // A text area without focus hides its selection
        fontPreview.getCaret().setSelectionVisible(true);
        colorPreview.getCaret().setSelectionVisible(true);
    }

    @NotNull
    private ChromiteTheme getColorTheme() {
        return colorThemeCombo.getSelectedItem() instanceof ChromiteTheme theme ? theme : ChromiteTheme.getCurrent();
    }

    private void updateColorControls() {
        EditorColors.Item item = colorItemList.getSelectedValue();
        updatingColorControls = true;
        try {
            colorButton.setEnabled(item != null);
            boolean token = item != null && item.isToken();
            boldBox.setEnabled(token);
            italicBox.setEnabled(token);
            EditorColors.Setting setting = item == null ? null : colors.get(getColorTheme()).get(item);
            colorButton.setIcon(setting == null ? null : new ColorIcon(setting.color()));
            boldBox.setSelected(setting != null && setting.bold());
            italicBox.setSelected(setting != null && setting.italic());
        } finally {
            updatingColorControls = false;
        }
    }

    private void chooseColor() {
        EditorColors.Item item = colorItemList.getSelectedValue();
        if (item == null) {
            return;
        }
        EditorColors.Setting setting = colors.get(getColorTheme()).get(item);
        Color color = JColorChooser.showDialog(this, item.getLabel() + " (" + getColorTheme().getLabel() + ")", setting.color());
        if (color != null) {
            // Opaque: the editor paints text and backgrounds without alpha
            setColorSetting(item, new EditorColors.Setting(new Color(color.getRGB() & 0xFFFFFF), setting.bold(), setting.italic()));
        }
    }

    private void updateFontStyle() {
        EditorColors.Item item = colorItemList.getSelectedValue();
        if (item != null && !updatingColorControls) {
            EditorColors.Setting setting = colors.get(getColorTheme()).get(item);
            setColorSetting(item, new EditorColors.Setting(setting.color(), boldBox.isSelected(), italicBox.isSelected()));
        }
    }

    private void setColorSetting(@NotNull EditorColors.Item item, @NotNull EditorColors.Setting setting) {
        colors.get(getColorTheme()).put(item, setting);
        colorItemList.repaint();
        updateColorControls();
        updatePreviews();
    }

    /**
     * Section title: font.ui.semibold with a border.subtle line, 20 above (except the first) and 6 below.
     */
    private static int addSection(@NotNull JPanel form, int row, @NotNull String title) {
        JLabel label = new JLabel(title);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        JPanel section = new JPanel(new BorderLayout(8, 0));
        section.add(label, BorderLayout.WEST);
        section.add(new JSeparator(), BorderLayout.CENTER);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(row == 0 ? 0 : 20, 0, 6, 0);
        form.add(section, c);
        return row + 1;
    }

    /**
     * Label (no colon, SSMS), control, and an optional hint in text.secondary; space.6 between them and between rows.
     */
    private static int addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control, @Nullable String hint) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 6);
        if (label != null) {
            c.gridx = 0;
            form.add(new JLabel(label), c);
        }
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        if (control instanceof JSpinner spinner) {
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(8);
            c.fill = GridBagConstraints.NONE;
        }
        form.add(control, c);
        if (hint != null) {
            JLabel hintLabel = new JLabel(hint);
            hintLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            c.gridx = 2;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 1;
            form.add(hintLabel, c);
        }
        return row + 1;
    }

    private void apply() {
        if (autoCompleteBox.isSelected() != SqlCompletionPopup.isAutoActivateOnLetters()) {
            SqlCompletionPopup.setAutoActivateOnLetters(autoCompleteBox.isSelected());
        }
        int delay = ((Number) delaySpinner.getValue()).intValue();
        if (delay != SqlCompletionPopup.getAutoActivationDelay()) {
            SqlCompletionPopup.setAutoActivationDelay(delay);
        }
        if (showProceduresBox.isSelected() != SqlCompletionPopup.isShowProcedures()) {
            SqlCompletionPopup.setShowProcedures(showProceduresBox.isSelected());
        }
        DBPPreferenceStore platformPreferences = getPlatformPreferences();
        int maxRows = ((Number) maxRowsSpinner.getValue()).intValue();
        if (maxRows != platformPreferences.getInt(ModelPreferences.RESULT_SET_MAX_ROWS)) {
            platformPreferences.setValue(ModelPreferences.RESULT_SET_MAX_ROWS, maxRows);
            PrefUtils.savePreferenceStore(platformPreferences);
        }
        if (nativeDateTimeBox.isSelected() != platformPreferences.getBoolean(ModelPreferences.RESULT_NATIVE_DATETIME_FORMAT)) {
            platformPreferences.setValue(ModelPreferences.RESULT_NATIVE_DATETIME_FORMAT, nativeDateTimeBox.isSelected());
            PrefUtils.savePreferenceStore(platformPreferences);
        }
        int historyDays = ((Number) historyDaysSpinner.getValue()).intValue();
        if (historyDays != QueryHistory.getRetentionDays()) {
            QueryHistory.setRetentionDays(historyDays);
        }
        String formatterId = formatterCombo.getSelectedIndex() == 1 ? SqlEditCommands.FORMATTER_COMPACT : SqlEditCommands.FORMATTER_DEFAULT;
        DBPIdentifierCase keywordCase = switch (keywordCaseCombo.getSelectedIndex()) {
            case 1 -> DBPIdentifierCase.UPPER;
            case 2 -> DBPIdentifierCase.LOWER;
            case 3 -> DBPIdentifierCase.MIXED;
            default -> null;
        };
        if (!formatterId.equals(SqlEditCommands.getFormatterId()) || keywordCase != SqlEditCommands.getKeywordCase()) {
            SqlEditCommands.saveFormatSettings(formatterId, keywordCase);
        }
        templatesPage.apply();
        boolean editorChanged = false;
        String family = (String) fontFamilyCombo.getSelectedItem();
        int size = ((Number) fontSizeSpinner.getValue()).intValue();
        if (family != null && (!family.equals(EditorFont.getSavedFamily()) || size != EditorFont.getSavedSize())) {
            EditorFont.save(family, size);
            editorChanged = true;
        }
        for (ChromiteTheme theme : ChromiteTheme.values()) {
            if (!colors.get(theme).equals(EditorColors.getSaved(theme))) {
                EditorColors.save(theme, colors.get(theme));
                editorChanged = true;
            }
        }
        if (editorChanged) {
            SqlEditorPanel.SqlTextArea.applyThemeToAll();
        }
        int libraryHistoryRuns = ((Number) libraryHistoryRunsSpinner.getValue()).intValue();
        int libraryHistoryDays = ((Number) libraryHistoryDaysSpinner.getValue()).intValue();
        if (libraryHistoryRuns != QueryLibraryHistory.getMaxRuns() || libraryHistoryDays != QueryLibraryHistory.getMaxDays()) {
            QueryLibraryHistory.setRetention(libraryHistoryRuns, libraryHistoryDays);
        }
        // Theme last: it updates every window, this dialog is about to close
        if (iconStyleCombo.getSelectedItem() instanceof UiIconStyle iconStyle && iconStyle != UiIconStyle.getCurrent()) {
            iconStyle.apply();
        }
        if (themeCombo.getSelectedItem() instanceof ChromiteTheme theme && theme != ChromiteTheme.getCurrent()) {
            theme.apply();
        }
    }

    @NotNull
    private static DBPPreferenceStore getPlatformPreferences() {
        return DBWorkbench.getPlatform().getPreferenceStore();
    }

    /**
     * Editor color items: a swatch of the edited color and the label.
     */
    private final class ColorItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof EditorColors.Item item) {
                setText(item.getLabel());
                setIcon(new ColorIcon(colors.get(getColorTheme()).get(item).color()));
                setIconTextGap(8);
            }
            return this;
        }
    }

    /**
     * A color swatch with a border, so that colors close to the background stay visible.
     */
    private record ColorIcon(@NotNull Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            int size = getIconWidth();
            g.setColor(color);
            g.fillRect(x, y, size, size);
            g.setColor(UIManager.getColor("Component.borderColor")); //$NON-NLS-1$
            g.drawRect(x, y, size - 1, size - 1);
        }

        @Override
        public int getIconWidth() {
            return UIScale.scale(14);
        }

        @Override
        public int getIconHeight() {
            return getIconWidth();
        }
    }

    /**
     * Theme and icon style items show their labels.
     */
    private static final class LabelRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ChromiteTheme theme) {
                setText(theme.getLabel());
            } else if (value instanceof UiIconStyle iconStyle) {
                setText(iconStyle.getLabel());
            }
            return this;
        }
    }
}
