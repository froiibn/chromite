/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.exasol.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.exasol.tools.ExasolUtils;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBPSaveableObject;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;

import java.sql.Date;
import java.sql.ResultSet;
import java.util.Map;

/**
 * Exasol CONNECTION object ({@code EXA_DBA_CONNECTIONS}).
 *
 * 설계: 연결 문자열은 필수 속성({@code required = true})이다. {@code CREATE CONNECTION … TO '…'}에 꼭 있어야 하므로, 이름만 받는 새 개체
 * 길(Swing New Connection…)이 이 선언을 보고 이 종류를 뺀다. 암호는 암호 속성이라 속성 표에서 가린다.
 *
 * 변경 이력:
 * - 2026-11-08 연결 문자열을 필수로 선언 (docs/chromite/password-handling.md)
 */
public class ExasolConnection
		implements DBPRefreshableObject, DBPNamedObject2, DBPSaveableObject, DBPScriptObject{

	private ExasolDataSource dataSource;
	private String connectionName;
	private String connectionString;
	private String userName;
	private String password="";
	private Date created;
	private String comment="";
	private Boolean persisted;

	public ExasolConnection(
	        ExasolDataSource dataSource
	        )
	{
	    this.dataSource = dataSource;
	    this.persisted = false;
	}
	
	public ExasolConnection(
	        ExasolDataSource dataSource,
	        String name,
	        String url,
            String comment,
	        String user,
	        String password
	        )
	{
	    this.persisted = false;
	    this.connectionName = name;
	    this.connectionString = url;
	    this.comment = comment;
	    this.userName = user;
	    this.password = password;
	    this.dataSource = dataSource;
	}
	
	public ExasolConnection(ExasolDataSource dataSource, ResultSet dbResult)
	{
		this.dataSource = dataSource;
		if (dbResult != null) {
			this.persisted = true;
			this.connectionName = JDBCUtils.safeGetString(dbResult,"CONNECTION_NAME");
			this.connectionString = JDBCUtils.safeGetString(dbResult,"CONNECTION_STRING");
			this.created = JDBCUtils.safeGetDate(dbResult, "CREATED");
			this.comment = JDBCUtils.safeGetString(dbResult,"CONNECTION_COMMENT");
			this.userName = JDBCUtils.safeGetString(dbResult, "USER_NAME");
		} else {
			this.connectionName = "new connection";
			this.persisted = false;
			this.password = "";
		}

	}

	@NotNull
    @Override
	@Property(viewable = true, editable=true, order = 10)
	public String getName()
	{
		return this.connectionName;
	}
	
	@Override
	public void setName(@NotNull String name)
	{
	    this.connectionName = name;
	}

	@Property(viewable = true,editable=true, updatable=true, required = true, order = 20)
	public String getConnectionString()
	{
		return this.connectionString;
	}
	
	public void setConnectionString(String url)
	{
	    this.connectionString = url;
	}

	@Property(viewable = true, order = 30)
	public Date getCreated()
	{
		return this.created;
	}

	@Property(viewable = true,editable=true, updatable=true, order = 30)
	public String getUserName()
	{
		return this.userName;
	}

   public void setUserName(String userName)
    {
        this.userName = userName;
    }

	@Override
	@Property(viewable = true, editable= true, updatable=true, length = PropertyLength.MULTILINE, order = 50)
	public String getDescription()
	{
		return this.comment;
	}
	
	public void setDescription(String comment)
	{
	    this.comment = comment;
	}

	@Override
	public DBSObject getParentObject()
	{
		return this.dataSource.getContainer();
	}

	@Override
	public ExasolDataSource getDataSource()
	{
		return this.dataSource;
	}

	@Override
	public boolean isPersisted()
	{
		return this.persisted;
	}

	@Override
	public void setPersisted(boolean persisted)
	{
		this.persisted = persisted;

	}

	@Override
	public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor)
			throws DBException
	{
        ((ExasolDataSource) getDataSource()).refreshObject(monitor);
		return this;
	}

	@NotNull
	@Override
	public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options)
			throws DBException
	{
		if (getDataSource().isAuthorizedForConnections()) {
			return ExasolUtils.getConnectionDdl(this, monitor);
		} else {
			return "User needs full access to dictionary or dba privilege to generate ddl for connections";
		}
	}
	
    @Property(viewable = true, editable= true, updatable=true, password = true, order = 35)
	public String getPassword()
	{
	    return password;
	}

    public void setPassword(String password) 
    {
        this.password = password;
    }
    
    @Override
    public String toString()
    {
    	return "Connection "+getName();
    }
}
