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

import org.jkiss.code.NotNull;

/**
 * An uncommitted transaction a tool call would end (disconnect, database switch, implicit DDL commit, kill, commit request).
 *
 * @param owner       whose transaction: "agent", "query window", or "server session 57"
 * @param description what is pending, for the confirmation UI (statements, rows, age)
 * @param canCommit   false when Chromite can't commit it (another server session: killing it rolls it back)
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "트랜잭션 안전")
 */
public record AIPendingTransaction(@NotNull String owner, @NotNull String description, boolean canCommit) {
}
