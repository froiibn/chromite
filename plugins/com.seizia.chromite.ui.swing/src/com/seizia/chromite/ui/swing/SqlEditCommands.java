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

import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPIdentifierCase;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLModelPreferences;
import org.jkiss.dbeaver.model.sql.SQLSyntaxManager;
import org.jkiss.dbeaver.model.sql.format.SQLFormatUtils;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.Element;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Text commands of the SQL editor: Format SQL, upper/lower case, comment/uncomment lines, increase/decrease indent.
 *
 * 설계:
 * <ul>
 *     <li>Format SQL은 SWT 편집기의 서식 전략({@code SQLFormattingStrategy.format})과 같이 DBeaver 서식기를 쓴다:
 *     연결 방언과 연결 설정 저장소로 {@link SQLSyntaxManager}를 만들고 {@link SQLFormatUtils#formatSQL}이 설정의 서식기
 *     ({@link SQLModelPreferences#SQL_FORMAT_FORMATTER}: default, compact)와 키워드 대소문자({@link ModelPreferences#SQL_FORMAT_KEYWORD_CASE})를 적용한다.
 *     두 설정은 플랫폼 설정 저장소에 두고(SWT 설정과 같은 키) Tools &gt; Options에서 바꾼다. 연결 설정 저장소는 플랫폼 저장소를 부모로 가지므로
 *     연결별로 따로 정하지 않은 연결에 적용된다. 연결이 없으면 표준 SQL 방언이다.</li>
 *     <li>선택이 있으면 선택한 부분만, 없으면 문서 전체를 바꾼다. 바꾸기는 되돌리기 한 번에 취소되도록 한 편집({@code beginAtomicEdit})으로 묶는다.</li>
 *     <li>주석 넣기·빼기는 선택한 줄(또는 커서 줄) 단위로, 방언의 한 줄 주석 기호(대부분 {@code --})를 쓴다. 넣을 때는 빈 줄을 빼고
 *     가장 작은 들여쓰기 위치에 넣는다(SQL Server 편집기와 같은 모양). 들여쓰기는 RSyntaxTextArea의 동작(여러 줄 선택 시 Tab, Shift+Tab)을 그대로 부른다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 */
final class SqlEditCommands {

    private static final Log log = Log.getLog(SqlEditCommands.class);

    /**
     * Formatter ids of {@code org.jkiss.dbeaver.sqlFormatter} offered in Options (the external formatter needs a command line).
     */
    static final String FORMATTER_DEFAULT = "default"; //$NON-NLS-1$
    static final String FORMATTER_COMPACT = "compact"; //$NON-NLS-1$

    private SqlEditCommands() {
    }

    /**
     * Formats the selection, or the whole text without a selection.
     */
    static void format(@NotNull SqlEditorPanel.SqlTextArea textArea, @Nullable DBPDataSourceContainer container) {
        replaceSelectionOrAll(textArea, text -> formatSql(text, container));
    }

    @NotNull
    static String formatSql(@NotNull String text, @Nullable DBPDataSourceContainer container) {
        DBPDataSource dataSource = container != null && container.isConnected() ? container.getDataSource() : null;
        SQLDialect dialect = getDialect(container);
        DBPPreferenceStore preferences = container != null ? container.getPreferenceStore() : DBWorkbench.getPlatform().getPreferenceStore();
        SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dialect, preferences);
        try {
            String formatted = SQLFormatUtils.formatSQL(dataSource, syntaxManager, text, getIndent());
            // The formatter drops trailing line breaks of the selection; keep them so the following line stays apart
            String trailing = text.substring(text.stripTrailing().length());
            return formatted.stripTrailing() + (trailing.contains("\n") ? trailing : ""); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (Exception e) {
            log.warn("SQL formatting failed", e);
            return text;
        }
    }

    @NotNull
    private static String getIndent() {
        return "    "; //$NON-NLS-1$
    }

    @NotNull
    static SQLDialect getDialect(@Nullable DBPDataSourceContainer container) {
        if (container == null) {
            return BasicSQLDialect.INSTANCE;
        }
        DBPDataSource dataSource = container.isConnected() ? container.getDataSource() : null;
        if (dataSource != null) {
            return dataSource.getSQLDialect();
        }
        try {
            return container.getScriptDialect().createInstance();
        } catch (Exception e) {
            log.debug("Can't create the SQL dialect of " + container.getName(), e);
            return BasicSQLDialect.INSTANCE;
        }
    }

    /**
     * Upper or lower case of the selection, or of the word at the caret without a selection.
     */
    static void changeCase(@NotNull SqlEditorPanel.SqlTextArea textArea, boolean upper) {
        if (textArea.getSelectionStart() == textArea.getSelectionEnd()) {
            selectWordAtCaret(textArea);
        }
        if (textArea.getSelectionStart() == textArea.getSelectionEnd()) {
            return;
        }
        int start = textArea.getSelectionStart();
        int end = textArea.getSelectionEnd();
        String text = textArea.getSelectedText();
        String changed = upper ? text.toUpperCase(Locale.ROOT) : text.toLowerCase(Locale.ROOT);
        if (!changed.equals(text)) {
            textArea.replaceSelection(changed);
            textArea.select(start, end);
        }
    }

    private static void selectWordAtCaret(@NotNull SqlEditorPanel.SqlTextArea textArea) {
        String text = textArea.getText();
        int caret = textArea.getCaretPosition();
        int start = caret;
        while (start > 0 && isWordPart(text.charAt(start - 1))) {
            start--;
        }
        int end = caret;
        while (end < text.length() && isWordPart(text.charAt(end))) {
            end++;
        }
        textArea.select(start, end);
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Puts the dialect's line comment before every non-blank selected line (at the smallest indentation).
     */
    static void comment(@NotNull SqlEditorPanel.SqlTextArea textArea, @Nullable DBPDataSourceContainer container) {
        String prefix = getLineComment(container);
        int[] lines = getSelectedLines(textArea);
        Element root = textArea.getDocument().getDefaultRootElement();
        String text = textArea.getText();
        int indent = Integer.MAX_VALUE;
        for (int line = lines[0]; line <= lines[1]; line++) {
            String lineText = getLineText(text, root.getElement(line));
            if (!lineText.isBlank()) {
                indent = Math.min(indent, lineText.length() - lineText.stripLeading().length());
            }
        }
        if (indent == Integer.MAX_VALUE) {
            return;
        }
        textArea.beginAtomicEdit();
        try {
            // Bottom-up, so the offsets of the lines above stay valid
            for (int line = lines[1]; line >= lines[0]; line--) {
                Element element = root.getElement(line);
                if (!getLineText(textArea.getText(), element).isBlank()) {
                    textArea.getDocument().insertString(element.getStartOffset() + indent, prefix, null);
                }
            }
        } catch (BadLocationException e) {
            log.debug(e);
        } finally {
            textArea.endAtomicEdit();
        }
        selectLines(textArea, lines);
    }

    /**
     * Removes the dialect's line comment from the start (after indentation) of every selected line.
     */
    static void uncomment(@NotNull SqlEditorPanel.SqlTextArea textArea, @Nullable DBPDataSourceContainer container) {
        String prefix = getLineComment(container);
        int[] lines = getSelectedLines(textArea);
        Element root = textArea.getDocument().getDefaultRootElement();
        textArea.beginAtomicEdit();
        try {
            for (int line = lines[1]; line >= lines[0]; line--) {
                Element element = root.getElement(line);
                String lineText = getLineText(textArea.getText(), element);
                String stripped = lineText.stripLeading();
                if (stripped.startsWith(prefix)) {
                    textArea.getDocument().remove(element.getStartOffset() + lineText.length() - stripped.length(), prefix.length());
                }
            }
        } catch (BadLocationException e) {
            log.debug(e);
        } finally {
            textArea.endAtomicEdit();
        }
        selectLines(textArea, lines);
    }

    /**
     * Increase indent: the text area's Tab (indents every line of a multi-line selection).
     */
    static void indent(@NotNull SqlEditorPanel.SqlTextArea textArea) {
        runTextAction(textArea, DefaultEditorKit.insertTabAction);
    }

    /**
     * Decrease indent: the text area's Shift+Tab.
     */
    static void outdent(@NotNull SqlEditorPanel.SqlTextArea textArea) {
        runTextAction(textArea, RSyntaxTextAreaEditorKit.rstaDecreaseIndentAction);
    }

    private static void runTextAction(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull String actionKey) {
        Action action = textArea.getActionMap().get(actionKey);
        if (action != null) {
            action.actionPerformed(new ActionEvent(textArea, ActionEvent.ACTION_PERFORMED, actionKey));
        }
    }

    @NotNull
    private static String getLineComment(@Nullable DBPDataSourceContainer container) {
        String[] comments = getDialect(container).getSingleLineComments();
        return comments != null && comments.length > 0 && !CommonUtils.isEmpty(comments[0]) ? comments[0] : "--"; //$NON-NLS-1$
    }

    /**
     * First and last line of the selection; a selection that ends at the start of a line does not include that line.
     */
    @NotNull
    private static int[] getSelectedLines(@NotNull SqlEditorPanel.SqlTextArea textArea) {
        Element root = textArea.getDocument().getDefaultRootElement();
        int start = textArea.getSelectionStart();
        int end = textArea.getSelectionEnd();
        int first = root.getElementIndex(start);
        int last = root.getElementIndex(end);
        if (end > start && last > first && root.getElement(last).getStartOffset() == end) {
            last--;
        }
        return new int[]{first, last};
    }

    private static void selectLines(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull int[] lines) {
        Element root = textArea.getDocument().getDefaultRootElement();
        int start = root.getElement(lines[0]).getStartOffset();
        int end = Math.min(root.getElement(lines[1]).getEndOffset() - 1, textArea.getDocument().getLength());
        if (lines[0] == lines[1]) {
            // A single line keeps a caret, like the editor of SQL Server tools
            textArea.setCaretPosition(Math.min(textArea.getCaretPosition(), end));
        } else {
            textArea.select(start, end);
        }
    }

    @NotNull
    private static String getLineText(@NotNull String text, @NotNull Element line) {
        int end = Math.min(line.getEndOffset(), text.length());
        String lineText = text.substring(line.getStartOffset(), end);
        return lineText.endsWith("\n") ? lineText.substring(0, lineText.length() - 1) : lineText; //$NON-NLS-1$
    }

    /**
     * Replaces the selection (or the whole text) with the transformed text as one undoable edit and selects the result.
     */
    private static void replaceSelectionOrAll(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull UnaryOperator<String> transform) {
        boolean selection = textArea.getSelectionStart() != textArea.getSelectionEnd();
        int start = selection ? textArea.getSelectionStart() : 0;
        int end = selection ? textArea.getSelectionEnd() : textArea.getDocument().getLength();
        String original = textArea.getText().substring(start, end);
        if (original.isBlank()) {
            return;
        }
        String result = transform.apply(original);
        if (result.equals(original)) {
            return;
        }
        int caret = textArea.getCaretPosition();
        textArea.beginAtomicEdit();
        try {
            textArea.getDocument().remove(start, end - start);
            textArea.getDocument().insertString(start, result, null);
        } catch (BadLocationException e) {
            log.debug(e);
        } finally {
            textArea.endAtomicEdit();
        }
        if (selection) {
            textArea.select(start, start + result.length());
        } else {
            textArea.setCaretPosition(Math.min(caret, textArea.getDocument().getLength()));
        }
    }

    //////////////////////////////////////////////////////////
    // Edit > Advanced

    /**
     * Edit &gt; Advanced: the editing commands of the active query window. The keys are handled by the text area
     * ({@code SqlTextArea}); the menu shows them.
     *
     * @param activeTextArea text area of the active document tab, or null
     */
    @NotNull
    static JMenu createAdvancedMenu(@NotNull Supplier<SqlEditorPanel.SqlTextArea> activeTextArea) {
        JMenu menu = new JMenu("Advanced");
        menu.setMnemonic(KeyEvent.VK_V);
        List<JMenuItem> items = new ArrayList<>();
        addItem(menu, items, activeTextArea, "Format SQL", "Ctrl+K, Ctrl+D", //$NON-NLS-2$
            KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), SqlEditorPanel.SqlTextArea.Command.FORMAT);
        menu.addSeparator();
        addItem(menu, items, activeTextArea, "Make Uppercase", null,
            KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), SqlEditorPanel.SqlTextArea.Command.UPPER_CASE);
        addItem(menu, items, activeTextArea, "Make Lowercase", null,
            KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), SqlEditorPanel.SqlTextArea.Command.LOWER_CASE);
        menu.addSeparator();
        addItem(menu, items, activeTextArea, "Comment Selection", "Ctrl+K, Ctrl+C", null, SqlEditorPanel.SqlTextArea.Command.COMMENT); //$NON-NLS-2$
        addItem(menu, items, activeTextArea, "Uncomment Selection", "Ctrl+K, Ctrl+U", null, SqlEditorPanel.SqlTextArea.Command.UNCOMMENT); //$NON-NLS-2$
        menu.addSeparator();
        addItem(menu, items, activeTextArea, "Increase Indent", null,
            KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), SqlEditorPanel.SqlTextArea.Command.INDENT);
        addItem(menu, items, activeTextArea, "Decrease Indent", null,
            KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), SqlEditorPanel.SqlTextArea.Command.OUTDENT);
        menu.addSeparator();
        addItem(menu, items, activeTextArea, "Insert Template…", "Ctrl+K, Ctrl+X", null, SqlEditorPanel.SqlTextArea.Command.INSERT_TEMPLATE); //$NON-NLS-2$
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                SqlEditorPanel.SqlTextArea textArea = activeTextArea.get();
                boolean editable = textArea != null && textArea.isEditable();
                items.forEach(item -> item.setEnabled(editable));
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        return menu;
    }

    private static void addItem(
        @NotNull JMenu menu,
        @NotNull List<JMenuItem> items,
        @NotNull Supplier<SqlEditorPanel.SqlTextArea> activeTextArea,
        @NotNull String text,
        @Nullable String chord,
        @Nullable KeyStroke shortcut,
        @NotNull SqlEditorPanel.SqlTextArea.Command command
    ) {
        String keys = chord;
        if (shortcut != null && shortcut.getKeyCode() == KeyEvent.VK_TAB) {
            // Tab must stay the text area's key: named in the text, not registered as a window accelerator
            keys = shortcut.getModifiers() == 0 ? "Tab" : "Shift+Tab"; //$NON-NLS-1$ //$NON-NLS-2$
            shortcut = null;
        }
        // A menu accelerator is one key stroke; two-key chords (Ctrl+K, Ctrl+D) are named in the item text
        JMenuItem item = new JMenuItem(keys == null ? text : text + "    (" + keys + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        item.setAccelerator(shortcut);
        item.addActionListener(e -> {
            SqlEditorPanel.SqlTextArea textArea = activeTextArea.get();
            if (textArea != null) {
                textArea.requestFocusInWindow();
                textArea.runCommand(command);
            }
        });
        items.add(item);
        menu.add(item);
    }

    //////////////////////////////////////////////////////////
    // Settings (Tools > Options)

    /**
     * Saved formatter id ({@link #FORMATTER_DEFAULT} or {@link #FORMATTER_COMPACT}; other formatters show as default).
     */
    @NotNull
    static String getFormatterId() {
        String id = getPreferences().getString(SQLModelPreferences.SQL_FORMAT_FORMATTER);
        return FORMATTER_COMPACT.equalsIgnoreCase(id) ? FORMATTER_COMPACT : FORMATTER_DEFAULT;
    }

    /**
     * Saved keyword case, or null for the database's own case (the default).
     */
    @Nullable
    static DBPIdentifierCase getKeywordCase() {
        String name = getPreferences().getString(ModelPreferences.SQL_FORMAT_KEYWORD_CASE);
        return CommonUtils.isEmpty(name) ? null : CommonUtils.valueOf(DBPIdentifierCase.class, name.toUpperCase(Locale.ENGLISH), null);
    }

    static void saveFormatSettings(@NotNull String formatterId, @Nullable DBPIdentifierCase keywordCase) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(SQLModelPreferences.SQL_FORMAT_FORMATTER, formatterId);
        preferences.setValue(ModelPreferences.SQL_FORMAT_KEYWORD_CASE, keywordCase == null ? "" : keywordCase.name()); //$NON-NLS-1$
        PrefUtils.savePreferenceStore(preferences);
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return DBWorkbench.getPlatform().getPreferenceStore();
    }
}
