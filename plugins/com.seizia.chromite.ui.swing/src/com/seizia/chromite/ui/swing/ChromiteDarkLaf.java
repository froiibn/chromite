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

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;

/**
 * Chromite 다크 테마 (DataGrip New UI Dark 모양).
 *
 * 설계: FlatLaf의 기본 다크 테마({@link FlatDarkLaf})를 상속하고, 값은 같은 패키지의 {@code ChromiteDarkLaf.properties}에서
 * 디자인 토큰(docs/chromite/design-spec.md)으로 바꾼다({@link ChromiteLightLaf}와 같은 방식).
 * Darcula({@code FlatDarculaLaf})를 쓰지 않는 이유: 트리·콤보 화살표를 삼각형으로 그리고 트리·표 바탕을 창 바탕보다 밝게 덧칠해,
 * New UI(셰브런, 트리 = 창 바탕, 편집기·결과 = 더 어두운 바탕)와 달라진다.
 * JetBrains의 테마 파일·아이콘은 쓰지 않고 값만 옮겼다.
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 디자인 적용: 상위 클래스를 FlatDarculaLaf에서 FlatDarkLaf로 바꾸고 P0 토큰 적용 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 P1: 스크롤바 UI({@link ChromiteScrollBarUI}) 등록 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-08 탭 창 UI({@link ChromiteTabbedPaneUI}) 등록 (docs/chromite/swing-followup-fixes.md "작은 화면 수정")
 * - 2026-11-12 스크롤 창 UI({@link ChromiteScrollPaneUI}) 등록: 안쪽 스크롤 창이 휠을 바깥으로 넘김 (앱 변경 #97, docs/chromite/swing-options.md "휠 스크롤 (#97)")
 */
public class ChromiteDarkLaf extends FlatDarkLaf {

    public static final String NAME = "Chromite Dark"; //$NON-NLS-1$

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public UIDefaults getDefaults() {
        UIDefaults defaults = super.getDefaults();
        ChromiteScrollBarUI.install(defaults);
        ChromiteTabbedPaneUI.install(defaults);
        ChromiteScrollPaneUI.install(defaults);
        return defaults;
    }
}
