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
package com.seizia.chromite.ext.firebird.model.plan;

import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Build tree of plan nodes.
 *
 * @author tomashorak@post.cz
 */
public class FireBirdPlanBuilder {
	
	private String plan;
	
	public FireBirdPlanBuilder(String plan) {
		super();
		this.plan = plan;
	}

	public List<FireBirdPlanNode> Build(JDBCSession session) throws DBCException {
		List<FireBirdPlanNode> rootNodes = new ArrayList<>();
		String [] plans = plan.split("\\n");
		for (String plan: plans) {
			FireBirdPlanParser pm = new FireBirdPlanParser(plan, session);
			FireBirdPlanNode node = null;
			try {
				node = pm.parse();
			} catch (FireBirdPlanException e) {
				throw new DBCException(e.getMessage());
			}
			rootNodes.add(node);
		}
		return rootNodes;
	}
	
}
