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

package com.seizia.chromite.runtime.ui;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.runtime.DBRProgressListener;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * Connections Service
 *
 * 변경 이력:
 * - 2026-11-07 {@code confirmTransactionsClose}를 지움: 유일한 호출(업스트림 유휴 트랜잭션 롤백)이 {@link UIServiceIdleTransactions}로 바뀜
 *   (docs/chromite/idle-transactions.md)
 */
public interface UIServiceConnections {

    void openConnectionEditor(@NotNull DBPDataSourceContainer dataSourceContainer, @Nullable String defaultPageName);

    void connectDataSource(@NotNull DBPDataSourceContainer dataSourceContainer, DBRProgressListener onFinish);

    void disconnectDataSource(@NotNull DBPDataSourceContainer dataSourceContainer);

    void askToReconnect(@NotNull DBPDataSourceContainer dataSourceContainer);

    void closeActiveTransaction(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext context, boolean commitTxn);

    boolean checkAndCloseActiveTransaction(@NotNull DBCExecutionContext[] contexts);

}