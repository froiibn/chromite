/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.ext.altibase;

import org.jkiss.code.NotNull;
import org.jkiss.utils.StandardConstants;

/**
 * Altibase constants.
 *
 * 변경 이력:
 * - 2026-11-12 Chromite: {@link #EC_SESSION_CLOSED}(4163). 서버가 세션을 닫으면 실행 중인 문장의 첫 오류가 4163(SQLState 08004)이라
 *   연결 끊김으로 분류되지 않았다 (docs/chromite/connection-lifecycle.md "끊긴 세션의 오류 분류")
 * - 2026-11-14 Chromite: 서버 출력 콜백의 클래스 이름 접미사(`.jdbc.driver.AltibaseConnection`, `AltibaseMessageCallback`)를 지움.
 *   콜백 인터페이스를 연결의 {@code registerMessageCallback} 메서드에서 얻어, 이름이 다른 Altibase 계열 드라이버(ShardSphere)도
 *   연결된다 (docs/chromite/drivers.md "ShardSphere")
 * - 2026-11-14 Chromite: {@link #EC_TABLE_NOT_FOUND}. 복제 카탈로그가 없는 서버(ShardSphere)의 복제 목록을 빈 목록으로 읽는다
 *   (AltibaseDataSource.getReplications)
 * - 2026-11-15 Chromite: {@link #QUERY_GET_DB_NAME}. 데이터베이스 이름을 드라이버 매개변수 query-get-active-db에서 읽었는데, 그 질의는
 *   이제 세션의 기본 스키마(로그인 사용자)를 읽는다 (docs/chromite/altibase.md "세션의 기본 스키마", 앱 변경 #116)
 * - 2026-11-17 Chromite: {@link #QUERY_DBMS_METADATA_SUBPROGRAMS}, {@link #DBMS_METADATA_GET_DEPENDENT_DDL},
 *   {@link #DBMS_METADATA_SET_TRANSFORM_PARAM}. ShardSphere의 DBMS_METADATA에는 이 두 서브프로그램이 없어, 서버에 있는지 보고 부른다
 *   (docs/chromite/altibase.md "ShardSphere에서 다른 문장", 앱 변경 #139)
 * - 2026-11-24 Chromite: {@link #PACKAGE_SPEC_HEADER}, {@link #PACKAGE_BODY_HEADER}. 메타 모델이 저장된 원본 앞의 이 줄을 알아보고 한 번만
 *   보인다(앱 변경 182, docs/chromite/altibase.md "저장 루틴의 문장 나누기")
 * - 2026-11-25 Chromite: {@link #NO_DBMS_METADATA}의 글을 사실대로("Without DBMS_METADATA package" → DBMS_METADATA가 DDL을 주지 않음).
 *   ShardSphere에는 패키지가 있고 GET_DDL이 그 종류를 거절한다. 이 줄은 이제 앱이 만든 DDL(테이블, 시노님) 앞에만 붙는다
 *   (docs/chromite/altibase.md "뷰 DDL의 경고 줄")
 */
public class AltibaseConstants {
    
    public static final boolean DEBUG = false;
    
    public static final String NEW_LINE = System.getProperty(StandardConstants.ENV_LINE_SEPARATOR);

    public static final String DBOBJ_INDEX = "INDEX";
    public static final String USER_PUBLIC = "PUBLIC";
    public static final String USER_SYSTEM_ = "SYSTEM_";
    public static final String USER_SYS = "SYS";

    // The database name (V$DATABASE), shown with sessions and locks. Not the driver's query-get-active-db: that query names
    // the session's default schema (the login user) for the execution context
    public static final String QUERY_GET_DB_NAME = "SELECT db_name FROM V$DATABASE";

    // The subprograms of the server's DBMS_METADATA package: Altibase 7.3 and ShardSphere 5.1 have different ones
    public static final String QUERY_DBMS_METADATA_SUBPROGRAMS = "SELECT DISTINCT pp.OBJECT_NAME"
        + " FROM SYSTEM_.SYS_PACKAGE_PARAS_ pp, SYSTEM_.SYS_USERS_ u"
        + " WHERE u.USER_ID = pp.USER_ID AND u.USER_NAME = 'SYS' AND pp.PACKAGE_NAME = 'DBMS_METADATA'";
    public static final String DBMS_METADATA_GET_DEPENDENT_DDL = "GET_DEPENDENT_DDL";
    public static final String DBMS_METADATA_SET_TRANSFORM_PARAM = "SET_TRANSFORM_PARAM";

    public static boolean isSysUser(@NotNull String userName) {
        return (USER_SYS.equals(userName) || USER_SYSTEM_.equals(userName));
    }
    
    public static final String SYSTEM_GENERATED_PREFIX = "__SYS_";

    public static final int PACKAGE_TYPE_SPEC = 6;
    public static final int PACKAGE_TYPE_BODY = 7;
    
    // The comment lines the package's DDL text has before its specification and its body (AltibasePackage)
    public static final String PACKAGE_SPEC_HEADER = "-- Package specification ";
    public static final String PACKAGE_BODY_HEADER = "-- Package body ";

    public static final int TYPE_BYTE = 20001;
    public static final int TYPE_VARBYTE = 20003;
    public static final int TYPE_NIBBLE = 20002;
    
    public static final int TYPE_GEOMETRY = 10003;
    public static final String TYPE_NAME_GEOMETRY = "GEOMETRY";
    public static final String TYPE_NAME_TIMESTAMP = "TIMESTAMP";
    public static final String TYPE_NAME_DATE = "DATE";
    public static final String TYPE_NAME_NUMBER = "NUMBER";
    public static final String TYPE_NAME_BYTE = "BYTE";
    public static final String TYPE_NAME_VARBYTE = "VARBYTE";
    public static final String TYPE_NAME_VARBIT = "VARBIT";
    public static final String TYPE_NAME_NIBBLE = "NIBBLE";
    public static final String TYPE_NAME_JSON = "JSON";

    public static final String OPERATION_MODIFY = "MODIFY";
    
    public static final String SRID_EQ = "SRID=";
    
    public static final String OBJ_TYPE_MATERIALIZED_VIEW = "MATERIALIZED VIEW";
    public static final String OBJ_TYPE_TYPESET = "TYPESET";
    
    // Before a DDL this model generates itself because DBMS_METADATA gave none: no package, a kind the server's GET_DDL
    // refuses (ShardSphere), or a failed call
    public static final String NO_DBMS_METADATA = "-- [WARNING] DBMS_METADATA gave no DDL, " 
            + "the generated DDL may not be correct." + NEW_LINE;
    
    public static final String NO_DDL_WITHOUT_DBMS_METADATA = 
            "-- [WARNING] DBMS_METADATA package is required to have DDL for %s." + NEW_LINE;
    
    /*
     * Preference page
     */
    public static final String PREF_EXPLAIN_PLAN_TYPE = "altibase.explain.plan.type";
    public static final String PREF_DBMS_OUTPUT = "altibase.dbms.output";
    public static final String PREF_PLAN_PREFIX = "altibase.plan.prefix";
    
    public enum ExplainPlan {
        ONLY("EXPLAIN PLAN = ONLY", (byte) 2),
        ON("EXPLAIN PLAN = ON", (byte) 1);
        
        private String title;
        private byte value;
        
        ExplainPlan(String title, byte value) {
            this.title = title;
            this.value = value;
        }
        
        public String getTitle() {
            return this.title;
        }
        
        public byte getArgValue() {
            return this.value;
        }
        
        /**
         * Get EXPLAIN_PLAN enum object by index
         */
        public static ExplainPlan getByIndex(int idx) throws ArrayIndexOutOfBoundsException {
            for (ExplainPlan expPlan : ExplainPlan.values()) {
                if (expPlan.ordinal() == idx) {
                    return expPlan;
                }
            }
            
            throw new ArrayIndexOutOfBoundsException("No such index value in EXPLAIN_PLAN: " + idx);
        }
    }
    
    public static final String SQL_STATE_TOO_LONG = "22026";
    
    /*
     * Password grace time
     */
    public static final String SQL_WARNING_TITILE = "SQL Warning";
    public static final String PASSWORD_WILL_EXPIRE_WARN_DESCRIPTION = "Change the password or contact the DBA.";
    
    public static final int EC_PASSWORD_WILL_EXPIRE = 0x51A79;
    public static final int EC_DBMS_METADATA_NOT_FOUND = 0xF1B3D;
    // "The session has been closed by the server" (SQLState 08004): the statement in flight when the session is closed
    // (ALTER DATABASE ... SESSION CLOSE); the next statement gets 08S01
    public static final int EC_SESSION_CLOSED = 4163;
    // "Table or view was not found" (SQLState 42S02), e.g. SYSTEM_.SYS_REPLICATIONS_ on a server without the replication catalog
    public static final int EC_TABLE_NOT_FOUND = 0x31031;

    /*
     * DBMS output
     */
    // The connection method whose only parameter is the driver's callback interface (AltibaseMessageCallback,
    // shardsphereMessageCallback, ...): the interface is taken from it, not from a class name
    public static final String METHOD_NAME_4_REGISTER_MESSAGE_CALLBACK = "registerMessageCallback";
    
    public static final String RESULT_YES_VALUE = "YES";
    public static final String RESULT_Y_VALUE = "Y";
    public static final String RESULT_T_VALUE = "T";
    public static final String RESULT_1_VALUE = "1";
    
    /*
     * PSM Type: SYS_PROCEDURES_ (OBJECT_TYPE)
     */
    public static final int PSM_TYPE_PROCEDURE  = 0;
    public static final int PSM_TYPE_FUNCTION   = 1;
    public static final int PSM_TYPE_TYPESET    = 3;

    /*
     * V$PROPERTY attributes
     */
    public static final long IDP_ATTR_RD_WRITABLE = 0x00000000;
    public static final long IDP_ATTR_RD_READONLY = 0x00000002;
    
    public static final String ALTER_REPL_PREFIX = "ALTER REPLICATION";

    public static final String PROP_LOB_CACHE_THRESHOLD = "LOB_CACHE_THRESHOLD";
    public static final int PROP_LOB_CACHE_THRESHOLD_DEFAULT = 8192;

    public static final int ERR_LOB_LOCATOR_SPAN_TRANS = 69828;
    public static final int ERR_TEMP_LOB_SEEK_FAILED = 201915;
}