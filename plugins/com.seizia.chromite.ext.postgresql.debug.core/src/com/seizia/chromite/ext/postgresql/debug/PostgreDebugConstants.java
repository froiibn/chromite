/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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
package com.seizia.chromite.ext.postgresql.debug;

/**
 * PostgreSQL keys of a debug configuration (the generic ones are in {@code DBGConstants}).
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 시작 방식·대상·인수 키는 DBGConstants로 옮기고, 쓰지 않는
 *   DEBUG_TYPE_FUNCTION 삭제 (docs/chromite/procedure-debugger.md)
 */
public class PostgreDebugConstants {

    public static final String ATTR_DATABASE_NAME = "PG.ATTR_DATABASE_NAME"; //$NON-NLS-1$
    public static final String ATTR_SCHEMA_NAME = "PG.ATTR_SCHEMA_NAME"; //$NON-NLS-1$
    public static final String ATTR_FUNCTION_OID = "PG.ATTR_FUNCTION_OID"; //$NON-NLS-1$

}
