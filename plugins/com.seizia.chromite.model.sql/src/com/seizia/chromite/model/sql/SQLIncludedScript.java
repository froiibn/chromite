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
package com.seizia.chromite.model.sql;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;

import java.nio.file.Path;
import java.util.List;

/**
 * The statements of a script file that an {@code @include} command (MySQL {@code SOURCE}) read, with the script context
 * they run in.
 *
 * 설계: 포함한 파일의 문장은 포함한 스크립트의 세션과 트랜잭션에서, 그 명령 자리에 끼워 실행한다. 명령 처리기
 * ({@code SQLCommandInclude})는 파일을 읽고 나누기만 하고 이 객체를 {@link SQLControlResult#transform}으로 돌려주며, 실행기가
 * 문장을 실행한다({@code SQLQueryExecuteJob}은 남은 문장 앞에 끼우고, {@code SQLScriptProcessor}는 그 자리에서 실행한다).
 * 문장마다 이 파일의 {@link #getScriptContext() 스크립트 문맥}(부모 = 포함한 쪽의 문맥, 원본 파일 = 이 파일)을 쓰므로, 안에서 다시
 * 포함하는 상대 경로는 이 파일의 폴더를 기준으로 하고 포함 고리는 부모 문맥을 따라가며 찾으며, {@code @set} 값은 부모 문맥까지 올라간다.
 * SWT는 포함한 파일을 새 편집기로 열어 실행했다. 편집기 없이 실행 엔진 안에서 처리해 오류 처리, 취소, 결과와 메시지가 한 실행에 모인다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-query-commands.md "@include")
 */
public class SQLIncludedScript extends SQLScript {

    @NotNull
    private final Path file;
    @NotNull
    private final SQLScriptContext scriptContext;

    /**
     * @param text          the text of the file; the offsets of the elements refer to it
     * @param elements      the elements to execute, in order
     * @param file          the real path of the file
     * @param scriptContext the context the elements run in (its source file is {@code file})
     */
    public SQLIncludedScript(
        @Nullable DBPDataSource dataSource,
        @NotNull String text,
        @NotNull List<SQLScriptElement> elements,
        @NotNull Path file,
        @NotNull SQLScriptContext scriptContext
    ) {
        super(dataSource, text, elements);
        this.file = file;
        this.scriptContext = scriptContext;
    }

    @NotNull
    public Path getFile() {
        return file;
    }

    @NotNull
    public SQLScriptContext getScriptContext() {
        return scriptContext;
    }
}
