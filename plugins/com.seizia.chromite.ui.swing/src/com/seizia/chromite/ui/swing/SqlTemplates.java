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

import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.XMLException;
import org.jkiss.utils.xml.XMLUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.DocumentFilter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL templates of the editor: type a template name and press Tab to insert it ({@code sel} + Tab).
 *
 * 설계: SWT 편집기의 템플릿({@code SQLTemplatesRegistry}, {@code SQLTemplateStore})은 JFace 템플릿 저장소라 SWT UI 플러그인에만 있다.
 * 같은 데이터를 Swing에서 쓰도록 필요한 부분만 다시 만들었다.
 * <ul>
 *     <li>기본 템플릿: SWT 플러그인의 {@code templates/default-templates.xml} 다섯 개(같은 id)와 Chromite의 짧은 이름 템플릿(sel, ins, upd, del).</li>
 *     <li>사용자 템플릿은 SWT와 같은 작업 공간 설정 파일({@value #CONFIG_FILE}, {@code DBConfigurationController})에 JFace
 *     {@code TemplateReaderWriter} 형식으로 둔다: id가 없는 항목은 사용자 템플릿, id가 기본 템플릿과 같으면 그 기본 템플릿을 바꾼 것,
 *     {@code deleted="true"}면 지운 것이다. 그래서 같은 작업 공간의 SWT 제품과 템플릿을 함께 쓴다.</li>
 *     <li>변수: {@code ${name}}(또는 {@code ${name:type(args)}})은 이름 글자로 넣고, 넣은 뒤 첫 변수를 선택한다. 템플릿을 넣은 동안에는
 *     SWT의 linked mode처럼 같은 이름 변수의 모든 자리가 함께 바뀌고, Tab이 다음 변수로, 마지막 변수 뒤에는 {@code ${cursor}}(없으면 템플릿 끝)로
 *     옮겨 간다({@link Session}). {@code $$}는 {@code $}다. 여러 줄 템플릿은 현재 줄의 들여쓰기를 이어 간다.
 *     문맥 변수 값 계산(테이블 이름 제안)은 하지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 설정 가져오기·되돌리기가 파일을 바꾼 뒤 다시 읽게 {@link #reload()} (docs/chromite/settings-transfer.md)
 * - 2026-11-28 여러 문장 템플릿이 잘 안 되던 결함(VOC V190): 같은 이름 변수는 첫 자리만 바뀌고 나머지는 변수 이름으로 남았고
 *   ({@code delete ... where deptno = deptno}), Tab이 마지막 변수 뒤 첫 변수로 돌아가 {@code ${cursor}}에 닿지 않았으며, 템플릿 바로 뒤에 친
 *   글까지 템플릿으로 쳐서 다음 템플릿 이름의 Tab을 가로챘다. 변수 자리를 묶어 함께 고치고 세션 끝을 정했다({@link Session}).
 *   템플릿 끝의 자기 이름을 다음 Tab이 다시 펼치지 않는다. 범위를 받는 {@link #expand(SqlEditorPanel.SqlTextArea, Template, int, int)}:
 *   Ctrl+K Ctrl+X 목록이 캐럿 앞 낱말까지 지웠다 (docs/chromite/swing-sql-editor-plus.md)
 */
final class SqlTemplates {

    private static final Log log = Log.getLog(SqlTemplates.class);

    /**
     * Workspace configuration file of the custom templates ({@code SQLTemplateStore.TEMPLATES_CONFIG_XML}).
     */
    static final String CONFIG_FILE = "templates.xml"; //$NON-NLS-1$
    private static final String CONTEXT = "sql"; //$NON-NLS-1$
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]*)}|\\$\\$"); //$NON-NLS-1$
    private static final String CURSOR_VARIABLE = "cursor"; //$NON-NLS-1$

    /**
     * One template.
     *
     * @param id      id of a built-in template, null for a template the user added
     * @param enabled disabled templates are not inserted
     */
    record Template(@Nullable String id, @NotNull String name, @NotNull String description, @NotNull String pattern, boolean enabled) {
    }

    /**
     * Built-in templates. The first five are the defaults of the SWT SQL editor (same ids and English text).
     * Descriptions follow the UI language; only a built-in template the user changed is saved, with its text as shown.
     */
    static final List<Template> DEFAULTS = List.of(
        new Template("com.seizia.chromite.templates.scount", "scount", EditorMessages.editor_templates_default_scount, "select count(*) from ${table};", true), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new Template("com.seizia.chromite.templates.swhere", "swhere", EditorMessages.editor_templates_default_swhere, //$NON-NLS-1$ //$NON-NLS-2$
            "select * from ${table} where ${column}='${value}';", true), //$NON-NLS-1$
        new Template("com.seizia.chromite.templates.scgb", "scgb", EditorMessages.editor_templates_default_scgb, //$NON-NLS-1$ //$NON-NLS-2$
            "select ${col:column(table)},count(*)\n    from ${table} t group by ${col};", true), //$NON-NLS-1$
        new Template("com.seizia.chromite.templates.scob", "sob", EditorMessages.editor_templates_default_sob, //$NON-NLS-1$ //$NON-NLS-2$
            "select * from ${table} t order by ${column};", true), //$NON-NLS-1$
        new Template("com.seizia.chromite.templates.sf", "sf", "select * from ", "select * from ${table};", true), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-4$
        new Template("com.seizia.chromite.templates.sel", "sel", EditorMessages.editor_templates_default_sel, "select * from ${table};", true), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        new Template("com.seizia.chromite.templates.ins", "ins", EditorMessages.editor_templates_default_ins, //$NON-NLS-1$ //$NON-NLS-2$
            "insert into ${table} (${columns})\nvalues (${values});", true), //$NON-NLS-1$
        new Template("com.seizia.chromite.templates.upd", "upd", EditorMessages.editor_templates_default_upd, //$NON-NLS-1$ //$NON-NLS-2$
            "update ${table}\nset ${column} = ${value}\nwhere ${condition};", true), //$NON-NLS-1$
        new Template("com.seizia.chromite.templates.del", "del", EditorMessages.editor_templates_default_del, //$NON-NLS-1$ //$NON-NLS-2$
            "delete from ${table}\nwhere ${condition};", true) //$NON-NLS-1$
    );

    // EDT only
    @Nullable
    private static List<Template> templates;

    private SqlTemplates() {
    }

    /**
     * Built-in templates (with the user's changes) followed by the user's own, in that order.
     */
    @NotNull
    static synchronized List<Template> getTemplates() {
        if (templates == null) {
            templates = load();
        }
        return templates;
    }

    @NotNull
    private static List<Template> load() {
        Map<String, Template> byId = new LinkedHashMap<>();
        for (Template template : DEFAULTS) {
            byId.put(template.id(), template);
        }
        List<Template> custom = new ArrayList<>();
        try {
            String content = DBWorkbench.getPlatform().getProductConfigurationController().loadConfigurationFile(CONFIG_FILE);
            if (!CommonUtils.isEmpty(content)) {
                Element root = XMLUtils.parseDocument(new StringReader(content)).getDocumentElement();
                for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (!(node instanceof Element element) || !"template".equals(element.getTagName())) { //$NON-NLS-1$
                        continue;
                    }
                    String id = CommonUtils.nullIfEmpty(element.getAttribute("id")); //$NON-NLS-1$
                    boolean deleted = CommonUtils.toBoolean(element.getAttribute("deleted")); //$NON-NLS-1$
                    String context = element.getAttribute("context"); //$NON-NLS-1$
                    if (!CommonUtils.isEmpty(context) && !CONTEXT.equals(context)) {
                        continue;
                    }
                    Template template = new Template(id, element.getAttribute("name"), element.getAttribute("description"), //$NON-NLS-1$ //$NON-NLS-2$
                        element.getTextContent(), !"false".equals(element.getAttribute("enabled"))); //$NON-NLS-1$ //$NON-NLS-2$
                    if (id != null && byId.containsKey(id)) {
                        if (deleted) {
                            byId.remove(id);
                        } else {
                            byId.put(id, template);
                        }
                    } else if (!deleted) {
                        // Templates contributed by plugins that are not in this product are kept as the user's own
                        custom.add(new Template(null, template.name(), template.description(), template.pattern(), template.enabled()));
                    }
                }
            }
        } catch (DBException | XMLException e) {
            log.warn("Can't read the SQL templates", e);
        }
        List<Template> result = new ArrayList<>(byId.values());
        result.addAll(custom);
        return result;
    }

    /**
     * Reads the templates from the configuration file again when they are next used (Import Settings wrote the file).
     */
    static synchronized void reload() {
        templates = null;
    }

    /**
     * Saves the templates: built-in ones that were changed or removed, and the user's own.
     */
    static synchronized void save(@NotNull List<Template> newTemplates) throws DBException {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><templates>"); //$NON-NLS-1$
        for (Template builtIn : DEFAULTS) {
            Template current = newTemplates.stream().filter(t -> builtIn.id().equals(t.id())).findFirst().orElse(null);
            if (current == null) {
                appendTemplate(xml, builtIn, true);
            } else if (!current.equals(builtIn)) {
                appendTemplate(xml, current, false);
            }
        }
        for (Template template : newTemplates) {
            if (template.id() == null) {
                appendTemplate(xml, template, false);
            }
        }
        xml.append("</templates>"); //$NON-NLS-1$
        DBWorkbench.getPlatform().getProductConfigurationController().saveConfigurationFile(CONFIG_FILE, xml.toString());
        templates = List.copyOf(newTemplates);
    }

    private static void appendTemplate(@NotNull StringBuilder xml, @NotNull Template template, boolean deleted) {
        xml.append("<template autoinsert=\"true\" context=\"").append(CONTEXT).append('"') //$NON-NLS-1$
            .append(" deleted=\"").append(deleted).append('"') //$NON-NLS-1$
            .append(" description=\"").append(XMLUtils.escapeXml(template.description())).append('"') //$NON-NLS-1$
            .append(" enabled=\"").append(template.enabled()).append('"'); //$NON-NLS-1$
        if (template.id() != null) {
            xml.append(" id=\"").append(XMLUtils.escapeXml(template.id())).append('"'); //$NON-NLS-1$
        }
        xml.append(" name=\"").append(XMLUtils.escapeXml(template.name())).append("\">") //$NON-NLS-1$ //$NON-NLS-2$
            .append(XMLUtils.escapeXml(template.pattern()))
            .append("</template>"); //$NON-NLS-1$
    }

    /**
     * The enabled template whose name is the word just before the caret (no selection), or null.
     */
    @Nullable
    static Template findAtCaret(@NotNull SqlEditorPanel.SqlTextArea textArea) {
        if (!textArea.isEditable() || textArea.getSelectionStart() != textArea.getSelectionEnd()) {
            return null;
        }
        String word = getWordBeforeCaret(textArea);
        if (word.isEmpty()) {
            return null;
        }
        for (Template template : getTemplates()) {
            if (template.enabled() && template.name().equalsIgnoreCase(word)) {
                return template;
            }
        }
        return null;
    }

    @NotNull
    private static String getWordBeforeCaret(@NotNull SqlEditorPanel.SqlTextArea textArea) {
        int caret = textArea.getCaretPosition();
        Document document = textArea.getDocument();
        try {
            if (caret < document.getLength() && isWordPart(document.getText(caret, 1).charAt(0))) {
                // In the middle of a word
                return "";
            }
            int start = caret;
            while (start > 0 && isWordPart(document.getText(start - 1, 1).charAt(0))) {
                start--;
            }
            return document.getText(start, caret - start);
        } catch (BadLocationException e) {
            return "";
        }
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Replaces the template name before the caret with the template (Tab after a name), see {@link #expand(SqlEditorPanel.SqlTextArea, Template, int, int)}.
     */
    @Nullable
    static Session expand(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull Template template) {
        int caret = textArea.getCaretPosition();
        return expand(textArea, template, caret - getWordBeforeCaret(textArea).length(), caret);
    }

    /**
     * Replaces the text from start to end with the template, selects its first variable (or puts the caret on
     * {@code ${cursor}}, else at the end) and returns the session that moves between the variables with Tab.
     */
    @Nullable
    static Session expand(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull Template template, int start, int end) {
        Document document = textArea.getDocument();
        String indent = getLineIndent(textArea, start);

        StringBuilder text = new StringBuilder();
        // Every occurrence of every variable in the order of the text: variable index, start, end
        List<int[]> occurrences = new ArrayList<>();
        Map<String, Integer> variables = new LinkedHashMap<>();
        int cursor = -1;
        int cursorOrder = 0;
        String pattern = template.pattern().replace("\r\n", "\n").replace("\n", "\n" + indent); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Matcher matcher = VARIABLE.matcher(pattern);
        int last = 0;
        while (matcher.find()) {
            text.append(pattern, last, matcher.start());
            last = matcher.end();
            if (matcher.group(1) == null) {
                text.append('$');
                continue;
            }
            String variable = matcher.group(1);
            int colon = variable.indexOf(':');
            String name = (colon < 0 ? variable : variable.substring(0, colon)).trim();
            if (name.equalsIgnoreCase(CURSOR_VARIABLE)) {
                cursor = text.length();
                cursorOrder = occurrences.size();
                continue;
            }
            String key = name.toLowerCase(Locale.ENGLISH);
            Integer index = variables.get(key);
            if (index == null) {
                index = variables.size();
                variables.put(key, index);
            }
            occurrences.add(new int[]{index, start + text.length(), start + text.length() + name.length()});
            text.append(name);
        }
        text.append(pattern.substring(last));

        textArea.beginAtomicEdit();
        try {
            document.remove(start, end - start);
            document.insertString(start, text.toString(), null);
        } catch (BadLocationException e) {
            log.debug(e);
            return null;
        } finally {
            textArea.endAtomicEdit();
        }
        int exit = start + (cursor >= 0 ? cursor : text.length());
        Session session = new Session(textArea, occurrences, variables.size(), exit, cursor >= 0 ? cursorOrder : occurrences.size());
        session.select(0);
        return session;
    }

    @NotNull
    private static String getLineIndent(@NotNull SqlEditorPanel.SqlTextArea textArea, int offset) {
        javax.swing.text.Element root = textArea.getDocument().getDefaultRootElement();
        javax.swing.text.Element line = root.getElement(root.getElementIndex(offset));
        try {
            String text = textArea.getDocument().getText(line.getStartOffset(), offset - line.getStartOffset());
            return text.substring(0, text.length() - text.stripLeading().length());
        } catch (BadLocationException e) {
            return "";
        }
    }

    /**
     * What Tab does while a template session is open ({@link Session#tab()}).
     */
    enum TabResult {
        /** The next variable or the end of the template is selected. */
        MOVED,
        /** The caret is at the end of the template: a plain Tab, never the template name the template itself just inserted. */
        PLAIN,
        /** The caret is somewhere else: the session is over, Tab works as usual. */
        OUTSIDE
    }

    /**
     * Variables of an inserted template, edited as in the SWT editor's linked mode.
     *
     * 설계: 같은 이름 변수의 모든 자리를 한 변수로 묶는다. 한 자리를 고치면 문서 필터({@link LinkedEdits})가 같은 편집을 다른 자리에도
     * 한다(여러 문장 템플릿의 {@code ${table}}이 첫 문장에만 들어가고 나머지는 변수 이름으로 남던 결함). 자리의 오프셋은 Swing
     * {@code Position}이 아니라 이 세션이 편집마다 직접 고친다: {@code Position}은 바꾼 변수 자리가 빈 범위로 줄고, 템플릿 끝은 바로 뒤에
     * 친 글까지 늘어나 다음 템플릿의 Tab을 가로챘다. Tab은 다음 변수, 마지막 변수 뒤에는 {@code ${cursor}}(없으면 템플릿 끝)로 간다. 그 끝
     * 자리에서는 SWT linked mode를 나온 것과 같다: 어떤 편집이든 세션을 끝내고, 거기서 누른 Tab은 보통 Tab이다(템플릿이 자기 이름으로 끝나면
     * 다시 펼쳐지던 결함). 변수 밖을 고치거나(입력, 되돌리기 포함) 다른 곳에서 Tab을 누르면 세션이 끝난다.
     */
    static final class Session implements DocumentListener {
        private final SqlEditorPanel.SqlTextArea textArea;
        private final AbstractDocument document;
        @Nullable
        private final DocumentFilter previousFilter;
        // Occurrences in the order of the text: variable index, start, end (document offsets, kept up to date by every edit)
        private final List<int[]> occurrences;
        private final int variableCount;
        // ${cursor} or the end of the template, and the number of occurrences before it
        private int exit;
        private final int exitOrder;
        private int current;
        private boolean active = true;
        // The session's own edits are running (the listener ignores their events)
        private boolean editing;

        private Session(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull List<int[]> occurrences, int variableCount, int exit, int exitOrder) {
            this.textArea = textArea;
            this.document = (AbstractDocument) textArea.getDocument();
            this.previousFilter = document.getDocumentFilter();
            this.occurrences = occurrences;
            this.variableCount = variableCount;
            this.exit = exit;
            this.exitOrder = exitOrder;
            document.setDocumentFilter(new LinkedEdits());
            document.addDocumentListener(this);
        }

        boolean isActive() {
            return active;
        }

        /**
         * Ends the session: the text stays as it is.
         */
        void finish() {
            if (active) {
                active = false;
                document.setDocumentFilter(previousFilter);
                document.removeDocumentListener(this);
            }
        }

        @NotNull
        TabResult tab() {
            if (active && textArea.getDocument() == document) {
                if (current == variableCount) {
                    // At the exit: the session is over (as the SWT linked mode), this Tab is a plain one
                    boolean atExit = textArea.getSelectionStart() == exit && textArea.getSelectionEnd() == exit;
                    finish();
                    return atExit ? TabResult.PLAIN : TabResult.OUTSIDE;
                }
                int index = findOccurrence(textArea.getSelectionStart(), textArea.getSelectionEnd());
                if (index >= 0) {
                    select(occurrences.get(index)[0] + 1);
                    return TabResult.MOVED;
                }
            }
            finish();
            return TabResult.OUTSIDE;
        }

        /**
         * Selects the first occurrence of the variable, or puts the caret on the exit after the last variable.
         */
        private void select(int variable) {
            current = variable;
            for (int[] occurrence : occurrences) {
                if (occurrence[0] == variable) {
                    textArea.select(occurrence[1], occurrence[2]);
                    return;
                }
            }
            current = variableCount;
            textArea.setCaretPosition(exit);
        }

        /**
         * The occurrence that holds the range (the current variable's first), or -1.
         */
        private int findOccurrence(int from, int to) {
            int found = -1;
            for (int i = 0; i < occurrences.size(); i++) {
                int[] occurrence = occurrences.get(i);
                if (occurrence[1] <= from && to <= occurrence[2]) {
                    if (occurrence[0] == current) {
                        return i;
                    }
                    if (found < 0) {
                        found = i;
                    }
                }
            }
            return found;
        }

        /**
         * An edit of the document: inside a variable it is made in every occurrence of that variable, outside it ends the session.
         */
        private void edit(@NotNull DocumentFilter.FilterBypass bypass, int offset, int length, @Nullable String text, @Nullable AttributeSet attributes)
            throws BadLocationException {
            // After the last variable (at the exit) every edit ends the session
            int index = active && current < variableCount ? findOccurrence(offset, offset + length) : -1;
            if (index < 0) {
                finish();
                bypass.replace(offset, length, text, attributes);
                return;
            }
            int variable = occurrences.get(index)[0];
            int relative = offset - occurrences.get(index)[1];
            int inserted = text == null ? 0 : text.length();
            boolean linked = occurrences.stream().filter(o -> o[0] == variable).count() > 1;
            editing = true;
            if (linked) {
                // One undo step for all occurrences
                textArea.beginAtomicEdit();
            }
            try {
                // From the last occurrence: the offsets of the earlier ones stay valid. All occurrences hold the same text.
                for (int i = occurrences.size() - 1; i >= 0; i--) {
                    int[] occurrence = occurrences.get(i);
                    if (occurrence[0] != variable) {
                        continue;
                    }
                    int at = occurrence[1] + relative;
                    int removed = Math.clamp(occurrence[2] - at, 0, length);
                    bypass.replace(at, removed, text, attributes);
                    shift(i, inserted - removed);
                }
            } finally {
                if (linked) {
                    textArea.endAtomicEdit();
                }
                editing = false;
            }
        }

        /**
         * The occurrence grew or shrank by delta: the occurrences and the exit after it move.
         */
        private void shift(int index, int delta) {
            occurrences.get(index)[2] += delta;
            for (int i = index + 1; i < occurrences.size(); i++) {
                occurrences.get(i)[1] += delta;
                occurrences.get(i)[2] += delta;
            }
            if (exitOrder > index) {
                exit += delta;
            }
        }

        @Override
        public void insertUpdate(DocumentEvent e) {
            changedOutside();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            changedOutside();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            // Attributes only
        }

        private void changedOutside() {
            // Undo, redo and other changes that do not go through the filter: the offsets are no longer known
            if (!editing) {
                finish();
            }
        }

        /**
         * Every edit of the document while the session is open.
         */
        private final class LinkedEdits extends DocumentFilter {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                edit(fb, offset, 0, string, attr);
            }

            @Override
            public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
                edit(fb, offset, length, null, null);
            }

            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                edit(fb, offset, length, text, attrs);
            }
        }
    }
}
