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
package com.seizia.chromite.ext.mssql.edit;

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.util.Map;

/**
 * Object manager base of SQL Server filegroups and database files: its create, delete and rename commands run in auto-commit
 * mode.
 *
 * 설계: 이 객체들의 문장은 모두 {@code ALTER DATABASE}이고 서버는 사용자 트랜잭션 안에서 이 문장을 거절한다(오류 226, 시험 서버에서 확인:
 * 명시적 트랜잭션, 그리고 수동 커밋 세션에서 이미 열린 암시적 트랜잭션 모두). 모델의 저장 경로({@code AbstractCommandContext.saveChanges})는
 * 저장하는 동안 세션을 수동 커밋으로 바꾸므로, 새 데이터베이스({@code CREATE DATABASE})처럼 명령이
 * {@link DBECommandContext#OPTION_AVOID_TRANSACTIONS}를 켜서 자동 커밋으로 저장하게 한다. 그래서 세션의 커밋 모드를 바꾸지 않는다.
 * 열린 트랜잭션은 실행 전에 화면이 묻는다(사용자 규칙 "트랜잭션 안전"); 묻지 못한 트랜잭션이 남아 있으면 서버가 226으로 거절하고 그
 * 트랜잭션은 그대로 남는다(커밋도 롤백도 되지 않음, 시험 서버에서 확인).
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #128, docs/chromite/mssql-storage.md)
 */
public abstract class SQLServerStorageObjectEditor<OBJECT_TYPE extends DBSObject, CONTAINER_TYPE extends DBSObject>
    extends SQLObjectEditor<OBJECT_TYPE, CONTAINER_TYPE> implements DBEObjectRenamer<OBJECT_TYPE> {

    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource) {
        return FEATURE_SAVE_IMMEDIATELY;
    }

    @Override
    public boolean canEditObject(@NotNull OBJECT_TYPE object) {
        return false;
    }

    @NotNull
    @Override
    public ObjectCreateCommand makeCreateCommand(@NotNull OBJECT_TYPE object, @NotNull Map<String, Object> options) {
        return new ObjectCreateCommand(object, ModelMessages.model_jdbc_create_new_object, options) {
            @Override
            public void validateCommand(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> commandOptions) throws DBException {
                super.validateCommand(monitor, commandOptions);
                commandOptions.put(DBECommandContext.OPTION_AVOID_TRANSACTIONS, true);
            }
        };
    }

    @Override
    public void deleteObject(@NotNull DBECommandContext commandContext, @NotNull OBJECT_TYPE object, @NotNull Map<String, Object> options) {
        commandContext.addCommand(new ObjectDeleteCommand(object, ModelMessages.model_jdbc_delete_object) {
            @Override
            public void validateCommand(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> commandOptions) {
                commandOptions.put(DBECommandContext.OPTION_AVOID_TRANSACTIONS, true);
            }
        }, new DeleteObjectReflector<>(this), true);
    }

    @Override
    public void renameObject(
        @NotNull DBECommandContext commandContext,
        @NotNull OBJECT_TYPE object,
        @NotNull Map<String, Object> options,
        @NotNull String newName
    ) {
        commandContext.addCommand(new ObjectRenameCommand(object, ModelMessages.model_jdbc_rename_object, options, newName) {
            @Override
            public void validateCommand(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> commandOptions) {
                commandOptions.put(DBECommandContext.OPTION_AVOID_TRANSACTIONS, true);
            }
        }, new RenameObjectReflector(), true);
    }
}
