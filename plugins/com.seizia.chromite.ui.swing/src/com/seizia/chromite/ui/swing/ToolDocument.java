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

import org.jkiss.code.NotNull;

import javax.swing.*;

/**
 * 메인 창 문서 탭에 여는 도구 화면 (Activity Monitor, Find Objects).
 *
 * 설계: 메인 창은 SQL 편집기와 DDL 탭만 알았다. 도구 화면을 늘릴 때마다 메인 창의 탭 처리(닫기, 제목)를 고치지 않도록
 * 탭 제목·도구 설명·아이콘과 닫을 때 정리(타이머, 서버 연결)를 이 인터페이스로 묻는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-07 닫기 전 확인 {@link #confirmClose}: 프로시저 디버거가 디버깅한 세션의 열린 트랜잭션을 묻는다
 *   (docs/chromite/procedure-debugger.md)
 */
interface ToolDocument {

    @NotNull
    String getTitle();

    @NotNull
    String getToolTip();

    @NotNull
    Icon getIcon();

    /**
     * Asked before the tab closes and before Chromite exits (it may ask the user); false keeps it open. EDT.
     */
    default boolean confirmClose() {
        return true;
    }

    /**
     * Releases what the document holds (timers, connections); called when its tab is closed or the window closes.
     */
    void dispose();
}
