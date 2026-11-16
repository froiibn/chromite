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

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Position;
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
 *     Tab이 다음 변수로 옮겨 간다({@link Session}). {@code ${cursor}}는 끝낼 위치, {@code $$}는 {@code $}다. 여러 줄 템플릿은 현재 줄의 들여쓰기를 이어 간다.
 *     SWT의 같은 이름 변수 동시 편집(linked mode)과 문맥 변수 값 계산(테이블 이름 제안)은 하지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 설정 가져오기·되돌리기가 파일을 바꾼 뒤 다시 읽게 {@link #reload()} (docs/chromite/settings-transfer.md)
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
     * Replaces the template name before the caret with the template, selects its first variable and returns the
     * session that moves between the variables with Tab (null when it has no variables).
     */
    @Nullable
    static Session expand(@NotNull SqlEditorPanel.SqlTextArea textArea, @NotNull Template template) {
        int caret = textArea.getCaretPosition();
        int start = caret - getWordBeforeCaret(textArea).length();
        Document document = textArea.getDocument();
        String indent = getLineIndent(textArea, start);

        StringBuilder text = new StringBuilder();
        List<int[]> variables = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        int cursor = -1;
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
                continue;
            }
            if (seen.putIfAbsent(name.toLowerCase(Locale.ENGLISH), Boolean.TRUE) == null) {
                variables.add(new int[]{text.length(), text.length() + name.length()});
            }
            text.append(name);
        }
        text.append(pattern.substring(last));

        textArea.beginAtomicEdit();
        try {
            document.remove(start, caret - start);
            document.insertString(start, text.toString(), null);
        } catch (BadLocationException e) {
            log.debug(e);
            return null;
        } finally {
            textArea.endAtomicEdit();
        }
        int end = start + text.length();
        if (variables.isEmpty()) {
            textArea.setCaretPosition(cursor >= 0 ? start + cursor : end);
            return null;
        }
        try {
            List<Position[]> positions = new ArrayList<>();
            for (int[] range : variables) {
                positions.add(new Position[]{document.createPosition(start + range[0]), document.createPosition(start + range[1])});
            }
            Session session = new Session(positions, document.createPosition(start), document.createPosition(end));
            session.select(textArea, 0);
            return session;
        } catch (BadLocationException e) {
            log.debug(e);
            return null;
        }
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
     * Variables of an inserted template: Tab selects the next one while the caret stays inside the template.
     */
    static final class Session {
        private final List<Position[]> variables;
        private final Position start;
        private final Position end;
        private int current;

        private Session(@NotNull List<Position[]> variables, @NotNull Position start, @NotNull Position end) {
            this.variables = variables;
            this.start = start;
            this.end = end;
        }

        /**
         * Whether the caret is still inside the inserted text.
         */
        boolean contains(int offset) {
            return offset >= start.getOffset() && offset <= end.getOffset();
        }

        void selectNext(@NotNull SqlEditorPanel.SqlTextArea textArea) {
            select(textArea, (current + 1) % variables.size());
        }

        private void select(@NotNull SqlEditorPanel.SqlTextArea textArea, int index) {
            current = index;
            Position[] range = variables.get(index);
            textArea.select(range[0].getOffset(), range[1].getOffset());
        }
    }
}
