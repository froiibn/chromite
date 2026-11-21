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
package com.seizia.chromite.ext.shardsphere.model;

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.ext.altibase.model.AltibaseDataSource;
import com.seizia.chromite.ext.altibase.model.AltibaseSchema;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionSource;
import com.seizia.chromite.model.exec.DBCFeatureNotSupportedException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCConstants;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCStructCache;
import com.seizia.chromite.model.impl.jdbc.struct.JDBCTable;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAssociation;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.rdb.DBSTableConstraint;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A stream table or continuous table as the schema's object list shows it: an entity with columns that SQL completion,
 * name resolution, the result grid and the data transfer pickers can use (the schema's object list,
 * {@code AltibaseServerExtensionState#getSchemaObjects}).
 *
 * 설계: 탐색기의 "스트림" 노드 객체({@link StreamTable}, {@link ContinuousObject})는 그대로 두고, 그 객체가 이 객체 하나를 갖는다
 * ({@code getRelation}). 그래서 트리 메뉴(데이터 보기, 디자인, 잘라내기 …)는 바뀌지 않고 테이블 폴더에도 들어가지 않으며, 탐색기에서
 * 지우거나 다시 읽으면 목록도 같이 바뀐다. 부모는 스키마다: 질의가 {@code 소유자.이름}으로 부르는 객체이고, 개체 목록의 다른 객체와 같다.
 * <ul>
 *     <li>열: 처음 물을 때 객체마다 드라이버 {@code getColumns} 한 번(일반 테이블과 같은 호출, {@link StreamColumn}). 연결할 때와 트리를
 *     열 때는 읽지 않는다.</li>
 *     <li>데이터 기능(2026-11-22 시험용 개체로 확인): 네 종류 모두 SELECT(열 목록, 별칭, {@code 소유자.이름})를 받는다. 스트림 테이블과
 *     연속 스트림 테이블은 INSERT(열 목록이 있을 때; 없으면 {@code $INSERT_TIME}까지 세어 200787)와 INSERT … SELECT를 받고 UPDATE·DELETE는
 *     201854, MERGE·TRUNCATE는 200753으로 거절한다. 연속 테이블은 모든 쓰기를 거절한다(집계 있음 INSERT 200792, UPDATE·DELETE·MERGE 201636;
 *     집계 없음 201589; TRUNCATE 200753). 그래서 넣기만 받는 종류에만 {@link #FEATURE_DATA_INSERT}를 주고 바꾸기·지우기·잘라내기는 어느 종류에도
 *     주지 않는다. 가져오기·테이블 복사의 대상, 결과 그리드의 Generate SQL과 편집이 이 답을 묻는다. 바꾸기·지우기·잘라내기를 부르면 문장을
 *     만들지 않고 지원하지 않는다고 답한다. 데이터 전송 화면은 이 객체에 "대상 비우기"·다시 만들기·새 열을 내지 않고(객체 관리자가 없음), 저장된
 *     작업의 비우기는 경고만 남기고 넘어간다.</li>
 *     <li>키: 없다. 스트림 테이블의 PRIMARY KEY는 카탈로그에 남지만 서버가 같은 키의 행을 받는다(확인함). 그래서 결과 그리드가 행을 가리킬
 *     키가 없어 편집하지 않는다.</li>
 *     <li>연속 테이블(연속 스트림 테이블 포함)은 뷰로 답한다({@link #isView}): 서버의 연속 질의가 채우는 객체이고, 아이콘이 "스트림" 노드의
 *     연속 테이블 폴더와 같다. 스트림 테이블은 테이블이다.</li>
 *     <li>정의(DDL)는 노드 객체의 것이다.</li>
 * </ul>
 * 연속 쿼리는 이 객체가 없다(SELECT를 200753으로 거절). 저장 테이블 {@code $AGGR}과 {@code $TEXT}도 없다(서버의 내부 객체).
 *
 * 변경 이력:
 * - 2026-11-22 최초 작성(앱 변경 #163, docs/chromite/shardsphere.md "자동 완성의 스트림 객체")
 */
public class StreamRelation extends JDBCTable<AltibaseDataSource, AltibaseSchema> implements DBPScriptObject {

    @NotNull
    private final DBPScriptObject source;
    private final boolean view;
    private final boolean acceptsInsert;
    // Read on the first question, one driver call
    @Nullable
    private volatile List<StreamColumn> columns;

    /**
     * @param source        the stream node's object of the same name (its DDL is this object's)
     * @param view          a continuous table, filled by the server's continuous query
     * @param acceptsInsert the server accepts INSERT into it
     */
    StreamRelation(@NotNull AltibaseSchema schema, @NotNull DBPScriptObject source, @NotNull String name, boolean view, boolean acceptsInsert) {
        super(schema, name, true);
        this.source = source;
        this.view = view;
        this.acceptsInsert = acceptsInsert;
    }

    @Override
    public boolean isView() {
        return view;
    }

    /**
     * Reading (count, filter, search), and inserted rows where the server accepts them; never updated, deleted or truncated.
     */
    @NotNull
    @Override
    public String[] getSupportedFeatures() {
        return acceptsInsert
            ? new String[] {FEATURE_DATA_COUNT, FEATURE_DATA_FILTER, FEATURE_DATA_SEARCH, FEATURE_DATA_INSERT}
            : new String[] {FEATURE_DATA_COUNT, FEATURE_DATA_FILTER, FEATURE_DATA_SEARCH};
    }

    @Override
    protected boolean isTruncateSupported() {
        return false;
    }

    @NotNull
    @Override
    public ExecuteBatch updateData(
        @NotNull DBCSession session,
        @NotNull DBSAttributeBase[] updateAttributes,
        @NotNull DBSAttributeBase[] keyAttributes,
        @Nullable DBDDataReceiver keysReceiver,
        @NotNull DBCExecutionSource source
    ) throws DBCException {
        throw new DBCFeatureNotSupportedException("The server does not update rows of " + getName());
    }

    @NotNull
    @Override
    public ExecuteBatch deleteData(
        @NotNull DBCSession session,
        @NotNull DBSAttributeBase[] keyAttributes,
        @NotNull DBCExecutionSource source
    ) throws DBCException {
        throw new DBCFeatureNotSupportedException("The server does not delete rows of " + getName());
    }

    @NotNull
    @Override
    public DBCStatistics truncateData(@NotNull DBCSession session, @NotNull DBCExecutionSource source) throws DBException {
        throw new DBCFeatureNotSupportedException("The server does not truncate " + getName());
    }

    @Nullable
    @Override
    public List<StreamColumn> getAttributes(@NotNull DBRProgressMonitor monitor) throws DBException {
        List<StreamColumn> result = columns;
        if (result == null) {
            synchronized (this) {
                result = columns;
                if (result == null) {
                    result = readColumns(monitor);
                    columns = result;
                }
            }
        }
        return result;
    }

    @Nullable
    @Override
    public DBSEntityAttribute getAttribute(@NotNull DBRProgressMonitor monitor, @NotNull String attributeName) throws DBException {
        return DBUtils.findObject(getAttributes(monitor), attributeName);
    }

    /**
     * The driver's column list of this object, as for an ordinary table ({@code GenericMetaModel}).
     */
    @NotNull
    private List<StreamColumn> readColumns(@NotNull DBRProgressMonitor monitor) throws DBException {
        AltibaseSchema schema = getContainer();
        List<StreamColumn> result = new ArrayList<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read columns of '" + getName() + "'");
             JDBCResultSet dbResult = session.getMetaData().getColumns(
                 null,
                 JDBCUtils.escapeWildCards(session, schema.getName()),
                 JDBCUtils.escapeWildCards(session, getName()),
                 getDataSource().getAllObjectsPattern())) {
            while (dbResult.next()) {
                // The name patterns are escaped; the exact names keep another object out all the same
                if (!getName().equals(JDBCUtils.safeGetString(dbResult, JDBCConstants.TABLE_NAME))) {
                    continue;
                }
                String name = JDBCUtils.safeGetString(dbResult, JDBCConstants.COLUMN_NAME);
                if (name == null) {
                    continue;
                }
                String typeName = JDBCUtils.safeGetStringTrimmed(dbResult, JDBCConstants.TYPE_NAME);
                int valueType = JDBCUtils.safeGetInt(dbResult, JDBCConstants.DATA_TYPE);
                long columnSize = JDBCUtils.safeGetLong(dbResult, JDBCConstants.COLUMN_SIZE);
                result.add(new StreamColumn(
                    this,
                    name,
                    typeName == null ? "" : typeName,
                    valueType,
                    JDBCUtils.safeGetInt(dbResult, JDBCConstants.ORDINAL_POSITION),
                    columnSize,
                    JDBCUtils.safeGetInteger(dbResult, JDBCConstants.DECIMAL_DIGITS),
                    getDataSource().getMetaModel().extractPrecisionOfNumericColumn(valueType, columnSize),
                    JDBCUtils.safeGetInt(dbResult, JDBCConstants.NULLABLE) == DatabaseMetaData.columnNoNulls,
                    JDBCUtils.safeGetString(dbResult, JDBCConstants.COLUMN_DEF)));
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, getDataSource());
        }
        return result;
    }

    /**
     * No keys: a stream table's primary key is not enforced (the server takes rows with the same key).
     */
    @NotNull
    @Override
    public Collection<? extends DBSTableConstraint> getConstraints(@NotNull DBRProgressMonitor monitor) {
        return List.of();
    }

    @NotNull
    @Override
    public Collection<? extends DBSTableIndex> getIndexes(@NotNull DBRProgressMonitor monitor) {
        return List.of();
    }

    @NotNull
    @Override
    public Collection<? extends DBSEntityAssociation> getAssociations(@NotNull DBRProgressMonitor monitor) {
        return List.of();
    }

    @NotNull
    @Override
    public Collection<? extends DBSEntityAssociation> getReferences(@NotNull DBRProgressMonitor monitor) {
        return List.of();
    }

    /**
     * No structure cache: the columns are this object's own ({@link #getAttributes}), and nothing changes its structure.
     */
    @Nullable
    @Override
    public JDBCStructCache<AltibaseSchema, ? extends DBSEntity, ? extends DBSEntityAttribute> getCache() {
        return null;
    }

    /**
     * The definition of the stream node's object: the server's creating statement.
     */
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        return source.getObjectDefinitionText(monitor, options);
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(getDataSource(), getContainer(), this);
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

}
