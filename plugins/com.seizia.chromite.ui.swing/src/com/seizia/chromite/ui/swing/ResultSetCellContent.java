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
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.data.DBDContentCached;
import com.seizia.chromite.model.data.DBDContentStorage;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.data.JDBCContentBytes;
import com.seizia.chromite.model.impl.jdbc.data.JDBCContentChars;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Path;

/**
 * 결과 칸 하나의 LOB 내용(값 패널용). 수신기가 Job 스레드에서 만든다({@link ResultSetGridReceiver}).
 *
 * 설계: 그리드는 칸마다 표시 문자열만 갖고 LOB 값 객체({@link DBDContent})는 놓아 주지만, 값 패널은 JSON·XML·이미지·16진수로 보려면
 * 내용 전체가 필요하다. SWT 결과 뷰어는 값 객체를 결과 모델에 남겨 두고 패널이 열 때 읽는다({@code ContentPanelEditor}).
 * 같은 방식으로 하되 이미 메모리에 있는 내용({@link DBDContentCached}: PostgreSQL bytea·json, SQL Server varbinary·image, MariaDB BLOB처럼
 * 드라이버가 바이트·문자열로 준 값)은 그 배열·문자열만 남기고 값 객체는 놓아 준다. 메모리에 없는 LOB(BLOB·CLOB 로케이터, SQLXML)는
 * 값 객체를 남겼다가 패널이 처음 보여 줄 때 백그라운드 Job에서 {@link DBDContent#getContents}로 읽는다(SWT와 같은 지연 읽기).
 * 읽는 양은 {@link #MAX_LOAD_SIZE}까지다(SWT 기본 LOB 캐시 크기 {@code content.cache.maxsize}와 같은 1,000,000바이트).
 * 결과를 지울 때 {@link #release}로 남긴 값 객체를 놓아 준다.
 * 파일로 저장할 때는({@link #writeTo}) 다 읽은 내용은 메모리에서, 잘라 읽었거나 아직 읽지 않은 LOB는 DB 값에서 끝까지 쓴다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 값 패널 (docs/chromite/swing-result-viewer.md "값 패널")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-08 파일로 저장({@link #writeTo}), 글자 내용 판단({@link #isText}) (docs/chromite/swing-grid-editing.md "LOB 파일")
 * - 2026-11-08 만든 문장의 값({@link #toStatementValue}): 결과 행에서 SQL 만들기가 LOB 칸을 리터럴로 쓰도록 남긴 값 객체나 받은 바이트·글자를
 *   값 객체로 돌려줌 (docs/chromite/swing-result-generate-sql.md)
 */
final class ResultSetCellContent {

    /**
     * Bytes or characters read from a LOB for the value panel (SWT default {@code content.cache.maxsize}).
     */
    static final int MAX_LOAD_SIZE = 1_000_000;

    /**
     * Loaded content: bytes or text, the full length, and whether it was cut at {@link #MAX_LOAD_SIZE}.
     */
    record Loaded(@Nullable byte[] bytes, @Nullable String text, long length, boolean truncated) {
    }

    @NotNull
    private final String contentType;
    @Nullable
    private DBDContent liveContent;
    @Nullable
    private volatile Loaded loaded;

    private ResultSetCellContent(@NotNull String contentType, @Nullable DBDContent liveContent, @Nullable Loaded loaded) {
        this.contentType = contentType;
        this.liveContent = liveContent;
        this.loaded = loaded;
    }

    /**
     * Makes the panel content of a fetched LOB value (job thread, the result set is open). If it is not
     * {@link #isLive() live}, the receiver releases the value object as before.
     */
    @NotNull
    static ResultSetCellContent capture(@NotNull DBDContent content) {
        String contentType = CommonUtils.notEmpty(content.getContentType());
        if (content instanceof DBDContentCached cached) {
            Object value = cached.getCachedValue();
            if (value instanceof byte[] bytes) {
                return new ResultSetCellContent(contentType, null, new Loaded(bytes, null, bytes.length, false));
            }
            if (value instanceof String text) {
                return new ResultSetCellContent(contentType, null, new Loaded(null, text, text.length(), false));
            }
        }
        return new ResultSetCellContent(contentType, content, null);
    }

    /**
     * Whether the receiver must keep the value object (it is read later).
     */
    boolean isLive() {
        return liveContent != null;
    }

    @NotNull
    String getContentType() {
        return contentType;
    }

    /**
     * The content, read from the database on the first call (background job only: it may run a query).
     */
    @NotNull
    Loaded load(@NotNull DBRProgressMonitor monitor) throws DBException {
        Loaded result = loaded;
        if (result != null) {
            return result;
        }
        DBDContent content;
        synchronized (this) {
            content = liveContent;
        }
        if (content == null) {
            throw new DBException(EditorMessages.editor_value_released);
        }
        DBDContentStorage storage = content.getContents(monitor);
        if (storage == null) {
            result = new Loaded(null, "", 0, false);
        } else {
            try {
                if (storage instanceof DBDContentCached cached && cached.getCachedValue() instanceof String text) {
                    result = new Loaded(null, text, text.length(), false);
                } else if (contentType.startsWith("text") || contentType.endsWith("json") || contentType.endsWith("xml")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    result = readText(storage);
                } else {
                    result = readBytes(storage);
                }
            } catch (IOException e) {
                throw new DBException(EditorMessages.editor_value_cant_read_content, e);
            }
        }
        loaded = result;
        return result;
    }

    @NotNull
    private static Loaded readBytes(@NotNull DBDContentStorage storage) throws IOException {
        long length = storage.getContentLength();
        try (InputStream in = storage.getContentStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Math.max(length, 0), MAX_LOAD_SIZE));
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while (total < MAX_LOAD_SIZE && (count = in.read(buffer, 0, Math.min(buffer.length, MAX_LOAD_SIZE - total))) > 0) {
                out.write(buffer, 0, count);
                total += count;
            }
            boolean truncated = total >= MAX_LOAD_SIZE && (length > total || in.read() >= 0);
            return new Loaded(out.toByteArray(), null, Math.max(length, total), truncated);
        }
    }

    @NotNull
    private static Loaded readText(@NotNull DBDContentStorage storage) throws IOException {
        long length = storage.getContentLength();
        try (Reader in = storage.getContentReader()) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while (out.length() < MAX_LOAD_SIZE && (count = in.read(buffer, 0, Math.min(buffer.length, MAX_LOAD_SIZE - out.length()))) > 0) {
                out.append(buffer, 0, count);
            }
            boolean truncated = out.length() >= MAX_LOAD_SIZE && (length > out.length() || in.read() >= 0);
            return new Loaded(null, out.toString(), Math.max(length, out.length()), truncated);
        }
    }

    /**
     * The content as a value of a generated statement (Generate SQL, background job only): the kept LOB value, which the
     * statement writer reads to its end, or a value of the bytes or text the driver gave (their value object was
     * released when the row was read).
     */
    @NotNull
    DBDContent toStatementValue(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext context) throws DBException {
        DBDContent content;
        synchronized (this) {
            content = liveContent;
        }
        if (content != null) {
            return content;
        }
        Loaded result = load(monitor);
        if (result.text() != null) {
            return new JDBCContentChars(context, result.text());
        }
        return new JDBCContentBytes(context, result.bytes() == null ? new byte[0] : result.bytes());
    }

    /**
     * Whether the content is text (read as characters, saved to a file as UTF-8): the driver gave a string, or the
     * content type is text, JSON or XML.
     */
    boolean isText() {
        Loaded result = loaded;
        if (result != null) {
            return result.text() != null;
        }
        return contentType.startsWith("text") || contentType.endsWith("json") || contentType.endsWith("xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Writes the whole content to a file (background job only): from memory when it was read completely, otherwise
     * from the database value, so a file gets more than the {@link #MAX_LOAD_SIZE} the value panel shows.
     */
    void writeTo(@NotNull DBRProgressMonitor monitor, @NotNull Path file) throws DBException {
        Loaded result = loaded;
        if (result != null && !result.truncated()) {
            ResultSetLobFiles.writeLoaded(result.text(), result.bytes(), file);
            return;
        }
        DBDContent content;
        synchronized (this) {
            content = liveContent;
        }
        if (content == null) {
            throw new DBException(EditorMessages.editor_value_released);
        }
        ResultSetLobFiles.writeContent(monitor, content, file, isText());
    }

    /**
     * Releases a value object kept for later reading (the results are cleared).
     */
    void release() {
        DBDContent content;
        synchronized (this) {
            content = liveContent;
            liveContent = null;
        }
        if (content != null) {
            DBUtils.releaseValue(content);
        }
    }
}
