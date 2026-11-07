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
package com.seizia.chromite.model.ai.tools.internal;

import org.eclipse.osgi.util.NLS;

/**
 * Text of the AI agent tools that the user reads (the open-transaction list of the confirmation dialog).
 *
 * 설계: 이 번들의 문구는 대부분 AI 모델이 읽는 것(도구 설명, 결과 JSON, 오류)이라 영어로 둔다. 사용자에게만 보이는 문구만
 * 여기 두고 화면 언어를 따른다. 같은 사실을 모델에 돌려주는 값(예: getTransactionStatus의 agentTransactions)은 영어 그대로다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 열린 트랜잭션 확인 목록의 소유자·설명(docs/chromite/swing-l10n.md "남은 한국어 문구 정리")
 */
public class AIToolsMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.model.ai.tools.internal.AIToolsMessages"; //$NON-NLS-1$

    public static String pending_owner_agent;
    public static String pending_owner_query_window;
    public static String pending_owner_server_session;
    public static String pending_agent_work_default;
    public static String pending_agent_work_database;
    public static String pending_agent_work_ddl_default;
    public static String pending_agent_work_ddl_database;
    public static String pending_query_window_work;
    public static String pending_server_session_work;

    static {
        NLS.initializeMessages(BUNDLE_NAME, AIToolsMessages.class);
    }

    private AIToolsMessages() {
    }
}
