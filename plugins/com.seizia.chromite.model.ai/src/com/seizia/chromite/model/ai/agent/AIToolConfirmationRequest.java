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
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;

import java.util.List;
import java.util.Map;

/**
 * What the confirmation UI shows before a CONFIRM tool runs.
 *
 * @param client     who called the tool
 * @param toolId     function ID (e.g. {@code runSql})
 * @param toolName   display name
 * @param access     effective permission class of this call
 * @param connection connection the tool works on, or null
 * @param arguments  call arguments with secrets masked
 * @param preview    the exact SQL/DDL (or a description) the tool will run, or null when the tool has no preview
 * @param reason     policy rule that asked for confirmation
 * @param pendingTransactions uncommitted transactions the call would end (ask Commit / Roll back / Cancel), usually empty
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "확인")
 */
public record AIToolConfirmationRequest(
    @NotNull AIToolClient client,
    @NotNull String toolId,
    @NotNull String toolName,
    @NotNull AIToolAccess access,
    @Nullable DBPDataSourceContainer connection,
    @NotNull Map<String, Object> arguments,
    @Nullable String preview,
    @NotNull String reason,
    @NotNull List<AIPendingTransaction> pendingTransactions
) {
}
