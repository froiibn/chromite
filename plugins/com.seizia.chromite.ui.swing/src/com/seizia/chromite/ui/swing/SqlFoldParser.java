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
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldParser;
import org.fife.ui.rsyntaxtextarea.folding.FoldParserManager;
import org.fife.ui.rsyntaxtextarea.folding.FoldType;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.text.BadLocationException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 쿼리 편집기의 접기 범위(문장, BEGIN…END·CASE…END 블록, 여러 줄 괄호, 여러 줄 블록 주석)와 Outline의 문장 목록.
 *
 * 설계:
 * <ul>
 *     <li>RSyntaxTextArea 4.0.1에는 SQL용 접기 파서가 없다({@code FoldParserManager}에 중괄호·XML·HTML·Python 등만 있음).
 *     그래서 같은 라이브러리의 {@link FoldParser}를 구현해 SQL 문법 스타일에 등록한다({@link #install()}). 접기 관리자·여백의 접기 단추·
 *     접기 명령(Ctrl+NumPad -/+/÷/×)은 라이브러리 것을 그대로 쓴다.</li>
 *     <li>편집기가 이미 칠해 둔 토큰({@link RSyntaxTextArea#getTokenListForLine})으로 읽는다. 문자열·따옴표 이름·주석 안의 글자는
 *     토큰 종류로 건너뛰므로 {@code 'BEGIN'}이나 주석 속 괄호에 속지 않는다. 방언 파서({@code SQLScriptParser})는 연결이 있어야 하고
 *     접기는 글자를 칠 때마다 EDT에서 다시 계산되므로 쓰지 않는다. 그래서 문장 경계는 실행의 문장 나누기와 다를 수 있다(문서에 적음).</li>
 *     <li>문장 끝: 블록(BEGIN/CASE) 밖의 {@code ;}, 줄에 혼자 있는 {@code GO}(SQL Server 배치)나 {@code /}(Oracle), 블록·괄호 밖의 빈 줄.
 *     빈 줄을 끝으로 보는 것은 세미콜론 없이 빈 줄로 문장을 나누는 SSMS식 스크립트 때문이다.</li>
 *     <li>블록: {@code BEGIN}과 {@code CASE}가 열고 {@code END}가 닫는다. {@code BEGIN TRAN/TRANSACTION/WORK/DISTRIBUTED/ISOLATION}과
 *     {@code BEGIN;}은 트랜잭션 시작이라 열지 않는다. {@code END IF/LOOP/WHILE/REPEAT/FOR}는 연 적 없는 블록을 닫으므로 무시하고,
 *     {@code END CASE}는 CASE를 닫는다. 짝이 맞지 않는 {@code END}는 무시하고, 문장 끝까지 닫히지 않은 괄호·블록의 접기는 버린다(입력 중).</li>
 *     <li>한 줄 안에 끝나는 범위는 접지 않는다(라이브러리의 다른 파서와 같음).</li>
 * </ul>
 * 모든 메서드는 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 코드 접기와 Outline (docs/chromite/swing-sql-editor-ergonomics.md)
 */
final class SqlFoldParser implements FoldParser {

    private static final Log log = Log.getLog(SqlFoldParser.class);

    private static final Set<String> TRANSACTION_WORDS = Set.of("TRAN", "TRANSACTION", "WORK", "DISTRIBUTED", "ISOLATION"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    private static final Set<String> UNOPENED_END_WORDS = Set.of("IF", "LOOP", "WHILE", "REPEAT", "FOR"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    private static final String BEGIN = "BEGIN"; //$NON-NLS-1$
    private static final String CASE = "CASE"; //$NON-NLS-1$
    private static final String END = "END"; //$NON-NLS-1$

    private static boolean installed;

    /**
     * A statement of the script: {@code [start, end)} in the document.
     */
    record Statement(int start, int end) {
    }

    /**
     * Registers the parser for the SQL syntax style (once; the library keeps one parser per style).
     */
    static void install() {
        if (!installed) {
            FoldParserManager.get().addFoldParserMapping(SyntaxConstants.SYNTAX_STYLE_SQL, new SqlFoldParser());
            installed = true;
        }
    }

    @Override
    public List<Fold> getFolds(RSyntaxTextArea textArea) {
        Scan scan = new Scan(textArea, true);
        scan.run();
        return scan.folds;
    }

    /**
     * The statements of the text area (Outline, Copy as Source Code), with the same boundaries as the statement folds.
     */
    @NotNull
    static List<Statement> findStatements(@NotNull RSyntaxTextArea textArea) {
        Scan scan = new Scan(textArea, false);
        scan.run();
        return scan.statements;
    }

    /**
     * The statement that contains an offset (or ends right before it), or null between statements.
     */
    @Nullable
    static Statement findStatementAt(@NotNull List<Statement> statements, int offset) {
        for (Statement statement : statements) {
            if (offset >= statement.start() && offset <= statement.end()) {
                return statement;
            }
        }
        return null;
    }

    private enum Kind {
        PAREN, BLOCK
    }

    private record Open(@NotNull Kind kind, @Nullable Fold fold, int start) {
    }

    /**
     * One pass over the tokens of the document.
     */
    private static final class Scan {
        private final RSyntaxTextArea textArea;
        private final boolean makeFolds;
        final List<Fold> folds = new ArrayList<>();
        final List<Statement> statements = new ArrayList<>();
        private final Deque<Open> open = new ArrayDeque<>();
        // Current statement: first token offset (-1 between statements) and end of its last content
        private int statementStart = -1;
        private int statementEnd;
        @Nullable
        private Fold statementFold;
        // Block comment being read (it spans lines)
        @Nullable
        private Fold commentFold;
        private int commentStart = -1;
        // A BEGIN waits for the next word: a block, or the start of a transaction
        private int pendingBegin = -1;
        // End offset of an END that waits for the next word: END IF etc. close nothing
        private int pendingEnd = -1;

        Scan(@NotNull RSyntaxTextArea textArea, boolean makeFolds) {
            this.textArea = textArea;
            this.makeFolds = makeFolds;
        }

        void run() {
            int lineCount = textArea.getLineCount();
            try {
                for (int line = 0; line < lineCount; line++) {
                    scanLine(line);
                }
                resolvePending(null);
                endStatement();
                if (commentFold != null) {
                    // An unterminated comment folds to the end of the document
                    closeFold(commentFold, textArea.getDocument().getLength());
                }
            } catch (BadLocationException e) {
                log.debug(e);
            }
        }

        private void scanLine(int line) throws BadLocationException {
            List<Token> tokens = new ArrayList<>();
            for (Token token = textArea.getTokenListForLine(line); token != null && token.isPaintable(); token = token.getNextToken()) {
                if (token.getType() == TokenTypes.COMMENT_MULTILINE) {
                    scanComment(token);
                } else if (!token.isCommentOrWhitespace()) {
                    tokens.add(token);
                }
            }
            if (tokens.isEmpty()) {
                if (commentStart < 0 && isBlankLine(line)) {
                    // A blank line outside of blocks and parentheses ends the statement (scripts without semicolons)
                    resolvePending(null);
                    if (open.isEmpty()) {
                        endStatement();
                    }
                }
                return;
            }
            if (tokens.size() <= 2 && isBatchDelimiter(tokens)) {
                resolvePending(null);
                closeAllOpen();
                endStatement();
                return;
            }
            for (Token token : tokens) {
                scanToken(token);
            }
        }

        private boolean isBlankLine(int line) throws BadLocationException {
            int start = textArea.getLineStartOffset(line);
            int end = textArea.getLineEndOffset(line);
            return textArea.getText(start, end - start).isBlank();
        }

        /**
         * {@code GO} (optionally with a repeat count) or {@code /} alone on a line.
         */
        private static boolean isBatchDelimiter(@NotNull List<Token> tokens) {
            Token first = tokens.getFirst();
            if (tokens.size() == 1 && first.isSingleChar('/')) {
                return true;
            }
            if (!"GO".equalsIgnoreCase(first.getLexeme())) { //$NON-NLS-1$
                return false;
            }
            return tokens.size() == 1 || tokens.get(1).getLexeme().chars().allMatch(Character::isDigit);
        }

        private void scanComment(@NotNull Token token) throws BadLocationException {
            String lexeme = token.getLexeme();
            if (commentStart < 0 && lexeme.startsWith("/*")) { //$NON-NLS-1$
                commentStart = token.getOffset();
                commentFold = makeFolds ? createFold(FoldType.COMMENT, commentStart) : null;
            }
            if (commentStart >= 0 && lexeme.endsWith("*/") && (token.getOffset() != commentStart || lexeme.length() >= 4)) { //$NON-NLS-1$
                int end = token.getEndOffset();
                if (commentFold != null) {
                    closeFold(commentFold, end);
                }
                if (statementStart >= 0) {
                    statementEnd = Math.max(statementEnd, end);
                }
                commentFold = null;
                commentStart = -1;
            }
        }

        private void scanToken(@NotNull Token token) throws BadLocationException {
            String word = getWord(token);
            if (resolvePending(word)) {
                // The CASE of END CASE
                statementEnd = token.getEndOffset();
                return;
            }
            if (statementStart < 0) {
                statementStart = token.getOffset();
                statementFold = makeFolds ? createFold(FoldType.CODE, statementStart) : null;
            }
            statementEnd = token.getEndOffset();
            if (token.isSingleChar('(')) {
                open.push(new Open(Kind.PAREN, makeFolds ? createFold(FoldType.CODE, token.getOffset()) : null, token.getOffset()));
            } else if (token.isSingleChar(')')) {
                closeOpen(Kind.PAREN, token.getEndOffset());
            } else if (token.isSingleChar(';')) {
                if (!hasOpenBlock()) {
                    closeAllOpen();
                    endStatement();
                }
            } else if (BEGIN.equals(word)) {
                // Decided by the next word (BEGIN TRAN) or a statement end (BEGIN;)
                pendingBegin = token.getOffset();
            } else if (CASE.equals(word)) {
                open.push(new Open(Kind.BLOCK, makeFolds ? createFold(FoldType.CODE, token.getOffset()) : null, token.getOffset()));
            } else if (END.equals(word)) {
                // Decided by the next word (END IF closes nothing that was opened here)
                pendingEnd = token.getEndOffset();
            }
        }

        /**
         * Settles a BEGIN or END seen before the given word (null: not a word, or a statement or batch ends here).
         *
         * @return true if the word was part of the END ({@code END CASE})
         */
        private boolean resolvePending(@Nullable String nextWord) throws BadLocationException {
            if (pendingBegin >= 0) {
                int begin = pendingBegin;
                pendingBegin = -1;
                if (nextWord != null && !TRANSACTION_WORDS.contains(nextWord)) {
                    open.push(new Open(Kind.BLOCK, makeFolds ? createFold(FoldType.CODE, begin) : null, begin));
                }
            }
            if (pendingEnd >= 0) {
                int end = pendingEnd;
                pendingEnd = -1;
                if (nextWord == null || !UNOPENED_END_WORDS.contains(nextWord)) {
                    closeOpen(Kind.BLOCK, end);
                    return CASE.equals(nextWord);
                }
            }
            return false;
        }

        private boolean hasOpenBlock() {
            for (Open item : open) {
                if (item.kind() == Kind.BLOCK) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Closes the innermost open range of a kind; ranges of the other kind opened inside it are dropped (unbalanced).
         */
        private void closeOpen(@NotNull Kind kind, int end) {
            if (open.stream().noneMatch(item -> item.kind() == kind)) {
                // An END or ")" without an opening: ignored
                return;
            }
            while (!open.isEmpty()) {
                Open item = open.pop();
                if (item.kind() == kind) {
                    if (item.fold() != null) {
                        closeFold(item.fold(), end);
                    }
                    return;
                }
                discardFold(item.fold());
            }
        }

        private void closeAllOpen() {
            while (!open.isEmpty()) {
                discardFold(open.pop().fold());
            }
        }

        private void endStatement() {
            closeAllOpen();
            if (statementStart < 0) {
                return;
            }
            statements.add(new Statement(statementStart, statementEnd));
            if (statementFold != null) {
                closeFold(statementFold, statementEnd);
            }
            statementStart = -1;
            statementFold = null;
        }

        /**
         * A new fold inside the innermost open fold (statement, block or parenthesis).
         */
        @NotNull
        private Fold createFold(int type, int start) throws BadLocationException {
            Fold parent = null;
            for (Open item : open) {
                if (item.fold() != null) {
                    parent = item.fold();
                    break;
                }
            }
            if (parent == null && statementStart >= 0 && statementStart <= start) {
                parent = statementFold;
            }
            if (parent != null) {
                return parent.createChild(type, start);
            }
            Fold fold = new Fold(type, textArea, start);
            folds.add(fold);
            return fold;
        }

        private void closeFold(@NotNull Fold fold, int end) {
            try {
                // The fold's end is the offset of its last character
                fold.setEndOffset(Math.max(fold.getStartOffset(), end - 1));
            } catch (BadLocationException e) {
                log.debug(e);
            }
            if (fold.isOnSingleLine()) {
                discardFold(fold);
            }
        }

        private void discardFold(@Nullable Fold fold) {
            if (fold == null) {
                return;
            }
            if (!fold.removeFromParent()) {
                folds.remove(fold);
            }
        }

        /**
         * Upper-case text of a word token (keyword or name), null for other tokens (operators, strings, numbers).
         */
        @Nullable
        private static String getWord(@NotNull Token token) {
            int type = token.getType();
            if (type == TokenTypes.LITERAL_STRING_DOUBLE_QUOTE || type == TokenTypes.LITERAL_CHAR || type == TokenTypes.LITERAL_BACKQUOTE) {
                return null;
            }
            String lexeme = token.getLexeme();
            if (lexeme.isEmpty() || !Character.isLetter(lexeme.charAt(0))) {
                return null;
            }
            return lexeme.toUpperCase(Locale.ROOT);
        }
    }
}
