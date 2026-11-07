/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.exasol.manager;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.exasol.model.ExasolConnection;
import com.seizia.chromite.ext.exasol.model.ExasolDataSource;
import com.seizia.chromite.ext.exasol.tools.ExasolUtils;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectRenamer;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.impl.sql.edit.SQLObjectEditor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;


/**
 * Exasol CONNECTION objects: CREATE/ALTER/RENAME/DROP CONNECTION and the comment.
 *
 * 설계: 새 CONNECTION은 이름 말고는 값이 없다(사용자 이름·암호·설명이 null). 그래서 값 검사는 null을 견디게 하고
 * ({@link CommonUtils#isEmpty}), 연결 문자열이 없으면 문장을 만들지 않고 거절한다(Exasol은 {@code TO '…'}가 필수). 모델은 연결 문자열을
 * 필수 속성으로 선언해 Swing의 이름만 길(New Connection…)이 이 종류를 빼게 한다. 암호가 든 {@code IDENTIFIED BY '…'}는 쿼리 관리자
 * 기록에서 뺀다({@code setDisableSessionLogging}).
 *
 * 변경 이력:
 * - 2026-11-08 새 CONNECTION의 NullPointerException 고침, 연결 문자열 없는 CREATE 거절, 암호가 든 문장의 기록 끄기
 *   (docs/chromite/password-handling.md)
 */
public class ExasolConnectionManager
        extends SQLObjectEditor<ExasolConnection, ExasolDataSource> implements DBEObjectRenamer<ExasolConnection> {
    
    @Override
    public long getMakerOptions(@NotNull DBPDataSource dataSource)
    {
        return FEATURE_SAVE_IMMEDIATELY;
    }
    
    
    @Override
    public DBSObjectCache<ExasolDataSource, ExasolConnection> getObjectsCache(
            ExasolConnection object)
    {
        ExasolDataSource source = object.getDataSource();
        return source.getConnectionCache();
    }
    
    @Override
    protected ExasolConnection createDatabaseObject(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBECommandContext context,
        @NotNull Object container,
        @Nullable Object copyFrom,
        @NotNull Map<String, Object> options
    ) {
        return new ExasolConnection((ExasolDataSource) container, null, null, null, null, null);
    }
    
    private SQLDatabasePersistAction getCommentCommand(ExasolConnection con)
    {
    	return new SQLDatabasePersistAction(
                	"Comment on Connection",
                	String.format("COMMENT ON CONNECTION %s is '%s'",
    	                DBUtils.getQuotedIdentifier(con),
    	                ExasolUtils.quoteString(con.getDescription())
    	            )                
                );
    }
    
    /**
     * Whether the {@code USER … IDENTIFIED BY …} clause is written: only with both a user name and a password (null for a
     * new connection that has neither).
     */
    private static boolean hasCredentials(@NotNull ExasolConnection con) {
        return !CommonUtils.isEmpty(con.getUserName()) && !CommonUtils.isEmpty(con.getPassword());
    }

    @Override
    protected void addObjectCreateActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                          @NotNull ObjectCreateCommand command, @NotNull Map<String, Object> options) throws DBException
    {
        final ExasolConnection con = command.getObject();
        if (CommonUtils.isEmpty(con.getConnectionString())) {
            throw new DBException("A connection string is required for the connection " + con.getName());
        }
        
        StringBuilder script = new StringBuilder(String.format("CREATE CONNECTION %s TO ",DBUtils.getQuotedIdentifier(con)));
       
        script.append(" '" + ExasolUtils.quoteString(con.getConnectionString()) + "' ");
        
        if (hasCredentials(con))
        {
            script.append(String.format("USER '%s' IDENTIFIED BY '%s'",
                    ExasolUtils.quoteString(con.getUserName()), 
                    ExasolUtils.quoteString(con.getPassword())
                            )
                    );
            command.setDisableSessionLogging(true); // Hide password from Query Manager
        }
        
        actions.add(
                new SQLDatabasePersistAction("Create Connection", script.toString())
                );
        
        if (!CommonUtils.isEmpty(con.getDescription()))
        {
            actions.add(getCommentCommand(con));
        }
       
        
    }
    
    @Override
    protected void addObjectRenameActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                          @NotNull ObjectRenameCommand command, @NotNull Map<String, Object> options)
    {
        ExasolConnection obj = command.getObject();
        actions.add(
            new SQLDatabasePersistAction(
                "Rename Connection",
                "RENAME CONNECTION " +  DBUtils.getQuotedIdentifier(obj.getDataSource(), command.getOldName()) + " to " +
                    DBUtils.getQuotedIdentifier(obj.getDataSource(), command.getNewName()))
        );
    }
    
    @Override
    protected void addObjectDeleteActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actions,
                                          @NotNull ObjectDeleteCommand command, @NotNull Map<String, Object> options)
    {
        final ExasolConnection con = command.getObject();
        actions.add(
                new SQLDatabasePersistAction("Drop Connection","DROP CONNECTION " + DBUtils.getQuotedIdentifier(con))
                );
    }
    
    @Override
    protected void addObjectModifyActions(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext executionContext, @NotNull List<DBEPersistAction> actionList,
                                          @NotNull ObjectChangeCommand command, @NotNull Map<String, Object> options)
    {
        ExasolConnection con = command.getObject();
        
        Map<Object, Object> com = command.getProperties();
        
        if (com.containsKey("description"))
        {
            actionList.add(
                    getCommentCommand(con)
                    );
        }
        

        // url, username or password have changed
        if (com.containsKey("url") | com.containsKey("userName") | com.containsKey("password") )
        {
            // possible loss of information - warn
            StringBuilder script = new StringBuilder(String.format("ALTER CONNECTION %s TO",DBUtils.getQuotedIdentifier(con)));
            script.append(" '" + ExasolUtils.quoteString(CommonUtils.notEmpty(con.getConnectionString())) + "' ");
            if (hasCredentials(con))
            {
                script.append(String.format(" USER '%s' IDENTIFIED BY '%s'",
                        ExasolUtils.quoteString(con.getUserName()), 
                        ExasolUtils.quoteString(con.getPassword())
                                )
                        );
                command.setDisableSessionLogging(true); // Hide password from Query Manager
            }
            
            actionList.add(
                    new SQLDatabasePersistAction("alter Connection", script.toString())
                    );
        }
    }


    @Override
    public void renameObject(@NotNull DBECommandContext commandContext,
                             @NotNull ExasolConnection object, @NotNull Map<String, Object> options, @NotNull String newName) throws DBException
    {
        processObjectRename(commandContext, object, options, newName);
    }
    
    
    

}
