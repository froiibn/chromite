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
package com.seizia.chromite.model.admin;

import org.eclipse.osgi.util.NLS;

/**
 * Activity Monitor의 권한 안내와 세션 끝내기 결과 문구(PostgreSQL, Oracle).
 *
 * 설계: {@link ServerAdminMessages}(세션 필터·명령 이름)와 따로 둔다. 여러 작업이 이 번들의 문구 파일을 함께 고치면 병합이 충돌하므로
 * 세션·잠금 권한 확인 작업의 문구만 이 클래스에 모은다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: PostgreSQL·Oracle 권한 확인 (docs/chromite/swing-admin-tools.md)
 */
public class ActivityAccessMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.model.admin.ActivityAccessMessages"; //$NON-NLS-1$

    public static String access_postgres_own_sessions;
    public static String access_oracle_missing_views;
    public static String access_oracle_no_alter_system;
    public static String session_not_ended;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ActivityAccessMessages.class);
    }

    private ActivityAccessMessages() {
    }
}
