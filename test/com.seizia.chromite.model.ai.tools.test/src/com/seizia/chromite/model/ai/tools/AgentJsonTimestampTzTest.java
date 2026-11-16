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
package com.seizia.chromite.model.ai.tools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.ResultSet;
import java.sql.Types;

/**
 * Regression tests of how the Chromite Agent tools read time zone timestamps ({@code AgentJson.readValue}): Oracle
 * TIMESTAMP WITH [LOCAL] TIME ZONE came out as the driver object's name ({@code oracle.sql.TIMESTAMPTZ@...}) instead of
 * the value (fixed 2026-11-08).
 *
 * 설계: Oracle 드라이버의 {@code getObject}는 값이 아닌 {@code toString}을 가진 객체를 주므로, 시간대가 있는 시각 형식(표준
 * {@link Types#TIMESTAMP_WITH_TIMEZONE}과 Oracle의 -101, -102)은 드라이버 글자({@code getString})로 읽어야 한다. 드라이버 없이 결과 집합
 * mock으로 어느 메서드를 부르는지와 결과 JSON을 본다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "Chromite Agent")
 */
public class AgentJsonTimestampTzTest {

    private static final String TEXT = "2026-11-08 09:30:00.123 Asia/Seoul";

    @Test
    public void timeZoneTimestampsAreReadAsDriverText() throws Exception {
        for (int sqlType : new int[] {Types.TIMESTAMP_WITH_TIMEZONE, -101, -102}) {
            ResultSet rs = resultSet();
            AgentJson.Value value = AgentJson.readValue(rs, 1, sqlType, 1000);
            Assertions.assertEquals(TEXT, value.json().getAsString(), "SQL type " + sqlType);
            Assertions.assertFalse(value.cut());
            Mockito.verify(rs, Mockito.never()).getObject(1);
        }
    }

    @Test
    public void otherTypesStillUseTheObject() throws Exception {
        ResultSet rs = resultSet();
        Mockito.when(rs.getObject(1)).thenReturn(42);
        Assertions.assertEquals(42, AgentJson.readValue(rs, 1, Types.INTEGER, 1000).json().getAsInt());
        Mockito.when(rs.getString(1)).thenReturn(null);
        Assertions.assertTrue(AgentJson.readValue(rs, 1, -101, 1000).json().isJsonNull());
    }

    private static ResultSet resultSet() throws Exception {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getString(1)).thenReturn(TEXT);
        // What the Oracle driver's getObject gives: its toString is not the value
        Mockito.when(rs.getObject(1)).thenReturn(new Object() {
            @Override
            public String toString() {
                return "oracle.sql.TIMESTAMPTZ@1b2c3d";
            }
        });
        return rs;
    }
}
