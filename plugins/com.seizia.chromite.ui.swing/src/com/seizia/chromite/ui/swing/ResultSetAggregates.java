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
import org.jkiss.dbeaver.model.data.aggregate.FunctionAvg;
import org.jkiss.dbeaver.model.data.aggregate.FunctionMax;
import org.jkiss.dbeaver.model.data.aggregate.FunctionMin;
import org.jkiss.dbeaver.model.data.aggregate.FunctionSum;
import org.jkiss.dbeaver.model.data.aggregate.IAggregateFunction;

import javax.swing.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * 결과 표에서 고른 칸들의 집계(상태 줄 오른쪽): 개수, 그리고 숫자 칸이 있으면 합계·평균·최솟값·최댓값.
 *
 * 설계: 계산은 모델 계층의 집계 함수({@code org.jkiss.dbeaver.model.data.aggregate}, SWT {@code AggregateColumnsPanel}이 쓰는 것)를 그대로 쓴다.
 * SWT 집계 패널은 문자열도 숫자로 읽어 보지만, 여기서는 숫자 값({@link Number})만 넣는다(코드 열의 "001" 같은 글자가 합계에 들어가지 않게).
 * 칸이 하나뿐이면 아무것도 쓰지 않는다. 아주 큰 선택은 {@link #MAX_CELLS}칸까지만 센다(EDT에서 계산하므로).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-result-viewer.md "표 편의 기능")
 */
final class ResultSetAggregates {

    private static final int MAX_CELLS = 1_000_000;
    private static final MathContext DISPLAY_PRECISION = new MathContext(15);

    /**
     * Original value of a cell by table row and model column.
     */
    interface CellValues {
        @Nullable
        Object get(int row, int column);
    }

    private ResultSetAggregates() {
    }

    /**
     * The aggregates of the selected cells, or an empty string for one cell or none.
     */
    @NotNull
    static String describeSelection(@NotNull JTable table, @NotNull CellValues values) {
        int[] rows = table.getSelectedRows();
        int[] columns = table.getSelectedColumns();
        long cells = (long) rows.length * columns.length;
        if (cells < 2) {
            return "";
        }
        IAggregateFunction sum = new FunctionSum();
        IAggregateFunction avg = new FunctionAvg();
        IAggregateFunction min = new FunctionMin();
        IAggregateFunction max = new FunctionMax();
        int numbers = 0;
        int counted = 0;
        boolean capped = false;
        outer:
        for (int row : rows) {
            for (int column : columns) {
                if (counted >= MAX_CELLS) {
                    capped = true;
                    break outer;
                }
                counted++;
                Object value = values.get(row, table.convertColumnIndexToModel(column));
                if (value instanceof Number) {
                    numbers++;
                    sum.accumulate(value, false);
                    avg.accumulate(value, false);
                    min.accumulate(value, false);
                    max.accumulate(value, false);
                }
            }
        }
        StringBuilder text = new StringBuilder("Count: ").append(String.format("%,d", cells));
        if (numbers > 0) {
            text.append("   Sum: ").append(format(sum.getResult(numbers)))
                .append("   Avg: ").append(format(avg.getResult(numbers)))
                .append("   Min: ").append(format(min.getResult(numbers)))
                .append("   Max: ").append(format(max.getResult(numbers)));
        }
        if (capped) {
            text.append("   (first ").append(String.format("%,d", MAX_CELLS)).append(" cells)");
        }
        return text.toString();
    }

    @NotNull
    static String format(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                return String.valueOf(number);
            }
            return new BigDecimal(number).round(DISPLAY_PRECISION).stripTrailingZeros().toPlainString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger || value instanceof Number) {
            return value.toString();
        }
        return String.valueOf(value);
    }
}
