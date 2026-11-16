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
package com.seizia.chromite.model.ai.agent;

/**
 * Result of a tool call as written to the audit log.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "감사 기록")
 */
public enum AIToolOutcome {
    SUCCESS,
    /** The tool ran and failed. */
    ERROR,
    /** The user (or the missing confirmation UI) said no. */
    DENIED,
    /** The policy blocks the tool on this connection. */
    BLOCKED,
    /** The connection is read-only and the tool changes the database. */
    READ_ONLY,
    CANCELED,
    /** Unknown tool, unknown connection or bad arguments. */
    INVALID
}
