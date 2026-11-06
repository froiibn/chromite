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
package com.seizia.chromite.model.admin.space;

import org.jkiss.code.NotNull;

import java.text.NumberFormat;

/**
 * 크기를 사람이 읽는 단위로 쓰기(1.5 GB).
 *
 * 설계: 1024 단위에 KB·MB·GB·TB 이름을 쓴다(Windows 탐색기와 SQL Server·Oracle 관리 도구가 쓰는 방식). 10 미만은 소수 둘째 자리,
 * 100 미만은 첫째 자리까지 보여 열 안에서 자릿수가 비슷하게 한다. 숫자 모양(소수점·자릿수 구분)은 시스템 지역 설정을 따른다.
 * 단위 이름은 기술 기호라 옮기지 않는다. 표의 크기 열과 SQL Server 자동 증가 설명(64 MB)이 같은 모양을 쓰도록 모델에 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량 (docs/chromite/swing-admin-tools.md)
 */
public final class SpaceFormat {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB", "EB"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    private SpaceFormat() {
    }

    /**
     * "0 B", "512 B", "1.25 KB", "64 MB", "1.5 TB".
     */
    @NotNull
    public static String bytes(long bytes) {
        double value = bytes;
        int unit = 0;
        while (Math.abs(value) >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        NumberFormat format = NumberFormat.getNumberInstance();
        if (unit == 0) {
            format.setMaximumFractionDigits(0);
        } else {
            format.setMaximumFractionDigits(Math.abs(value) < 10 ? 2 : Math.abs(value) < 100 ? 1 : 0);
        }
        return format.format(value) + " " + UNITS[unit]; //$NON-NLS-1$
    }

    /**
     * The exact number with digit grouping ("1,610,612,736").
     */
    @NotNull
    public static String exact(long value) {
        return NumberFormat.getIntegerInstance().format(value);
    }
}
