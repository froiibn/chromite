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

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;

/**
 * Chromite 라이트 테마 (DataGrip New UI "Light with Light Header" 모양).
 *
 * 설계: FlatLaf 기본 라이트 테마를 상속하고, 값은 같은 패키지의 {@code ChromiteLightLaf.properties}에서 바꾼다.
 * FlatLaf는 Look and Feel 클래스 계층마다 "클래스 이름.properties"를 읽어 덮어쓰므로, 별도 테마 파일(외부 자산) 없이
 * 디자인 토큰(docs/chromite/design-spec.md)의 색·치수·글꼴만 바꿀 수 있다. JetBrains나 Microsoft의 테마 파일·아이콘은 쓰지 않고 값만 옮겼다.
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 디자인 적용: SSMS풍 색 대신 DataGrip New UI 라이트 P0 토큰 적용 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 P1: 스크롤바 UI({@link ChromiteScrollBarUI}) 등록 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-08 탭 창 UI({@link ChromiteTabbedPaneUI}) 등록 (docs/chromite/swing-followup-fixes.md "작은 화면 수정")
 * - 2026-11-12 스크롤 창 UI({@link ChromiteScrollPaneUI}) 등록: 안쪽 스크롤 창이 휠을 바깥으로 넘김 (앱 변경 #97, docs/chromite/swing-options.md "휠 스크롤 (#97)")
 * - 2026-11-12 메뉴 구분선 UI({@link ChromitePopupMenuSeparatorUI}) 등록 (앱 변경 #98, docs/chromite/swing-frontend.md "메뉴 간격과 구분선")
 */
public class ChromiteLightLaf extends FlatLightLaf {

    public static final String NAME = "Chromite Light"; //$NON-NLS-1$

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
        ChromitePopupMenuSeparatorUI.install(defaults);
        return defaults;
    }
}
