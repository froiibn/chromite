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
package com.seizia.chromite.ui.swing;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What the graphical execution plan draws: statements, each with a tree of operators. It knows nothing of the plan model.
 *
 * 설계: 그림({@link PlanDiagramView})은 DB 계획 모델({@code DBCPlan})을 모른다. DB마다 다른 값(비용, 예상·실제 행, 경고)은
 * {@link PlanDiagramBuilder}가 이 구조로 옮기고, 그림은 이 구조만 배치하고 그린다. 그래서 새 DB를 지원할 때 그림 코드는 바뀌지 않는다.
 * 글자는 화면에 보일 문구(지역화된 것)이고, {@link Op#userObject}·{@link Statement#userObject}는 선택했을 때 속성 표에 넘길 원래 객체다.
 * 배치 좌표({@code x}, {@code y})는 {@link PlanDiagramView}가 채운다(배율 1의 화면 픽셀).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sql-editor-plus.md "실행 계획 세 보기")
 */
final class PlanDiagram {

    private PlanDiagram() {
    }

    /**
     * One statement: a header and its operator tree (none when the plan could not be made).
     */
    static final class Statement {
        /**
         * "Query N: Query cost (relative to the batch): X%".
         */
        final String title;
        /**
         * First line of the statement text.
         */
        final String text;
        /**
         * Lines below the text (missing index suggestions, the error of a failed plan).
         */
        final List<String> notes = new ArrayList<>();
        /**
         * Whether the notes are an error (drawn in the error color) rather than suggestions.
         */
        boolean error;
        @Nullable
        Op root;
        @NotNull
        final Object userObject;

        // Layout
        double x;
        double y;
        double width;
        double height;
        double headerHeight;

        Statement(@NotNull String title, @NotNull String text, @NotNull Object userObject) {
            this.title = title;
            this.text = text;
            this.userObject = userObject;
        }
    }

    /**
     * One operator box.
     */
    static final class Op {
        final String title;
        /**
         * Second line of the box: the object (table, index), or the logical operation of a join.
         */
        @Nullable
        final String detail;
        /**
         * UI icon id (operator icons {@code plan-*}).
         */
        final String iconId;
        /**
         * Cost of the operator itself in percent of its statement, or null when unknown.
         */
        @Nullable
        Double costPercent;
        @Nullable
        Double estimatedRows;
        /**
         * Rows the operator returned; null for an estimated plan.
         */
        @Nullable
        Double actualRows;
        /**
         * The actual rows are this many times off the estimate (only set when it is a large miss).
         */
        @Nullable
        Double misestimate;
        /**
         * Warnings reported by the server, as shown text.
         */
        final List<String> warnings = new ArrayList<>();
        /**
         * Main properties for the tool tip: name and value.
         */
        final List<String[]> properties = new ArrayList<>();
        final List<Op> children = new ArrayList<>();
        @NotNull
        final Object userObject;

        // Layout
        double x;
        double y;

        Op(@NotNull String title, @Nullable String detail, @NotNull String iconId, @NotNull Object userObject) {
            this.title = title;
            this.detail = detail;
            this.iconId = iconId;
            this.userObject = userObject;
        }

        /**
         * The rows this operator passes to its parent (actual when the plan ran).
         */
        @Nullable
        Double getFlowRows() {
            return actualRows != null ? actualRows : estimatedRows;
        }
    }
}
