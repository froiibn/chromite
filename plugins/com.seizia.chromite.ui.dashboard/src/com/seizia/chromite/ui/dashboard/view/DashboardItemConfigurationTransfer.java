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
package com.seizia.chromite.ui.dashboard.view;

import com.seizia.chromite.model.dashboard.registry.DashboardItemConfiguration;
import com.seizia.chromite.ui.dnd.LocalObjectTransfer;

public final class DashboardItemConfigurationTransfer extends LocalObjectTransfer<DashboardItemConfiguration> {

        public static final DashboardItemConfigurationTransfer INSTANCE = new DashboardItemConfigurationTransfer();
        private static final String TYPE_NAME = "Dashboard.Item.Config Transfer" + System.currentTimeMillis() + ":" + INSTANCE.hashCode();//$NON-NLS-1$
        private static final int TYPEID = registerType(TYPE_NAME);

        private DashboardItemConfigurationTransfer() {
        }

        @Override
        protected int[] getTypeIds() {
            return new int[] { TYPEID };
        }

        @Override
        protected String[] getTypeNames() {
            return new String[] { TYPE_NAME };
        }

    }
