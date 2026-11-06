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

import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.TextUtilities;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.DBPKeywordType;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLBatchDelimiterElement;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLModelPreferences;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.model.sql.completion.SQLCompletionContext;
import com.seizia.chromite.model.sql.completion.SQLCompletionProposalBase;
import com.seizia.chromite.model.sql.completion.SQLCompletionRequest;
import com.seizia.chromite.model.sql.parser.SQLParserContext;
import com.seizia.chromite.model.sql.parser.SQLParserPartitions;
import com.seizia.chromite.model.sql.parser.SQLRuleManager;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.sql.semantics.SQLDocumentSyntaxContext;
import com.seizia.chromite.model.sql.semantics.SQLQueryModelRecognizer;
import com.seizia.chromite.model.sql.semantics.SQLQueryRecognitionContext;
import com.seizia.chromite.model.sql.semantics.SQLScriptItemAtOffset;
import com.seizia.chromite.model.sql.semantics.completion.SQLQueryCompletionAnalyzer;
import com.seizia.chromite.model.sql.semantics.completion.SQLQueryCompletionContext;
import com.seizia.chromite.model.sql.semantics.completion.SQLQueryCompletionItemKind;
import com.seizia.chromite.model.sql.semantics.completion.SQLQueryCompletionProposal;
import com.seizia.chromite.model.sql.semantics.completion.SQLQueryCompletionProposalContext;
import com.seizia.chromite.model.sql.semantics.completion.SQLQueryWordEntry;
import com.seizia.chromite.model.sql.semantics.model.SQLQueryModel;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 편집기 자동완성 제안 계산(작업 스레드). 화면은 {@link SqlCompletionPopup}이 맡는다.
 *
 * 설계:
 * <ul>
 *     <li>분석기는 SWT 편집기의 기본 자동완성 엔진과 같은 {@link SQLQueryCompletionAnalyzer}(model.sql의 의미 분석 기반 "새 엔진")다.
 *     SWT는 설정 {@code SQLEditor.ContentAssistant.experimental.mode}의 기본값이 {@code NEW}이고({@code SQLEditorPreferencesInitializer}),
 *     모든 방언이 {@code BasicSQLDialect}를 상속하므로 기본 설정에서는 새 엔진만 쓴다({@code SQLCompletionProcessor.computeCompletionProposals}).
 *     예전 엔진({@code SQLCompletionAnalyzer})은 그 설정을 바꿔야 쓰이므로 Swing 편집기에는 넣지 않았다.</li>
 *     <li>SWT는 편집기의 백그라운드 파싱 Job이 미리 만들어 둔 의미 모델에서 완성 문맥을 얻는다({@code SQLBackgroundParsingJob.obtainCompletionContext}).
 *     Swing 편집기에는 그 Job이 없으므로 요청마다 커서가 있는 문장 하나만 파서로 찾아({@code SQLScriptParser.extractQueryAtPos}, SWT의
 *     {@code SQLEditorBase.extractQueryAtPos}와 같음) SWT 파싱 Job과 같은 인자로 의미 모델을 만든다({@link SQLQueryModelRecognizer}).
 *     메타데이터 읽기·함수 검사 설정은 SWT 기본값(읽기 켬, 검사 끔)이다. 모델 계층에는 그 기본값이 없어 설정을 그대로 읽으면 꺼짐이 된다. 스크립트 변수({@code @set}) 추적은 Swing 편집기에 아직 없어 빈 변수 집합으로 분석한다.</li>
 *     <li>파서 문맥(방언 구문, 연결별 규칙, 주석·문자열 분할기)은 실행과 같은 {@link SQLScriptBatches#createParserContext}로 만든다.
 *     SWT처럼 커서 바로 앞이 한 줄 주석이나 제어 명령이면 제안하지 않고, 커서 위치가 일반 SQL·문자열·따옴표 이름일 때만 분석한다.</li>
 *     <li>완성 옵션({@link SQLCompletionContext})은 SWT 설정 화면의 기본값({@code SQLEditorPreferencesInitializer})과 같다.
 *     그 설정 키는 SWT 번들({@code SQLPreferenceConstants})에 있어 Swing 제품에서는 읽을 수 없으므로 값을 그대로 옮겼다.
 *     짧은 이름/완전한 이름 형식만 모델 설정({@link SQLModelPreferences})을 연결 설정에서 읽는다(SWT와 같음).
 *     프로시저·함수 제안({@code isSearchProcedures}, SWT {@code SHOW_COLUMN_PROCEDURES})은 Tools &gt; Options의 설정을 따른다(SWT처럼 기본 끔).</li>
 *     <li>분석은 SWT처럼 {@link DBExecUtils#tryExecuteRecover}로 감싸 연결이 끊긴 경우 복구를 시도한다.
 *     실행 문맥은 F5 실행과 같은 연결의 기본(주) 문맥이다.</li>
 *     <li>결과 제안({@link Proposal})은 넣을 글자(교체 문자열), 교체 시작 위치, 표시 문자열·꾸밈 글자·아이콘을 담는다.
 *     입력을 계속해 목록을 거를 때는 엔진의 {@link SQLQueryCompletionProposal#validate}를 그대로 쓴다(SWT 팝업과 같은 일치 규칙·점수).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 F단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 * - 2026-11-07 프로시저·함수 제안을 늘 끄던 것을 Tools &gt; Options 설정으로 바꿈 (docs/chromite/swing-followup-fixes.md "자동완성 프로시저")
 */
final class SqlCompletionAnalysis {

    private static final Log log = Log.getLog(SqlCompletionAnalysis.class);

    /**
     * Same value as {@code SQLPreferenceConstants.PROPOSALS_MATCH_CONTAINS} default of the SWT editor:
     * proposals match the typed text anywhere in the name, ranked by the engine's score.
     */
    static final boolean SEARCH_INSIDE_NAMES = true;
    /**
     * Same value as {@code SQLPreferenceConstants.PROPOSAL_SORT_ALPHABETICALLY} default of the SWT editor.
     */
    static final boolean SORT_ALPHABETICALLY = true;

    private SqlCompletionAnalysis() {
    }

    /**
     * Snapshot of the editor taken in the EDT.
     *
     * @param explicit Ctrl+Space (connects if needed) rather than auto-activation while typing
     */
    record Request(@NotNull DBPDataSourceContainer dataSourceContainer, @NotNull String text, int offset, boolean explicit) {
    }

    /**
     * Proposals of one request.
     *
     * @param requestOffset caret offset the proposals were computed for
     */
    record Result(@NotNull Request request, int requestOffset, @NotNull List<Proposal> proposals) {
    }

    /**
     * Computes the proposals in a job thread. Returns {@code null} when there is nothing to propose here
     * (not connected for an auto-activation, comment, canceled).
     */
    @Nullable
    static Result compute(@NotNull DBRProgressMonitor monitor, @NotNull Request request) throws DBException {
        DBPDataSourceContainer container = request.dataSourceContainer();
        if (!container.isConnected()) {
            if (!request.explicit()) {
                // Typing must not open connections; Ctrl+Space does, like F5
                return null;
            }
            // Reports connection errors itself (SwingConnectionService)
            DBUtils.initDataSource(monitor, container, null);
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null || !container.isConnected() || monitor.isCanceled()) {
            return null;
        }
        DBCExecutionContext executionContext = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
        if (executionContext == null) {
            return null;
        }

        SQLParserContext parserContext = SQLScriptBatches.createParserContext(dataSource, request.text());
        IDocument document = parserContext.getDocument();
        int offset = request.offset();
        String contentType = getCompletionContentType(document, offset);
        if (contentType == null) {
            return null;
        }

        SwingCompletionContext completionContext = new SwingCompletionContext(
            dataSource, executionContext, parserContext.getSyntaxManager(), parserContext.getRuleManager());
        SQLScriptElement activeQuery = SQLScriptParser.extractQueryAtPos(parserContext, offset, false);
        // The SWT editor uses the simple mode for auto-activated sessions
        SQLCompletionRequest completionRequest = new SQLCompletionRequest(
            completionContext, document, offset, activeQuery, !request.explicit());
        completionRequest.setContentType(contentType);

        Analyzer analyzer = new Analyzer(
            m -> obtainCompletionContext(m, parserContext, executionContext, activeQuery, offset),
            completionRequest,
            () -> offset);
        DBExecUtils.tryExecuteRecover(monitor, dataSource, analyzer);
        if (monitor.isCanceled()) {
            return null;
        }
        List<Proposal> proposals = new ArrayList<>(analyzer.getResult().size());
        for (SQLQueryCompletionProposal proposal : analyzer.getResult()) {
            if (proposal instanceof Proposal swingProposal) {
                proposals.add(swingProposal);
            }
        }
        Integer contextOffset = analyzer.getActualContextOffset();
        return new Result(request, contextOffset != null ? contextOffset : offset, proposals);
    }

    /**
     * Partition at the caret, as {@code SQLCompletionProcessor.computeCompletionProposals}: nothing right after a
     * single-line comment or a control command, and only plain SQL, strings and quoted identifiers are analyzed.
     */
    @Nullable
    private static String getCompletionContentType(@NotNull IDocument document, int offset) {
        try {
            IRegion line = document.getLineInformationOfOffset(offset);
            if (offset <= line.getOffset() + line.getLength() && line.getLength() > 0 && offset > 0) {
                String previousType = TextUtilities.getContentType(document, SQLParserPartitions.SQL_PARTITIONING, offset - 1, true);
                if (SQLParserPartitions.CONTENT_TYPE_SQL_COMMENT.equals(previousType)
                    || SQLParserPartitions.CONTENT_TYPE_SQL_CONTROL.equals(previousType)) {
                    return null;
                }
            }
            String contentType = TextUtilities.getContentType(document, SQLParserPartitions.SQL_PARTITIONING, offset, true);
            if (IDocument.DEFAULT_CONTENT_TYPE.equals(contentType)
                || SQLParserPartitions.CONTENT_TYPE_SQL_STRING.equals(contentType)
                || SQLParserPartitions.CONTENT_TYPE_SQL_QUOTED.equals(contentType)) {
                return contentType;
            }
            return null;
        } catch (BadLocationException e) {
            log.debug(e);
            return null;
        }
    }

    /**
     * Semantic model of the statement at the caret, built with the arguments of {@code SQLBackgroundParsingJob}
     * (real metadata unless extra metadata reads are disabled, function validation setting, the connection's dialect).
     */
    @NotNull
    private static SQLQueryCompletionContext obtainCompletionContext(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLParserContext parserContext,
        @NotNull DBCExecutionContext executionContext,
        @Nullable SQLScriptElement activeQuery,
        int offset
    ) {
        if (!(activeQuery instanceof SQLQuery query)) {
            // Between statements (or in a control command): keywords that can start a statement
            return activeQuery == null || activeQuery instanceof SQLBatchDelimiterElement
                ? SQLQueryCompletionContext.prepareOffquery(0, offset)
                : SQLQueryCompletionContext.prepareEmpty(0, offset);
        }
        // SWT defaults: READ_METADATA_FOR_SEMANTIC_ANALYSIS = true, VALIDATE_FUNCTIONS = false (the SWT preference
        // initializer sets them; the model layer has no defaults for them). Extra metadata reads can still be disabled
        // per connection with the model setting, as in SQLBackgroundParsingJob.isReadMetadataEnabled.
        boolean useRealMetadata = !parserContext.getPreferenceStore().getBoolean(ModelPreferences.META_DISABLE_EXTRA_READ);
        boolean validateFunctions = false;
        SQLDialect dialect = parserContext.getDialect();
        SQLQueryRecognitionContext recognitionContext = new SQLQueryRecognitionContext(
            monitor, executionContext, useRealMetadata, validateFunctions, parserContext.getSyntaxManager(), dialect);
        SQLQueryModel model = SQLQueryModelRecognizer.recognizeQuery(recognitionContext, query.getOriginalText());
        if (model == null || monitor.isCanceled()) {
            return SQLQueryCompletionContext.prepareEmpty(0, offset);
        }
        SQLDocumentSyntaxContext syntaxContext = new SQLDocumentSyntaxContext();
        syntaxContext.registerScriptItemContext(
            query.getOriginalText(), model, query.getOffset(), query.getLength(), Boolean.TRUE.equals(query.isEndsWithDelimiter()));
        // The character before the caret belongs to the statement being completed (as in SQLBackgroundParsingJob)
        SQLScriptItemAtOffset scriptItem = syntaxContext.findScriptItem(offset - 1);
        return scriptItem != null
            ? SQLQueryCompletionContext.prepareCompletionContext(scriptItem, offset, executionContext, dialect)
            : SQLQueryCompletionContext.prepareOffquery(0, offset);
    }

    /**
     * The model analyzer, creating {@link Proposal}s that expose what the popup needs.
     */
    private static final class Analyzer extends SQLQueryCompletionAnalyzer {
        private SQLQueryCompletionProposalContext proposalContext;

        Analyzer(
            @NotNull Function<DBRProgressMonitor, SQLQueryCompletionContext> completionContextSupplier,
            @NotNull SQLCompletionRequest request,
            @NotNull Supplier<Integer> currentCompletionOffsetSupplier
        ) {
            super(completionContextSupplier, request, currentCompletionOffsetSupplier);
        }

        @NotNull
        @Override
        protected SQLQueryCompletionProposalContext createProposalContext(@NotNull SQLQueryCompletionContext completionContext) {
            return proposalContext = new ProposalContext(request, completionContext.getRequestOffset());
        }

        @NotNull
        @Override
        protected SQLQueryCompletionProposal createProposal(
            @NotNull SQLQueryCompletionItemKind itemKind,
            @Nullable DBSObject object,
            @Nullable DBPImage image,
            @Nullable String displayString,
            @Nullable String decorationString,
            @NotNull String description,
            @NotNull String replacementString,
            int replacementOffset,
            int replacementLength,
            @Nullable SQLQueryWordEntry filterString,
            int proposalScore
        ) {
            return new Proposal(proposalContext, itemKind, object, image, displayString, decorationString, description,
                replacementString, replacementOffset, replacementLength, filterString, proposalScore);
        }
    }

    /**
     * Same as the SWT {@code SQLEditorQueryCompletionProposalContext} with its default setting
     * ({@code INSERT_SPACE_AFTER_PROPOSALS} = true): a space follows inserted table and column names.
     */
    private static final class ProposalContext extends SQLQueryCompletionProposalContext {
        ProposalContext(@NotNull SQLCompletionRequest completionRequest, int requestOffset) {
            super(completionRequest, requestOffset);
        }

        @Override
        public boolean isInsertSpaceAfterProposal() {
            return true;
        }
    }

    /**
     * A proposal of the model analyzer with access to its insertion text and decoration.
     */
    static final class Proposal extends SQLQueryCompletionProposal {
        Proposal(
            @NotNull SQLQueryCompletionProposalContext proposalContext,
            @NotNull SQLQueryCompletionItemKind itemKind,
            @Nullable DBSObject object,
            @Nullable DBPImage image,
            @Nullable String displayString,
            @Nullable String decorationString,
            @NotNull String description,
            @NotNull String replacementString,
            int replacementOffset,
            int replacementLength,
            @Nullable SQLQueryWordEntry filterString,
            int proposalScore
        ) {
            super(proposalContext, itemKind, object, image, displayString, decorationString, description,
                replacementString, replacementOffset, replacementLength, filterString, proposalScore);
        }

        /**
         * Text inserted from {@link #getReplacementOffset()} to the caret, including a trailing space where the engine
         * adds one ({@link #getReplacementString()} returns the display text instead).
         */
        @NotNull
        String getInsertText() {
            return replacementString;
        }

        /**
         * Gray text after the name (e.g. the table of a column), may be null.
         */
        @Nullable
        String getDecoration() {
            return decorationString;
        }
    }

    /**
     * Completion options with the defaults of the SWT editor's preference initializer, except the procedure proposals,
     * which follow the Tools &gt; Options setting ({@link SqlCompletionPopup#isShowProcedures()}).
     */
    private static final class SwingCompletionContext implements SQLCompletionContext {
        private final DBPDataSource dataSource;
        private final DBCExecutionContext executionContext;
        private final SQLSyntaxManager syntaxManager;
        private final SQLRuleManager ruleManager;
        private final boolean useShortNames;
        private final boolean useFqNames;
        private final boolean searchProcedures;

        SwingCompletionContext(
            @NotNull DBPDataSource dataSource,
            @NotNull DBCExecutionContext executionContext,
            @NotNull SQLSyntaxManager syntaxManager,
            @NotNull SQLRuleManager ruleManager
        ) {
            this.dataSource = dataSource;
            this.executionContext = executionContext;
            this.syntaxManager = syntaxManager;
            this.ruleManager = ruleManager;
            // SQLCompletionObjectNameFormKind.getFromPreferences reads these model settings of the connection
            DBPPreferenceStore preferences = dataSource.getContainer().getPreferenceStore();
            this.useShortNames = preferences.getBoolean(SQLModelPreferences.SQL_EDITOR_PROPOSAL_SHORT_NAME);
            this.useFqNames = preferences.getBoolean(SQLModelPreferences.SQL_EDITOR_PROPOSAL_ALWAYS_FQ);
            this.searchProcedures = SqlCompletionPopup.isShowProcedures();
        }

        @Override
        public DBPDataSource getDataSource() {
            return dataSource;
        }

        @Nullable
        @Override
        public DBCExecutionContext getExecutionContext() {
            return executionContext;
        }

        @Override
        public SQLSyntaxManager getSyntaxManager() {
            return syntaxManager;
        }

        @Override
        public SQLRuleManager getRuleManager() {
            return ruleManager;
        }

        @Override
        public boolean isUseFQNames() {
            return useFqNames;
        }

        @Override
        public boolean isReplaceWords() {
            return false;
        }

        @Override
        public boolean isShowServerHelp() {
            return false;
        }

        @Override
        public boolean isUseShortNames() {
            return useShortNames;
        }

        @Override
        public int getInsertCase() {
            return PROPOSAL_CASE_DEFAULT;
        }

        @Override
        public boolean isSearchProcedures() {
            return searchProcedures;
        }

        @Override
        public boolean isSearchInsideNames() {
            return SEARCH_INSIDE_NAMES;
        }

        @Override
        public boolean isSortAlphabetically() {
            return SORT_ALPHABETICALLY;
        }

        @Override
        public boolean isSearchGlobally() {
            return false;
        }

        @Override
        public boolean isHideDuplicates() {
            return false;
        }

        @Override
        public boolean isShowValues() {
            return false;
        }

        @Override
        public boolean isForceQualifiedColumnNames() {
            return false;
        }

        @Override
        public SQLCompletionProposalBase createProposal(
            @NotNull SQLCompletionRequest request,
            @NotNull String displayString,
            @NotNull String replacementString,
            int cursorPosition,
            @Nullable DBPImage image,
            @NotNull DBPKeywordType proposalType,
            @Nullable String description,
            @Nullable DBPNamedObject object,
            @NotNull Map<String, Object> params
        ) {
            // Used by the older analyzer only; kept functional as in the LSP completion context
            return new SQLCompletionProposalBase(request, displayString, replacementString, cursorPosition, image,
                proposalType, description, object, params);
        }
    }
}
