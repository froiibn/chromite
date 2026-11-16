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
package com.seizia.chromite.ext.oracle.debug.internal;

import com.seizia.chromite.debug.DBGController;
import com.seizia.chromite.debug.DBGControllerFactory;
import com.seizia.chromite.debug.DBGException;
import com.seizia.chromite.ext.oracle.model.OracleProcedureBase;
import com.seizia.chromite.model.struct.DBSObject;
import org.eclipse.core.runtime.IAdapterFactory;
import org.jkiss.code.NotNull;

import java.util.Map;

/**
 * Debugger of Oracle procedures and functions, standalone and packaged ({@code DBMS_DEBUG}).
 *
 * 설계: 컨트롤러 팩터리를 루틴 클래스의 어댑터로 등록해 Swing "Debug…"가 Oracle 루틴에서만 켜진다(PostgreSQL과 같은 방식). 디버깅할 수
 * 있는지(권한, 디버그 컴파일, 감싼(wrapped) 원본)는 메타데이터를 읽어야 알 수 있어 메뉴를 켤 때(EDT)는 보지 않고 디버거를 열 때
 * 서버에 묻는다({@link OracleDebugController#readSource}). attach 모드는 지원하지 않는다: {@code DBMS_DEBUG}는 대상 세션이 스스로
 * {@code INITIALIZE}·{@code DEBUG_ON}을 불러야 해서 다른 세션의 호출을 기다리는 방식이 없다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 */
public class OracleDebugAdapterFactory implements IAdapterFactory {

    private static final Class<?>[] CLASSES = new Class[] { DBGControllerFactory.class };

    private static final DBGControllerFactory CONTROLLER_FACTORY = new DBGControllerFactory() {
        @Override
        public boolean supportsAttach() {
            return false;
        }

        @NotNull
        @Override
        public DBGController createController(@NotNull DBSObject routine, @NotNull Map<String, Object> configuration) throws DBGException {
            if (!(routine instanceof OracleProcedureBase<?> procedure)) {
                throw new DBGException("Not an Oracle procedure or function: " + routine.getName());
            }
            return new OracleDebugController(procedure, configuration);
        }
    };

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        if (adapterType == DBGControllerFactory.class && adaptableObject instanceof OracleProcedureBase<?> procedure && procedure.isPersisted()) {
            return adapterType.cast(CONTROLLER_FACTORY);
        }
        return null;
    }

    @Override
    public Class<?>[] getAdapterList() {
        return CLASSES;
    }
}
