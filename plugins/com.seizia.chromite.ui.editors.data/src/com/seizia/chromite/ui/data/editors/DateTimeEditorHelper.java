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
package com.seizia.chromite.ui.data.editors;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.data.DBDDataFormatter;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.ui.data.IValueController;

/**
* DateTimeEditorHelper
*/
public interface DateTimeEditorHelper {

    DBDDataFormatter getFormatter(@NotNull IValueController controller, DBSTypedObject column);

}
