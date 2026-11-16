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
package com.seizia.chromite.ui.swing.internal;

import org.eclipse.osgi.util.NLS;

/**
 * 도구 창 도킹(머리말의 옵션 메뉴: 떠 있는 창, 도킹, 자동 숨기기, 숨기기)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 도킹만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code DockingMessages.properties}), 한국어는 {@code DockingMessages_ko.properties}이고 OS 언어를 따른다.
 * 키 접두사는 {@code docking_}다. 도구 창 이름, Reset Window Layout, 숨기기 단추 도구 설명은 이미 {@link CoreMessages}에 있어 그대로 쓴다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-frontend.md "도구 창 도킹")
 */
public class DockingMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.DockingMessages"; //$NON-NLS-1$

    public static String docking_options;
    public static String docking_float;
    public static String docking_dock;
    public static String docking_auto_hide;
    public static String docking_hide;

    static {
        NLS.initializeMessages(BUNDLE_NAME, DockingMessages.class);
    }

    private DockingMessages() {
    }
}
