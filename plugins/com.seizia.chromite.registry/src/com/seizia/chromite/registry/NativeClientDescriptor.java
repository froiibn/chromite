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

package com.seizia.chromite.registry;

import org.eclipse.core.runtime.IConfigurationElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.impl.AbstractDescriptor;
import com.seizia.chromite.model.runtime.OSDescriptor;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.ArrayList;
import java.util.List;

/**
 * NativeClientDescriptor
 *
 * 변경 이력:
 * - 2026-11-07 Microsoft Store 제품 삭제: Store 앱의 가상화된 AppData 경로를 건너뛰던 검사를 지웠다.
 *   Store 앱 표시 속성은 삭제한 ui.app.msstore 번들만 켰다.
 */
public class NativeClientDescriptor extends AbstractDescriptor {

    private final List<NativeClientDistributionDescriptor> distributions = new ArrayList<>();
    private String id;
    private String label;

    NativeClientDescriptor(IConfigurationElement config) {
        super(config.getContributor().getName());
        this.id = config.getAttribute(RegistryConstants.ATTR_ID);
        this.label = config.getAttribute(RegistryConstants.ATTR_LABEL);
        for (IConfigurationElement clientElement : config.getChildren("dist")) {
            this.distributions.add(new NativeClientDistributionDescriptor(clientElement));
        }
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    @Nullable
    public NativeClientDistributionDescriptor findDistribution(@NotNull DBPDriver driver) {
        OSDescriptor localSystem = DBWorkbench.getPlatform().getLocalSystem();

        for (NativeClientDistributionDescriptor distr : distributions) {
            // Include all clients for custom drivers
            if (!driver.isCustom() && !distr.supports(driver)) {
                continue;
            }
            if (distr.getOs().matches(localSystem)) {
                return distr;
            }
        }
        return null;

    }

    @Override
    public String toString() {
        return id;
    }

}
