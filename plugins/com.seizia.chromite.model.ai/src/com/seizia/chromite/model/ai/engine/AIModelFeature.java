/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.ai.engine;

/**
 * Model capabilities reported by an AI provider.
 *
 * 설계: 제공자 모델 목록 API가 알려 주거나 모델 ID로 추정할 수 있는 기능만 둔다.
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: 카탈로그만 채우던 PDF_INPUT, STRUCTURED_OUTPUT,
 *   TEMPERATURE_UNSUPPORTED를 삭제했다.
 */
public enum AIModelFeature {
    CHAT,
    STREAMING,
    SPEECH_TO_TEXT,
    EMBEDDING,
    ALWAYS_DEFAULT_TEMPERATURE,
    TOOL_CALL,
    REASONING,
    VISION
}
