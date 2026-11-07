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
package com.seizia.chromite.tools.compare.schema;

import org.eclipse.osgi.util.NLS;

/**
 * Comments that the schema compare script carries (manual steps and the header).
 *
 * 설계: 스크립트 주석은 사용자가 쿼리 창에서 읽는 글이므로 NLS 묶음에 둔다. 영어가 기본({@code SchemaCompareMessages.properties}),
 * 한국어는 {@code SchemaCompareMessages_ko.properties}이고 OS 언어를 따른다. {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로
 * 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/schema-compare.md)
 */
public class SchemaCompareMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.tools.compare.schema.SchemaCompareMessages"; //$NON-NLS-1$

    public static String script_header_source;
    public static String script_header_target;
    public static String script_header_review;
    public static String script_cannot_drop;
    public static String script_cannot_create;
    public static String script_cannot_change_type;
    public static String script_other_database;
    public static String script_no_definition;
    public static String script_default_not_copied;

    static {
        NLS.initializeMessages(BUNDLE_NAME, SchemaCompareMessages.class);
    }

    private SchemaCompareMessages() {
    }
}
