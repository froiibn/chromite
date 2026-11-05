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

import com.formdev.flatlaf.FlatDarculaLaf;

/**
 * Chromite 다크 테마 (DataGrip풍: Darcula 기반).
 *
 * 설계: FlatLaf의 Darcula 테마를 상속하고, 색은 같은 패키지의 {@code ChromiteDarkLaf.properties}에서 바꾼다
 * ({@link ChromiteLightLaf}와 같은 방식). JetBrains의 테마 파일·아이콘은 쓰지 않고 색감만 참고했다.
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 */
public class ChromiteDarkLaf extends FlatDarculaLaf {

    public static final String NAME = "Chromite Dark"; //$NON-NLS-1$

    @Override
    public String getName() {
        return NAME;
    }
}
