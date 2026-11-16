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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * File helpers of the updates folder.
 *
 * 설계: 업데이트 폴더({@link UpdateService#defaultFolder()}) 안의 버전 폴더만 지운다. 심볼릭 링크는 따라가지 않는다
 * ({@link Files#walk}의 기본값). 지우지 못한 파일은 남기고 다음 차례에 다시 받거나 확인한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
final class UpdateFiles {

    private UpdateFiles() {
    }

    static void deleteTree(@NotNull Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // (left for the next round)
                }
            });
        } catch (IOException ignored) {
            // (left for the next round)
        }
    }
}
