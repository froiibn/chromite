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
import com.seizia.chromite.ext.altibase.model.AltibaseReplicationCatalog;
import com.seizia.chromite.model.DBPObjectWithContributedProperties;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.dbeaver.DBException;

import java.util.Collection;
import java.util.List;

/**
 * One connection's state of a detected {@link AltibaseServerExtension}: what the extension found on the server and
 * what it contributes to that connection.
 *
 * 설계: 데이터 원본 인스턴스가 연결마다 이 객체를 갖고({@link AltibaseDataSource#getAdapter}, {@link AltibaseDataSource#getExtensionObjects})
 * 새로 고침과 다시 연결에서 버린다. 그래서 같은 앱에 열린 Altibase 연결과 ShardSphere 연결이 서로의 답을 보지 않는다.
 * 메서드는 기본 구현이 "기여 없음"이라 확장은 쓰는 것만 구현한다. 방언 기여(낱말, 함수, 문장 접두어)도 이 연결의 상태다: 데이터 원본이
 * 감지 뒤 자기 방언 객체({@link com.seizia.chromite.ext.altibase.model.AltibaseSQLDialect#applyServerExtensions})에 넘기고, 방언 객체는
 * 데이터 원본마다 하나라 다른 연결에 가지 않는다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성: 어댑터와 트리 객체(앱 변경 #139, docs/chromite/shardsphere.md)
 * - 2026-11-17 방언 기여: 문법 낱말, 함수, 문장 접두어 길이(앱 변경 #139 작업 6a, docs/chromite/altibase.md "서버 확장")
 * - 2026-11-17 방언 기여: 접두어 문장의 하위 질의 형태, 입력 중인 접두어의 제안(앱 변경 #139 작업 6b, docs/chromite/altibase.md "서버 확장")
 * - 2026-11-17 복제 카탈로그({@link #getReplicationCatalog()}): 복제를 다른 카탈로그에 두는 서버(앱 변경 #139 작업 4·5,
 *   docs/chromite/altibase.md "복제 카탈로그")
 * - 2026-11-17 기존 객체의 추가 속성({@link #getObjectProperties}): 서버 확장이 그 서버의 카탈로그에서 읽어 속성 보기에 더하는 값
 *   (앱 변경 #139 수정 6, docs/chromite/altibase.md "기존 객체의 추가 속성")
 */
public interface AltibaseServerExtensionState {

    /**
     * An adapter of the connection's data source. Asked before the Altibase data source's own adapters, so an
     * extension can answer for its server what the family answers otherwise.
     *
     * @return the adapter, or null to leave the request to the next extension and then to the data source
     */
    @Nullable
    default <T> T getAdapter(@NotNull Class<T> adapter) {
        return null;
    }

    /**
     * Objects shown in Object Explorer under the connection's System Info folder (the tree host {@code serverExtension}
     * in ext.altibase's {@code plugin.xml}). Their {@code @Property} getters appear in the property view.
     */
    @NotNull
    default Collection<? extends DBSObject> getTreeObjects() {
        return List.of();
    }

    /**
     * Syntax words of this server that the connection's dialect adds as keywords (highlighting, completion) but that do
     * not make a name quoted unless the server reports them in {@code V$RESERVED_WORDS}.
     */
    @NotNull
    default Collection<String> getSyntaxKeywords() {
        return List.of();
    }

    /**
     * Functions of this server that the connection's dialect adds (highlighting, completion).
     */
    @NotNull
    default Collection<String> getFunctions() {
        return List.of();
    }

    /**
     * The length of a statement prefix of this server at the start of the text, as
     * {@link com.seizia.chromite.model.sql.SQLDialect#getStatementPrefixLength}; 0 if none.
     */
    default int getStatementPrefixLength(@NotNull String text) {
        return 0;
    }

    /**
     * The subquery form of a statement with this server's prefix, as
     * {@link com.seizia.chromite.model.sql.SQLDialect#getPrefixedStatementSubquery}; null if this extension has none.
     */
    @Nullable
    default String getPrefixedStatementSubquery(@NotNull String text) {
        return null;
    }

    /**
     * Proposals for this server's statement prefix being typed, as
     * {@link com.seizia.chromite.model.sql.SQLDialect#getStatementPrefixProposals}; null if the caret is not in one.
     */
    @Nullable
    default SQLDialect.StatementPrefixProposals getStatementPrefixProposals(@NotNull String text) {
        return null;
    }

    /**
     * The replication catalog of this server when it is not the family's own
     * ({@link com.seizia.chromite.ext.altibase.model.AltibaseDefaultReplicationCatalog}); null to leave it to the next
     * extension and then to the default.
     */
    @Nullable
    default AltibaseReplicationCatalog getReplicationCatalog() {
        return null;
    }

    /**
     * Read-only properties this extension adds to an object of this connection's Altibase model, shown after the object's
     * own in the property view (objects that implement {@link DBPObjectWithContributedProperties}, today the table). Called
     * only when the property view shows the object, off the UI thread; an extension that has nothing for this object or
     * this server returns an empty list without a query.
     */
    @NotNull
    default List<DBPObjectWithContributedProperties.ContributedProperty> getObjectProperties(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObject object
    ) throws DBException {
        return List.of();
    }

}
