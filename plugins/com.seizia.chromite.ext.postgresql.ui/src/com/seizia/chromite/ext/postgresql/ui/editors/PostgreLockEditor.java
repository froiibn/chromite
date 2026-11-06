/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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

package com.seizia.chromite.ext.postgresql.ui.editors;

import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.swt.widgets.Composite;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.lock.PostgreLock;
import com.seizia.chromite.ext.postgresql.model.lock.PostgreLockManager;
import com.seizia.chromite.model.admin.locks.DBAServerLock;
import com.seizia.chromite.model.admin.locks.DBAServerLockItem;
import com.seizia.chromite.model.admin.locks.DBAServerLockManager;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.ui.editors.locks.edit.AbstractLockEditor;
import com.seizia.chromite.ui.editors.locks.manage.LockManagerViewer;

import java.util.HashMap;

public class PostgreLockEditor extends AbstractLockEditor {


    @SuppressWarnings("unchecked")
    @Override
    protected LockManagerViewer createLockViewer(DBCExecutionContext executionContext, Composite parent) {

        DBAServerLockManager<DBAServerLock, DBAServerLockItem> lockManager = (DBAServerLockManager) new PostgreLockManager((PostgreDataSource) executionContext.getDataSource());

        return new LockManagerViewer(this, parent, lockManager) {
            @Override
            protected void contributeToToolbar(DBAServerLockManager<DBAServerLock, DBAServerLockItem> sessionManager, IContributionManager contributionManager) {
                contributionManager.add(new Separator());
            }

            @Override
            protected void onLockSelect(final DBAServerLock lock) {
                super.onLockSelect(lock);
                if (lock != null) {
                    final PostgreLock pLock = (PostgreLock) lock;
                    super.refreshDetail(new HashMap<>() {{
                        put(PostgreLockManager.pidHold, pLock.getHold_pid());
                        put(PostgreLockManager.pidWait, pLock.getWait_pid());
                    }});
                }
            }
        };
    }

}

