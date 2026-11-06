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

/**
 * This installation as the update list names it: {@code platform} ({@code windows}, {@code macos}), {@code arch}
 * ({@code x64}, {@code arm64}), {@code kind} of file it updates from ({@code setup} on Windows, {@code zip} on macOS)
 * and its own plain version.
 *
 * 설계: 목록(releases.json, docs/chromite/distribution.md)의 같은 이름 필드와 글자 그대로 비교한다. macOS의 dmg는
 * 내려받기 페이지의 파일이고 업데이트는 zip을 쓴다(Turbrix와 같음).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public record UpdateTarget(@NotNull String platform, @NotNull String arch, @NotNull String kind, @NotNull String version) {

    public static final String PLATFORM_WINDOWS = "windows"; //$NON-NLS-1$
    public static final String PLATFORM_MACOS = "macos"; //$NON-NLS-1$
    public static final String ARCH_X64 = "x64"; //$NON-NLS-1$
    public static final String ARCH_ARM64 = "arm64"; //$NON-NLS-1$
    public static final String KIND_SETUP = "setup"; //$NON-NLS-1$
    public static final String KIND_ZIP = "zip"; //$NON-NLS-1$
    public static final String KIND_DMG = "dmg"; //$NON-NLS-1$
}
