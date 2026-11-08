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
import com.seizia.chromite.ui.swing.internal.AiMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPIdentifierCase;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.LayerUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Tools &gt; Options (SSMS): 기능별 설정을 한곳에서 바꾸는 대화상자.
 *
 * 설계: 이 대화상자는 설정을 따로 저장하지 않고, 각 기능이 가진 설정의 저장 위치와 적용 방법을 그대로 쓴다.
 * <ul>
 *     <li>Theme, Icon style: {@link ChromiteTheme#apply()}, {@link UiIconStyle#apply()}(View &gt; Theme 메뉴와 같음, 실행 중 바로 바뀌고 저장).</li>
 *     <li>Auto-complete while typing, Completion delay: {@link SqlCompletionPopup}의 설정(Query &gt; Auto-Complete While Typing과 같은 키).</li>
 *     <li>위험한 문 확인(WHERE 없는 UPDATE/DELETE, DROP): {@link SqlExecutionSafety}의 설정(SWT 확인 대화상자와 같은 키).</li>
 *     <li>Editor 탭: SQL 편집기 글꼴과 크기({@link EditorFont}). Editor Colors 탭: 테마마다 구문·편집기 색({@link EditorColors}),
 *     SWT 제품의 Colors and Fonts &gt; SQL Editor에 해당한다. 두 탭의 미리 보기는 저장하지 않은 값(한글 포함)을 바로 보여 주고,
 *     OK를 누르면 저장해서 열린 모든 편집기와 DDL 탭에 적용한다.</li>
 *     <li>Editor 탭의 Formatting: SQL 서식기 종류와 키워드 대소문자({@link SqlEditCommands}, SWT 서식 설정과 같은 플랫폼 설정 키).
 *     General 탭의 Query History: 쿼리 기록 보관 일수({@link QueryHistory}). Templates 탭: SQL 템플릿({@link SqlTemplatesPage}).</li>
 *     <li>Query Library 기록 보존(실행 수, 일수): {@link QueryLibraryHistory}의 설정. 다음 기록을 덧붙일 때 줄인다.
 *     폴더 확인 간격: {@link QueryLibraryWatcher}의 설정. 지금 기다리는 확인이 끝난 뒤부터 적용된다.</li>
 *     <li>Updates 탭: 자동 업데이트 확인 켜기·끄기(업데이트 모델이 설치 단위로 저장), 현재 버전, 지금 확인({@link UpdateOptionsPage}).</li>
 *     <li>연결별 값을 둘 수 있는 모델 키 페이지({@link SettingsPage}): Query Execution({@link SqlExecutionOptionsPage}), Query Results
 *     ({@link ResultSetOptionsPage}: Maximum rows, 가져오기 크기, 이진 값, 숫자·날짜 형식, 저장 전 스크립트), Data Formats({@link DataFormatOptionsPage}),
 *     Errors and Timeouts({@link ConnectionErrorsOptionsPage}), Metadata({@link MetadataOptionsPage}), Client Identification
 *     ({@link ClientIdentificationOptionsPage}), Oracle({@link OracleOptionsPage}). 여기서는 플랫폼 설정 저장소에 쓴다. 연결의 설정 저장소는
 *     이것을 부모로 가지므로({@code DataSourcePreferenceStore}) 연결별 값을 두지 않은 모든 연결에 적용된다. 같은 페이지를 연결 대화상자
 *     Options &gt; Settings가 연결별 값에 쓴다({@link ConnectionSettingsOverrides}).</li>
 *     <li>2026-11-08 남은 키 페이지: 연결별 값도 있는 Binary and LOB({@link ContentOptionsPage}), Code Completion({@link CodeCompletionOptionsPage}),
 *     Data Transfer({@link DataTransferOptionsPage}, 전역 값은 데이터 전송 번들 저장소), Altibase({@link AltibaseOptionsPage}), CUBRID
 *     ({@link CubridOptionsPage})와 전역만 있는 Connections({@link ConnectionsOptionsPage}), Drivers({@link DriversOptionsPage}), Object Explorer
 *     ({@link NavigatorOptionsPage}), Notifications({@link NotificationsOptionsPage}), Logs({@link LogsOptionsPage}), Mimer SQL({@link MimerOptionsPage}).
 *     페이지가 많아 왼쪽 목록은 넘치면 스크롤한다.</li>
 *     <li>Connection Types 탭: 연결 유형 편집({@link ConnectionTypesPage}). 모두 OK에서 저장한다.
 *     다른 탭과 같이 쓰는 행·절 모양은 {@link #addSection}, {@link #addRow}, {@link #wrapPage}다.</li>
 *     <li>페이지는 SSMS Options의 왼쪽 목록처럼 왼쪽 목록에서 고른다. 페이지가 한 줄 탭에 들어가지 않는다. 목록은 {@link JList}, 페이지는
 *     {@link CardLayout}이다({@link JTabbedPane}은 탭을 숨길 수 없어 찾기로 걸러 보일 수 없다). 목록 위에는 찾기 칸을 고정하고, 페이지 이름과
 *     페이지 안의 설정 글자(라벨, 체크 상자, 단추, 테두리 제목, 하위 탭 이름)로 페이지를 거르고, 보이는 페이지에서 일치한 글자를 {@link JLayer}로
 *     칠한다(페이지 코드는 그대로).</li>
 *     <li>페이지 왼쪽 여백은 페이지 자리 한 곳에서 준다. {@link #wrapPage}는 탭이 위에 있는 다른 대화상자도 쓰므로 바꾸지 않는다.</li>
 *     <li>다른 화면이 특정 페이지를 열 때는 화면 이름이 아니라 페이지 식별자({@link #PAGE_AGENT})로 고른다({@link #open(Frame, String)}).</li>
 * </ul>
 * OK를 누를 때 바뀐 값만 적용한다. 읽기는 열 때 한 번 한다. 한 페이지의 저장이 실패해도 나머지 페이지는 저장하고, 실패한 페이지를
 * 마지막에 한 번에 알린다(전에는 예외가 OK 처리를 끊어 뒤 페이지가 말없이 저장되지 않았다).
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
 * - 2026-11-07 Updates 탭: 자동 업데이트 확인, 현재 버전, 지금 확인({@link UpdateOptionsPage}) (docs/chromite/auto-update.md)
 * - 2026-11-07 AI Assistant 탭: 제공자, 도구 권한 정책, MCP 서버, 감사 기록({@link AiOptionsPage}) (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 결과 표 저장 전 스크립트 표시 설정(SWT Data Editor 설정 페이지의 같은 항목) (docs/chromite/swing-frontend.md "저장 전 스크립트 미리 보기")
 * - 2026-11-07 Query editor 절에 위험한 문 확인 설정 두 개 (docs/chromite/swing-sql-exec-safety.md)
 * - 2026-11-07 Query Library 절에 폴더 확인 간격(공유 루트 자동 새로 고침) (docs/chromite/swing-query-library.md "공유")
 * - 2026-11-07 SWT 설정 페이지 중 Swing에서 효과가 있는 키: Query Execution·Connection Types·Oracle 탭, Query Results의 가져오기 크기·이진 값·숫자 형식
 *   (docs/chromite/swing-options.md)
 * - 2026-11-08 General 탭의 Query Results 절을 Query Results 탭({@link ResultSetOptionsPage})으로 옮김. Data Formats·Errors and Timeouts·Metadata·
 *   Client Identification 탭. 탭을 왼쪽 목록으로 (docs/chromite/swing-options.md "연결별 설정")
 * - 2026-11-08 남은 설정 키 페이지 11개, 왼쪽 목록 스크롤 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 * - 2026-11-08 General 탭의 Object Explorer 절: 두 번 클릭(객체, 연결), 긴 폴더에 한 번에 보일 객체 수({@link ExplorerPreferences})
 *   (docs/chromite/swing-explorer-productivity.md)
 * - 2026-11-08 OK가 한 페이지의 예외로 멈추지 않고 모든 페이지를 저장한 뒤 실패한 페이지를 알림(evening #33,
 *   docs/chromite/swing-options.md)
 * - 2026-11-08 왼쪽 목록을 넓히고(최소 220) 찾기 칸, 창을 pack 크기보다 크게, 페이지 왼쪽 여백, 페이지 식별자로 열기(app round 1 #8, #13,
 *   docs/chromite/swing-options.md "목록, 찾기, 페이지 열기")
 */
final class OptionsDialog extends JDialog {

    /**
     * Page id of Chromite Agent, for {@link #open(Frame, String)}. A new dialog shows its first tab, Providers.
     */
    static final String PAGE_AGENT = "agent"; //$NON-NLS-1$

    private static final Log log = Log.getLog(OptionsDialog.class);
    private static final int MAX_DELAY = 5000;
    // The page list is at least this wide (scaled), wider when a title needs it
    private static final int PAGE_LIST_MIN_WIDTH = 220;
    // Between the page list and the page: design-spec group spacing
    private static final int PAGE_GAP = 20;
    // The dialog opens this much larger than its packed size (the packed size stays the minimum), within this share of the screen
    private static final double SIZE_FACTOR = 1.18;
    private static final double MAX_SCREEN_SHARE = 0.9;
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>"); //$NON-NLS-1$
    private static final Pattern WHITESPACE = Pattern.compile("\\s+"); //$NON-NLS-1$
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
    private final JCheckBox confirmDangerousSqlBox = new JCheckBox(CoreMessages.options_confirm_dangerous_sql);
    private final JCheckBox confirmDropSqlBox = new JCheckBox(CoreMessages.options_confirm_drop_sql);
    private final JSpinner delaySpinner;
    private final JSpinner historyDaysSpinner = new JSpinner(new SpinnerNumberModel(
        Math.min(QueryHistory.getRetentionDays(), QueryHistory.MAX_RETENTION_DAYS), 0, QueryHistory.MAX_RETENTION_DAYS, 1));
    private final JComboBox<String> formatterCombo = new JComboBox<>(new String[]{CoreMessages.options_formatter_default, CoreMessages.options_formatter_compact});
    private final JComboBox<String> keywordCaseCombo = new JComboBox<>(new String[]{
        CoreMessages.options_case_database, CoreMessages.options_case_upper, CoreMessages.options_case_lower, CoreMessages.options_case_as_typed});
    // Pages of model keys that connections can also override (connection dialog, Options > Settings)
    private final List<SettingsPage> settingsPages = List.of(
        new SqlExecutionOptionsPage(true), new ResultSetOptionsPage(true), new ContentOptionsPage(true), new DataFormatOptionsPage(true),
        new ConnectionErrorsOptionsPage(true), new MetadataOptionsPage(true), new ClientIdentificationOptionsPage(true),
        new CodeCompletionOptionsPage(true), new DataTransferOptionsPage(true));
    // Pages of model keys read from the global settings only (not in the connection dialog)
    private final List<SettingsPage> globalPages = List.of(
        new ConnectionsOptionsPage(), new DriversOptionsPage(), new NavigatorOptionsPage(), new NotificationsOptionsPage(),
        new LogsOptionsPage());
    private final ConnectionTypesPage connectionTypesPage = new ConnectionTypesPage();
    // Database pages, after Connection Types as before; Altibase and CUBRID connections can override theirs as Oracle ones do
    private final List<SettingsPage> databasePages = List.of(
        new OracleOptionsPage(true), new AltibaseOptionsPage(true), new CubridOptionsPage(true), new MimerOptionsPage());
    private final SqlTemplatesPage templatesPage = new SqlTemplatesPage();
    private final UpdateOptionsPage updatesPage = new UpdateOptionsPage();
    private final AiOptionsPage aiPage = new AiOptionsPage();

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
    private final JSpinner libraryRefreshSpinner;
    // General tab: Object Explorer
    private final JComboBox<ExplorerPreferences.ObjectDoubleClick> objectDoubleClickCombo = new JComboBox<>(ExplorerPreferences.ObjectDoubleClick.values());
    private final JComboBox<ExplorerPreferences.ConnectionDoubleClick> connectionDoubleClickCombo =
        new JComboBox<>(ExplorerPreferences.ConnectionDoubleClick.values());
    private final JSpinner explorerPageSizeSpinner = new JSpinner(new SpinnerNumberModel(ExplorerPreferences.getPageSize(),
        ExplorerPreferences.MIN_PAGE_SIZE, ExplorerPreferences.MAX_PAGE_SIZE, 100));

    // The page list (filtered by the search field) and the shown page
    private final List<Page> pages = new ArrayList<>();
    private final DefaultListModel<Page> pageListModel = new DefaultListModel<>();
    private final JList<Page> pageList = new JList<>(pageListModel);
    private final CardLayout pageCards = new CardLayout();
    private final JPanel pageHolder = new JPanel(pageCards);
    private final JTextField searchField = new JTextField();
    private final JLabel noMatchLabel = new JLabel(CoreMessages.options_search_no_match);
    private final MatchHighlighter highlighter = new MatchHighlighter();
    // Matched texts per page id, for the search of the moment (empty without a search)
    private final Map<String, List<Match>> matches = new HashMap<>();

    private OptionsDialog(@NotNull Frame owner, @Nullable String pageId) {
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

        fontFamilyCombo = new JComboBox<>(EditorFont.getMonospacedFamilies().toArray(String[]::new));
        fontFamilyCombo.setSelectedItem(EditorFont.getSavedFamily());
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(EditorFont.getSavedSize(), EditorFont.MIN_SIZE, EditorFont.MAX_SIZE, 1));
        for (ChromiteTheme theme : ChromiteTheme.values()) {
            colors.put(theme, EditorColors.getSaved(theme));
        }
        libraryHistoryRunsSpinner = new JSpinner(new SpinnerNumberModel(QueryLibraryHistory.getMaxRuns(), 1, 100000, 10));
        libraryHistoryDaysSpinner = new JSpinner(new SpinnerNumberModel(QueryLibraryHistory.getMaxDays(), 1, 3650, 1));
        libraryRefreshSpinner = new JSpinner(new SpinnerNumberModel(QueryLibraryWatcher.getInterval(), 0, QueryLibraryWatcher.MAX_INTERVAL_SECONDS, 5));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = addSection(form, row, CoreMessages.options_environment);
        row = addRow(form, row, CoreMessages.options_theme, themeCombo, null);
        row = addRow(form, row, CoreMessages.options_icon_style, iconStyleCombo, null);
        row = addSection(form, row, ObjectExplorerMessages.oe_options_section);
        objectDoubleClickCombo.setSelectedItem(ExplorerPreferences.getObjectDoubleClick());
        objectDoubleClickCombo.setRenderer(new LabelRenderer());
        connectionDoubleClickCombo.setSelectedItem(ExplorerPreferences.getConnectionDoubleClick());
        connectionDoubleClickCombo.setRenderer(new LabelRenderer());
        row = addRow(form, row, ObjectExplorerMessages.oe_options_object_dblclick, objectDoubleClickCombo, null);
        row = addRow(form, row, ObjectExplorerMessages.oe_options_connection_dblclick, connectionDoubleClickCombo, null);
        row = addRow(form, row, ObjectExplorerMessages.oe_options_page_size, explorerPageSizeSpinner, ObjectExplorerMessages.oe_options_page_size_hint);
        row = addSection(form, row, CoreMessages.options_query_editor);
        row = addRow(form, row, null, autoCompleteBox, null);
        row = addRow(form, row, CoreMessages.options_completion_delay, delaySpinner, CoreMessages.options_completion_delay_hint);
        row = addRow(form, row, null, showProceduresBox, null);
        confirmDangerousSqlBox.setSelected(SqlExecutionSafety.isConfirmDangerousSql());
        confirmDangerousSqlBox.setToolTipText(CoreMessages.options_confirm_dangerous_sql_tip);
        row = addRow(form, row, null, confirmDangerousSqlBox, null);
        confirmDropSqlBox.setSelected(SqlExecutionSafety.isConfirmDropSql());
        confirmDropSqlBox.setToolTipText(CoreMessages.options_confirm_drop_sql_tip);
        row = addRow(form, row, null, confirmDropSqlBox, null);
        row = addSection(form, row, CoreMessages.options_query_history);
        row = addRow(form, row, CoreMessages.options_keep_history, historyDaysSpinner, CoreMessages.options_keep_history_hint);
        row = addSection(form, row, CoreMessages.options_query_library);
        row = addRow(form, row, CoreMessages.options_keep_runs, libraryHistoryRunsSpinner, CoreMessages.options_keep_runs_hint);
        row = addRow(form, row, CoreMessages.options_keep_days, libraryHistoryDaysSpinner, CoreMessages.options_keep_days_hint);
        addRow(form, row, CoreMessages.options_library_refresh, libraryRefreshSpinner, CoreMessages.options_library_refresh_hint);

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
        // Pages on the left, as the SSMS Options tree: more pages than one row of tabs holds. Settings pages are identified by their class.
        addPage("general", CoreMessages.options_general, wrapPage(form)); //$NON-NLS-1$
        addPage("editor", CoreMessages.options_editor, createEditorPage()); //$NON-NLS-1$
        addPage("editor-colors", CoreMessages.options_editor_colors, createColorsPage()); //$NON-NLS-1$
        for (SettingsPage page : settingsPages) {
            addPage(page.getClass().getSimpleName(), page.getTitle(), page);
        }
        for (SettingsPage page : globalPages) {
            addPage(page.getClass().getSimpleName(), page.getTitle(), page);
        }
        addPage("connection-types", SettingsMessages.types_tab, connectionTypesPage); //$NON-NLS-1$
        for (SettingsPage page : databasePages) {
            addPage(page.getClass().getSimpleName(), page.getTitle(), page);
        }
        addPage("templates", CoreMessages.options_templates, templatesPage); //$NON-NLS-1$
        addPage("updates", UpdateMessages.update_options_tab, updatesPage); //$NON-NLS-1$
        addPage(PAGE_AGENT, AiMessages.ai_options_tab, aiPage);
        content.add(createPageList(), BorderLayout.WEST);
        // The left margin of every page, here once: wrapPage serves other dialogs too, and the other pages have none of their own
        pageHolder.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(PAGE_GAP), 0, 0));
        content.add(new JLayer<>(pageHolder, highlighter), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        selectPage(pageId);

        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        }, KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), JComponent.WHEN_IN_FOCUSED_WINDOW);
        // The page list has the focus at first, not the search field above it (Ctrl+F goes there)
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                pageList.requestFocusInWindow();
            }
        });
        pack();
        Dimension packed = getSize();
        setMinimumSize(packed);
        setSize(getOpeningSize(packed, owner));
        setLocationRelativeTo(owner);
    }

    static void open(@NotNull Frame owner) {
        new OptionsDialog(owner, null).setVisible(true);
    }

    /**
     * Opens the dialog at a page, given by its id (not its title, which follows the language).
     */
    static void open(@NotNull Frame owner, @NotNull String pageId) {
        new OptionsDialog(owner, pageId).setVisible(true);
    }

    private void addPage(@NotNull String id, @NotNull String title, @NotNull JComponent component) {
        Page page = new Page(id, title, component);
        pages.add(page);
        pageListModel.addElement(page);
        pageHolder.add(component, id);
    }

    /**
     * The search field fixed above the page list, the "no match" text under it, and the list, at least {@link #PAGE_LIST_MIN_WIDTH}
     * wide and as wide as its longest title with the scroll bar, so that no title is cut.
     */
    @NotNull
    private JComponent createPageList() {
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, CoreMessages.options_search_placeholder);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                filterPages();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                filterPages();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterPages();
            }
        });
        // Esc empties a non-empty search; on an empty one it is disabled, so that the dialog's Esc closes the dialog
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "chromite.options.clearSearch"); //$NON-NLS-1$
        searchField.getActionMap().put("chromite.options.clearSearch", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(ActionEvent e) {
                searchField.setText(""); //$NON-NLS-1$
            }

            @Override
            public boolean isEnabled() {
                return !searchField.getText().isEmpty();
            }
        });
        noMatchLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        noMatchLabel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(4), 0, 0));
        noMatchLabel.setVisible(false);

        pageList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // A few rows: the list scrolls, the pages set the dialog height; the width then counts the scroll bar
        pageList.setVisibleRowCount(8);
        pageList.addListSelectionListener(e -> {
            // The selection is empty while the search rebuilds the list; the shown page stays until one is selected
            Page page = pageList.getSelectedValue();
            if (page != null) {
                pageCards.show(pageHolder, page.id());
                highlighter.setMatches(matches.getOrDefault(page.id(), List.of()));
            }
        });

        JPanel search = new JPanel(new BorderLayout());
        search.add(searchField, BorderLayout.NORTH);
        search.add(noMatchLabel, BorderLayout.CENTER);
        JPanel panel = new JPanel(new BorderLayout(0, UIScale.scale(6)));
        panel.add(search, BorderLayout.NORTH);
        panel.add(new JScrollPane(pageList), BorderLayout.CENTER);
        Dimension size = panel.getPreferredSize();
        size.width = Math.max(size.width, UIScale.scale(PAGE_LIST_MIN_WIDTH));
        panel.setPreferredSize(size);
        return panel;
    }

    /**
     * Selects a page by id, or the first page when there is none with that id.
     */
    private void selectPage(@Nullable String pageId) {
        int index = -1;
        for (int i = 0; i < pageListModel.size(); i++) {
            if (pageListModel.get(i).id().equals(pageId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            if (pageId != null) {
                log.warn("No options page with id " + pageId); //$NON-NLS-1$
            }
            index = 0;
        }
        pageList.setSelectedIndex(index);
        pageList.ensureIndexIsVisible(index);
    }

    /**
     * The packed size enlarged by {@link #SIZE_FACTOR}, within {@link #MAX_SCREEN_SHARE} of the owner's screen, never below the packed size.
     */
    @NotNull
    private static Dimension getOpeningSize(@NotNull Dimension packed, @NotNull Frame owner) {
        GraphicsConfiguration config = owner.getGraphicsConfiguration();
        Rectangle screen = config.getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(config);
        int maxWidth = (int) ((screen.width - screenInsets.left - screenInsets.right) * MAX_SCREEN_SHARE);
        int maxHeight = (int) ((screen.height - screenInsets.top - screenInsets.bottom) * MAX_SCREEN_SHARE);
        return new Dimension(
            Math.max(packed.width, Math.min((int) Math.round(packed.width * SIZE_FACTOR), maxWidth)),
            Math.max(packed.height, Math.min((int) Math.round(packed.height * SIZE_FACTOR), maxHeight)));
    }

    /**
     * Shows the pages whose title or settings texts contain the search text (case and spaces ignored), all pages without one.
     * The selected page stays when it matches, otherwise the first match is selected.
     */
    private void filterPages() {
        String key = normalize(searchField.getText());
        Page selected = pageList.getSelectedValue();
        matches.clear();
        List<Page> shown = new ArrayList<>();
        for (Page page : pages) {
            if (key.isEmpty()) {
                shown.add(page);
                continue;
            }
            List<Match> pageMatches = new ArrayList<>();
            findMatches(page.component(), key, pageMatches);
            if (!pageMatches.isEmpty() || normalize(page.title()).contains(key)) {
                matches.put(page.id(), pageMatches);
                shown.add(page);
            }
        }
        pageListModel.clear();
        pageListModel.addAll(shown);
        noMatchLabel.setVisible(shown.isEmpty());
        if (selected != null && shown.contains(selected)) {
            pageList.setSelectedValue(selected, true);
        } else if (!shown.isEmpty()) {
            pageList.setSelectedIndex(0);
        } else {
            highlighter.setMatches(List.of());
        }
    }

    /**
     * Adds the texts of a component tree that contain the key: labels, buttons and check boxes, titled border titles, and tabs whose
     * title or content matches. Hidden tabs and cards count (their page shows them on a click), other hidden components do not.
     * Cell renderers are skipped: their component holds the text of the last painted cell, not a setting.
     */
    private static void findMatches(@NotNull Component component, @NotNull String key, @NotNull List<Match> found) {
        if (component instanceof CellRendererPane) {
            return;
        }
        if (component instanceof JLabel label && containsKey(label.getText(), key)) {
            found.add(new Match(label, Match.TEXT));
        } else if (component instanceof AbstractButton button && containsKey(button.getText(), key)) {
            found.add(new Match(button, Match.TEXT));
        }
        if (component instanceof JComponent jComponent && jComponent.getBorder() instanceof TitledBorder titled
            && containsKey(titled.getTitle(), key)) {
            found.add(new Match(jComponent, Match.BORDER_TITLE));
        }
        if (component instanceof JTabbedPane tabbedPane) {
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                List<Match> tabMatches = new ArrayList<>();
                findMatches(tabbedPane.getComponentAt(i), key, tabMatches);
                if (!tabMatches.isEmpty() || containsKey(tabbedPane.getTitleAt(i), key)) {
                    found.add(new Match(tabbedPane, i));
                    found.addAll(tabMatches);
                }
            }
            return;
        }
        if (component instanceof Container container) {
            boolean cards = container.getLayout() instanceof CardLayout;
            for (Component child : container.getComponents()) {
                if (child.isVisible() || cards) {
                    findMatches(child, key, found);
                }
            }
        }
    }

    private static boolean containsKey(@Nullable String text, @NotNull String key) {
        return text != null && normalize(text).contains(key);
    }

    /**
     * Lower case without spaces; HTML text without its tags and common entities.
     */
    @NotNull
    private static String normalize(@NotNull String text) {
        if (text.regionMatches(true, 0, "<html>", 0, 6)) { //$NON-NLS-1$
            text = HTML_TAG.matcher(text).replaceAll(" ") //$NON-NLS-1$
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$ //$NON-NLS-10$
        }
        return WHITESPACE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(""); //$NON-NLS-1$
    }

    /**
     * A tab page: the form at the top, below the tabs.
     */
    @NotNull
    static JComponent wrapPage(@NotNull JComponent form) {
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
    static int addSection(@NotNull JPanel form, int row, @NotNull String title) {
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
    static int addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control, @Nullable String hint) {
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

    /**
     * Saves every page, also when one of them fails (a failed page would otherwise leave the pages after it unsaved
     * with no notice), then reports the pages that failed.
     */
    private void apply() {
        List<String> failures = new ArrayList<>();
        applyStep(failures, UpdateMessages.update_options_tab, updatesPage::apply);
        applyStep(failures, AiMessages.ai_options_tab, aiPage::apply);
        applyStep(failures, CoreMessages.options_general, this::applyQueryEditorSettings);
        for (SettingsPage page : settingsPages) {
            applyStep(failures, page.getTitle(), page::apply);
        }
        for (SettingsPage page : globalPages) {
            applyStep(failures, page.getTitle(), page::apply);
        }
        for (SettingsPage page : databasePages) {
            applyStep(failures, page.getTitle(), page::apply);
        }
        applyStep(failures, SettingsMessages.types_tab, () -> connectionTypesPage.apply(getOwner()));
        applyStep(failures, CoreMessages.options_general, this::applyExplorerAndHistorySettings);
        applyStep(failures, CoreMessages.options_editor, this::applyFormatSettings);
        applyStep(failures, CoreMessages.options_templates, templatesPage::apply);
        boolean[] editorChanged = {false};
        applyStep(failures, CoreMessages.options_editor, () -> editorChanged[0] |= applyFontSettings());
        applyStep(failures, CoreMessages.options_editor_colors, () -> editorChanged[0] |= applyColorSettings());
        if (editorChanged[0]) {
            applyStep(failures, CoreMessages.options_editor, SqlEditorPanel.SqlTextArea::applyThemeToAll);
        }
        applyStep(failures, CoreMessages.options_general, this::applyLibrarySettings);
        // Theme last: it updates every window, this dialog is about to close
        applyStep(failures, CoreMessages.options_general, this::applyThemeSettings);
        if (!failures.isEmpty()) {
            JOptionPane.showMessageDialog(getOwner(), NLS.bind(CoreMessages.options_save_failed, String.join("\n", failures)), //$NON-NLS-1$
                CoreMessages.options_save_failed_title, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Runs one saving step; a failure is logged and added to the list as "page: error" instead of stopping the others.
     */
    private static void applyStep(@NotNull List<String> failures, @NotNull String page, @NotNull Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.error("Can't save the options of " + page, e);
            failures.add(page + ": " + ErrorText.describeLine(e)); //$NON-NLS-1$
        }
    }

    private void applyQueryEditorSettings() {
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
        if (confirmDangerousSqlBox.isSelected() != SqlExecutionSafety.isConfirmDangerousSql()) {
            SqlExecutionSafety.setConfirmDangerousSql(confirmDangerousSqlBox.isSelected());
        }
        if (confirmDropSqlBox.isSelected() != SqlExecutionSafety.isConfirmDropSql()) {
            SqlExecutionSafety.setConfirmDropSql(confirmDropSqlBox.isSelected());
        }
    }

    private void applyExplorerAndHistorySettings() {
        if (objectDoubleClickCombo.getSelectedItem() instanceof ExplorerPreferences.ObjectDoubleClick objectDoubleClick
            && connectionDoubleClickCombo.getSelectedItem() instanceof ExplorerPreferences.ConnectionDoubleClick connectionDoubleClick) {
            int pageSize = ((Number) explorerPageSizeSpinner.getValue()).intValue();
            if (objectDoubleClick != ExplorerPreferences.getObjectDoubleClick() || connectionDoubleClick != ExplorerPreferences.getConnectionDoubleClick()
                || pageSize != ExplorerPreferences.getPageSize()) {
                ExplorerPreferences.save(objectDoubleClick, connectionDoubleClick, pageSize);
            }
        }
        int historyDays = ((Number) historyDaysSpinner.getValue()).intValue();
        if (historyDays != QueryHistory.getRetentionDays()) {
            QueryHistory.setRetentionDays(historyDays);
        }
    }

    private void applyFormatSettings() {
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
    }

    /**
     * @return whether the editor font changed (open editors are updated after the colors are saved)
     */
    private boolean applyFontSettings() {
        String family = (String) fontFamilyCombo.getSelectedItem();
        int size = ((Number) fontSizeSpinner.getValue()).intValue();
        if (family != null && (!family.equals(EditorFont.getSavedFamily()) || size != EditorFont.getSavedSize())) {
            EditorFont.save(family, size);
            return true;
        }
        return false;
    }

    /**
     * @return whether the editor colors of a theme changed
     */
    private boolean applyColorSettings() {
        boolean changed = false;
        for (ChromiteTheme theme : ChromiteTheme.values()) {
            if (!colors.get(theme).equals(EditorColors.getSaved(theme))) {
                EditorColors.save(theme, colors.get(theme));
                changed = true;
            }
        }
        return changed;
    }

    private void applyLibrarySettings() {
        int libraryHistoryRuns = ((Number) libraryHistoryRunsSpinner.getValue()).intValue();
        int libraryHistoryDays = ((Number) libraryHistoryDaysSpinner.getValue()).intValue();
        if (libraryHistoryRuns != QueryLibraryHistory.getMaxRuns() || libraryHistoryDays != QueryLibraryHistory.getMaxDays()) {
            QueryLibraryHistory.setRetention(libraryHistoryRuns, libraryHistoryDays);
        }
        int libraryRefresh = ((Number) libraryRefreshSpinner.getValue()).intValue();
        if (libraryRefresh != QueryLibraryWatcher.getInterval()) {
            QueryLibraryWatcher.setInterval(libraryRefresh);
        }
    }

    private void applyThemeSettings() {
        if (iconStyleCombo.getSelectedItem() instanceof UiIconStyle iconStyle && iconStyle != UiIconStyle.getCurrent()) {
            iconStyle.apply();
        }
        if (themeCombo.getSelectedItem() instanceof ChromiteTheme theme && theme != ChromiteTheme.getCurrent()) {
            theme.apply();
        }
    }

    /**
     * A page of the list: its id (for {@link #open(Frame, String)} and the card), its title (shown by the list) and its component.
     */
    private record Page(@NotNull String id, @NotNull String title, @NotNull JComponent component) {
        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * A text the search found: a label or button ({@link #TEXT}), the title of a titled border ({@link #BORDER_TITLE}),
     * or a tab of a tabbed pane (its index).
     */
    private record Match(@NotNull JComponent component, int tab) {
        static final int TEXT = -1;
        static final int BORDER_TITLE = -2;
    }

    /**
     * Paints the matches of the shown page over it (translucent, so the text stays readable), without changing the page's
     * components. Matches inside hidden tabs or scrolled out of view are not painted.
     */
    private final class MatchHighlighter extends LayerUI<JComponent> {
        private List<Match> shown = List.of();

        void setMatches(@NotNull List<Match> matches) {
            shown = matches;
            pageHolder.repaint();
        }

        @Override
        public void paint(Graphics g, JComponent layer) {
            super.paint(g, layer);
            if (shown.isEmpty()) {
                return;
            }
            Color color = UIManager.getColor("Component.focusedBorderColor"); //$NON-NLS-1$
            Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 56);
            int arc = UIScale.scale(6);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (Match match : shown) {
                    Rectangle bounds = getBounds(match, layer);
                    if (bounds != null) {
                        g2.setColor(fill);
                        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc);
                        g2.setColor(color);
                        g2.drawRoundRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1, arc, arc);
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        /**
         * The match's text area in layer coordinates: the preferred width of a label or button (a stretched check box is wider than its
         * text), the title strip of a titled border, the tab of a tabbed pane; clipped to what is visible.
         */
        @Nullable
        private Rectangle getBounds(@NotNull Match match, @NotNull JComponent layer) {
            JComponent component = match.component();
            if (!component.isShowing()) {
                return null;
            }
            Rectangle bounds;
            if (match.tab() >= 0) {
                if (!(component instanceof JTabbedPane tabbedPane) || match.tab() >= tabbedPane.getTabCount()) {
                    return null;
                }
                bounds = tabbedPane.getBoundsAt(match.tab());
                if (bounds == null) {
                    return null;
                }
            } else if (match.tab() == Match.BORDER_TITLE) {
                if (!(component.getBorder() instanceof TitledBorder titled)) {
                    return null;
                }
                Font font = Objects.requireNonNullElse(titled.getTitleFont(), component.getFont());
                int titleWidth = component.getFontMetrics(font).stringWidth(titled.getTitle());
                bounds = new Rectangle(0, 0, Math.min(component.getWidth(), titleWidth + UIScale.scale(16)), titled.getBorderInsets(component).top);
            } else {
                bounds = new Rectangle(0, 0, Math.min(component.getWidth(), component.getPreferredSize().width), component.getHeight());
            }
            bounds = bounds.intersection(component.getVisibleRect());
            return bounds.isEmpty() ? null : SwingUtilities.convertRectangle(component, bounds, layer);
        }
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
            } else if (value instanceof ExplorerPreferences.ObjectDoubleClick action) {
                setText(action.getLabel());
            } else if (value instanceof ExplorerPreferences.ConnectionDoubleClick action) {
                setText(action.getLabel());
            }
            return this;
        }
    }
}
