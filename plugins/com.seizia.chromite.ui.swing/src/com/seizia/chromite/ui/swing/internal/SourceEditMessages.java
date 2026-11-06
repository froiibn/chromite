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
 * Object Explorer의 개체 정의 편집(Modify, Script as ALTER, New View/Procedure/Function) 화면 문구.
 *
 * 설계: 다른 기능과 같은 파일을 고치지 않도록(병합 충돌) 이 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code SourceEditMessages.properties}), 한국어는 {@code SourceEditMessages_ko.properties}이고, 플랫폼이 OS 언어로 고른다.
 * 키 접두사는 {@code source_}다. {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 * 템플릿·스크립트의 SQL은 옮기지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-source-editor.md)
 */
public class SourceEditMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.SourceEditMessages"; //$NON-NLS-1$

    public static String source_modify;
    public static String source_modify_tip;
    public static String source_alter_to;
    public static String source_script_alter;
    public static String source_new_view;
    public static String source_new_procedure;
    public static String source_new_function;
    public static String source_new_view_title;
    public static String source_new_procedure_title;
    public static String source_new_function_title;
    public static String source_new_tip;
    public static String source_error_not_modifiable;
    public static String source_error_no_script;
    public static String source_error_not_connected;
    public static String source_error_no_template;

    static {
        NLS.initializeMessages(BUNDLE_NAME, SourceEditMessages.class);
    }

    private SourceEditMessages() {
    }
}
