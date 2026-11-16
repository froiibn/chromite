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
package com.seizia.chromite.registry;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Removes stored values of upstream preferences that Chromite deleted for transaction safety.
 *
 * 설계:
 * <ul>
 *     <li>{@code query.rollback-on-error}는 문장이 실패하면 수동 커밋 트랜잭션을 묻지 않고 롤백했고({@code JDBCStatementImpl.handleExecuteError}),
 *     {@code execute.cancel.checkTimeout}은 문장 취소가 듣지 않으면 세션 단위 취소로 넘어가 연결과 열린 트랜잭션을 잃을 수 있었다
 *     ({@code AbstractJob}). 두 키를 읽던 모델 코드를 지웠으므로 설정 파일, 설정 가져오기, 연결 파일, 손으로 고친 파일 어느 것도 다시 켤 수 없다.
 *     값을 기본값으로 되돌리는 것만으로는 다음 가져오기가 다시 켤 수 있어, 읽는 코드를 지우는 쪽을 골랐다.</li>
 *     <li>여기서는 남은 값만 정리한다. 효과는 없지만 설정 내보내기가 옮기거나 사용자가 켜져 있다고 오해하지 않게 지우고, 지운 키마다 정보 로그를
 *     한 줄 남긴다.</li>
 *     <li>전역 값은 플랫폼 설정 저장소(연결 설정 저장소의 부모, 두 키를 읽던 곳)에서 시작할 때 지운다({@link BasePlatformImpl}). 연결의 값은
 *     {@code data-sources*.json}의 {@code custom-properties}를 읽을 때 뺀다({@link DataSourceSerializerModern}). 파일에서는 레지스트리가 그
 *     연결 파일을 다음에 저장할 때 빠진다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "Errors and Timeouts")
 */
final class RemovedPreferences {

    private static final Log log = Log.getLog(RemovedPreferences.class);

    private static final List<String> NAMES = List.of(
        "query.rollback-on-error", //$NON-NLS-1$
        "execute.cancel.checkTimeout" //$NON-NLS-1$
    );

    private RemovedPreferences() {
    }

    /**
     * Resets the removed preferences in a global store and saves it if anything was reset.
     */
    static void removeFrom(@NotNull DBPPreferenceStore store) {
        boolean changed = false;
        for (String name : NAMES) {
            if (store.contains(name) && !store.isDefault(name)) {
                log.info("Reset removed preference '" + name + "' (was '" + store.getString(name) + "'): Chromite no longer reads it");
                store.setToDefault(name);
                changed = true;
            }
        }
        if (changed) {
            PrefUtils.savePreferenceStore(store);
        }
    }

    /**
     * Drops the removed preferences from a connection's stored preferences before they are put into its store.
     */
    static void removeFrom(@NotNull Map<String, String> connectionPreferences, @NotNull String connectionName) {
        for (String name : NAMES) {
            String value = connectionPreferences.remove(name);
            if (value != null) {
                log.info("Reset removed preference '" + name + "' (was '" + value + "') of connection '" + connectionName
                    + "': Chromite no longer reads it");
            }
        }
    }
}
