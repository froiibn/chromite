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

import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SQL 파일 하나의 내용과 저장 형식(문자 집합, BOM, 줄바꿈).
 *
 * 설계: 편집기는 줄바꿈을 {@code \n}으로만 다루므로, 읽을 때 원래 줄바꿈(첫 줄바꿈이 {@code \r\n}이면 CRLF)을 기억했다가
 * 저장할 때 되돌린다. 문자 집합은 BOM(UTF-8, UTF-16 LE/BE)이 있으면 그것을 따르고 저장할 때 BOM도 다시 쓴다.
 * BOM이 없으면 UTF-8로 엄격하게 읽고(잘못된 바이트가 있으면 실패), UTF-8이 아니면 운영체제 기본 문자 집합
 * ({@code native.encoding}, 예: 한국어 Windows의 MS949)으로 읽는다. 대체 문자로 읽은 뒤 저장해 원본을 망가뜨리지 않기 위해서다.
 * 저장도 같은 이유로 그 문자 집합으로 표현할 수 없는 글자가 있으면 쓰지 않고 실패한다.
 *
 * 변경 이력:
 * - 2026-11-06 E단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 *
 * @param text          editor text with {@code \n} line breaks
 * @param charset       character set of the file
 * @param bom           whether the file starts with a byte order mark
 * @param lineSeparator line separator of the file
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 {@link #parse}: 디스크에 없는 파일 바이트(Query Library 묶음 파일의 항목)도 같은 규칙으로 읽음 (docs/chromite/swing-query-library.md "공유")
 */
record SqlFileContent(@NotNull String text, @NotNull Charset charset, boolean bom, @NotNull String lineSeparator) {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    /**
     * Format of a new (never saved) query: UTF-8 without BOM, platform line separator (CRLF on Windows, as SSMS).
     */
    @NotNull
    static SqlFileContent newFile(@NotNull String text) {
        return new SqlFileContent(text, StandardCharsets.UTF_8, false, System.lineSeparator());
    }

    @NotNull
    static SqlFileContent read(@NotNull Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    /**
     * The content of file bytes that are not on disk (an entry of a Query Library archive).
     */
    @NotNull
    static SqlFileContent parse(@NotNull byte[] bytes) throws IOException {
        Charset charset;
        int bomLength;
        if (startsWith(bytes, UTF8_BOM)) {
            charset = StandardCharsets.UTF_8;
            bomLength = UTF8_BOM.length;
        } else if (startsWith(bytes, UTF16LE_BOM)) {
            charset = StandardCharsets.UTF_16LE;
            bomLength = UTF16LE_BOM.length;
        } else if (startsWith(bytes, UTF16BE_BOM)) {
            charset = StandardCharsets.UTF_16BE;
            bomLength = UTF16BE_BOM.length;
        } else {
            charset = StandardCharsets.UTF_8;
            bomLength = 0;
        }
        String text;
        try {
            text = decode(bytes, bomLength, charset);
        } catch (CharacterCodingException e) {
            if (bomLength > 0) {
                throw new IOException(NLS.bind(CoreMessages.sql_file_invalid_text, charset.displayName()), e);
            }
            // No BOM and not UTF-8: a legacy file in the system's native encoding
            charset = getNativeCharset();
            try {
                text = decode(bytes, 0, charset);
            } catch (CharacterCodingException e2) {
                throw new IOException(NLS.bind(CoreMessages.sql_file_neither, charset.displayName()), e2);
            }
        }
        String lineSeparator = System.lineSeparator();
        int lineBreak = text.indexOf('\n');
        if (lineBreak >= 0) {
            lineSeparator = lineBreak > 0 && text.charAt(lineBreak - 1) == '\r' ? "\r\n" : "\n";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return new SqlFileContent(normalized, charset, bomLength > 0, lineSeparator);
    }

    /**
     * The same format with other text.
     */
    @NotNull
    SqlFileContent withText(@NotNull String newText) {
        return new SqlFileContent(newText, charset, bom, lineSeparator);
    }

    /**
     * Writes the text in this format. Nothing is written if a character can't be encoded.
     */
    void write(@NotNull Path file) throws IOException {
        CharsetEncoder encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded;
        try {
            encoded = encoder.encode(CharBuffer.wrap(text.replace("\n", lineSeparator)));
        } catch (CharacterCodingException e) {
            throw new IOException(NLS.bind(CoreMessages.sql_file_unmappable, charset.displayName()), e);
        }
        byte[] bomBytes = !bom ? new byte[0]
            : charset == StandardCharsets.UTF_16LE ? UTF16LE_BOM
            : charset == StandardCharsets.UTF_16BE ? UTF16BE_BOM
            : UTF8_BOM;
        byte[] bytes = new byte[bomBytes.length + encoded.remaining()];
        System.arraycopy(bomBytes, 0, bytes, 0, bomBytes.length);
        encoded.get(bytes, bomBytes.length, encoded.remaining());
        Files.write(file, bytes);
    }

    @NotNull
    private static String decode(@NotNull byte[] bytes, int offset, @NotNull Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    }

    @NotNull
    private static Charset getNativeCharset() {
        String name = System.getProperty("native.encoding"); //$NON-NLS-1$
        try {
            return name != null ? Charset.forName(name) : Charset.defaultCharset();
        } catch (IllegalArgumentException e) {
            return Charset.defaultCharset();
        }
    }

    private static boolean startsWith(@NotNull byte[] bytes, @NotNull byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
