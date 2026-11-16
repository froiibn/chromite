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
package com.seizia.chromite.model.virtual;

import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

public class DBVUtilsTest extends ChromiteUnitTest {
    @Test
    void evaluatesExtensionNamespaceFunction() {
        var expression = DBVUtils.parseExpression("geo.wktPoint(1, 2)");
        var context = new DBVDataContext(
            mock(DBSObject.class),
            new DBDAttributeBinding[0],
            new Object[0],
            null
        );

        Object result = expression.evaluate(context);

        Assertions.assertNotNull(result);
        Assertions.assertEquals("POINT(1 2)", result.toString());
    }
}
