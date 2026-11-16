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

import com.github.weisj.jsvg.logging.LogManager;
import com.github.weisj.jsvg.logging.Logger;
import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;

import java.util.function.Supplier;

/**
 * JSVG 로그를 Chromite 로그({@link Log})로 보내는 JSVG 로깅 공급자.
 *
 * 설계: JSVG OSGi 번들은 매니페스트에서 {@code osgi.serviceloader} 능력({@link LogManager} 공급자)을 필수로 요구한다.
 * Eclipse에서는 SWT 조각 {@code org.eclipse.swt.svg}가 이 능력을 제공하는데, 그 조각을 쓰면 p2가 SWT 번들까지 제품에 넣는다
 * (B단계 첫 빌드에서 확인: swt, swt.svg, swt.win32 3개가 들어감). Swing 제품에는 SWT가 없어야 하므로 이 번들이 같은 능력을
 * 제공한다({@code MANIFEST.MF}의 {@code Provide-Capability}, {@code META-INF/services}). SPI Fly가 동작하면 JSVG가 이 클래스를
 * 쓰고, 그렇지 않으면 JSVG 기본 로거(java.util.logging)를 쓴다. 어느 쪽이든 아이콘 그리기에는 영향이 없다.
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 */
public class JsvgLogManager implements LogManager {

    @NotNull
    @Override
    public Logger createLogger(@NotNull String name) {
        Log log = Log.getLog(name);
        return new Logger() {
            @Override
            public void log(@NotNull Level level, @NotNull String message) {
                log(level, message, null);
            }

            @Override
            public void log(@NotNull Level level, @NotNull String message, Throwable error) {
                switch (level) {
                    case ERROR -> log.error(message, error);
                    case WARNING -> log.warn(message, error);
                    // JSVG info messages are diagnostics; keep them out of the normal log level
                    default -> log.debug(message, error);
                }
            }

            @Override
            public void log(@NotNull Level level, @NotNull Supplier<String> messageSupplier) {
                log(level, messageSupplier.get(), null);
            }
        };
    }
}
