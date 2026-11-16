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
package com.seizia.chromite.registry;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads the connections of another tool's configuration or export file.
 *
 * 설계: 형식마다 하나씩 구현한다(CSV는 이 번들의 {@link CsvConnectionReader}, Oracle 전용 도구는 그 DB 속성을 아는
 * {@code ext.oracle} 번들). 읽기만 하고 레지스트리를 바꾸지 않는다. 읽을 수 없는 파일은 {@link DBException}으로 알린다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 */
public interface ForeignConnectionReader {

    /**
     * The connections of the file, in file order.
     */
    @NotNull
    List<ForeignConnection> read(@NotNull Path file) throws DBException;
}
