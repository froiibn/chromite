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
 * Object Explorer에서 테이블 저장 방식(메모리/디스크/휘발성)을 말로 알려 주는 툴팁 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 이 기능만의 {@link NLS} 묶음을 둔다. 저장 방식의 이름
 * ({0})은 모델이 준다(DBSTableStorageKind, ModelMessages). 영어가 기본({@code TableStorageMessages.properties}),
 * 한국어는 {@code TableStorageMessages_ko.properties}다. 키 접두사는 {@code table_storage_}다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Altibase 메모리/디스크 테이블 색 구분의 툴팁 (docs/chromite/altibase.md)
 */
public class TableStorageMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.TableStorageMessages"; //$NON-NLS-1$

    public static String table_storage_tooltip;
    public static String table_storage_tooltip_with_description;

    static {
        NLS.initializeMessages(BUNDLE_NAME, TableStorageMessages.class);
    }

    private TableStorageMessages() {
    }
}
