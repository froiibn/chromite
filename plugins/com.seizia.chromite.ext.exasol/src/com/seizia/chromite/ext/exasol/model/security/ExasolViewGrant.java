/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2016-2016 Karl Griesser (fullref@gmail.com)
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
package com.seizia.chromite.ext.exasol.model.security;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.exasol.model.ExasolView;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

public class ExasolViewGrant extends ExasolBaseObjectGrant {

	public ExasolViewGrant(ExasolBaseObjectGrant grant) throws DBException
	{
		super(grant);
	}
	
	@Property(viewable = true, order = 10)
	public ExasolView getView(DBRProgressMonitor monitor) throws DBException
	{
		return super.getSchema().getView(monitor, super.getObjectName());
	}
		

}
