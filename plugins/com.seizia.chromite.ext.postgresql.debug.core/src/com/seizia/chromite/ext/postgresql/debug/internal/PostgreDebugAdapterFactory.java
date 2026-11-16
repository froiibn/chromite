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
package com.seizia.chromite.ext.postgresql.debug.internal;

import org.eclipse.core.runtime.IAdapterFactory;
import com.seizia.chromite.debug.DBGControllerFactory;
import com.seizia.chromite.ext.postgresql.debug.internal.impl.PostgreDebugControllerFactory;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedure;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedureKind;

/**
 * Debugger of PostgreSQL functions and procedures.
 *
 * 설계: pldbgapi는 PL/pgSQL 함수와 프로시저만 디버깅한다. 집계·창 함수는 본문이 없어 여기서 뺀다. 언어(PL/pgSQL인지)는 메타데이터
 * 캐시를 읽어야 알 수 있어 메뉴를 켤 때(EDT)는 보지 않고, 디버거를 시작할 때 서버에 묻는다({@code PostgreDebugController#readSource}).
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 연결 대신 루틴의 어댑터, DBGResolver 삭제
 *   (docs/chromite/procedure-debugger.md)
 */
public class PostgreDebugAdapterFactory implements IAdapterFactory {

    private static final Class<?>[] CLASSES = new Class[] { DBGControllerFactory.class };

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        if (adapterType == DBGControllerFactory.class && adaptableObject instanceof PostgreProcedure procedure && procedure.isPersisted()
            && (procedure.getKind() == PostgreProcedureKind.f || procedure.getKind() == PostgreProcedureKind.p)) {
            return adapterType.cast(new PostgreDebugControllerFactory());
        }
        return null;
    }

    @Override
    public Class<?>[] getAdapterList() {
        return CLASSES;
    }

}
