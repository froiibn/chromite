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

import com.seizia.chromite.model.ai.agent.AIToolArgumentException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

/**
 * Regression tests of where the Oracle actual plan puts its hint ({@code PlanTools.oracleHint}): after the first
 * keyword of the statement, past leading comments, joined with a hint the user wrote, in linear time and without a stack
 * that grows with the input (the comment pattern once allowed exponential backtracking, CodeQL java/redos, and then
 * overflowed the stack on long comments).
 *
 * 설계: 힌트는 쿼리 블록 키워드 바로 뒤의 첫 주석만 힌트로 읽히므로 위치가 결과를 바꾼다. 앞 주석 안의 키워드는 키워드가 아니다.
 * 큰 입력은 닫힌 주석이 수만 개 이어지고 키워드가 없는 문장(ReDoS 입력), 닫히지 않은 주석, 10만 자 주석, 10만 자 공백, 긴 WITH 본문이다.
 * 각각 시간 제한 안에 끝나야 하고 StackOverflowError가 나면 실패한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "Chromite Agent")
 * - 2026-11-08 앞 주석 건너뛰기를 반복문으로 고친 뒤({@code PlanTools.skipLeadingComments}) 10만 자 입력과 건너뛰기 규칙을 더함
 */
public class PlanToolsOracleHintTest {

    private static final String MARKER = "chromite-plan-1";
    private static final String HINT = " /*+ GATHER_PLAN_STATISTICS */ /* " + MARKER + " */";

    @Test
    public void hintFollowsTheFirstKeyword() throws Exception {
        Assertions.assertEquals("SELECT" + HINT + " * FROM t", PlanTools.oracleHint("SELECT * FROM t", MARKER));
        Assertions.assertEquals("delete" + HINT + " FROM t WHERE id = 1", PlanTools.oracleHint("delete FROM t WHERE id = 1", MARKER));
        Assertions.assertEquals("MERGE" + HINT + " INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.a = s.a",
            PlanTools.oracleHint("MERGE INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.a = s.a", MARKER));
    }

    @Test
    public void leadingCommentsAreSkippedAndTheirKeywordsIgnored() throws Exception {
        Assertions.assertEquals("-- SELECT in a comment\n/* UPDATE too */ DELETE" + HINT + " FROM t",
            PlanTools.oracleHint("-- SELECT in a comment\n/* UPDATE too */ DELETE FROM t", MARKER));
        Assertions.assertEquals("/* a ** b */\nINSERT" + HINT + " INTO t VALUES (1)",
            PlanTools.oracleHint("/* a ** b */\nINSERT INTO t VALUES (1)", MARKER));
    }

    @Test
    public void withQueryGetsTheHintInItsFirstQueryBlock() throws Exception {
        Assertions.assertEquals("WITH a AS (SELECT" + HINT + " 1 x FROM dual) SELECT * FROM a",
            PlanTools.oracleHint("WITH a AS (SELECT 1 x FROM dual) SELECT * FROM a", MARKER));
    }

    @Test
    public void userHintIsJoinedNotHidden() throws Exception {
        Assertions.assertEquals("SELECT /*+ GATHER_PLAN_STATISTICS INDEX(t ix_t) */ /* " + MARKER + " */ * FROM t",
            PlanTools.oracleHint("SELECT /*+ INDEX(t ix_t) */ * FROM t", MARKER));
    }

    @Test
    public void otherStatementsAreRefused() {
        for (String sql : List.of("CREATE TABLE t (a NUMBER)", "BEGIN NULL; END;", "-- SELECT only in a comment", "/* DELETE */", "")) {
            Assertions.assertThrows(AIToolArgumentException.class, () -> PlanTools.oracleHint(sql, MARKER), sql);
        }
    }

    @Test
    public void leadingCommentsAreSkippedLikeOracleReadsThem() {
        Assertions.assertEquals(0, PlanTools.skipLeadingComments("SELECT 1"));
        Assertions.assertEquals(6, PlanTools.skipLeadingComments(" \t\r\n\f\u000BSELECT 1"));
        Assertions.assertEquals(4, PlanTools.skipLeadingComments("/**/SELECT"));
        // Not nested: the first star-slash closes the comment, the rest is where the statement starts
        Assertions.assertEquals(11, PlanTools.skipLeadingComments("/* /* x */ */SELECT"));
        Assertions.assertEquals(7, PlanTools.skipLeadingComments("/*/ */ SELECT"));
        Assertions.assertEquals(9, PlanTools.skipLeadingComments("-- /* x\n SELECT"));
        Assertions.assertEquals(5, PlanTools.skipLeadingComments("   \n "));
        // Ends inside a comment: nothing to run
        Assertions.assertEquals(-1, PlanTools.skipLeadingComments("/* no end SELECT 1"));
        Assertions.assertEquals(-1, PlanTools.skipLeadingComments("-- no line break SELECT 1"));
    }

    @Test
    public void hugeInputsFinishQuicklyWithoutAStackOverflow() {
        // The CodeQL java/redos input: many short comments and no keyword
        String closedComments = "/* *//* *//*".repeat(30_000) + " x";
        String unclosed = "/*" + "*".repeat(100_000) + " no end";
        String longComment = "/*" + " header line\n".repeat(8_000) + "*/\nSELECT 1 FROM dual";
        String whitespace = " ".repeat(100_000) + "SELECT 1 FROM dual";
        String lineComments = "-- note\n".repeat(20_000) + "SELECT 1 FROM dual";
        String longWith = "WITH a AS (SELECT " + "x, ".repeat(30_000) + "1 FROM dual) SELECT * FROM a";
        String hintAfterSpaces = "SELECT" + " ".repeat(100_000) + "/*+ FULL(t) */ * FROM t";
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            Assertions.assertThrows(AIToolArgumentException.class, () -> PlanTools.oracleHint(closedComments, MARKER));
            Assertions.assertThrows(AIToolArgumentException.class, () -> PlanTools.oracleHint(unclosed, MARKER));
            Assertions.assertTrue(longComment.length() > 100_000);
            Assertions.assertTrue(PlanTools.oracleHint(longComment, MARKER).endsWith("SELECT" + HINT + " 1 FROM dual"));
            Assertions.assertTrue(PlanTools.oracleHint(whitespace, MARKER).endsWith("SELECT" + HINT + " 1 FROM dual"));
            Assertions.assertTrue(PlanTools.oracleHint(lineComments, MARKER).endsWith("SELECT" + HINT + " 1 FROM dual"));
            Assertions.assertTrue(PlanTools.oracleHint(longWith, MARKER).startsWith("WITH a AS (SELECT" + HINT + " x, "));
            Assertions.assertTrue(PlanTools.oracleHint(hintAfterSpaces, MARKER).endsWith("/*+ GATHER_PLAN_STATISTICS FULL(t) */ /* " + MARKER + " */ * FROM t"));
        });
    }
}
