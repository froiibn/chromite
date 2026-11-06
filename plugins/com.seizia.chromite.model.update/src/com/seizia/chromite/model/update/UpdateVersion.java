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
package com.seizia.chromite.model.update;

import org.jkiss.code.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 제품 버전 {@code major.minor.dev}(docs/chromite/versioning.md)의 형식 검사와 비교.
 *
 * 설계: 업데이트 목록의 버전과 실행 중인 버전은 정확히 정수 셋이어야 한다. 접미사(-rc1), 넷째 자리, 공백은
 * 받지 않는다. 형식이 틀린 값은 비교 결과가 없어서(null) 고르지 않는다. 숫자는 자리마다 6자리까지만 받아
 * {@code int} 넘침이 없다. OSGi {@code Version}은 한정자를 받아들여서 쓰지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public final class UpdateVersion {

    private static final Pattern PATTERN = Pattern.compile("^(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$"); //$NON-NLS-1$

    private UpdateVersion() {
    }

    public static boolean isValid(@Nullable String version) {
        return version != null && PATTERN.matcher(version).matches();
    }

    /**
     * @return -1, 0 or 1 like {@link Comparable#compareTo}; null when either value is not a plain {@code x.y.z}
     */
    @Nullable
    public static Integer compare(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) {
            return null;
        }
        Matcher x = PATTERN.matcher(a);
        Matcher y = PATTERN.matcher(b);
        if (!x.matches() || !y.matches()) {
            return null;
        }
        for (int i = 1; i <= 3; i++) {
            int d = Integer.compare(Integer.parseInt(x.group(i)), Integer.parseInt(y.group(i)));
            if (d != 0) {
                return d < 0 ? -1 : 1;
            }
        }
        return 0;
    }

    /**
     * Whether {@code candidate} is a plain version strictly above {@code current}.
     */
    public static boolean isNewer(@Nullable String candidate, @Nullable String current) {
        Integer c = compare(candidate, current);
        return c != null && c > 0;
    }
}
