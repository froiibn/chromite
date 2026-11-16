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
package com.seizia.chromite.model.ai.functions;

import com.seizia.chromite.model.ai.AIFunctionContext;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionVerifier.FunctionState;
import com.seizia.chromite.model.ai.engine.AIDatabaseContext;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ListTableNamesFunctionTest extends ChromiteUnitTest {
    @Test
    public void unavailableWithoutDatabaseConnection() {
        AIFunctionContext context = Mockito.mock(AIFunctionContext.class);
        AIFunctionDescriptor function = Mockito.mock(AIFunctionDescriptor.class);

        Assertions.assertEquals(FunctionState.NOT_APPLICABLE,
            new ListTableNamesFunction().getFunctionState(context, function));

        Mockito.when(context.getContext()).thenReturn(Mockito.mock(AIDatabaseContext.class));
        Assertions.assertEquals(FunctionState.APPLICABLE,
            new ListTableNamesFunction().getFunctionState(context, function));
    }
}
