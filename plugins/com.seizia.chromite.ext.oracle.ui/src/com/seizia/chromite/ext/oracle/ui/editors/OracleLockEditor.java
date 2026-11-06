/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.oracle.ui.editors;

import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.swt.widgets.Composite;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.lock.OracleLock;
import com.seizia.chromite.ext.oracle.model.lock.OracleLockManager;
import com.seizia.chromite.model.admin.locks.DBAServerLock;
import com.seizia.chromite.model.admin.locks.DBAServerLockItem;
import com.seizia.chromite.model.admin.locks.DBAServerLockManager;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.ui.editors.locks.edit.AbstractLockEditor;
import com.seizia.chromite.ui.editors.locks.manage.LockManagerViewer;

import java.util.HashMap;

public class OracleLockEditor extends AbstractLockEditor {

    @Override
    protected LockManagerViewer createLockViewer(DBCExecutionContext executionContext, Composite parent) {

        DBAServerLockManager<DBAServerLock, DBAServerLockItem> lockManager = (DBAServerLockManager) new OracleLockManager((OracleDataSource) executionContext.getDataSource());

        return new LockManagerViewer(this, parent, lockManager) {
            @Override
            protected void contributeToToolbar(DBAServerLockManager<DBAServerLock, DBAServerLockItem> sessionManager, IContributionManager contributionManager) {
                contributionManager.add(new Separator());
            }

            @Override
            protected void onLockSelect(final DBAServerLock lock) {
                super.onLockSelect(lock);
                if (lock != null) {
                    final OracleLock pLock = (OracleLock) lock;
                    super.refreshDetail(new HashMap<String, Object>() {{
                        put(OracleLockManager.sidHold, pLock.getHold_sid());
                        put(OracleLockManager.sidWait, pLock.getWait_sid());
                    }});
                }
            }
        };
    }

}
