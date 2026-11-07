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
package com.seizia.chromite.tools.transfer.database;

import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityAttributeRef;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.DBSEntityReferrer;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests of the primary key that Copy Tables creates on a new target table ({@code DatabaseTransferUtils},
 * docs/chromite/swing-data-transfer.md): the source table's declared primary key, in key order.
 *
 * 설계: 새 테이블 DDL은 이 키 이름으로 {@code ALTER TABLE ... ADD PRIMARY KEY}를 만들고 키 열을 NOT NULL로 한다. 고유 키는 NULL을 허용할 수
 * 있어 기본 키로 쓰면 안 되므로, 선언된 기본 키만 키 순서대로 읽는지 확인한다. 읽기 메서드는 private이라 리플렉션으로 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "데이터")
 */
public class DatabaseTransferPrimaryKeyTest {

    @Test
    public void declaredPrimaryKeyColumnsInKeyOrder() throws Exception {
        DBSEntity table = Mockito.mock(DBSEntity.class);
        DBSEntityConstraint unique = constraint(DBSEntityConstraintType.UNIQUE_KEY, "email");
        DBSEntityConstraint primary = constraint(DBSEntityConstraintType.PRIMARY_KEY, "tenant_id", "id");
        Mockito.doReturn(List.of(unique, primary)).when(table).getConstraints(Mockito.any());

        Assertions.assertEquals(List.of("tenant_id", "id"), primaryKeyNames(table));
    }

    @Test
    public void noPrimaryKeyForUniqueKeysOnlyOrOtherSources() throws Exception {
        DBSEntity table = Mockito.mock(DBSEntity.class);
        Mockito.doReturn(List.of(constraint(DBSEntityConstraintType.UNIQUE_KEY, "email"))).when(table).getConstraints(Mockito.any());
        Assertions.assertEquals(List.of(), primaryKeyNames(table));

        DBSEntity noConstraints = Mockito.mock(DBSEntity.class);
        Mockito.doReturn(null).when(noConstraints).getConstraints(Mockito.any());
        Assertions.assertEquals(List.of(), primaryKeyNames(noConstraints));

        // A query or another non-table source
        Assertions.assertEquals(List.of(), primaryKeyNames(Mockito.mock(DBSObject.class)));
        Assertions.assertEquals(List.of(), primaryKeyNames(null));
    }

    @NotNull
    private static DBSEntityConstraint constraint(@NotNull DBSEntityConstraintType type, @NotNull String... columns) throws Exception {
        DBSEntityConstraint constraint = Mockito.mock(DBSEntityConstraint.class, Mockito.withSettings().extraInterfaces(DBSEntityReferrer.class));
        Mockito.when(constraint.getConstraintType()).thenReturn(type);
        List<DBSEntityAttributeRef> refs = new ArrayList<>();
        for (String column : columns) {
            DBSEntityAttribute attribute = Mockito.mock(DBSEntityAttribute.class);
            Mockito.when(attribute.getName()).thenReturn(column);
            DBSEntityAttributeRef ref = Mockito.mock(DBSEntityAttributeRef.class);
            Mockito.when(ref.getAttribute()).thenReturn(attribute);
            refs.add(ref);
        }
        Mockito.doReturn(refs).when((DBSEntityReferrer) constraint).getAttributeReferences(Mockito.any());
        return constraint;
    }

    @SuppressWarnings("unchecked")
    @NotNull
    private static List<String> primaryKeyNames(DBSObject source) throws Exception {
        Method method = DatabaseTransferUtils.class.getDeclaredMethod("getPrimaryKeyNames", DBRProgressMonitor.class, DBSObject.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(null, Mockito.mock(DBRProgressMonitor.class), source);
    }
}
