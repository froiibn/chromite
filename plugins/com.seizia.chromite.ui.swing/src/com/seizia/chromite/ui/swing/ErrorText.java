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

import com.seizia.chromite.ui.swing.internal.ErrorMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 예외({@link Throwable})와 상태({@link IStatus})를 화면에 보여 줄 문구로 바꾸는 유일한 곳.
 *
 * 설계: 예외를 {@code toString()}이나 {@code CommonUtils.getAllExceptionMessages}로 그대로 보여 주면 Java 클래스 이름
 * ({@code com.seizia.chromite.DBRuntimeException: …})이 화면에 나온다. {@code Throwable(Throwable cause)} 생성자가 원인의
 * {@code toString()}을 메시지로 삼고, DBeaver의 {@code GeneralUtils.getExceptionMessage}도 {@code toString()}을 쓰기
 * 때문이다. 그래서 Swing 화면은 예외 문구를 모두 이 클래스로 만든다. 규칙:
 * <ul>
 *     <li>원인 사슬(과 {@link SQLException#getNextException()})을 따라 각 예외의 {@code getLocalizedMessage()}를 한 줄씩 모은다.</li>
 *     <li>메시지 앞의 예외 클래스 이름({@code java.sql.SQLException: })은 떼고, 클래스 이름뿐인 메시지는 버린다.</li>
 *     <li>제품 내부 패키지 이름({@code org.jkiss.…}, {@code java.…} 등)이 문장 안에 있으면 단순 이름만 남긴다.</li>
 *     <li>앞 줄에 이미 들어 있는 메시지는 버리고, 앞 줄을 품은 더 긴 메시지는 앞 줄을 대신한다(같은 문구 반복 방지).</li>
 *     <li>메시지가 인자뿐인 예외(알 수 없는 호스트, 파일 없음 등)는 종류 이름을 앞에 붙인다.</li>
 *     <li>DB 오류 번호와 SQLSTATE는 {@code DBDatabaseException}이 메시지에 이미 넣는다({@code SQL Error [208] [S0002]: …}).
 *     그렇게 들어 있지 않을 때만 쿼리 메시지 머리말과 같은 꼴({@code Msg 208, State S0002})로 마지막 줄에 붙인다.</li>
 *     <li>보여 줄 메시지가 하나도 없으면(메시지 없는 예외, JVM이 만든 NPE 문구 등) "예기치 않은 오류(메모리 부족)"처럼
 *     지역화된 일반 문구와 종류 이름만 보여 준다.</li>
 * </ul>
 * 스택 추적({@link #stackTrace})에는 패키지 이름이 그대로 있다. 버그 보고에 필요하므로 오류 대화상자의 접힌 "자세히"
 * 영역에서만 보여 주고({@link ErrorDialog}), 처음 보이는 문구에는 넣지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-frontend.md "오류 문구")
 */
final class ErrorText {

    /**
     * A Java class name at the start of a message ({@code Throwable(Throwable)} uses the cause's {@code toString()}):
     * package segments start with a lower-case letter, the simple name with an upper-case one.
     */
    private static final Pattern CLASS_PREFIX = Pattern.compile("^\\s*(?:Caused by:\\s*)?((?:[a-z_$][\\w$]*\\.)+([A-Z][\\w$]*))(?::\\s*|\\s*$)"); //$NON-NLS-1$

    /**
     * Qualified names of the product and the Java platform inside a sentence (also in the {@code a/b/C} form of
     * {@code NoClassDefFoundError}). Only these roots: other dotted names in messages are usually database objects
     * ({@code sales.dbo.Orders}).
     */
    private static final Pattern INTERNAL_NAME = Pattern.compile(
        "\\b(?:org[./]jkiss|com[./]seizia|org[./]eclipse|java|javax|jdk|sun)[./](?:[\\w$]+[./])*([A-Z][\\w$]*)"); //$NON-NLS-1$

    private ErrorText() {
    }

    /**
     * Messages of the error and its causes, one per line, without class names. Never empty.
     */
    @NotNull
    static String describe(@NotNull Throwable error) {
        return String.join("\n", describeLines(error)); //$NON-NLS-1$
    }

    /**
     * {@link #describe} without the {@code Msg 208, State S0002} line, for texts that show the code in a header of
     * their own (the query Messages tab).
     */
    @NotNull
    static String describeMessages(@NotNull Throwable error) {
        List<String> messages = collectMessages(error);
        return messages.isEmpty() ? genericMessage(error) : String.join("\n", messages); //$NON-NLS-1$
    }

    /**
     * {@link #describe} on one line, for status lines, table cells and tool tips.
     */
    @NotNull
    static String describeLine(@NotNull Throwable error) {
        return oneLine(describe(error));
    }

    /**
     * The most specific message (the deepest cause that has one), for a short place such as a table cell.
     */
    @NotNull
    static String summary(@NotNull Throwable error) {
        List<String> messages = collectMessages(error);
        return messages.isEmpty() ? genericMessage(error) : oneLine(messages.get(messages.size() - 1));
    }

    /**
     * Text of a status and its children (a failed connection job, a data transfer job), without class names.
     */
    @NotNull
    static String describe(@NotNull IStatus status) {
        List<String> lines = new ArrayList<>();
        appendStatus(lines, status);
        if (lines.isEmpty()) {
            Throwable exception = findException(status);
            return exception != null ? describe(exception) : ErrorMessages.error_unexpected;
        }
        return String.join("\n", lines); //$NON-NLS-1$
    }

    /**
     * The first exception in a status tree (the details of an error dialog), or null.
     */
    @Nullable
    static Throwable findException(@NotNull IStatus status) {
        if (status.getException() != null) {
            return status.getException();
        }
        for (IStatus child : status.getChildren()) {
            Throwable exception = findException(child);
            if (exception != null) {
                return exception;
            }
        }
        return null;
    }

    /**
     * A message text from the model layer (not an exception): class name prefixes are removed from each line and
     * qualified product names shortened.
     */
    @NotNull
    static String clean(@Nullable String text) {
        if (CommonUtils.isEmpty(text)) {
            return ""; //$NON-NLS-1$
        }
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\n", -1)) { //$NON-NLS-1$
            if (!result.isEmpty()) {
                result.append('\n');
            }
            String cleaned = cleanMessage(line, Set.of());
            if (cleaned != null) {
                // Keep the indentation of the line (tool output, aligned details)
                int indent = 0;
                while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                    indent++;
                }
                result.append(line, 0, indent).append(cleaned);
            }
        }
        return result.toString();
    }

    /**
     * Full stack trace for the collapsed Details part of error dialogs (package names are expected there).
     */
    @NotNull
    static String stackTrace(@NotNull Throwable error) {
        StringWriter out = new StringWriter();
        try (PrintWriter writer = new PrintWriter(out)) {
            error.printStackTrace(writer);
        }
        return out.toString();
    }

    /**
     * True for the lines of a printed stack trace (the header {@code a.b.CException: message}, {@code at …},
     * {@code ... n more}, {@code Caused by:}, {@code Suppressed:}), which logs write into task output.
     */
    static boolean isStackTraceLine(@NotNull String line) {
        String trimmed = line.strip();
        // Frames are indented; tool output lines that start with "at" are not
        boolean indented = trimmed.length() < line.length() && Character.isWhitespace(line.charAt(0));
        if ((indented && (trimmed.startsWith("at ") || trimmed.startsWith("... "))) //$NON-NLS-1$ //$NON-NLS-2$
            || trimmed.startsWith("Caused by:") || trimmed.startsWith("Suppressed:")) { //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        Matcher matcher = CLASS_PREFIX.matcher(trimmed);
        return matcher.find() && isThrowableName(matcher.group(1), matcher.group(2), Set.of());
    }

    //////////////////////////////////////////////////////////
    // Building

    @NotNull
    private static List<String> describeLines(@NotNull Throwable error) {
        List<String> lines = collectMessages(error);
        if (lines.isEmpty()) {
            lines.add(genericMessage(error));
            return lines;
        }
        String sqlCode = describeSqlCode(error, lines);
        if (sqlCode != null) {
            lines.add(sqlCode);
        }
        return lines;
    }

    /**
     * Distinct cleaned messages of the cause chain, outermost first.
     */
    @NotNull
    private static List<String> collectMessages(@NotNull Throwable error) {
        List<Throwable> chain = getChain(error);
        Set<String> classNames = new HashSet<>();
        for (Throwable throwable : chain) {
            classNames.add(throwable.getClass().getName());
        }
        List<String> lines = new ArrayList<>();
        for (Throwable throwable : chain) {
            addMessage(lines, messageOf(throwable, classNames));
        }
        return lines;
    }

    /**
     * The error, its causes and the chained SQL exceptions (a batch reports the real errors there), each once.
     */
    @NotNull
    private static List<Throwable> getChain(@NotNull Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable throwable = error; throwable != null && seen.add(throwable); throwable = throwable.getCause()) {
            chain.add(throwable);
            if (throwable instanceof SQLException sqlException) {
                for (SQLException next = sqlException.getNextException(); next != null && seen.add(next); next = next.getNextException()) {
                    chain.add(next);
                }
            }
        }
        return chain;
    }

    private static void addMessage(@NotNull List<String> lines, @Nullable String message) {
        if (CommonUtils.isEmptyTrimmed(message)) {
            return;
        }
        for (String line : lines) {
            if (line.contains(message)) {
                return;
            }
        }
        int last = lines.size() - 1;
        if (last >= 0 && message.contains(lines.get(last))) {
            // A wrapper repeating the start of its cause's message: keep the complete one
            lines.set(last, message);
            return;
        }
        lines.add(message);
    }

    /**
     * The cleaned message of one throwable, or null if it has none worth showing.
     */
    @Nullable
    private static String messageOf(@NotNull Throwable throwable, @NotNull Set<String> classNames) {
        if (isInternalError(throwable)) {
            // JVM texts such as 'Cannot invoke "a.B.c()" because "<local1>" is null' are not for users
            return null;
        }
        String message = cleanMessage(throwable.getLocalizedMessage(), classNames);
        if (message == null) {
            return null;
        }
        String kind = getArgumentKind(throwable);
        return kind == null ? message : NLS.bind(ErrorMessages.error_kind_detail, kind, message);
    }

    /**
     * Removes leading class names and shortens qualified product names; null if nothing is left.
     */
    @Nullable
    private static String cleanMessage(@Nullable String message, @NotNull Set<String> classNames) {
        if (message == null) {
            return null;
        }
        String text = message.strip();
        // Wrappers of wrappers: "a.BException: c.DException: text"
        for (Matcher matcher = CLASS_PREFIX.matcher(text); matcher.find(); matcher = CLASS_PREFIX.matcher(text)) {
            if (!isThrowableName(matcher.group(1), matcher.group(2), classNames)) {
                break;
            }
            text = text.substring(matcher.end()).strip();
        }
        text = INTERNAL_NAME.matcher(text).replaceAll("$1"); //$NON-NLS-1$
        return text.isEmpty() ? null : text;
    }

    private static boolean isThrowableName(@NotNull String qualifiedName, @NotNull String simpleName, @NotNull Set<String> classNames) {
        return classNames.contains(qualifiedName) || simpleName.endsWith("Exception") || simpleName.endsWith("Error") //$NON-NLS-1$ //$NON-NLS-2$
            || simpleName.endsWith("Throwable") || simpleName.endsWith("Warning") || simpleName.endsWith("Fault"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Programming errors whose JVM-made messages describe code, not the problem.
     */
    private static boolean isInternalError(@NotNull Throwable throwable) {
        return throwable instanceof NullPointerException || throwable instanceof ClassCastException
            || throwable instanceof IndexOutOfBoundsException || throwable instanceof ArrayStoreException;
    }

    /**
     * Kind name for exceptions whose message is only the argument (a host name, a path, a class name).
     */
    @Nullable
    private static String getArgumentKind(@NotNull Throwable throwable) {
        if (throwable instanceof UnknownHostException) {
            return ErrorMessages.error_kind_unknown_host;
        } else if (throwable instanceof NoSuchFileException) {
            return ErrorMessages.error_kind_file_not_found;
        } else if (throwable instanceof FileAlreadyExistsException) {
            return ErrorMessages.error_kind_file_exists;
        } else if (throwable instanceof AccessDeniedException) {
            return ErrorMessages.error_kind_access_denied;
        } else if (throwable instanceof DirectoryNotEmptyException) {
            return ErrorMessages.error_kind_directory_not_empty;
        } else if (throwable instanceof NotDirectoryException) {
            return ErrorMessages.error_kind_not_directory;
        } else if (throwable instanceof ClassNotFoundException || throwable instanceof NoClassDefFoundError) {
            return ErrorMessages.error_kind_class_not_found;
        }
        return null;
    }

    /**
     * "Unexpected error (kind)" for an error chain without any message.
     */
    @NotNull
    private static String genericMessage(@NotNull Throwable error) {
        String kind = null;
        for (Throwable throwable : getChain(error)) {
            String throwableKind = getKind(throwable);
            if (throwableKind != null) {
                // The innermost kind is the most specific one
                kind = throwableKind;
            }
        }
        return kind == null ? ErrorMessages.error_unexpected : NLS.bind(ErrorMessages.error_unexpected_kind, kind);
    }

    @Nullable
    private static String getKind(@NotNull Throwable throwable) {
        if (throwable instanceof OutOfMemoryError) {
            return ErrorMessages.error_kind_out_of_memory;
        } else if (throwable instanceof StackOverflowError) {
            return ErrorMessages.error_kind_stack_overflow;
        } else if (throwable instanceof InterruptedException || throwable instanceof CancellationException) {
            return ErrorMessages.error_kind_canceled;
        } else if (throwable instanceof TimeoutException || throwable instanceof SocketTimeoutException) {
            return ErrorMessages.error_kind_timeout;
        } else if (throwable instanceof IOException) {
            return ErrorMessages.error_kind_io;
        } else if (throwable instanceof RuntimeException || throwable instanceof Error) {
            return ErrorMessages.error_kind_internal;
        }
        return null;
    }

    /**
     * {@code Msg 208, State S0002} of the first SQL exception, unless the messages already show its code or state
     * ({@code DBDatabaseException} puts both into its message).
     */
    @Nullable
    private static String describeSqlCode(@NotNull Throwable error, @NotNull List<String> lines) {
        SQLException sqlException = null;
        for (Throwable throwable : getChain(error)) {
            if (throwable instanceof SQLException e) {
                sqlException = e;
                break;
            }
        }
        if (sqlException == null) {
            return null;
        }
        String state = sqlException.getSQLState();
        int code = sqlException.getErrorCode();
        boolean hasState = !CommonUtils.isEmpty(state);
        boolean hasCode = code > 0;
        if (!hasState && !hasCode) {
            return null;
        }
        String text = String.join("\n", lines); //$NON-NLS-1$
        if ((hasState && text.contains(state)) || (hasCode && text.contains(String.valueOf(code)))) {
            return null;
        }
        if (!hasCode) {
            return NLS.bind(ErrorMessages.error_sql_state, state);
        }
        return hasState ? NLS.bind(ErrorMessages.error_sql_code_state, code, state) : NLS.bind(ErrorMessages.error_sql_code, code);
    }

    private static void appendStatus(@NotNull List<String> lines, @NotNull IStatus status) {
        // Status messages of exception statuses are often the exception's toString() (GeneralUtils.getExceptionMessage)
        addMessage(lines, cleanMessage(status.getMessage(), Set.of()));
        Throwable exception = status.getException();
        if (exception != null) {
            for (String line : describeLines(exception)) {
                addMessage(lines, line);
            }
        }
        for (IStatus child : status.getChildren()) {
            appendStatus(lines, child);
        }
    }

    @NotNull
    private static String oneLine(@NotNull String text) {
        return text.replaceAll("\\s+", " ").strip(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
