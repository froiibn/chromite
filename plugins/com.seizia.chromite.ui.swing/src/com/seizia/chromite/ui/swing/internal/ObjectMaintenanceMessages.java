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
 * Object Explorer의 유지 관리 명령(테이블 잘라내기, 트리거 사용/사용 안 함, 구체화된 뷰 새로 고침)의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 이 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code ObjectMaintenanceMessages.properties}), 한국어는 {@code ObjectMaintenanceMessages_ko.properties}이고 OS 언어를 따른다.
 * 키 접두사는 {@code objmaint_}다. 미리 보기 머리글·진행 문구·제한 안내는 삭제와 같은 {@code NavigatorMessages}를 쓴다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-explorer-object-actions.md "유지 관리 명령")
 */
public class ObjectMaintenanceMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ObjectMaintenanceMessages"; //$NON-NLS-1$

    public static String objmaint_truncate;
    public static String objmaint_truncate_tip;
    public static String objmaint_truncate_title;
    public static String objmaint_truncate_question_one;
    public static String objmaint_truncate_question_many;
    public static String objmaint_truncate_note;
    public static String objmaint_truncate_note_commit;
    public static String objmaint_truncate_button;
    public static String objmaint_enable_trigger;
    public static String objmaint_enable_trigger_tip;
    public static String objmaint_enable_trigger_title;
    public static String objmaint_disable_trigger;
    public static String objmaint_disable_trigger_tip;
    public static String objmaint_disable_trigger_title;
    public static String objmaint_refresh_mview;
    public static String objmaint_refresh_mview_tip;
    public static String objmaint_refresh_mview_title;
    public static String objmaint_error_statement;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ObjectMaintenanceMessages.class);
    }

    private ObjectMaintenanceMessages() {
    }
}
