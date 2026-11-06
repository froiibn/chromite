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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.Document;
import org.fife.ui.rtextarea.RTextArea;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.semantics.completion.SQLCompletionProposalComparator;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * SQL 편집기의 자동완성 팝업(DataGrip·SSMS IntelliSense처럼 커서 아래 목록). 제안 계산은 {@link SqlCompletionAnalysis}가 작업 스레드에서 한다.
 *
 * 설계:
 * <ul>
 *     <li>띄우는 때: Ctrl+Space(편집기 키와 Query > Complete), 구조 구분자 {@code .}을 입력했을 때, 그리고 설정이 켜져 있으면 글자·{@code _}를
 *     입력했을 때(기본 켬, Query > Auto-Complete While Typing). 자동으로 띄울 때는 입력이 멈춘 뒤 지연 시간({@code sqlEditor.completion.autoActivationDelay},
 *     기본 {@value #DEFAULT_ACTIVATION_DELAY}ms, Tools &gt; Options에서 바꿈)이 지나야 분석을 시작한다. SWT 편집기도 같은 두 가지 자동 실행(점, 글자)이 기본으로 켜져 있다.</li>
 *     <li>비동기: 분석은 {@link AbstractJob}에서 돌고 EDT는 기다리지 않는다. 분석 중에 문서가 바뀌면(계속 입력) 그 Job을 취소하고
 *     지연 시간 뒤 새 위치에서 다시 분석한다. 그래서 결과는 늘 마지막 입력 기준이고, 늦게 끝난 이전 결과는 요청 번호로 버린다.</li>
 *     <li>목록이 뜬 뒤 이름 글자를 더 입력하거나 지우면 다시 분석하지 않고 받은 제안을 거른다. 거르기는 엔진의 일치 규칙
 *     ({@code SQLQueryCompletionProposal.validate}: 이름 안 어디든 일치, 점수)을 그대로 쓰고, 정렬은 SWT 팝업과 같은
 *     {@link SQLCompletionProposalComparator}(점수, 이름순, 종류)다. 남는 것이 없거나, 이름이 아닌 글자를 입력하거나, 커서가 단어 앞으로 가면 닫는다.
 *     {@code .}을 입력하면 닫고 새로 분석한다(별칭 뒤 열 목록).</li>
 *     <li>넣기: Enter·Tab·두 번 누르기. SWT 새 엔진 제안과 같이 교체 시작 위치부터 커서까지를 엔진이 만든 글자(따옴표 규칙, 뒤 공백 포함)로 바꾼다.
 *     되돌리기 한 번에 취소되도록 한 편집으로 묶는다. Ctrl+Space로 띄웠는데 제안이 하나뿐이면 목록 없이 바로 넣는다(SWT 기본 설정과 같음).</li>
 *     <li>팝업은 초점을 가져가지 않는 소유 창({@link JWindow}, {@code focusableWindowState = false})이라 입력은 계속 편집기로 가고,
 *     목록 이동 키(위·아래, Page Up/Down, Enter, Tab, Esc)는 편집기의 키 리스너가 먼저 받아 소비한다. 편집기가 초점을 잃거나 창이 움직이면 닫는다.
 *     테마를 바꾸면 FlatLaf가 모든 창을 다시 그리므로 숨은 팝업도 함께 바뀌고, 팝업 창을 처음 만들 때는 편집기와 함께 만든 목록을 현재 테마로 맞춘다.</li>
 *     <li>RSyntaxTextArea의 AutoComplete 라이브러리({@code com.fifesoft:autocomplete})는 쓰지 않았다. 3.3.2 소스 기준으로
 *     {@code AutoCompletion.refreshPopupWindow}가 입력할 때마다 EDT에서 {@code CompletionProvider.getCompletions}를 동기로 부르고,
 *     {@code insertCompletion}은 커서 앞 "이미 입력한 글자"만 바꾼다. DB 메타데이터를 읽는 분석을 EDT 밖에서 취소하며 돌리고 엔진의 교체 범위를 쓰려면
 *     제공자, 활성화, 넣기를 모두 바꿔야 해서, 남는 것은 팝업 창뿐이라 의존성(빌드 때 받기, 체크섬, NOTICE)을 늘리지 않고 직접 만들었다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 F단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 * - 2026-11-06 P1: 자동완성 지연 설정 접근자와 처음 쓸 때 기본값 선언(Tools &gt; Options) (docs/chromite/swing-frontend.md "P1 디자인 적용")
 */
final class SqlCompletionPopup {

    private static final Log log = Log.getLog(SqlCompletionPopup.class);

    /**
     * Auto-activation after typing a letter or {@code _} (the structure separator always activates).
     */
    static final String PREF_AUTO_ACTIVATE_ON_LETTERS = "sqlEditor.completion.autoActivateOnLetters"; //$NON-NLS-1$
    /**
     * Pause after the last keystroke before an auto-activated analysis starts, in milliseconds.
     */
    static final String PREF_AUTO_ACTIVATION_DELAY = "sqlEditor.completion.autoActivationDelay"; //$NON-NLS-1$
    static final int DEFAULT_ACTIVATION_DELAY = 200;

    private static final int MAX_VISIBLE_ROWS = 12;
    // EDT only
    private static boolean defaultsSet;
    private static final int MIN_WIDTH = 280;
    private static final int MAX_WIDTH = 640;

    private final RTextArea textArea;
    private final Supplier<DBPDataSourceContainer> connectionSupplier;
    private final Timer activationTimer;
    private final SQLCompletionProposalComparator comparator =
        new SQLCompletionProposalComparator(SqlCompletionAnalysis.SORT_ALPHABETICALLY, SqlCompletionAnalysis.SEARCH_INSIDE_NAMES);

    // EDT only
    private int requestSerial;
    @Nullable
    private AbstractJob job;
    private boolean pendingExplicit;
    private boolean pending;
    @Nullable
    private SqlCompletionAnalysis.Result shown;
    /**
     * Start of the word being completed; edits before it end the session.
     */
    private int anchor;
    private boolean inserting;
    private boolean refilterQueued;
    @Nullable
    private JWindow window;
    private final DefaultListModel<SqlCompletionAnalysis.Proposal> listModel = new DefaultListModel<>();
    private final JList<SqlCompletionAnalysis.Proposal> list = new JList<>(listModel);
    @Nullable
    private Window listenedOwner;
    private final ComponentAdapter ownerListener = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) {
            hide();
        }

        @Override
        public void componentResized(ComponentEvent e) {
            hide();
        }
    };

    /**
     * @param connectionSupplier connection of the editor at the time of the request (may be null)
     */
    SqlCompletionPopup(@NotNull RTextArea textArea, @NotNull Supplier<DBPDataSourceContainer> connectionSupplier) {
        this.textArea = textArea;
        this.connectionSupplier = connectionSupplier;
        activationTimer = new Timer(DEFAULT_ACTIVATION_DELAY, e -> start(pendingExplicit));
        activationTimer.setRepeats(false);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ProposalRenderer());
        list.setFocusable(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    insertSelected();
                }
            }
        });

        textArea.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "chromite.complete"); //$NON-NLS-1$
        textArea.getActionMap().put("chromite.complete", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(ActionEvent e) {
                complete();
            }
        });
        // Key listeners run before the key bindings, so the list keys never reach the editor while the popup is open
        textArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKeyPressed(e);
            }
        });
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                documentChanged(e, true);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                documentChanged(e, false);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Attributes only
            }
        });
        textArea.addCaretListener(e -> {
            if (isVisible()) {
                queueRefilter();
            }
        });
        textArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                cancel();
            }
        });
    }

    /**
     * Ctrl+Space: proposals for the caret position; connects the editor's connection if needed.
     */
    void complete() {
        activationTimer.stop();
        start(true);
    }

    /**
     * Stops a running analysis and closes the popup (tab closed, focus lost).
     */
    void cancel() {
        activationTimer.stop();
        cancelJob();
        pending = false;
        hide();
    }

    static boolean isAutoActivateOnLetters() {
        return getPreferences().getBoolean(PREF_AUTO_ACTIVATE_ON_LETTERS);
    }

    static void setAutoActivateOnLetters(boolean enabled) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_AUTO_ACTIVATE_ON_LETTERS, enabled);
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * Pause after the last keystroke before an auto-activated analysis starts, in milliseconds.
     */
    static int getAutoActivationDelay() {
        return Math.max(0, getPreferences().getInt(PREF_AUTO_ACTIVATION_DELAY));
    }

    static void setAutoActivationDelay(int delay) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_AUTO_ACTIVATION_DELAY, Math.max(0, delay));
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * The bundle preferences with this popup's defaults; the defaults are declared on first use, because Tools &gt; Options
     * may read the settings before any query window (and its popup) exists.
     */
    @NotNull
    private static DBPPreferenceStore getPreferences() {
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        if (!defaultsSet) {
            preferences.setDefault(PREF_AUTO_ACTIVATE_ON_LETTERS, true);
            preferences.setDefault(PREF_AUTO_ACTIVATION_DELAY, DEFAULT_ACTIVATION_DELAY);
            defaultsSet = true;
        }
        return preferences;
    }

    private boolean isVisible() {
        return window != null && window.isVisible();
    }

    //////////////////////////////////////////////////////////
    // Activation and analysis

    private void documentChanged(@NotNull DocumentEvent e, boolean insert) {
        if (inserting) {
            return;
        }
        if (pending) {
            // The user keeps typing: the running analysis is for an old text; analyze again once typing pauses
            cancelJob();
            schedule(pendingExplicit);
            return;
        }
        String inserted = insert && e.getLength() == 1 ? getText(e.getOffset(), 1) : null;
        if (isVisible()) {
            if (e.getOffset() < anchor || (insert && !isNamePart(getText(e.getOffset(), e.getLength())))) {
                hide();
                if (".".equals(inserted)) { //$NON-NLS-1$
                    schedule(false);
                }
            } else {
                queueRefilter();
            }
            return;
        }
        if (inserted != null && (inserted.equals(".") //$NON-NLS-1$
            || (isAutoActivateOnLetters() && (Character.isLetter(inserted.charAt(0)) || inserted.charAt(0) == '_')))) {
            schedule(false);
        }
    }

    private void schedule(boolean explicit) {
        pending = true;
        pendingExplicit = explicit;
        activationTimer.setInitialDelay(getAutoActivationDelay());
        activationTimer.restart();
    }

    private void start(boolean explicit) {
        cancelJob();
        hide();
        int serial = ++requestSerial;
        DBPDataSourceContainer container = connectionSupplier.get();
        if (container == null) {
            pending = false;
            if (explicit) {
                // No connection chosen for this query window yet
                UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            }
            return;
        }
        pending = true;
        pendingExplicit = explicit;
        SqlCompletionAnalysis.Request request = new SqlCompletionAnalysis.Request(
            container, textArea.getText(), textArea.getCaretPosition(), explicit);
        AbstractJob analysisJob = new AbstractJob("Search SQL completion proposals") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                SqlCompletionAnalysis.Result result = null;
                try {
                    result = SqlCompletionAnalysis.compute(monitor, request);
                } catch (Throwable e) {
                    log.debug("SQL completion analysis failed", e);
                }
                SqlCompletionAnalysis.Result finalResult = monitor.isCanceled() ? null : result;
                SwingUtilities.invokeLater(() -> analysisDone(serial, finalResult));
                return Status.OK_STATUS;
            }
        };
        analysisJob.setSystem(true);
        analysisJob.setUser(false);
        job = analysisJob;
        analysisJob.schedule();
    }

    private void cancelJob() {
        if (job != null) {
            job.cancel();
            job = null;
        }
    }

    private void analysisDone(int serial, @Nullable SqlCompletionAnalysis.Result result) {
        if (serial != requestSerial || !pending) {
            // A newer request replaced this one, or the session was canceled
            return;
        }
        pending = false;
        job = null;
        if (result == null || result.proposals().isEmpty() || !textArea.isFocusOwner()) {
            return;
        }
        shown = result;
        anchor = result.requestOffset();
        for (SqlCompletionAnalysis.Proposal proposal : result.proposals()) {
            anchor = Math.min(anchor, proposal.getReplacementOffset());
        }
        List<SqlCompletionAnalysis.Proposal> proposals = filter(result);
        if (proposals.isEmpty()) {
            shown = null;
            return;
        }
        if (result.request().explicit() && proposals.size() == 1) {
            // As the SWT editor's default "insert single proposals automatically"
            insert(proposals.getFirst());
            return;
        }
        showList(proposals);
    }

    /**
     * Proposals that still match the text typed since the request, best first.
     */
    @NotNull
    private List<SqlCompletionAnalysis.Proposal> filter(@NotNull SqlCompletionAnalysis.Result result) {
        int caret = textArea.getCaretPosition();
        if (caret < anchor) {
            return List.of();
        }
        // The engine's matching reads the typed text from a JFace document
        Document document = new Document(textArea.getText());
        List<SqlCompletionAnalysis.Proposal> matching = new ArrayList<>();
        for (SqlCompletionAnalysis.Proposal proposal : result.proposals()) {
            if (proposal.getReplacementOffset() <= caret && proposal.validate(document, caret, null)) {
                matching.add(proposal);
            }
        }
        matching.sort(comparator);
        return matching;
    }

    private void queueRefilter() {
        if (refilterQueued) {
            return;
        }
        refilterQueued = true;
        // After the caret has followed the document change
        SwingUtilities.invokeLater(() -> {
            refilterQueued = false;
            if (!isVisible() || shown == null) {
                return;
            }
            List<SqlCompletionAnalysis.Proposal> proposals = filter(shown);
            if (proposals.isEmpty()) {
                hide();
            } else {
                showList(proposals);
            }
        });
    }

    //////////////////////////////////////////////////////////
    // Popup

    private void showList(@NotNull List<SqlCompletionAnalysis.Proposal> proposals) {
        SqlCompletionAnalysis.Proposal selected = list.getSelectedValue();
        listModel.clear();
        listModel.addAll(proposals);
        int selectedIndex = selected != null ? proposals.indexOf(selected) : -1;
        list.setSelectedIndex(Math.max(selectedIndex, 0));
        list.ensureIndexIsVisible(list.getSelectedIndex());

        JWindow popup = getWindow();
        if (popup == null) {
            return;
        }
        list.setVisibleRowCount(Math.min(MAX_VISIBLE_ROWS, proposals.size()));
        popup.pack();
        Dimension size = popup.getPreferredSize();
        size.width = Math.clamp(size.width, MIN_WIDTH, MAX_WIDTH);
        popup.setSize(size);
        popup.setLocation(computeLocation(size));
        if (!popup.isVisible()) {
            popup.setVisible(true);
        }
    }

    @Nullable
    private JWindow getWindow() {
        Window owner = SwingUtilities.getWindowAncestor(textArea);
        if (owner == null) {
            return null;
        }
        if (window == null || window.getOwner() != owner) {
            if (window != null) {
                window.dispose();
            }
            window = new JWindow(owner);
            window.setFocusableWindowState(false);
            window.setType(Window.Type.POPUP);
            JScrollPane scrollPane = new JScrollPane(list) {
                @Override
                public void updateUI() {
                    super.updateUI();
                    // The popup menu border of the current look and feel, also after a theme switch
                    setBorder(UIManager.getBorder("PopupMenu.border")); //$NON-NLS-1$
                }
            };
            scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            window.getContentPane().add(scrollPane);
            // The list was created with the editor and missed theme switches made before the popup first appeared
            SwingUtilities.updateComponentTreeUI(window);
        }
        if (listenedOwner != owner) {
            if (listenedOwner != null) {
                listenedOwner.removeComponentListener(ownerListener);
            }
            owner.addComponentListener(ownerListener);
            listenedOwner = owner;
        }
        return window;
    }

    /**
     * Below the line of the completed word, or above it when the screen has no room below.
     */
    @NotNull
    private Point computeLocation(@NotNull Dimension size) {
        Rectangle wordBounds;
        try {
            Rectangle2D view = textArea.modelToView2D(Math.min(anchor, textArea.getDocument().getLength()));
            wordBounds = view != null ? view.getBounds() : new Rectangle();
        } catch (BadLocationException e) {
            wordBounds = new Rectangle();
        }
        Point below = new Point(wordBounds.x, wordBounds.y + wordBounds.height);
        Point above = new Point(wordBounds.x, wordBounds.y);
        SwingUtilities.convertPointToScreen(below, textArea);
        SwingUtilities.convertPointToScreen(above, textArea);
        GraphicsConfiguration configuration = textArea.getGraphicsConfiguration();
        Rectangle screen = configuration != null ? configuration.getBounds() : new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        if (configuration != null) {
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            screen = new Rectangle(screen.x + insets.left, screen.y + insets.top,
                screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom);
        }
        int x = Math.max(screen.x, Math.min(below.x, screen.x + screen.width - size.width));
        int y = below.y + size.height <= screen.y + screen.height ? below.y : Math.max(screen.y, above.y - size.height);
        return new Point(x, y);
    }

    private void hide() {
        shown = null;
        if (window != null && window.isVisible()) {
            window.setVisible(false);
        }
    }

    private void handleKeyPressed(@NotNull KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE && pending) {
            activationTimer.stop();
            cancelJob();
            pending = false;
            e.consume();
            return;
        }
        if (!isVisible() || e.isAltDown() || e.isControlDown() || e.isMetaDown()) {
            return;
        }
        int size = listModel.getSize();
        int index = list.getSelectedIndex();
        int page = Math.max(1, list.getVisibleRowCount() - 1);
        switch (e.getKeyCode()) {
            case KeyEvent.VK_DOWN -> select(index + 1 < size ? index + 1 : 0);
            case KeyEvent.VK_UP -> select(index > 0 ? index - 1 : size - 1);
            case KeyEvent.VK_PAGE_DOWN -> select(Math.min(size - 1, index + page));
            case KeyEvent.VK_PAGE_UP -> select(Math.max(0, index - page));
            case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                if (e.isShiftDown()) {
                    return;
                }
                insertSelected();
            }
            case KeyEvent.VK_ESCAPE -> hide();
            default -> {
                return;
            }
        }
        e.consume();
    }

    private void select(int index) {
        if (index >= 0 && index < listModel.getSize()) {
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
        }
    }

    private void insertSelected() {
        SqlCompletionAnalysis.Proposal proposal = list.getSelectedValue();
        if (proposal != null) {
            insert(proposal);
        }
    }

    /**
     * Replaces the text from the proposal's replacement offset to the caret, as the SWT
     * {@code SQLEditorQueryCompletionProposal.apply}; the caret ends after the inserted text.
     */
    private void insert(@NotNull SqlCompletionAnalysis.Proposal proposal) {
        hide();
        int caret = textArea.getCaretPosition();
        int start = proposal.getReplacementOffset();
        if (start < 0 || start > caret) {
            return;
        }
        String text = proposal.getInsertText();
        inserting = true;
        textArea.beginAtomicEdit();
        try {
            textArea.replaceRange(text, start, caret);
            textArea.setCaretPosition(start + text.length());
        } finally {
            textArea.endAtomicEdit();
            inserting = false;
        }
    }

    @NotNull
    private String getText(int offset, int length) {
        try {
            return textArea.getDocument().getText(offset, length);
        } catch (BadLocationException e) {
            return "";
        }
    }

    /**
     * Characters of an unquoted name: typing them keeps the popup and filters it.
     */
    private static boolean isNamePart(@NotNull String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                return false;
            }
        }
        return !text.isEmpty();
    }

    /**
     * Icon of the object kind (navigator icons), the name, and the engine's decoration (e.g. the column's table) in gray.
     */
    private static final class ProposalRenderer extends JPanel implements ListCellRenderer<SqlCompletionAnalysis.Proposal> {
        private final JLabel nameLabel = new JLabel();
        private final JLabel decorationLabel = new JLabel();

        ProposalRenderer() {
            super(new BorderLayout(12, 0));
            nameLabel.setIconTextGap(6);
            add(nameLabel, BorderLayout.CENTER);
            add(decorationLabel, BorderLayout.EAST);
            setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 6));
        }

        @Override
        public Component getListCellRendererComponent(
            JList<? extends SqlCompletionAnalysis.Proposal> list,
            SqlCompletionAnalysis.Proposal value,
            int index,
            boolean isSelected,
            boolean cellHasFocus
        ) {
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            nameLabel.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            Color decorationColor = UIManager.getColor("Label.disabledForeground"); //$NON-NLS-1$
            decorationLabel.setForeground(isSelected ? list.getSelectionForeground() : decorationColor);
            nameLabel.setFont(list.getFont());
            decorationLabel.setFont(list.getFont());
            nameLabel.setText(value.getDisplayString());
            nameLabel.setIcon(NodeIcons.getIcon(value.getObjectImage()));
            decorationLabel.setText(CommonUtils.notEmpty(value.getDecoration()).strip());
            String description = value.getAdditionalProposalInfo();
            setToolTipText(CommonUtils.isEmpty(description) ? null : description);
            return this;
        }
    }
}
