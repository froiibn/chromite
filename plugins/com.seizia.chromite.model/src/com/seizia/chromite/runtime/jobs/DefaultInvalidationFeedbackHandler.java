/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.runtime.jobs;

import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPMessageType;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ChromiteNotifications;

import java.util.Collection;
import java.util.Set;

/**
 * 변경 이력:
 * - 2026-11-07 알림 문구를 ModelMessages로 옮김(docs/chromite/swing-l10n.md "남은 한국어 문구 정리")
 */
public class DefaultInvalidationFeedbackHandler implements InvalidateJob.InvalidationFeedbackHandler {
    @Override
    public boolean confirmInvalidate(@NotNull Set<DBPDataSourceContainer> containersToInvalidate) {
        return true;
    }

    @Override
    public void onInvalidateSuccess(
        @NotNull DBPDataSourceContainer container,
        @NotNull Collection<InvalidateJob.ContextInvalidateResult> results
    ) {
        ChromiteNotifications.showNotification(
            container.getDataSource(),
            ChromiteNotifications.NT_RECONNECT_SUCCESS,
            NLS.bind(ModelMessages.runtime_invalidate_success, InvalidateJob.getSucceededCount(results), results.size()),
            DBPMessageType.INFORMATION
        );
    }

    @Override
    public void onInvalidateFailure(
        @NotNull DBPDataSourceContainer container,
        @NotNull Collection<InvalidateJob.ContextInvalidateResult> results
    ) {
        ChromiteNotifications.showNotification(
            container.getDataSource(),
            ChromiteNotifications.NT_RECONNECT_FAILURE,
            ModelMessages.runtime_invalidate_failure,
            DBPMessageType.ERROR,
            () -> onNotificationFeedback(container)
        );
    }

    protected void onNotificationFeedback(@NotNull DBPDataSourceContainer container) {
        DBWorkbench.getPlatformUI().openConnectionEditor(container);
    }
}
