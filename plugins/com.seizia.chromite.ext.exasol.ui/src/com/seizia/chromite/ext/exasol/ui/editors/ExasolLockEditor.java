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
package com.seizia.chromite.ext.exasol.ui.editors;

import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.swt.widgets.Composite;
import com.seizia.chromite.ext.exasol.model.ExasolDataSource;
import com.seizia.chromite.ext.exasol.model.lock.ExasolLock;
import com.seizia.chromite.ext.exasol.model.lock.ExasolLockManager;
import com.seizia.chromite.model.admin.locks.DBAServerLock;
import com.seizia.chromite.model.admin.locks.DBAServerLockItem;
import com.seizia.chromite.model.admin.locks.DBAServerLockManager;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.ui.editors.locks.edit.AbstractLockEditor;
import com.seizia.chromite.ui.editors.locks.manage.LockManagerViewer;

import java.math.BigInteger;
import java.util.HashMap;


public class ExasolLockEditor extends AbstractLockEditor {

    public static final String sidHold = "hsid";
    public static final String sidWait = "wsid";


    @SuppressWarnings("unchecked")
    @Override
    protected LockManagerViewer createLockViewer(
        DBCExecutionContext executionContext, Composite parent) {
        @SuppressWarnings("rawtypes")
        DBAServerLockManager<DBAServerLock, DBAServerLockItem> lockManager = (DBAServerLockManager) new ExasolLockManager((ExasolDataSource) executionContext.getDataSource());

        return new LockManagerViewer(this, parent, lockManager) {
            @Override
            protected void contributeToToolbar(DBAServerLockManager<DBAServerLock, DBAServerLockItem> sessionManager, IContributionManager contributionManager) {
                contributionManager.add(new Separator());
            }

            @SuppressWarnings("serial")
            @Override
            protected void onLockSelect(final DBAServerLock lock) {
                super.onLockSelect(lock);
                if (lock != null) {
                    final ExasolLock pLock = (ExasolLock) lock;
                    super.refreshDetail(new HashMap<String, Object>() {{
                        put(sidHold, BigInteger.valueOf(pLock.getHold_sid()));
                        put(sidWait, BigInteger.valueOf(pLock.getWait_sid().longValue()));
                    }});
                }
            }
        };
    }

}
