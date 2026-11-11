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
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Tools &gt; Options (SSMS): 기능별 설정을 한곳에서 바꾸는 대화상자.
 *
 * 설계: 이 대화상자는 설정을 따로 저장하지 않고, 각 기능이 가진 설정의 저장 위치와 적용 방법을 그대로 쓴다.
 * <ul>
 *     <li>Theme, Icon style: {@link ChromiteTheme#apply()}, {@link UiIconStyle#apply()}(View &gt; Theme 메뉴와 같음, 실행 중 바로 바뀌고 저장).
 *     테마는 시스템·밝게·어둡게 아이콘 단추 세 개의 묶음이다(값이라 [저장]에서 적용).</li>
 *     <li>Auto-complete while typing, Completion delay: {@link SqlCompletionPopup}의 설정(Query &gt; Auto-Complete While Typing과 같은 키).</li>
 *     <li>위험한 문 확인(WHERE 없는 UPDATE/DELETE, DROP): {@link SqlExecutionSafety}의 설정(SWT 확인 대화상자와 같은 키).</li>
 *     <li>Editor 탭: SQL 편집기 글꼴과 크기({@link EditorFont}). Editor Colors 탭: 테마마다 구문·편집기 색({@link EditorColors}),
 *     SWT 제품의 Colors and Fonts &gt; SQL Editor에 해당한다. 두 탭의 미리 보기는 저장하지 않은 값(한글 포함)을 바로 보여 주고,
 *     [저장]을 누르면 저장해서 열린 모든 편집기와 DDL 탭에 적용한다.</li>
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
 *     <li>Connection Types 탭: 연결 유형 편집({@link ConnectionTypesPage}).
 *     다른 탭과 같이 쓰는 행·절 모양은 {@link #addSection}, {@link #addRow}({@link OptionsForm}), {@link #wrapPage}다.</li>
 *     <li>페이지는 SSMS Options의 왼쪽 목록처럼 왼쪽 목록에서 고른다. 페이지가 한 줄 탭에 들어가지 않는다. 목록은 {@link JList}, 페이지는
 *     {@link CardLayout}이다({@link JTabbedPane}은 탭을 숨길 수 없어 찾기로 걸러 보일 수 없다). 목록 위에는 찾기 칸을 고정하고, 페이지 이름과
 *     페이지 안의 설정 글자(라벨, 체크 상자, 단추, 테두리 제목, 하위 탭 이름)로 페이지를 거르고, 보이는 페이지에서 일치한 글자를 {@link JLayer}로
 *     칠한다(페이지 코드는 그대로).</li>
 *     <li>목록은 작은 회색 묶음 제목(일반·편집기·결과·연결·데이터베이스·Chromite) 아래에 아이콘과 이름으로 페이지를 보인다. 묶음 제목은 고를 수
 *     없다(고르면 다음 페이지로). 선택은 FlatLaf의 둥근 선택 모양이다.</li>
 *     <li>페이지 여백은 페이지 자리 한 곳에서 준다. 페이지는 세로로 스크롤되는 칸 안에 있고 칸의 폭을 따른다(가로 스크롤 없음).
 *     {@link #wrapPage}는 탭이 위에 있는 다른 대화상자도 쓰므로 바꾸지 않는다.</li>
 *     <li>다른 화면이 특정 페이지를 열 때는 화면 이름이 아니라 페이지 식별자({@link #PAGE_AGENT})로 고른다({@link #open(Frame, String)}).</li>
 *     <li>저장(Turbrix와 같은 저장 표준, {@link OptionsSaving}): 확인/취소가 없다. 토글은 누를 때 저장하고, 나머지 값은 페이지 아래에 고정된 줄의
 *     [저장]으로 저장한다. 바뀐 값이 있는 페이지를 떠나거나 창을 닫으면(×, Esc) 저장 / 버리기 / 취소를 묻는다. 바뀐 값이 있는 페이지는 찾기가
 *     걸러도 목록에 남는다. 다시 세기는 페이지의 값 컨트롤이 바뀔 때와 이 창의 마우스·키 입력 뒤에 한다.</li>
 * </ul>
 * 읽기는 열 때 한 번 한다(각 칸의 되돌리기는 저장된 값을 다시 읽음).
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
 * - 2026-11-09 단축키를 플랫폼 명령 키로({@link UiKeys}: macOS Command, 앱 변경 #24) (docs/chromite/shortcuts.md), 서식 안내의 두 키 조합 글자
 * - 2026-11-11 General의 테마 목록에 시스템 설정 따르기를 더하고 저장된 선택을 보임 (앱 변경 #38, docs/chromite/swing-options.md "테마")
 * - 2026-11-12 다시 디자인: 묶음·아이콘 목록, 카드 행({@link OptionsForm}), 테마 단추 셋, 확인/취소 대신 저장 표준({@link OptionsSaving})
 *   (앱 변경 #59, docs/chromite/swing-options.md "옵션 화면 모양과 저장 (#59)")
 */
final class OptionsDialog extends JDialog {

    /**
     * Page id of Chromite Agent, for {@link #open(Frame, String)}. A new dialog shows its first tab, Providers.
     */
    static final String PAGE_AGENT = "agent"; //$NON-NLS-1$

    private static final Log log = Log.getLog(OptionsDialog.class);
    private static final int MAX_DELAY = 5000;
    // The page list is at least this wide (scaled), wider when a title needs it
    private static final int PAGE_LIST_MIN_WIDTH = 236;
    // Around the page list; the page's margins (top, sides, bottom)
    private static final int LIST_PADDING = 12;
    private static final int PAGE_MARGIN_TOP = 18;
    private static final int PAGE_MARGIN_SIDE = 28;
    private static final int PAGE_MARGIN_BOTTOM = 20;
    // The dialog's opening and smallest size (scaled), within this share of the screen
    private static final Dimension OPENING_SIZE = new Dimension(1080, 760);
    private static final Dimension MINIMUM_SIZE = new Dimension(760, 520);
    private static final double MAX_SCREEN_SHARE = 0.9;
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>"); //$NON-NLS-1$
    private static final Pattern WHITESPACE = Pattern.compile("\\s+"); //$NON-NLS-1$
    private static final String PREVIEW_TEXT = """
        -- 한글 주석: editor preview
        DECLARE @cnt INT = 0;
        SELECT a.AlbumId, COUNT(*) AS cnt, UPPER(a.Title), DATEDIFF(SECOND, a.d1, a.d2)
        FROM Album a
        WHERE a.Title LIKE N'%한글%' AND a.AlbumId > 42.5 OR a.Note IS NULL;"""; //$NON-NLS-1$

    // Theme: one icon button per choice, in a group
    private final Map<ChromiteTheme.Choice, JToggleButton> themeButtons = new EnumMap<>(ChromiteTheme.Choice.class);
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
    private final ConnectionTypesPage connectionTypesPage = new ConnectionTypesPage();
    private final SqlTemplatesPage templatesPage = new SqlTemplatesPage();
    private final UpdateOptionsPage updatesPage = new UpdateOptionsPage();
    private final AiOptionsPage aiPage = new AiOptionsPage();

    // Editor tab
    private final JComboBox<String> fontFamilyCombo;
    private final JSpinner fontSizeSpinner;
    private final SqlEditorPanel.SqlTextArea fontPreview = createPreview();

    // Editor Colors tab: edited colors of both themes, saved with the page's Save
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

    // The page list (group titles and pages, filtered by the search field) and the shown page
    private final List<Page> pages = new ArrayList<>();
    private final DefaultListModel<Object> pageListModel = new DefaultListModel<>();
    private final JList<Object> pageList = new JList<>(pageListModel);
    private final CardLayout pageCards = new CardLayout();
    private final JPanel pageHolder = new JPanel(pageCards);
    private final JTextField searchField = new JTextField();
    private final JLabel noMatchLabel = new JLabel(CoreMessages.options_search_no_match);
    private final MatchHighlighter highlighter = new MatchHighlighter();
    // Matched texts per page id, for the search of the moment (empty without a search)
    private final Map<String, List<Match>> matches = new HashMap<>();
    // Saving: the fixed line under the page, the shown page, the list index before a selection (to skip group titles)
    private final OptionsSaving.Footer footer = new OptionsSaving.Footer();
    private Page shownPage;
    private int previousIndex;
    // While the list selection is set back or rebuilt (no question, no page change)
    private boolean selecting;
    private boolean updateScheduled;
    // Counts again after the user's mouse and key input in this dialog (values the page's listeners don't report)
    private final AWTEventListener inputListener = event -> {
        if (event.getSource() instanceof Component source && SwingUtilities.getWindowAncestor(source) == this
            && (event.getID() == MouseEvent.MOUSE_RELEASED || event.getID() == KeyEvent.KEY_RELEASED)) {
            scheduleUpdate();
        }
    };

    private OptionsDialog(@NotNull Frame owner, @Nullable String pageId) {
        super(owner, CoreMessages.options_title, true);
        // The window's close asks about unsaved changes first (requestClose)
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        iconStyleCombo.setSelectedItem(UiIconStyle.getCurrent());
        iconStyleCombo.setRenderer(new LabelRenderer());
        autoCompleteBox.setSelected(SqlCompletionPopup.isAutoActivateOnLetters());
        showProceduresBox.setSelected(SqlCompletionPopup.isShowProcedures());
        showProceduresBox.setToolTipText(CoreMessages.options_show_procedures_tip);
        delaySpinner = new JSpinner(new SpinnerNumberModel(getSavedDelay(), 0, MAX_DELAY, 50));

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
        row = addRow(form, row, CoreMessages.options_theme, createThemeButtons(), null);
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

        // Pages in groups on the left, as the SSMS Options tree: more pages than one row of tabs holds. Settings pages are identified
        // by their class.
        String group = CoreMessages.options_group_general;
        addGeneralParts(addPage("general", group, "settings", CoreMessages.options_general, wrapPage(form))); //$NON-NLS-1$ //$NON-NLS-2$
        addSettingsPage(group, "object-explorer", new NavigatorOptionsPage()); //$NON-NLS-1$
        addSettingsPage(group, "messages", new NotificationsOptionsPage()); //$NON-NLS-1$
        addSettingsPage(group, "history", new LogsOptionsPage()); //$NON-NLS-1$
        group = CoreMessages.options_group_editor;
        addEditorParts(addPage("editor", group, "file-sql", CoreMessages.options_editor, createEditorPage())); //$NON-NLS-1$ //$NON-NLS-2$
        addPage("editor-colors", group, "palette", CoreMessages.options_editor_colors, createColorsPage()) //$NON-NLS-1$ //$NON-NLS-2$
            .add(OptionsSaving.part(CoreMessages.options_editor_colors, this::countColorChanges, this::saveColors, this::revertColors));
        addSettingsPage(group, "function", new CodeCompletionOptionsPage(true)); //$NON-NLS-1$
        addSettingsPage(group, "run", new SqlExecutionOptionsPage(true)); //$NON-NLS-1$
        addPage("templates", group, "library-query", CoreMessages.options_templates, templatesPage) //$NON-NLS-1$ //$NON-NLS-2$
            .add(OptionsSaving.part(CoreMessages.options_templates, templatesPage::getChangeCount, templatesPage::save, templatesPage::revert));
        group = CoreMessages.options_group_results;
        addSettingsPage(group, "results", new ResultSetOptionsPage(true)); //$NON-NLS-1$
        addSettingsPage(group, "datatype", new ContentOptionsPage(true)); //$NON-NLS-1$
        addSettingsPage(group, "format", new DataFormatOptionsPage(true)); //$NON-NLS-1$
        addSettingsPage(group, "export", new DataTransferOptionsPage(true)); //$NON-NLS-1$
        group = CoreMessages.options_group_connections;
        addSettingsPage(group, "connection", new ConnectionsOptionsPage()); //$NON-NLS-1$
        addPage("connection-types", group, "categorized", SettingsMessages.types_tab, connectionTypesPage) //$NON-NLS-1$ //$NON-NLS-2$
            .add(OptionsSaving.part(SettingsMessages.types_tab, connectionTypesPage::getChangeCount, () -> connectionTypesPage.save(getOwner()),
                connectionTypesPage::revert));
        addSettingsPage(group, "warning", new ConnectionErrorsOptionsPage(true)); //$NON-NLS-1$
        addSettingsPage(group, "properties", new MetadataOptionsPage(true)); //$NON-NLS-1$
        addSettingsPage(group, "user", new ClientIdentificationOptionsPage(true)); //$NON-NLS-1$
        addSettingsPage(group, "connect", new DriversOptionsPage()); //$NON-NLS-1$
        group = CoreMessages.options_group_databases;
        // Altibase and CUBRID connections can override theirs as Oracle ones do
        for (SettingsPage page : List.of(new OracleOptionsPage(true), new AltibaseOptionsPage(true), new CubridOptionsPage(true), new MimerOptionsPage())) {
            addSettingsPage(group, "database", page); //$NON-NLS-1$
        }
        group = CoreMessages.options_group_chromite;
        addPage(PAGE_AGENT, group, "ai-assistant", AiMessages.ai_options_tab, aiPage).addAll(aiPage.getParts()); //$NON-NLS-1$
        // Only a toggle and actions: no Save line
        addPage("updates", group, "refresh", UpdateMessages.update_options_tab, updatesPage); //$NON-NLS-1$ //$NON-NLS-2$
        for (Page page : pages) {
            page.state().setListener(() -> {
                if (page == shownPage) {
                    footer.refresh();
                }
            });
            OptionsSaving.watch(page.component(), this::scheduleUpdate);
        }

        JPanel right = new JPanel(new BorderLayout());
        right.add(new JLayer<>(pageHolder, highlighter), BorderLayout.CENTER);
        right.add(footer, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout());
        content.add(createPageList(), BorderLayout.WEST);
        content.add(right, BorderLayout.CENTER);
        setContentPane(content);
        selectPage(pageId);

        // Esc and the window's close do the same: ask about unsaved changes, then close
        getRootPane().registerKeyboardAction(e -> requestClose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        }, UiKeys.command(KeyEvent.VK_F), JComponent.WHEN_IN_FOCUSED_WINDOW);
        addWindowListener(new WindowAdapter() {
            // The page list has the focus at first, not the search field above it (Ctrl+F goes there)
            @Override
            public void windowOpened(WindowEvent e) {
                pageList.requestFocusInWindow();
            }

            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });
        Toolkit.getDefaultToolkit().addAWTEventListener(inputListener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        setMinimumSize(UIScale.scale(MINIMUM_SIZE));
        setSize(getOpeningSize(owner));
        setLocationRelativeTo(owner);
    }

    @Override
    public void dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(inputListener);
        super.dispose();
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

    /**
     * Adds a page (in a vertically scrolling view with the page margins) under a group title of the list.
     *
     * @return the page's saving state, to add its parts to
     */
    @NotNull
    private OptionsSaving.PageState addPage(@NotNull String id, @NotNull String group, @NotNull String iconId, @NotNull String title,
                                            @NotNull JComponent component) {
        OptionsSaving.PageState state = new OptionsSaving.PageState(title);
        pages.add(new Page(id, title, group, iconId, component, state));
        PageView view = new PageView(component);
        view.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(PAGE_MARGIN_TOP), UIScale.scale(PAGE_MARGIN_SIDE),
            UIScale.scale(PAGE_MARGIN_BOTTOM), UIScale.scale(PAGE_MARGIN_SIDE)));
        JScrollPane scrollPane = new JScrollPane(view, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(UIScale.scale(16));
        pageHolder.add(scrollPane, id);
        return state;
    }

    /**
     * A model key page, saved through its {@code save(Writer)} ({@link OptionsSaving.SettingsPagePart}).
     */
    private void addSettingsPage(@NotNull String group, @NotNull String iconId, @NotNull SettingsPage page) {
        OptionsSaving.PageState state = addPage(page.getClass().getSimpleName(), group, iconId, page.getTitle(), page);
        state.add(new OptionsSaving.SettingsPagePart(page, state));
    }

    /**
     * The search field fixed above the page list, the "no match" text under it, and the list, at least {@link #PAGE_LIST_MIN_WIDTH}
     * wide and as wide as its longest title with the scroll bar, so that no title is cut. A line on the right parts it from the page.
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
        // Enter goes to the list (the shown page is the first match or the page kept for its changes)
        searchField.addActionListener(e -> pageList.requestFocusInWindow());
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
        pageList.setCellRenderer(new PageListRenderer());
        // A few rows: the list scrolls, the pages set the dialog height; the width then counts the scroll bar
        pageList.setVisibleRowCount(8);
        pageList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || selecting) {
                return;
            }
            int index = pageList.getSelectedIndex();
            if (index < 0) {
                // Empty while the search rebuilds the list; the shown page stays until one is selected
                return;
            }
            if (pageListModel.get(index) instanceof Page page) {
                previousIndex = index;
                requestShow(page);
            } else {
                // A group title is not a page: on to the next page in the direction of the move
                int next = index < previousIndex && index > 0 ? index - 1 : index + 1;
                if (next < pageListModel.size() && pageListModel.get(next) instanceof Page) {
                    pageList.setSelectedIndex(next);
                } else if (shownPage != null) {
                    setListSelection(shownPage);
                }
            }
        });
        rebuildList(pages);

        JPanel search = new JPanel(new BorderLayout());
        search.setOpaque(false);
        search.add(searchField, BorderLayout.NORTH);
        search.add(noMatchLabel, BorderLayout.CENTER);
        JScrollPane listScroll = new JScrollPane(pageList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel panel = new JPanel(new BorderLayout(0, UIScale.scale(10)));
        panel.setBorder(OptionsForm.createDividerBorder(SwingConstants.RIGHT,
            new Insets(UIScale.scale(LIST_PADDING), UIScale.scale(LIST_PADDING), UIScale.scale(LIST_PADDING), UIScale.scale(LIST_PADDING))));
        panel.add(search, BorderLayout.NORTH);
        panel.add(listScroll, BorderLayout.CENTER);
        Dimension size = panel.getPreferredSize();
        size.width = Math.max(size.width, UIScale.scale(PAGE_LIST_MIN_WIDTH));
        panel.setPreferredSize(size);
        return panel;
    }

    /**
     * Fills the list with pages, each group's title before its first page.
     */
    private void rebuildList(@NotNull List<Page> shown) {
        selecting = true;
        try {
            pageListModel.clear();
            String group = null;
            for (Page page : shown) {
                if (!page.group().equals(group)) {
                    group = page.group();
                    pageListModel.addElement(new Group(group));
                }
                pageListModel.addElement(page);
            }
        } finally {
            selecting = false;
        }
    }

    /**
     * Selects a page by id, or the first page when there is none with that id.
     */
    private void selectPage(@Nullable String pageId) {
        Page page = pages.stream().filter(p -> p.id().equals(pageId)).findFirst().orElse(null);
        if (page == null) {
            if (pageId != null) {
                log.warn("No options page with id " + pageId); //$NON-NLS-1$
            }
            page = pages.getFirst();
        }
        setListSelection(page);
        showPage(page);
    }

    /**
     * Selects a page in the list without the question and without showing it (the caller shows it or it is shown already).
     */
    private void setListSelection(@NotNull Page page) {
        selecting = true;
        try {
            pageList.setSelectedValue(page, true);
            previousIndex = pageList.getSelectedIndex();
        } finally {
            selecting = false;
        }
    }

    /**
     * Shows a page chosen in the list. When the shown page has unsaved changes the list goes back to it first, and the page changes
     * after the answer to Save / Discard / Cancel (asked after this list event, not inside it).
     */
    private void requestShow(@NotNull Page page) {
        if (page == shownPage) {
            return;
        }
        Page from = shownPage;
        if (from != null && hasChanges(from)) {
            setListSelection(from);
            SwingUtilities.invokeLater(() -> {
                if (from == shownPage && leave(from)) {
                    setListSelection(page);
                    showPage(page);
                }
            });
            return;
        }
        showPage(page);
    }

    private void showPage(@NotNull Page page) {
        shownPage = page;
        pageCards.show(pageHolder, page.id());
        highlighter.setMatches(matches.getOrDefault(page.id(), List.of()));
        footer.setPage(page.state());
        page.state().update();
    }

    private static boolean hasChanges(@NotNull Page page) {
        page.state().update();
        return page.state().getChangeCount() > 0;
    }

    /**
     * Asks about the page's unsaved changes: Save (stays when a part could not be saved), Discard (the saved values back) or Cancel.
     *
     * @return whether the page may be left
     */
    private boolean leave(@NotNull Page page) {
        return switch (OptionsSaving.askLeave(this, page.state())) {
            case SAVE -> page.state().save();
            case DISCARD -> {
                page.state().revert();
                yield true;
            }
            case CANCEL -> false;
        };
    }

    /**
     * Closes the dialog (Esc, the window's close) after the question about the shown page's unsaved changes.
     */
    private void requestClose() {
        if (shownPage != null && hasChanges(shownPage) && !leave(shownPage)) {
            return;
        }
        dispose();
    }

    /**
     * Counts the shown page again once, after the events of the moment (not while a table cell is edited: counting would end the edit
     * on pages whose save stops it; the table tells when the edit ends).
     */
    private void scheduleUpdate() {
        if (updateScheduled) {
            return;
        }
        updateScheduled = true;
        SwingUtilities.invokeLater(() -> {
            updateScheduled = false;
            if (shownPage != null && isDisplayable() && !OptionsSaving.isEditingCell(shownPage.component())) {
                shownPage.state().update();
            }
        });
    }

    /**
     * {@link #OPENING_SIZE}, within {@link #MAX_SCREEN_SHARE} of the owner's screen; the pages scroll when it is smaller than they are.
     */
    @NotNull
    private static Dimension getOpeningSize(@NotNull Frame owner) {
        GraphicsConfiguration config = owner.getGraphicsConfiguration();
        Rectangle screen = config.getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(config);
        int maxWidth = (int) ((screen.width - screenInsets.left - screenInsets.right) * MAX_SCREEN_SHARE);
        int maxHeight = (int) ((screen.height - screenInsets.top - screenInsets.bottom) * MAX_SCREEN_SHARE);
        Dimension size = UIScale.scale(OPENING_SIZE);
        return new Dimension(Math.min(size.width, maxWidth), Math.min(size.height, maxHeight));
    }

    /**
     * Shows the pages whose title or settings texts contain the search text (case and spaces ignored), all pages without one.
     * The shown page stays when it matches or has unsaved changes (it stays in front until the question about them is answered),
     * otherwise the first match is shown.
     */
    private void filterPages() {
        String key = normalize(searchField.getText());
        Page held = shownPage != null && hasChanges(shownPage) ? shownPage : null;
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
            } else if (page == held) {
                shown.add(page);
            }
        }
        rebuildList(shown);
        noMatchLabel.setVisible(shown.isEmpty());
        if (shownPage != null && shown.contains(shownPage)) {
            setListSelection(shownPage);
            highlighter.setMatches(matches.getOrDefault(shownPage.id(), List.of()));
        } else if (!shown.isEmpty()) {
            setListSelection(shown.getFirst());
            showPage(shown.getFirst());
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
        formatterCombo.setSelectedIndex(getSavedFormatterIndex());
        keywordCaseCombo.setSelectedIndex(getSavedKeywordCaseIndex());
        row = addSection(form, row, CoreMessages.options_formatting);
        row = addRow(form, row, CoreMessages.options_style, formatterCombo,
            NLS.bind(CoreMessages.options_style_hint, SqlEditorPanel.SqlTextArea.getChordText(KeyEvent.VK_D)));
        row = addRow(form, row, CoreMessages.options_keyword_case, keywordCaseCombo, CoreMessages.options_keyword_case_hint);
        addPreview(form, row, fontPreview);
        updatePreviews();
        return wrapPage(form);
    }

    private static int getSavedFormatterIndex() {
        return SqlEditCommands.FORMATTER_COMPACT.equals(SqlEditCommands.getFormatterId()) ? 1 : 0;
    }

    private static int getSavedKeywordCaseIndex() {
        DBPIdentifierCase keywordCase = SqlEditCommands.getKeywordCase();
        return keywordCase == null ? 0 : switch (keywordCase) {
            case UPPER -> 1;
            case LOWER -> 2;
            case MIXED -> 3;
        };
    }

    /**
     * The completion delay as the spinner can show it (a larger saved value shows as the largest).
     */
    private static int getSavedDelay() {
        return Math.clamp(SqlCompletionPopup.getAutoActivationDelay(), 0, MAX_DELAY);
    }

    /**
     * Theme: system, light and dark as icon buttons in one rounded group (the choice is a value, applied with Save).
     */
    @NotNull
    private JComponent createThemeButtons() {
        ButtonGroup group = new ButtonGroup();
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(2), UIScale.scale(2))) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(UIManager.getColor("Chromite.island.canvas")); //$NON-NLS-1$
                    int arc = UIScale.scale(10);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                } finally {
                    g2.dispose();
                }
            }
        };
        panel.setOpaque(false);
        for (ChromiteTheme.Choice choice : ChromiteTheme.Choice.values()) {
            JToggleButton button = new JToggleButton(UiIcons.get(switch (choice) {
                case SYSTEM -> "theme-system"; //$NON-NLS-1$
                case LIGHT -> "theme-light"; //$NON-NLS-1$
                case DARK -> "theme-dark"; //$NON-NLS-1$
            }));
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            button.setToolTipText(choice.getLabel());
            button.getAccessibleContext().setAccessibleName(choice.getLabel());
            group.add(button);
            themeButtons.put(choice, button);
            panel.add(button);
        }
        setThemeChoice(ChromiteTheme.getSavedChoice());
        return panel;
    }

    @NotNull
    private ChromiteTheme.Choice getThemeChoice() {
        for (Map.Entry<ChromiteTheme.Choice, JToggleButton> entry : themeButtons.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        return ChromiteTheme.getSavedChoice();
    }

    private void setThemeChoice(@NotNull ChromiteTheme.Choice choice) {
        themeButtons.get(choice).setSelected(true);
    }

    /**
     * The blocks of General (each section; theme and icon style are one) and its toggles, saved when they are clicked.
     */
    private void addGeneralParts(@NotNull OptionsSaving.PageState state) {
        state.add(OptionsSaving.part(CoreMessages.options_environment,
            () -> OptionsSaving.differs(getThemeChoice(), ChromiteTheme.getSavedChoice())
                + OptionsSaving.differs(iconStyleCombo.getSelectedItem(), UiIconStyle.getCurrent()),
            this::applyThemeSettings,
            () -> {
                setThemeChoice(ChromiteTheme.getSavedChoice());
                iconStyleCombo.setSelectedItem(UiIconStyle.getCurrent());
            }));
        state.add(OptionsSaving.part(ObjectExplorerMessages.oe_options_section,
            () -> OptionsSaving.differs(objectDoubleClickCombo.getSelectedItem(), ExplorerPreferences.getObjectDoubleClick())
                + OptionsSaving.differs(connectionDoubleClickCombo.getSelectedItem(), ExplorerPreferences.getConnectionDoubleClick())
                + OptionsSaving.differs(explorerPageSizeSpinner.getValue(), ExplorerPreferences.getPageSize()),
            this::applyExplorerSettings,
            () -> {
                objectDoubleClickCombo.setSelectedItem(ExplorerPreferences.getObjectDoubleClick());
                connectionDoubleClickCombo.setSelectedItem(ExplorerPreferences.getConnectionDoubleClick());
                explorerPageSizeSpinner.setValue(ExplorerPreferences.getPageSize());
            }));
        state.add(OptionsSaving.part(CoreMessages.options_query_editor,
            () -> OptionsSaving.differs(delaySpinner.getValue(), getSavedDelay()),
            () -> SqlCompletionPopup.setAutoActivationDelay(((Number) delaySpinner.getValue()).intValue()),
            () -> delaySpinner.setValue(getSavedDelay())));
        state.add(OptionsSaving.part(CoreMessages.options_query_history,
            () -> OptionsSaving.differs(historyDaysSpinner.getValue(), getSavedHistoryDays()),
            () -> QueryHistory.setRetentionDays(((Number) historyDaysSpinner.getValue()).intValue()),
            () -> historyDaysSpinner.setValue(getSavedHistoryDays())));
        state.add(OptionsSaving.part(CoreMessages.options_query_library,
            () -> OptionsSaving.differs(libraryHistoryRunsSpinner.getValue(), QueryLibraryHistory.getMaxRuns())
                + OptionsSaving.differs(libraryHistoryDaysSpinner.getValue(), QueryLibraryHistory.getMaxDays())
                + OptionsSaving.differs(libraryRefreshSpinner.getValue(), QueryLibraryWatcher.getInterval()),
            this::applyLibrarySettings,
            () -> {
                libraryHistoryRunsSpinner.setValue(QueryLibraryHistory.getMaxRuns());
                libraryHistoryDaysSpinner.setValue(QueryLibraryHistory.getMaxDays());
                libraryRefreshSpinner.setValue(QueryLibraryWatcher.getInterval());
            }));
        addToggle(state, autoCompleteBox, CoreMessages.options_auto_complete, SqlCompletionPopup::setAutoActivateOnLetters);
        addToggle(state, showProceduresBox, CoreMessages.options_show_procedures, SqlCompletionPopup::setShowProcedures);
        addToggle(state, confirmDangerousSqlBox, CoreMessages.options_confirm_dangerous_sql, SqlExecutionSafety::setConfirmDangerousSql);
        addToggle(state, confirmDropSqlBox, CoreMessages.options_confirm_drop_sql, SqlExecutionSafety::setConfirmDropSql);
    }

    /**
     * A toggle: its setting is saved when it is clicked; a failure is named under the page.
     */
    private static void addToggle(@NotNull OptionsSaving.PageState state, @NotNull JCheckBox box, @NotNull String name,
                                  @NotNull Consumer<Boolean> setter) {
        box.addActionListener(e -> {
            try {
                setter.accept(box.isSelected());
            } catch (RuntimeException ex) {
                state.toggleFailed(name, ex);
            }
        });
    }

    /**
     * The history days as the spinner can show them.
     */
    private static int getSavedHistoryDays() {
        return Math.min(QueryHistory.getRetentionDays(), QueryHistory.MAX_RETENTION_DAYS);
    }

    /**
     * The blocks of Editor: the font (open editors take it when it is saved) and the formatting.
     */
    private void addEditorParts(@NotNull OptionsSaving.PageState state) {
        state.add(OptionsSaving.part(CoreMessages.options_font,
            () -> OptionsSaving.differs(fontFamilyCombo.getSelectedItem(), EditorFont.getSavedFamily())
                + OptionsSaving.differs(fontSizeSpinner.getValue(), EditorFont.getSavedSize()),
            () -> {
                if (applyFontSettings()) {
                    SqlEditorPanel.SqlTextArea.applyThemeToAll();
                }
            },
            () -> {
                fontFamilyCombo.setSelectedItem(EditorFont.getSavedFamily());
                fontSizeSpinner.setValue(EditorFont.getSavedSize());
            }));
        state.add(OptionsSaving.part(CoreMessages.options_formatting,
            () -> OptionsSaving.differs(formatterCombo.getSelectedIndex(), getSavedFormatterIndex())
                + OptionsSaving.differs(keywordCaseCombo.getSelectedIndex(), getSavedKeywordCaseIndex()),
            this::applyFormatSettings,
            () -> {
                formatterCombo.setSelectedIndex(getSavedFormatterIndex());
                keywordCaseCombo.setSelectedIndex(getSavedKeywordCaseIndex());
            }));
    }

    /**
     * The edited color settings (theme and item) that differ from the saved ones.
     */
    private int countColorChanges() {
        int count = 0;
        for (ChromiteTheme theme : ChromiteTheme.values()) {
            Map<EditorColors.Item, EditorColors.Setting> saved = EditorColors.getSaved(theme);
            for (EditorColors.Item item : EditorColors.Item.values()) {
                count += OptionsSaving.differs(colors.get(theme).get(item), saved.get(item));
            }
        }
        return count;
    }

    private void saveColors() {
        if (applyColorSettings()) {
            SqlEditorPanel.SqlTextArea.applyThemeToAll();
        }
    }

    private void revertColors() {
        for (ChromiteTheme theme : ChromiteTheme.values()) {
            colors.put(theme, EditorColors.getSaved(theme));
        }
        colorItemList.repaint();
        updateColorControls();
        updatePreviews();
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
     * Section: a bold title over a rounded card that holds the next rows ({@link OptionsForm}).
     */
    static int addSection(@NotNull JPanel form, int row, @NotNull String title) {
        return OptionsForm.addSection(form, row, title);
    }

    /**
     * Row in the current section's card: the name (no colon, SSMS) with an optional grey description under it on the left, the control
     * at the right end; a check box's text is the name ({@link OptionsForm}).
     */
    static int addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control, @Nullable String hint) {
        return OptionsForm.addRow(form, row, label, control, hint);
    }

    private void applyExplorerSettings() {
        if (objectDoubleClickCombo.getSelectedItem() instanceof ExplorerPreferences.ObjectDoubleClick objectDoubleClick
            && connectionDoubleClickCombo.getSelectedItem() instanceof ExplorerPreferences.ConnectionDoubleClick connectionDoubleClick) {
            ExplorerPreferences.save(objectDoubleClick, connectionDoubleClick, ((Number) explorerPageSizeSpinner.getValue()).intValue());
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
     * @return whether the editor font changed (the caller then updates the open editors)
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

    /**
     * Icon style, then the theme (it updates every window, this dialog included).
     */
    private void applyThemeSettings() {
        if (iconStyleCombo.getSelectedItem() instanceof UiIconStyle iconStyle && iconStyle != UiIconStyle.getCurrent()) {
            iconStyle.apply();
        }
        ChromiteTheme.Choice choice = getThemeChoice();
        if (choice != ChromiteTheme.getSavedChoice()) {
            choice.apply();
        }
    }

    /**
     * A page of the list: its id (for {@link #open(Frame, String)} and the card), its title, group title and icon (shown by the list),
     * its component and its saving state.
     */
    private record Page(@NotNull String id, @NotNull String title, @NotNull String group, @NotNull String iconId, @NotNull JComponent component,
                        @NotNull OptionsSaving.PageState state) {
        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * A group title of the list (not selectable).
     */
    private record Group(@NotNull String title) {
        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * Holds a page in its scroll pane: as wide as the view (no horizontal scrolling), as high as the view when the page is lower.
     */
    private static final class PageView extends JPanel implements Scrollable {
        PageView(@NotNull JComponent page) {
            super(new BorderLayout());
            add(page, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return UIScale.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
        }
    }

    /**
     * Group titles small and grey with space above; pages with their icon, on roomy rows (the rounded selection is FlatLaf's,
     * List.selectionArc).
     */
    private static final class PageListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            if (value instanceof Group group) {
                super.getListCellRendererComponent(list, group.title(), index, false, false);
                setIcon(null);
                setForeground(UIManager.getColor("Chromite.secondaryForeground")); //$NON-NLS-1$
                setFont(list.getFont().deriveFont(list.getFont().getSize2D() - 1));
                setBorder(BorderFactory.createEmptyBorder(UIScale.scale(index == 0 ? 2 : 14), UIScale.scale(10), UIScale.scale(4), UIScale.scale(10)));
            } else {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Page page) {
                    setIcon(UiIcons.get(page.iconId()));
                }
                setIconTextGap(UIScale.scale(10));
                setBorder(BorderFactory.createEmptyBorder(UIScale.scale(7), UIScale.scale(10), UIScale.scale(7), UIScale.scale(10)));
            }
            return this;
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
     * Theme (Editor Colors), icon style and Object Explorer action items show their labels.
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
