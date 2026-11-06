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
package com.seizia.chromite.model.impl.config;

import com.seizia.chromite.model.impl.app.BaseApplicationImpl;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

public final class ProductConfigUtils {
    public static final String PRODUCT_CONFIG_DISABLE = "product.config.disable";
    private ProductConfigUtils() {
    }

    /**
     * Returns whether Product Config feature is available.
     * <p>
     * This is determined by the system property {@code chromite.show.easy.config.on.startup}.
     */
    public static boolean isAvailable() {
        return isProductApplicable();
    }

    /**
     * Returns whether Product Config is applicable to the current environment.
     */
    private static boolean isProductApplicable() {
        return BaseApplicationImpl.getInstance().isStandalone() && !DBWorkbench.isDistributed();
    }

    public static boolean isProductConfigSuppressed() {
        return CommonUtils.toBoolean(System.getProperty(PRODUCT_CONFIG_DISABLE));
    }
}
