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

package com.seizia.chromite.model.data.aggregate;

import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;


public class FunctionCountNullsTest extends ChromiteUnitTest {

    public static Stream<Arguments> data() {
        return Stream.of(
                Arguments.of(Arrays.asList(1, 2, 3, 4), 0L),
                Arguments.of(Arrays.asList(1, 2, 3, null), 1L),
                Arguments.of(Arrays.asList(null, null), 2L)
        );
    }

    @ParameterizedTest
    @MethodSource("data")
    public void shouldGetZeroCountWhenNoNullsPresent(List<Integer> values, Long expectedCount) {
        var nullsCountFunc = new FunctionCountNulls();
        values.forEach(value -> nullsCountFunc.accumulate(value, false));
        MatcherAssert.assertThat(nullsCountFunc.getResult(0), CoreMatchers.is(expectedCount));
    }

}
