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
package com.seizia.chromite.model.update;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.net.URI;

/**
 * One checked file of the update list ({@link UpdateFeed#parse}).
 *
 * 설계: 목록의 값은 모두 {@link UpdateFeed}가 형식을 확인한 뒤에만 이 레코드가 된다. {@code url}은 목록의 값이 아니라
 * 목록과 같은 출처의 {@code /downloads/<file>}로 만든다(목록이 다른 주소를 가리켜도 그곳에서 받지 않는다).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public record UpdateEntry(
    @NotNull String file,
    @NotNull String version,
    @NotNull String arch,
    @NotNull String kind,
    @NotNull String platform,
    long size,
    @NotNull String sha256,
    @NotNull URI url,
    @Nullable String notesUrl,
    @Nullable Integer dataGeneration
) {
}
