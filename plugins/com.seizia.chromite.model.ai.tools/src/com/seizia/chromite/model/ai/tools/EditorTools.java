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
package com.seizia.chromite.model.ai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.agent.AIAgentServices;
import com.seizia.chromite.model.ai.agent.AIEditorBridge;
import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import com.seizia.chromite.model.ai.agent.AIToolCallContext;
import com.seizia.chromite.model.ai.agent.AIToolExecutor;
import com.seizia.chromite.model.ai.agent.AIToolPreviewProvider;
import com.seizia.chromite.model.sql.Diff;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Editor tools on the open query windows of the running application: list them, read their text, and edit it.
 *
 * 설계:
 * <ul>
 *     <li>창을 읽고 고치는 일은 화면 쪽 {@link AIEditorBridge}가 EDT에서 한다. 이 클래스는 인수를 읽고 결과 JSON과 확인 미리 보기를 만든다.
 *     화면이 없으면(헤드리스) 오류다.</li>
 *     <li>{@code editQueryWindow}는 DB를 바꾸지 않지만 사용자의 글을 바꾸므로 WRITE({@code writes="none"})다. 기본 정책이 "실행 전 확인"이고
 *     Tool Policy에서 자동 실행으로 바꿀 수 있다. 연결 인수가 없어 운영·읽기 전용 연결 규칙은 걸리지 않는다. 실행하거나 저장하지 않고,
 *     되돌리기 한 번으로 취소된다(브리지 계약).</li>
 *     <li>확인 미리 보기는 바뀌는 글의 줄 차이다({@link AIToolPreviewProvider.Kind#TEXT_CHANGE}). 미리 보기와 실제 고치기가 같은 범위 계산
 *     ({@link AIEditorBridge#editQueryWindow}의 {@code apply})을 쓴다. 줄 차이는 쿼리 재구성에 쓰는 Myers 차이({@link Diff})로 구하고,
 *     앞뒤의 같은 줄을 먼저 잘라 바뀐 부분만 비교한다. 바뀐 부분이 아주 크면 비교하지 않고 지운 줄과 넣은 줄을 그대로 보인다.</li>
 *     <li>읽기 도구(READ)는 자동 실행이지만 MCP로 붙은 외부 에이전트가 사용자의 글을 읽는 것도 다른 호출처럼 실행기가 감사 기록에 남긴다
 *     (docs/chromite/ai-agent.md "감사 기록").</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: listQueryWindows, getQueryWindowText, editQueryWindow (docs/chromite/ai-agent.md "편집기 도구")
 */
public class EditorTools extends AgentTool implements AIToolPreviewProvider {

    static final String EDIT_TOOL = "editQueryWindow"; //$NON-NLS-1$

    private static final int DEFAULT_MAX_CHARS = 100_000;
    // Lines of unchanged text shown around each change in the preview
    private static final int CONTEXT_LINES = 2;
    // Above this many changed lines the preview lists removed and added lines without comparing them (Myers is O(ND))
    private static final int MAX_COMPARED_LINES = 5_000;
    private static final int MAX_PREVIEW_LINES = 2_000;

    @NotNull
    @Override
    protected JsonElement call(
        @NotNull AIToolCallContext context,
        @NotNull String toolId,
        @NotNull Map<String, Object> args
    ) throws DBException {
        AIEditorBridge bridge = requireBridge();
        return switch (toolId) {
            case "listQueryWindows" -> listWindows(bridge);
            case "getQueryWindowText" -> readText(bridge, args);
            case EDIT_TOOL -> edit(bridge, args);
            default -> throw unknownTool(toolId);
        };
    }

    @NotNull
    private static AIEditorBridge requireBridge() throws DBException {
        AIEditorBridge bridge = AIAgentServices.getEditorBridge();
        if (bridge == null) {
            throw new DBException("No query windows are available (the application runs without its user interface)");
        }
        return bridge;
    }

    @NotNull
    private static JsonElement listWindows(@NotNull AIEditorBridge bridge) throws DBException {
        JsonArray list = new JsonArray();
        for (AIEditorBridge.QueryWindow window : bridge.listQueryWindows()) {
            list.add(describe(window));
        }
        JsonObject json = new JsonObject();
        json.add("windows", list);
        json.addProperty("count", list.size());
        return json;
    }

    @NotNull
    private static JsonElement readText(@NotNull AIEditorBridge bridge, @NotNull Map<String, Object> args) throws DBException {
        AIEditorBridge.TextPart part = parsePart(AgentArgs.getString(args, "part"));
        int maxChars = Math.min(Math.max(1, AgentArgs.getInt(args, "maxChars", DEFAULT_MAX_CHARS)), AIToolExecutor.MAX_RESULT_CHARS);
        AIEditorBridge.QueryWindowText text = bridge.getQueryWindowText(AgentArgs.getString(args, "window"), part);
        JsonObject json = new JsonObject();
        json.add("window", describe(text.window()));
        json.addProperty("part", text.part().name().toLowerCase(Locale.ROOT));
        json.addProperty("start", text.start());
        json.addProperty("end", text.end());
        boolean truncated = text.text().length() > maxChars;
        json.addProperty("text", truncated ? text.text().substring(0, maxChars) : text.text());
        if (truncated) {
            json.addProperty("truncated", true);
            json.addProperty("note", "Only the first " + maxChars + " of " + text.text().length() + " characters are returned; raise maxChars to read more");
        }
        if (text.part() == AIEditorBridge.TextPart.SELECTION && text.text().isEmpty()) {
            json.addProperty("note", "Nothing is selected in this window");
        }
        return json;
    }

    @NotNull
    private static JsonElement edit(@NotNull AIEditorBridge bridge, @NotNull Map<String, Object> args) throws DBException {
        AIEditorBridge.EditMode mode = parseMode(AgentArgs.require(args, "mode"));
        AIEditorBridge.QueryWindowEdit edit = bridge.editQueryWindow(AgentArgs.getString(args, "window"), mode, getText(args, mode), true);
        JsonObject json = new JsonObject();
        json.add("window", describe(edit.window()));
        json.addProperty("mode", AgentArgs.require(args, "mode"));
        json.addProperty("start", edit.start());
        json.addProperty("end", edit.start() + edit.text().length());
        json.addProperty("removedChars", edit.end() - edit.start());
        json.addProperty("insertedChars", edit.text().length());
        json.addProperty("note", "The text was changed in the query window and is selected there. It was not run or saved; " +
            "the user can undo the change with Ctrl+Z.");
        return json;
    }

    /**
     * The {@code text} argument as given: an empty text is allowed when it replaces something (it deletes the selection).
     */
    @NotNull
    private static String getText(@NotNull Map<String, Object> args, @NotNull AIEditorBridge.EditMode mode) throws AIToolArgumentException {
        Object value = args.get("text");
        String text = value == null ? "" : value.toString();
        if (text.isEmpty() && mode == AIEditorBridge.EditMode.INSERT) {
            throw new AIToolArgumentException("Argument 'text' is required to insert");
        }
        return text;
    }

    @NotNull
    private static AIEditorBridge.TextPart parsePart(@Nullable String part) throws AIToolArgumentException {
        if (part == null) {
            return AIEditorBridge.TextPart.ALL;
        }
        return switch (normalize(part)) {
            case "all" -> AIEditorBridge.TextPart.ALL;
            case "selection" -> AIEditorBridge.TextPart.SELECTION;
            case "statement" -> AIEditorBridge.TextPart.STATEMENT;
            default -> throw new AIToolArgumentException("Argument 'part' must be all, selection or statement");
        };
    }

    @NotNull
    private static AIEditorBridge.EditMode parseMode(@NotNull String mode) throws AIToolArgumentException {
        return switch (normalize(mode)) {
            case "insert" -> AIEditorBridge.EditMode.INSERT;
            case "replaceselection" -> AIEditorBridge.EditMode.REPLACE_SELECTION;
            case "replaceall" -> AIEditorBridge.EditMode.REPLACE_ALL;
            default -> throw new AIToolArgumentException("Argument 'mode' must be insert, replaceSelection or replaceAll");
        };
    }

    // insert, replaceSelection, replace_selection and REPLACE-SELECTION are the same mode
    @NotNull
    private static String normalize(@NotNull String value) {
        return value.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @NotNull
    private static JsonObject describe(@NotNull AIEditorBridge.QueryWindow window) {
        JsonObject json = new JsonObject();
        json.addProperty("id", window.id());
        json.addProperty("title", window.title());
        DBPDataSourceContainer connection = window.connection();
        if (connection != null) {
            json.addProperty("connection", connection.getName());
            json.addProperty("connectionId", connection.getId());
        }
        if (window.database() != null) {
            json.addProperty("database", window.database());
        }
        if (window.file() != null) {
            json.addProperty("file", window.file());
        }
        json.addProperty("modified", window.modified());
        json.addProperty("active", window.active());
        json.addProperty("executing", window.executing());
        json.addProperty("length", window.length());
        json.addProperty("caret", window.caret());
        json.addProperty("hasSelection", window.hasSelection());
        if (window.hasSelection()) {
            json.addProperty("selectionStart", window.selectionStart());
            json.addProperty("selectionEnd", window.selectionEnd());
        }
        return json;
    }

    // ------------------------------------------------------------------ confirmation preview

    @Nullable
    @Override
    public String getPreview(@NotNull AIToolCallContext context, @NotNull Map<String, Object> arguments) throws DBException {
        if (!context.getTool().getId().equals(EDIT_TOOL)) {
            return null;
        }
        AIEditorBridge.EditMode mode = parseMode(AgentArgs.require(arguments, "mode"));
        AIEditorBridge.QueryWindowEdit edit = requireBridge().editQueryWindow(
            AgentArgs.getString(arguments, "window"), mode, getText(arguments, mode), false);
        DBPDataSourceContainer connection = edit.window().connection();
        return "@@ " + edit.window().title() + (connection == null ? "" : " - " + connection.getName()) + "\n" + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            describeChange(edit.before(), edit.after());
    }

    @NotNull
    @Override
    public Kind getPreviewKind(@NotNull AIToolCallContext context) {
        return context.getTool().getId().equals(EDIT_TOOL) ? Kind.TEXT_CHANGE : Kind.RUN;
    }

    /**
     * One line of the preview: {@code ' '} unchanged, {@code '-'} removed, {@code '+'} added; {@code line} is the 1-based line
     * of the text after the edit where it is (or, for a removed line, where it was).
     */
    private record DiffLine(char kind, @NotNull String text, int line) {
    }

    /**
     * Line differences of two texts with a few unchanged lines around each change and an {@code @@ line N} header per hunk.
     */
    @NotNull
    static String describeChange(@NotNull String before, @NotNull String after) {
        String[] a = before.split("\n", -1); //$NON-NLS-1$
        String[] b = after.split("\n", -1); //$NON-NLS-1$
        // Unchanged lines at the start and the end are not compared
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix && a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix])) {
            suffix++;
        }
        int aEnd = a.length - suffix;
        int bEnd = b.length - suffix;
        if (prefix == a.length && prefix == b.length) {
            return "(no change)"; //$NON-NLS-1$
        }

        List<DiffLine> lines = new ArrayList<>();
        for (int i = Math.max(0, prefix - CONTEXT_LINES); i < prefix; i++) {
            lines.add(new DiffLine(' ', a[i], i + 1));
        }
        int bLine = prefix;
        if ((aEnd - prefix) + (bEnd - prefix) <= MAX_COMPARED_LINES) {
            for (Diff.Range range : Diff.prepareDiff(a, prefix, aEnd - prefix, b, prefix, bEnd - prefix, String::equals)) {
                for (int i = 0; i < range.length; i++) {
                    switch (range.operation) {
                        case MATCH_AB -> lines.add(new DiffLine(' ', a[range.start + i], ++bLine));
                        case DELETE_A -> lines.add(new DiffLine('-', a[range.start + i], bLine + 1));
                        case INSERT_B -> lines.add(new DiffLine('+', b[range.start + i], ++bLine));
                    }
                }
            }
        } else {
            for (int i = prefix; i < aEnd; i++) {
                lines.add(new DiffLine('-', a[i], bLine + 1));
            }
            for (int i = prefix; i < bEnd; i++) {
                lines.add(new DiffLine('+', b[i], ++bLine));
            }
        }
        for (int i = 0; i < Math.min(CONTEXT_LINES, suffix); i++) {
            lines.add(new DiffLine(' ', b[bEnd + i], bEnd + i + 1));
        }

        // Unchanged lines more than CONTEXT_LINES away from a change are left out
        boolean[] shown = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).kind() != ' ') {
                for (int j = Math.max(0, i - CONTEXT_LINES); j <= Math.min(lines.size() - 1, i + CONTEXT_LINES); j++) {
                    shown[j] = true;
                }
            }
        }
        StringBuilder text = new StringBuilder();
        int written = 0;
        int hiddenChanges = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!shown[i]) {
                continue;
            }
            DiffLine line = lines.get(i);
            if (written >= MAX_PREVIEW_LINES) {
                if (line.kind() != ' ') {
                    hiddenChanges++;
                }
                continue;
            }
            if (i == 0 || !shown[i - 1]) {
                text.append("@@ line ").append(line.line()).append('\n'); //$NON-NLS-1$
            }
            text.append(line.kind()).append(' ').append(line.text()).append('\n');
            written++;
        }
        if (hiddenChanges > 0) {
            text.append("@@ ").append(hiddenChanges).append(" more changed lines are not shown\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return text.toString();
    }
}
