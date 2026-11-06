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

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.fife.ui.rsyntaxtextarea.modes.SQLTokenMaker;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPKeywordType;
import org.jkiss.dbeaver.model.impl.sql.BasicSQLDialect;
import org.jkiss.dbeaver.model.sql.SQLDialect;

import javax.swing.text.Segment;
import java.util.*;
import java.util.function.Supplier;

/**
 * SQL 편집기 구문 강조의 단어 분류: RSyntaxTextArea SQL 토크나이저의 결과를 연결의 SQL 방언과 SSMS 색 규칙으로 고친다.
 *
 * 설계:
 * <ul>
 *     <li>RSyntaxTextArea {@link SQLTokenMaker}는 고정된 단어 목록을 쓴다. {@code COUNT}, {@code SUM}은 예약어,
 *     {@code COALESCE}, {@code GETDATE}, {@code OVER}는 일반 이름, {@code ,}·{@code ;}는 이름, {@code a.b}의 {@code .}은 실수로 나온다.
 *     문자열·주석·숫자 경계는 맞으므로 토크나이저는 그대로 쓰고, 한 줄의 토큰 목록을 받은 뒤 종류만 바꾼다
 *     (여러 줄 주석·문자열의 상태는 줄 끝 토큰으로 이어지는데, 그 토큰은 바꾸지 않는다).</li>
 *     <li>단어 분류는 SWT 편집기({@code SQLRuleManager}, {@code SQLWordRule})와 같다: 방언의 예약어 중 FUNCTION/OTHER 종류는 함수,
 *     KEYWORD는 키워드, TYPE은 자료형이고, 연결되어 있으면 방언의 자료형·함수 목록을 더한다. 함수 이름은 뒤에 {@code (}가 오거나
 *     키워드가 아니면 함수다({@code LEFT JOIN}의 LEFT는 키워드, {@code LEFT(x)}는 함수). 방언이 모르는 단어는 토크나이저의 분류를 둔다.</li>
 *     <li>SSMS 규칙(design-spec "SQL 구문 색"): T-SQL 논리 연산자({@code ALL AND ANY BETWEEN EXISTS IN LIKE NOT OR SOME})와
 *     {@code IS}, {@code NULL}은 연산자(회색), {@code DATEADD}·{@code DATEDIFF} 같은 날짜 함수의 첫 인수인 날짜 부분({@code SECOND} 등)은 함수 색,
 *     {@code N'...'}의 {@code N}은 문자열, {@code @변수}와 {@code :매개변수}는 변수, {@code @@ROWCOUNT} 같은 시스템 변수는 함수 색이다.
 *     문장 부호({@code , ; .})는 연산자이고, 한정자 뒤({@code a.Count})의 단어는 이름이다.</li>
 *     <li>방언은 편집기의 연결에서 얻는다(연결되어 있으면 데이터 소스의 방언, 아니면 드라이버의 스크립트 방언, 연결이 없으면 표준 SQL).
 *     편집기가 연결을 바꾸면 {@link Words}를 새로 만들어 문서 전체를 다시 칠한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 방언 기반 함수·키워드·자료형 분류와 SSMS 연산자·날짜 부분·변수 규칙 (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 */
final class SqlTokenClassifier {

    private static final Log log = Log.getLog(SqlTokenClassifier.class);

    /**
     * T-SQL logical operators (Microsoft Learn "Logical operators (Transact-SQL)") and IS, NULL: SSMS "SQL Operator" color.
     */
    private static final Set<String> OPERATOR_WORDS = Set.of(
        "all", "and", "any", "between", "exists", "in", "like", "not", "or", "some", "is", "null");
    /**
     * Functions whose first argument is a date part (Microsoft Learn DATEADD, DATEDIFF, DATEDIFF_BIG, DATENAME, DATEPART,
     * DATETRUNC, DATE_BUCKET).
     */
    private static final Set<String> DATE_PART_FUNCTIONS = Set.of(
        "dateadd", "datediff", "datediff_big", "datename", "datepart", "datetrunc", "date_bucket");
    /**
     * Date part names and abbreviations of those functions.
     */
    private static final Set<String> DATE_PARTS = Set.of(
        "year", "yy", "yyyy", "quarter", "qq", "q", "month", "mm", "m", "dayofyear", "dy", "y", "day", "dd", "d",
        "week", "wk", "ww", "weekday", "dw", "w", "hour", "hh", "minute", "mi", "n", "second", "ss", "s",
        "millisecond", "ms", "microsecond", "mcs", "nanosecond", "ns", "tzoffset", "tz", "iso_week", "isowk", "isoww");

    private SqlTokenClassifier() {
    }

    /**
     * Keyword, type and function names of one SQL dialect, in lower case.
     */
    static final class Words {

        static final Words STANDARD = of(BasicSQLDialect.INSTANCE, null);

        private final Map<String, DBPKeywordType> words = new HashMap<>();
        private final Set<String> functions = new HashSet<>();

        private Words() {
        }

        /**
         * The words of the connection's dialect.
         */
        @NotNull
        static Words of(@Nullable DBPDataSourceContainer container) {
            if (container == null) {
                return STANDARD;
            }
            DBPDataSource dataSource = container.isConnected() ? container.getDataSource() : null;
            if (dataSource != null) {
                return of(dataSource.getSQLDialect(), dataSource);
            }
            try {
                return of(container.getScriptDialect().createInstance(), null);
            } catch (Exception e) {
                log.debug("Can't create the SQL dialect of " + container.getName(), e);
                return STANDARD;
            }
        }

        @NotNull
        private static Words of(@NotNull SQLDialect dialect, @Nullable DBPDataSource dataSource) {
            Words result = new Words();
            for (String word : dialect.getReservedWords()) {
                DBPKeywordType type = dialect.getKeywordType(word);
                String key = word.toLowerCase(Locale.ROOT);
                // Functions without parentheses have the type OTHER (as in SQLRuleManager)
                if (type == DBPKeywordType.FUNCTION || type == DBPKeywordType.OTHER) {
                    result.functions.add(key);
                } else {
                    result.words.put(key, type == DBPKeywordType.TYPE ? DBPKeywordType.TYPE : DBPKeywordType.KEYWORD);
                }
            }
            // Data types and functions of the server are known when connected (as in SQLRuleManager)
            if (dataSource != null) {
                for (String type : dialect.getDataTypes(dataSource)) {
                    result.words.put(type.toLowerCase(Locale.ROOT), DBPKeywordType.TYPE);
                }
                for (String function : dialect.getFunctions()) {
                    result.functions.add(function.toLowerCase(Locale.ROOT));
                }
            }
            return result;
        }
    }

    /**
     * Token maker factory of one text area: its SQL token maker reads the text area's current {@link Words}.
     */
    static final class Factory extends TokenMakerFactory {
        private final Supplier<Words> words;

        Factory(@NotNull Supplier<Words> words) {
            this.words = words;
        }

        @Override
        protected TokenMaker getTokenMakerImpl(String key) {
            return new ClassifyingTokenMaker(words);
        }

        @Override
        public Set<String> keySet() {
            return Set.of(SyntaxConstants.SYNTAX_STYLE_SQL);
        }
    }

    /**
     * RSyntaxTextArea's SQL tokenizer with the token types corrected per line.
     */
    static final class ClassifyingTokenMaker extends SQLTokenMaker {
        private final Supplier<Words> words;
        private final List<Token> lineTokens = new ArrayList<>();

        ClassifyingTokenMaker(@NotNull Supplier<Words> words) {
            this.words = words;
        }

        @Override
        public Token getTokenList(Segment text, int initialTokenType, int startOffset) {
            Token first = super.getTokenList(text, initialTokenType, startOffset);
            lineTokens.clear();
            for (Token t = first; t != null && t.isPaintable(); t = t.getNextToken()) {
                lineTokens.add(t);
            }
            classify(lineTokens, words.get());
            return first;
        }
    }

    /**
     * Corrects the token types of one line.
     */
    static void classify(@NotNull List<Token> tokens, @NotNull Words words) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            int type = token.getType();
            String lexeme = token.getLexeme();
            if (type == TokenTypes.LITERAL_NUMBER_FLOAT && lexeme.chars().noneMatch(Character::isDigit)) {
                // A lone '.' (qualified names) is lexed as a number
                token.setType(TokenTypes.OPERATOR);
            } else if (type == TokenTypes.IDENTIFIER && (lexeme.equals("@") || lexeme.equals("?"))) { //$NON-NLS-1$ //$NON-NLS-2$
                classifyVariable(tokens, i);
            } else if (type == TokenTypes.IDENTIFIER && lexeme.length() > 1 && lexeme.charAt(0) == ':' && isWord(lexeme.substring(1))) {
                classifyNamedParameter(tokens, i);
            } else if (isWordToken(type) && isWord(lexeme)) {
                classifyWord(tokens, i, words);
            } else if (type == TokenTypes.IDENTIFIER && lexeme.chars().noneMatch(SqlTokenClassifier::isNameChar)) {
                // Punctuation (',' ';') is lexed as an identifier; SSMS shows it in the operator color
                token.setType(TokenTypes.OPERATOR);
            }
        }
    }

    private static void classifyWord(@NotNull List<Token> tokens, int index, @NotNull Words words) {
        Token token = tokens.get(index);
        if (token.getType() == TokenTypes.VARIABLE || token.getType() == TokenTypes.FUNCTION && isAdjacentTo(tokens, index, "@")) { //$NON-NLS-1$
            return; // classified with its '@'
        }
        String word = token.getLexeme().toLowerCase(Locale.ROOT);
        Token next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
        if (word.equals("n") && next != null && next.getType() == TokenTypes.LITERAL_CHAR //$NON-NLS-1$
            && next.getOffset() == token.getEndOffset()) {
            token.setType(TokenTypes.LITERAL_CHAR); // N'...' Unicode string
            return;
        }
        Token previous = previousSignificant(tokens, index);
        if (previous != null && previous.isSingleChar('.')) {
            token.setType(TokenTypes.IDENTIFIER); // qualified name part
            return;
        }
        if (OPERATOR_WORDS.contains(word)) {
            token.setType(TokenTypes.OPERATOR);
            return;
        }
        if (DATE_PARTS.contains(word) && previous != null && previous.isSingleChar('(')) {
            Token function = previousSignificant(tokens, tokens.indexOf(previous));
            if (function != null && DATE_PART_FUNCTIONS.contains(function.getLexeme().toLowerCase(Locale.ROOT))) {
                token.setType(TokenTypes.FUNCTION);
                return;
            }
        }
        DBPKeywordType keywordType = words.words.get(word);
        Token following = nextSignificant(tokens, index);
        boolean call = following != null && following.isSingleChar('(');
        if (call && DATE_PART_FUNCTIONS.contains(word)) {
            // Not in every dialect's function list, but their date part arguments are colored as functions
            token.setType(TokenTypes.FUNCTION);
            return;
        }
        if (words.functions.contains(word)) {
            if (keywordType == null || call) {
                token.setType(TokenTypes.FUNCTION);
                return;
            }
        }
        if (keywordType == DBPKeywordType.KEYWORD) {
            token.setType(TokenTypes.RESERVED_WORD);
        } else if (keywordType == DBPKeywordType.TYPE) {
            token.setType(TokenTypes.DATA_TYPE);
        }
    }

    /**
     * {@code :name} parameters, lexed as one identifier; {@code ::name} is a cast (PostgreSQL), not a parameter.
     */
    private static void classifyNamedParameter(@NotNull List<Token> tokens, int index) {
        Token token = tokens.get(index);
        Token previous = index > 0 ? tokens.get(index - 1) : null;
        if (previous != null && previous.isSingleChar(':') && previous.getEndOffset() == token.getOffset()) {
            previous.setType(TokenTypes.OPERATOR);
            token.setType(TokenTypes.IDENTIFIER);
        } else {
            token.setType(TokenTypes.VARIABLE);
        }
    }

    /**
     * {@code @name} variables, {@code @@name} system variables and {@code ?} parameters. The lexer splits {@code @name}
     * into '@' and the name (a keyword when the name is one), so both tokens get the variable color.
     */
    private static void classifyVariable(@NotNull List<Token> tokens, int index) {
        Token token = tokens.get(index);
        if (token.isSingleChar('?')) {
            token.setType(TokenTypes.VARIABLE);
            return;
        }
        Token previous = index > 0 ? tokens.get(index - 1) : null;
        if (previous != null && previous.isSingleChar('@') && previous.getEndOffset() == token.getOffset()) {
            return; // second '@' of '@@', classified with the first
        }
        int nameIndex = index + 1;
        boolean system = false;
        if (token.isSingleChar('@') && nameIndex < tokens.size() && tokens.get(nameIndex).isSingleChar('@')
            && tokens.get(nameIndex).getOffset() == token.getEndOffset()) {
            system = true;
            nameIndex++;
        }
        if (nameIndex >= tokens.size()) {
            return;
        }
        Token name = tokens.get(nameIndex);
        if (name.getOffset() != tokens.get(nameIndex - 1).getEndOffset() || !isWord(name.getLexeme())) {
            return;
        }
        int type = system ? TokenTypes.FUNCTION : TokenTypes.VARIABLE;
        for (int i = index; i <= nameIndex; i++) {
            tokens.get(i).setType(type);
        }
    }

    private static boolean isAdjacentTo(@NotNull List<Token> tokens, int index, @NotNull String lexeme) {
        return index > 0 && tokens.get(index - 1).getLexeme().equals(lexeme)
            && tokens.get(index - 1).getEndOffset() == tokens.get(index).getOffset();
    }

    @Nullable
    private static Token previousSignificant(@NotNull List<Token> tokens, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (!tokens.get(i).isCommentOrWhitespace()) {
                return tokens.get(i);
            }
        }
        return null;
    }

    @Nullable
    private static Token nextSignificant(@NotNull List<Token> tokens, int index) {
        for (int i = index + 1; i < tokens.size(); i++) {
            if (!tokens.get(i).isCommentOrWhitespace()) {
                return tokens.get(i);
            }
        }
        return null;
    }

    private static boolean isWordToken(int type) {
        return switch (type) {
            case TokenTypes.IDENTIFIER, TokenTypes.RESERVED_WORD, TokenTypes.RESERVED_WORD_2, TokenTypes.FUNCTION,
                 TokenTypes.DATA_TYPE, TokenTypes.LITERAL_BOOLEAN -> true;
            default -> false;
        };
    }

    private static boolean isWord(@NotNull String lexeme) {
        return !lexeme.isEmpty() && (Character.isLetter(lexeme.charAt(0)) || lexeme.charAt(0) == '_')
            && lexeme.chars().allMatch(SqlTokenClassifier::isNameChar);
    }

    private static boolean isNameChar(int c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '@';
    }
}
