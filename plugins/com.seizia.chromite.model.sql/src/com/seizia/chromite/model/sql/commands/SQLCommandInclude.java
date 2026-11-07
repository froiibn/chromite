/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.sql.commands;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLControlCommand;
import com.seizia.chromite.model.sql.SQLControlCommandHandler;
import com.seizia.chromite.model.sql.SQLControlResult;
import com.seizia.chromite.model.sql.SQLIncludedScript;
import com.seizia.chromite.model.sql.SQLScriptContext;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.eval.ScriptVariablesResolver;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code @include file.sql} (and MySQL {@code SOURCE file.sql}): runs the statements of another script file in the same
 * session, at the place of the command.
 *
 * 설계:
 * <ul>
 *     <li>처리기는 파일을 찾고 읽어 연결의 방언으로 나누기만 하고({@link SQLScriptBatches}: {@code GO} 배치도 편집기와 같게),
 *     {@link SQLIncludedScript}로 돌려준다. 실행은 실행기가 한다. SWT는 포함한 파일을 새 SQL 편집기로 열어 실행하고 끝나기를 기다렸다
 *     ({@code ui.editors.sql}, 삭제됨). Swing에는 그런 편집기 연결이 없고, 실행기 안에서 처리하면 오류 처리·취소·결과가 한 실행에 모인다.</li>
 *     <li>상대 경로는 포함하는 스크립트 파일의 폴더 기준이다(중첩 포함은 바깥 파일이 아니라 그 파일의 폴더). 저장하지 않은 쿼리 창에서
 *     상대 경로를 쓰면 기준이 없어 오류로 알린다. SWT는 찾지 못하면 프로그램의 현재 폴더에서도 찾았지만, 데스크톱 앱의 현재 폴더는 설치
 *     폴더 등이라 뜻이 없어 쓰지 않는다. 파일 이름의 {@code ${var}}는 스크립트 변수로 바꾸고, 따옴표({@code '…'}, {@code "…"}, 방언의
 *     식별자 따옴표)로 감싼 이름은 벗긴다(공백이 든 경로).</li>
 *     <li>포함 고리(자기 자신이나 바깥 파일을 다시 포함)는 거절한다. 스크립트 문맥의 부모를 따라 원본 파일과 같은 파일인지
 *     ({@link Files#isSameFile}, 상대·기호 링크·대소문자가 달라도 같은 파일) 본다. SWT는 경로 문자열을 비교해 {@code ./a.sql}과
 *     {@code a.sql}을 다른 파일로 보아 끝없이 포함할 수 있었다.</li>
 *     <li>문자 집합은 쿼리 창이 파일을 여는 규칙과 같다: BOM(UTF-8, UTF-16)을 따르고, 없으면 UTF-8로 엄격하게 읽고, 아니면 운영체제 기본
 *     문자 집합이다. 줄바꿈은 {@code \n}으로 맞춘다(오류 줄 번호).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 모델 명령으로 다시 만듦(SWT {@code ui.editors.sql} 처리기는 SWT 프런트엔드와 함께 삭제됨, model.sql과 MySQL
 *   {@code mysql.source}에 등록) (docs/chromite/swing-query-commands.md "@include")
 */
public class SQLCommandInclude implements SQLControlCommandHandler {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16LE_BOM = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16BE_BOM = {(byte) 0xFE, (byte) 0xFF};

    @NotNull
    @Override
    public SQLControlResult handleCommand(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLControlCommand command,
        @NotNull SQLScriptContext scriptContext
    ) throws DBException {
        DBCExecutionContext executionContext = scriptContext.getExecutionContext();
        if (executionContext == null) {
            throw new DBException("Not connected: the included script has no session to run in");
        }
        DBPDataSource dataSource = executionContext.getDataSource();
        Path file = resolveFile(command.getParameter(), scriptContext, dataSource);
        checkNoLoop(file, scriptContext);
        String text = readText(file);
        List<SQLScriptElement> elements = SQLScriptBatches.extractQueries(dataSource, text, 0, text.length());
        SQLScriptContext includedContext = new SQLScriptContext(
            scriptContext,
            scriptContext::getExecutionContext,
            file,
            scriptContext.getOutputWriter(),
            scriptContext.getParametersProvider());
        return SQLControlResult.transform(new SQLIncludedScript(dataSource, text, elements, file, includedContext));
    }

    /**
     * The real path of the file the command names.
     */
    @NotNull
    private static Path resolveFile(
        @Nullable String parameter,
        @NotNull SQLScriptContext scriptContext,
        @NotNull DBPDataSource dataSource
    ) throws DBException {
        String name = CommonUtils.notEmpty(parameter).trim();
        if (name.isEmpty()) {
            throw new DBException("The file name is missing. Expected syntax:\n@include file.sql");
        }
        name = GeneralUtils.replaceVariables(name, new ScriptVariablesResolver(scriptContext), true).trim();
        name = unquote(dataSource, name);
        Path path;
        try {
            path = Path.of(name);
        } catch (InvalidPathException e) {
            throw new DBException("Invalid file name '" + name + "'", e);
        }
        if (!path.isAbsolute()) {
            Path folder = getScriptFolder(scriptContext);
            if (folder == null) {
                throw new DBException("The relative file name '" + name + "' needs a saved script to start from. "
                    + "Save the script, or use an absolute path.");
            }
            path = folder.resolve(path);
        }
        if (!Files.isRegularFile(path)) {
            throw new DBException("File '" + path + "' not found");
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new DBException("Can't read file '" + path + "'", e);
        }
    }

    @NotNull
    private static String unquote(@NotNull DBPDataSource dataSource, @NotNull String name) {
        if (name.length() >= 2) {
            char first = name.charAt(0);
            if ((first == '\'' || first == '"') && name.charAt(name.length() - 1) == first) {
                return name.substring(1, name.length() - 1).trim();
            }
        }
        // The dialect's identifier quotes (`…` in MySQL, […] in SQL Server)
        return DBUtils.getUnQuotedIdentifier(dataSource, name);
    }

    /**
     * The folder of the nearest script file: the file that contains the command, or the file that included it.
     */
    @Nullable
    private static Path getScriptFolder(@NotNull SQLScriptContext scriptContext) {
        for (SQLScriptContext context = scriptContext; context != null; context = context.getParentContext()) {
            Path source = context.getSourceFile();
            if (source != null) {
                return source.toAbsolutePath().getParent();
            }
        }
        return null;
    }

    /**
     * Refuses a file that is already running in this script: the script itself, or a file that includes this one.
     */
    private static void checkNoLoop(@NotNull Path file, @NotNull SQLScriptContext scriptContext) throws DBException {
        List<String> chain = new ArrayList<>();
        boolean loop = false;
        for (SQLScriptContext context = scriptContext; context != null; context = context.getParentContext()) {
            Path source = context.getSourceFile();
            if (source == null) {
                continue;
            }
            chain.add(String.valueOf(source.getFileName()));
            if (isSameFile(source, file)) {
                loop = true;
                break;
            }
        }
        if (loop) {
            Collections.reverse(chain);
            chain.add(String.valueOf(file.getFileName()));
            throw new DBException("Include loop: " + String.join(" > ", chain) + ". A script can't include itself or a script that includes it.");
        }
    }

    private static boolean isSameFile(@NotNull Path source, @NotNull Path file) {
        try {
            return Files.isSameFile(source, file);
        } catch (IOException e) {
            // The source file is gone (renamed while running): compare the paths
            return source.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize());
        }
    }

    @NotNull
    private static String readText(@NotNull Path file) throws DBException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DBException("Can't read file '" + file + "'", e);
        }
        Charset charset = StandardCharsets.UTF_8;
        int bomLength = 0;
        if (startsWith(bytes, UTF8_BOM)) {
            bomLength = UTF8_BOM.length;
        } else if (startsWith(bytes, UTF16LE_BOM)) {
            charset = StandardCharsets.UTF_16LE;
            bomLength = UTF16LE_BOM.length;
        } else if (startsWith(bytes, UTF16BE_BOM)) {
            charset = StandardCharsets.UTF_16BE;
            bomLength = UTF16BE_BOM.length;
        }
        String text;
        try {
            text = decode(bytes, bomLength, charset);
        } catch (CharacterCodingException e) {
            if (bomLength > 0) {
                throw new DBException("File '" + file + "' is not valid " + charset.displayName() + " text", e);
            }
            // No BOM and not UTF-8: a legacy file in the system's native encoding
            Charset nativeCharset = getNativeCharset();
            try {
                text = decode(bytes, 0, nativeCharset);
            } catch (CharacterCodingException e2) {
                throw new DBException("File '" + file + "' is neither UTF-8 nor " + nativeCharset.displayName() + " text", e2);
            }
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
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

    @NotNull
    private static String decode(@NotNull byte[] bytes, int offset, @NotNull Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
            .toString();
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
}
