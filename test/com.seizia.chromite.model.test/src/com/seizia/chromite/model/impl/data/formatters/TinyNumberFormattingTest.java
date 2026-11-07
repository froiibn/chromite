/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.impl.data.formatters;

import com.seizia.chromite.model.DBValueFormatting;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Locale;

/**
 * Regression tests of the display of tiny floating point values: -2.5E-300 was shown as -0 in the grid and written as -0
 * into the value editor and generated SQL (fixed 2026-11-07).
 *
 * 설계: 격자 표시는 숫자 형식의 기본값(작은 값은 지수 표기, {@link NumberFormatSample})으로, 편집 값과 SQL은
 * {@link DBValueFormatting#convertNumberToNativeString}으로 만든다. 두 곳 모두 값을 반올림해 0으로 만들면 안 되고, 반올림되지 않는 값은
 * 예전처럼 평범한 표기를 유지해야 한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "데이터")
 */
public class TinyNumberFormattingTest {

    @Test
    public void gridShowsTinyValuesInScientificNotationByDefault() {
        Assertions.assertEquals(Boolean.TRUE,
            new NumberFormatSample().getDefaultProperties(Locale.US).get(NumberFormatSample.PROP_SCIENTIFIC_SMALL_VALUES));
        NumberDataFormatter formatter = new NumberDataFormatter();
        formatter.init(null, Locale.US, new NumberFormatSample().getDefaultProperties(Locale.US));
        for (double value : new double[] {-2.5E-300, 2.5E-300, 1.0E-20}) {
            String text = formatter.formatValue(value);
            Assertions.assertTrue(text.contains("E"), value + " shown as " + text);
            Assertions.assertNotEquals(0.0, Double.parseDouble(text.replace(",", "")), value + " shown as " + text);
        }
    }

    @Test
    public void nativeTextKeepsTheExponentOnlyWhenThePlainFormWouldRound() {
        Assertions.assertEquals("-2.5E-300", DBValueFormatting.convertNumberToNativeString(-2.5E-300, false));
        Assertions.assertEquals("1.0E-12", DBValueFormatting.convertNumberToNativeString(1.0E-12f, false));
        // Values the plain form holds exactly stay plain, as before
        Assertions.assertEquals("0.000015", DBValueFormatting.convertNumberToNativeString(1.5E-5, false));
        Assertions.assertEquals("0.000015", DBValueFormatting.convertNumberToNativeString(1.5E-5f, false));
        Assertions.assertEquals("10000000000", DBValueFormatting.convertNumberToNativeString(1.0E10, false));
        Assertions.assertEquals("1.5E-5", DBValueFormatting.convertNumberToNativeString(1.5E-5, true));
    }
}
