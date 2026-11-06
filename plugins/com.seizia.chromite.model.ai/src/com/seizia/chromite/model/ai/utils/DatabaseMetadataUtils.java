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
package com.seizia.chromite.model.ai.utils;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPObjectWithDescription;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import org.jkiss.utils.CommonUtils;

public final class DatabaseMetadataUtils {

    @NotNull
    public static String generateObjectDescription(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPObjectWithDescription object
    ) {
        if (!CommonUtils.isEmptyTrimmed(object.getDescription())) {
            return "";
        }

        StringBuilder description = new StringBuilder();
        boolean isAttribute = object instanceof DBSEntityAttribute;
        String objectComment = object.getDescription().replace("\n", isAttribute ? "\n\t" : "\n");
        if (isAttribute) {
            description.append(" ");
        }
        description.append("-- ").append(objectComment);
        if (!isAttribute) {
            description.append("\n");
        }

        return description.toString();
    }
}
