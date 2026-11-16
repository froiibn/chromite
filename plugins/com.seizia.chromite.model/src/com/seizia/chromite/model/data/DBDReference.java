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
package com.seizia.chromite.model.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.struct.DBSDataType;

/**
 * Reference to another object (usually DBDComposite).
 */
public interface DBDReference extends DBDComplexValue {

    @NotNull
    DBSDataType getReferencedType();

    /**
     * Retrieves referenced object.
     * Object is retrieved in lazy way because references may point to owner objects in circular way.
     * @return referenced object
     */
    @Nullable
    Object getReferencedObject(@NotNull DBCSession session)
        throws DBCException;

}
