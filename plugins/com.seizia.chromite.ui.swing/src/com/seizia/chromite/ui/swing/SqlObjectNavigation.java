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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLSearchUtils;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import com.seizia.chromite.model.sql.parser.SQLIdentifierDetector;
import com.seizia.chromite.model.sql.parser.SQLParserContext;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.sql.semantics.SQLDocumentSyntaxContext;
import com.seizia.chromite.model.sql.semantics.SQLQuerySymbolByDbObjectDefinition;
import com.seizia.chromite.model.sql.semantics.SQLQuerySymbolDefinition;
import com.seizia.chromite.model.sql.semantics.SQLQuerySymbolEntry;
import com.seizia.chromite.model.sql.semantics.model.select.SQLQueryRowsTableDataModel;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rsyntaxtextarea.LinkGenerator;
import org.fife.ui.rsyntaxtextarea.LinkGeneratorResult;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.ToolTipSupplier;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 쿼리 편집기의 이름 정보: 마우스를 올리면 개체 설명(종류, 전체 이름, 열·매개변수, 주석), Ctrl+클릭하면 개체로 이동.
 *
 * 설계:
 * <ul>
 *     <li>이름이 무엇을 가리키는지는 SWT 편집기({@code SQLHyperlinkDetector.findLocalScopeReference}, {@code SQLContextInformer})처럼
 *     모델의 의미 분석으로 찾는다. 이름이 있는 문장 하나를 파서로 찾아 의미 모델을 만들고({@link SqlCompletionAnalysis#recognizeStatement},
 *     자동완성과 같은 인자) 그 위치의 기호 정의를 읽는다. 그래서 별칭({@code e.name})과 열 이름도 실제 테이블·열로 풀린다.
 *     의미 분석이 풀지 못하는 이름(EXEC 뒤의 프로시저, DDL 문)은 {@link SQLSearchUtils#findObjectsByFQN}으로 이름을 찾는다(기본 카탈로그·스키마 기준, 루틴 포함).</li>
 *     <li>분석은 메타데이터를 읽을 수 있어 작업 스레드({@link AbstractJob})에서 한다. 도구 설명은 동기로 글자를 돌려줘야 하므로,
 *     처음 올렸을 때는 분석을 시작하고 아무것도 보이지 않다가 결과가 오면 같은 이름 위에 마우스가 그대로 있을 때 도구 설명 관리자에게
 *     다시 묻게 한다. 결과는 문서가 바뀔 때까지 이름 위치별로 기억한다. 연결되지 않은 창에서는 연결을 열지 않고 아무것도 하지 않는다.</li>
 *     <li>Ctrl+클릭은 RSyntaxTextArea의 링크({@link LinkGenerator}, Ctrl을 누르고 이름 위에 가면 밑줄)다. 누르면 같은 분석을 하고
 *     데이터베이스 개체면 Object Explorer에서 그 노드를 펼쳐 고른다(Swing에는 개체 편집기가 없고 Properties 창이 선택을 따른다).
 *     별칭·CTE 같은 문장 안의 이름이면 그 정의를 편집기에서 고른다(SWT와 같음). 찾지 못하면 상태 줄에 알린다.</li>
 *     <li>도구 설명은 RSyntaxTextArea의 포커스 가능한 도구 설명(HTML)이다. 색은 테마의 도구 설명 색을 따른다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-sql-editor-ergonomics.md)
 */
final class SqlObjectNavigation implements ToolTipSupplier, LinkGenerator {

    private static final Log log = Log.getLog(SqlObjectNavigation.class);

    private static final int MAX_LISTED_CHILDREN = 30;

    /**
     * The query window side.
     */
    interface Host {
        @Nullable
        DBPDataSourceContainer getDataSourceContainer();

        /**
         * The window's session, or null to use the connection's main context.
         */
        @Nullable
        DBCExecutionContext getExecutionContext();

        /**
         * Shows a navigator node in Object Explorer.
         */
        void revealNode(@NotNull DBNDatabaseNode node);

        /**
         * A short message for the status line (nothing found).
         */
        void showMessage(@NotNull String message);
    }

    /**
     * What a name refers to.
     *
     * @param object          database object, or null
     * @param definitionStart start of the local definition in the document (alias, CTE), or -1
     * @param definitionEnd   end of the local definition
     * @param html            hover text, or null when there is nothing to show
     * @param node            navigator node of the object (Ctrl+click), or null
     */
    private record Target(@Nullable DBSObject object, int definitionStart, int definitionEnd, @Nullable String html, @Nullable DBNDatabaseNode node) {
        static final Target NONE = new Target(null, -1, -1, null, null);
    }

    private record Request(
        @NotNull DBPDataSourceContainer container,
        @Nullable DBCExecutionContext windowContext,
        @NotNull String text,
        int offset,
        int nameStart,
        int nameEnd,
        boolean navigate
    ) {
    }

    private final RSyntaxTextArea textArea;
    private final Host host;
    // EDT only: hover results by name start, until the document changes
    private final Map<Integer, Target> hoverCache = new HashMap<>();
    @Nullable
    private AbstractJob job;
    // EDT only: name under the mouse waiting for its hover text, and the last mouse event over it
    private int waitingNameStart = -1;
    @Nullable
    private MouseEvent lastMouseEvent;

    private SqlObjectNavigation(@NotNull RSyntaxTextArea textArea, @NotNull Host host) {
        this.textArea = textArea;
        this.host = host;
    }

    /**
     * Turns on the hover text and Ctrl+click of a query window's text area.
     */
    static void install(@NotNull RSyntaxTextArea textArea, @NotNull Host host) {
        SqlObjectNavigation navigation = new SqlObjectNavigation(textArea, host);
        textArea.setToolTipSupplier(navigation);
        textArea.setLinkGenerator(navigation);
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                navigation.documentChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                navigation.documentChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
    }

    private void documentChanged() {
        hoverCache.clear();
        waitingNameStart = -1;
        cancelJob();
    }

    //////////////////////////////////////////////////////////
    // Hover

    @Nullable
    @Override
    public String getToolTipText(RTextArea area, MouseEvent e) {
        int[] name = findNameAt(e.getPoint());
        if (name == null) {
            return null;
        }
        Target cached = hoverCache.get(name[0]);
        if (cached != null) {
            return cached.html();
        }
        DBPDataSourceContainer container = host.getDataSourceContainer();
        if (container == null || !container.isConnected()) {
            // Hovering must not open connections
            return null;
        }
        lastMouseEvent = e;
        if (waitingNameStart != name[0]) {
            waitingNameStart = name[0];
            start(new Request(container, host.getExecutionContext(), textArea.getText(), viewToModel(e.getPoint()), name[0], name[1], false));
        }
        return null;
    }

    private void hoverDone(@NotNull Request request, @NotNull Target target) {
        hoverCache.put(request.nameStart(), target);
        MouseEvent event = lastMouseEvent;
        if (waitingNameStart != request.nameStart() || event == null || target.html() == null) {
            return;
        }
        waitingNameStart = -1;
        Point mouse = textArea.getMousePosition();
        int[] name = mouse != null ? findNameAt(mouse) : null;
        if (name != null && name[0] == request.nameStart()) {
            // Still over the same name: the tool tip manager asks for the text again (now cached) and shows it
            ToolTipManager.sharedInstance().mouseMoved(new MouseEvent(textArea, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
                0, mouse.x, mouse.y, 0, false));
        }
    }

    //////////////////////////////////////////////////////////
    // Ctrl+click

    @Nullable
    @Override
    public LinkGeneratorResult isLinkAtOffset(RSyntaxTextArea area, int offset) {
        int[] name = findNameAtOffset(offset);
        DBPDataSourceContainer container = host.getDataSourceContainer();
        if (name == null || container == null) {
            return null;
        }
        return new LinkGeneratorResult() {
            @Nullable
            @Override
            public HyperlinkEvent execute() {
                start(new Request(container, host.getExecutionContext(), textArea.getText(), offset, name[0], name[1], true));
                return null;
            }

            @Override
            public int getSourceOffset() {
                return name[0];
            }
        };
    }

    private void navigateDone(@NotNull Request request, @NotNull Target target) {
        if (target.definitionStart() >= 0 && target.definitionEnd() <= textArea.getDocument().getLength()
            && textArea.getText().equals(request.text())) {
            textArea.select(target.definitionStart(), target.definitionEnd());
            textArea.requestFocusInWindow();
        } else if (target.node() != null) {
            host.revealNode(target.node());
        } else {
            host.showMessage(NLS.bind(EditorMessages.editor_nav_not_found, request.text().substring(request.nameStart(), request.nameEnd())));
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
        }
    }

    //////////////////////////////////////////////////////////
    // Analysis

    private void start(@NotNull Request request) {
        cancelJob();
        AbstractJob resolveJob = new AbstractJob("Resolve SQL name") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Target target = Target.NONE;
                try {
                    target = resolve(monitor, request);
                } catch (Throwable e) {
                    log.debug("SQL name resolution failed", e);
                }
                if (!monitor.isCanceled()) {
                    Target result = target;
                    SwingUtilities.invokeLater(() -> {
                        if (job == this) {
                            job = null;
                        }
                        if (request.navigate()) {
                            navigateDone(request, result);
                        } else if (textArea.getText().equals(request.text())) {
                            hoverDone(request, result);
                        }
                    });
                }
                return Status.OK_STATUS;
            }
        };
        resolveJob.setSystem(true);
        resolveJob.setUser(false);
        job = resolveJob;
        resolveJob.schedule();
    }

    private void cancelJob() {
        if (job != null) {
            job.cancel();
            job = null;
        }
    }

    /**
     * Finds what the name refers to (job thread): the semantic model of its statement first, then a lookup by name.
     */
    @NotNull
    private static Target resolve(@NotNull DBRProgressMonitor monitor, @NotNull Request request) {
        DBPDataSourceContainer container = request.container();
        if (request.navigate() && !container.isConnected()) {
            // Ctrl+click connects like Ctrl+Space (errors are reported by the connection service)
            try {
                DBUtils.initDataSource(monitor, container, null);
            } catch (Exception e) {
                log.debug(e);
            }
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null || !container.isConnected()) {
            return Target.NONE;
        }
        DBCExecutionContext windowContext = request.windowContext();
        DBCExecutionContext executionContext = windowContext != null && windowContext.getDataSource() == dataSource
            ? windowContext : DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
        if (executionContext == null) {
            return Target.NONE;
        }
        SQLParserContext parserContext = SQLScriptBatches.createParserContext(dataSource, request.text());
        int offset = Math.min(request.offset(), request.nameEnd() - 1);
        DBSObject object = null;
        int definitionStart = -1;
        int definitionEnd = -1;
        SQLScriptElement element = SQLScriptParser.extractQueryAtPos(parserContext, offset, false);
        if (element instanceof SQLQuery query && !monitor.isCanceled()) {
            SQLDocumentSyntaxContext syntaxContext = SqlCompletionAnalysis.recognizeStatement(monitor, parserContext, executionContext, query);
            SQLQuerySymbolEntry token = syntaxContext != null ? syntaxContext.findToken(offset) : null;
            if (token != null) {
                // Follow local definitions (alias -> its table) to a database object; the first local one is where Ctrl+click goes
                SQLQuerySymbolDefinition definition = token.getDefinition();
                for (int depth = 0; depth < 4 && definition != null && object == null; depth++) {
                    if (definition instanceof SQLQuerySymbolByDbObjectDefinition byObject) {
                        object = byObject.getDbObject();
                    } else if (definition instanceof SQLQueryRowsTableDataModel table && table.getTable() != null) {
                        object = table.getTable();
                    } else if (definition instanceof SQLQuerySymbolEntry entry && entry != token) {
                        if (definitionStart < 0) {
                            definitionStart = entry.getInterval().a + syntaxContext.getLastAccessedScriptElementOffset();
                            definitionEnd = definitionStart + entry.getInterval().length();
                        }
                        definition = entry.getDefinition();
                        continue;
                    }
                    break;
                }
            }
        }
        if (object == null && definitionStart < 0 && !monitor.isCanceled()) {
            object = findByName(monitor, dataSource, executionContext, request);
        }
        if (object == null) {
            return definitionStart >= 0 ? new Target(null, definitionStart, definitionEnd, null, null) : Target.NONE;
        }
        if (request.navigate()) {
            // A local definition is where Ctrl+click goes; the object's node is only needed without one
            DBNDatabaseNode node = definitionStart < 0 ? DBNUtils.getNodeByObject(monitor, object, true) : null;
            return new Target(object, definitionStart, definitionEnd, null, node);
        }
        return new Target(object, definitionStart, definitionEnd, describe(monitor, object), null);
    }

    /**
     * A database object by its (qualified) name, the part under the mouse and the parts before it.
     */
    @Nullable
    private static DBSObject findByName(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull DBCExecutionContext executionContext,
        @NotNull Request request
    ) {
        SQLIdentifierDetector detector = new SQLIdentifierDetector(dataSource.getSQLDialect());
        String text = request.text();
        int start = request.nameStart();
        while (start > 0 && detector.isWordPart(text.charAt(start - 1))) {
            start--;
        }
        String identifier = text.substring(start, request.nameEnd());
        List<String> names = Arrays.asList(detector.splitIdentifier(identifier));
        if (names.isEmpty() || names.stream().anyMatch(CommonUtils::isEmpty)) {
            return null;
        }
        DBSObjectContainer objectContainer = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
        // Any object: routines too (EXEC dbo.proc, schema.func(...)), not only tables and other children of a container
        List<? extends DBSObject> objects = SQLSearchUtils.findObjectsByFQN(monitor, objectContainer, executionContext, names, false, detector, true);
        return objects.isEmpty() ? null : objects.getFirst();
    }

    //////////////////////////////////////////////////////////
    // Description

    /**
     * Hover and completion documentation of a database object (job thread, may read metadata): kind and name, full
     * name, the column's type, the table's columns or the routine's parameters, and the comment.
     */
    @NotNull
    static String describe(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) {
        StringBuilder html = new StringBuilder("<html><body style='margin:2px'>"); //$NON-NLS-1$
        DBNDatabaseNode node = DBNUtils.getNodeByObject(monitor, object, false);
        html.append("<b>").append(escape(object.getName())).append("</b>"); //$NON-NLS-1$ //$NON-NLS-2$
        if (node != null) {
            html.append(" &nbsp;<span style='color:").append(getSecondaryColor()).append("'>") //$NON-NLS-1$ //$NON-NLS-2$
                .append(escape(node.getNodeTypeLabel())).append("</span>"); //$NON-NLS-1$
        }
        String fullName = DBUtils.getObjectFullName(object, DBPEvaluationContext.UI);
        if (!fullName.equals(object.getName())) {
            html.append("<br>").append(escape(fullName)); //$NON-NLS-1$
        }
        if (object instanceof DBSTypedObject typed && !(object instanceof DBSEntity)) {
            html.append("<br>").append(escape(DBUtils.getFullTypeName(typed))); //$NON-NLS-1$
            if (object instanceof DBSEntityAttribute attribute) {
                if (attribute.isRequired()) {
                    html.append(" NOT NULL"); //$NON-NLS-1$
                }
                if (!CommonUtils.isEmpty(attribute.getDefaultValue())) {
                    html.append(" DEFAULT ").append(escape(attribute.getDefaultValue())); //$NON-NLS-1$
                }
            }
        }
        try {
            if (object instanceof DBSEntity entity) {
                // Without the hidden system columns (PostgreSQL ctid, xmin...), as Object Explorer
                List<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
                appendChildren(html, attributes == null ? null : attributes.stream().filter(child -> !DBUtils.isHiddenObject(child)).toList(),
                    child -> html.append(escape(child.getName())).append(" &nbsp;") //$NON-NLS-1$
                    .append("<span style='color:").append(getSecondaryColor()).append("'>") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(escape(DBUtils.getFullTypeName(child))).append("</span>")); //$NON-NLS-1$
            } else if (object instanceof DBSProcedure procedure) {
                appendChildren(html, procedure.getParameters(monitor), parameter -> {
                    html.append(escape(parameter.getName())).append(" &nbsp;<span style='color:").append(getSecondaryColor()).append("'>"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (parameter.getParameterKind() != null) {
                        html.append(escape(parameter.getParameterKind().getTitle())).append(' ');
                    }
                    html.append(escape(DBUtils.getFullTypeName(parameter.getParameterType()))).append("</span>"); //$NON-NLS-1$
                });
            }
        } catch (Exception e) {
            log.debug("Can't read the children of " + object.getName(), e);
        }
        String description = object.getDescription();
        if (!CommonUtils.isEmpty(description)) {
            html.append("<hr>").append(escape(description).replace("\n", "<br>")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return html.append("</body></html>").toString(); //$NON-NLS-1$
    }

    private static <T extends DBSObject> void appendChildren(@NotNull StringBuilder html, @Nullable Collection<? extends T> children, @NotNull Consumer<T> line) {
        if (children == null || children.isEmpty()) {
            return;
        }
        html.append("<hr>"); //$NON-NLS-1$
        int count = 0;
        for (T child : children) {
            if (count == MAX_LISTED_CHILDREN) {
                html.append(NLS.bind(EditorMessages.editor_nav_more, children.size() - count)).append("<br>"); //$NON-NLS-1$
                break;
            }
            line.accept(child);
            html.append("<br>"); //$NON-NLS-1$
            count++;
        }
    }

    @NotNull
    private static String getSecondaryColor() {
        Color color = UIManager.getColor("Label.disabledForeground"); //$NON-NLS-1$
        return color == null ? "gray" : String.format("#%06x", color.getRGB() & 0xffffff); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private static String escape(@Nullable String text) {
        return CommonUtils.escapeHtml(CommonUtils.notEmpty(text));
    }

    //////////////////////////////////////////////////////////
    // Names in the text

    /**
     * Start and end of the name under a point of the text area, or null when the point is not over a name.
     */
    @Nullable
    private int[] findNameAt(@NotNull Point point) {
        int offset = viewToModel(point);
        int[] name = offset >= 0 ? findNameAtOffset(offset) : null;
        if (name == null) {
            return null;
        }
        try {
            // viewToModel gives the nearest offset; the point must really be over the name's text
            Rectangle2D start = textArea.modelToView2D(name[0]);
            Rectangle2D end = textArea.modelToView2D(name[1]);
            if (start == null || end == null || point.x < start.getX() || point.x > end.getX()
                || point.y < start.getY() || point.y > start.getY() + start.getHeight()) {
                return null;
            }
        } catch (BadLocationException e) {
            return null;
        }
        return name;
    }

    private int viewToModel(@NotNull Point point) {
        return textArea.viewToModel2D(point);
    }

    /**
     * Start and end of the name token at an offset: a plain or quoted identifier ({@code "x"}, {@code `x`}, {@code [x]}),
     * or a function name (a user function); keywords, literals and comments are not names.
     */
    @Nullable
    private int[] findNameAtOffset(int offset) {
        if (offset < 0 || offset >= textArea.getDocument().getLength()) {
            return null;
        }
        Token token = textArea.modelToToken(offset);
        if (token == null || offset < token.getOffset() || offset >= token.getEndOffset()) {
            return null;
        }
        int type = token.getType();
        String lexeme = token.getLexeme();
        // The SQL token maker reads a SQL Server [name] as a preprocessor token
        boolean name = type == TokenTypes.IDENTIFIER || type == TokenTypes.LITERAL_STRING_DOUBLE_QUOTE
            || type == TokenTypes.LITERAL_BACKQUOTE || type == TokenTypes.FUNCTION
            || type == TokenTypes.PREPROCESSOR && lexeme.startsWith("["); //$NON-NLS-1$
        if (!name || lexeme.isEmpty() || !(Character.isLetter(lexeme.charAt(0)) || lexeme.charAt(0) == '_'
            || lexeme.charAt(0) == '"' || lexeme.charAt(0) == '`' || lexeme.charAt(0) == '[')) {
            return null;
        }
        return new int[]{token.getOffset(), token.getEndOffset()};
    }
}
