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
package com.seizia.chromite.ext.altibase.extension;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;

/**
 * An Altibase server extension: features that only some servers of the Altibase family have, contributed by another
 * bundle through the extension point {@code com.seizia.chromite.altibase.serverExtension}.
 *
 * 설계: 확장은 드라이버나 제품 이름이 아니라 연결한 서버의 카탈로그로 자기가 맞는지 판단한다({@link #detect}). 이 객체는 확장 지점마다
 * 하나이고 모든 연결이 함께 쓰므로 연결마다의 상태를 갖지 않는다. 연결마다의 결과는 {@link #detect}가 돌려주는
 * {@link AltibaseServerExtensionState}이고, 데이터 원본 인스턴스({@link AltibaseDataSource})가 연결이 열린 동안 갖는다
 * (메타 모델·어댑터 팩토리·공급자의 트리 정의·정적 낱말 목록은 연결 사이에 공유되므로 그곳에 두지 않는다).
 * 확장 번들이 없으면 데이터 원본은 질의를 하나도 더 하지 않는다(docs/chromite/altibase.md "서버 확장").
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 감지, 어댑터, 트리 객체(앱 변경 #139, docs/chromite/shardsphere.md)
 */
public interface AltibaseServerExtension {

    /**
     * Reads the connected server's catalog (SELECT only) and answers whether this extension applies to it.
     * Called once per connection, after the connection is usable, and again after a refresh of the connection.
     * A probe that fails means "not present": it must not throw for an ordinary server of the family.
     *
     * @return this connection's state of the extension, or null if the extension does not apply to the server
     */
    @Nullable
    AltibaseServerExtensionState detect(@NotNull JDBCSession session, @NotNull AltibaseDataSource dataSource);

}
