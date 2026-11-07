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
package com.seizia.chromite.ui.swing.internal;

import org.eclipse.osgi.util.NLS;

/**
 * 결과 행에서 SQL 만들기(결과 표의 "SQL 생성" 메뉴)의 화면 문구.
 *
 * 설계: 기능마다 묶음 하나를 두는 Swing 관례({@link GridNavMessages}와 같음, 다른 작업과 같은 파일을 고치지 않게)를 따른다. 영어가 기본
 * ({@code GridSqlMessages.properties}), 한국어는 {@code GridSqlMessages_ko.properties}이고 OS 언어로 고른다. 키 접두사는 {@code gridsql_}다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 * SQL 문 이름(SELECT, INSERT, MERGE, 방언의 중복 키 처리 방식 이름)은 생성기·방언 확장의 레이블을 그대로 쓴다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 결과 행에서 SQL 만들기 (docs/chromite/swing-result-generate-sql.md)
 */
public class GridSqlMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.GridSqlMessages"; //$NON-NLS-1$

    // Menu (ResultSetGenerateSql)
    public static String gridsql_menu;
    public static String gridsql_insert_methods;
    public static String gridsql_ddl;
    public static String gridsql_ddl_tip;
    public static String gridsql_no_connection;
    public static String gridsql_no_rows;
    public static String gridsql_no_table;
    public static String gridsql_no_key;
    public static String gridsql_no_columns;

    // Generation and result (ResultSetGenerateSql)
    public static String gridsql_title;
    public static String gridsql_job;
    public static String gridsql_error;
    public static String gridsql_header_rows;
    public static String gridsql_header_ddl;
    public static String gridsql_no_schema;
    public static String gridsql_no_ddl;
    public static String gridsql_dynamic_metadata;

    static {
        NLS.initializeMessages(BUNDLE_NAME, GridSqlMessages.class);
    }

    private GridSqlMessages() {
    }
}
