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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.model.sql.exec.SQLActualPlanProvider;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.DBCAttributeMetaData;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCResultSetMetaData;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLXML;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Result receiver of a run with "Include Actual Execution Plan" on a connection that returns the plans as result sets
 * ({@link SQLActualPlanProvider}): plan result sets become plans, the others go to the result grid receiver.
 *
 * 설계: 엔진은 결과 집합마다 받는 쪽을 먼저 정하고({@code getDataReceiver}) 열 정보는 {@code fetchStart}에서야 알려 준다.
 * 그래서 이 수신기가 먼저 받고, 열 이름이 계획 결과 집합이면 값을 직접 읽고, 아니면 그때 결과 표 수신기를 만들어 모든 호출을 넘긴다.
 * 결과 표 쪽 코드({@link ResultSetGridReceiver})는 바꾸지 않는다. 계획 값은 표시 문자열이 아니라 원래 값(XML 전체)을 읽는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 */
final class ActualPlanReceiver implements DBDDataReceiver {

    private static final Log log = Log.getLog(ActualPlanReceiver.class);

    private final SQLActualPlanProvider provider;
    private final String query;
    private final Supplier<DBDDataReceiver> gridReceiverFactory;
    private final Consumer<ExecutionPlanView.Entry> planConsumer;
    @Nullable
    private DBDDataReceiver delegate;
    private boolean planResultSet;
    private final List<String> planData = new ArrayList<>();

    /**
     * @param planConsumer receives the plans in the job thread
     */
    ActualPlanReceiver(
        @NotNull SQLActualPlanProvider provider,
        @NotNull String query,
        @NotNull Supplier<DBDDataReceiver> gridReceiverFactory,
        @NotNull Consumer<ExecutionPlanView.Entry> planConsumer
    ) {
        this.provider = provider;
        this.query = query;
        this.gridReceiverFactory = gridReceiverFactory;
        this.planConsumer = planConsumer;
    }

    @Override
    public void fetchStart(@NotNull DBCSession session, @NotNull DBCResultSet resultSet, long offset, long maxRows) throws DBException {
        List<String> labels = new ArrayList<>();
        DBCResultSetMetaData metaData = resultSet.getMeta();
        if (metaData != null) {
            for (DBCAttributeMetaData attribute : metaData.getAttributes()) {
                labels.add(attribute.getLabel());
            }
        }
        planResultSet = provider.isPlanResultSet(labels);
        if (!planResultSet) {
            delegate = gridReceiverFactory.get();
            delegate.fetchStart(session, resultSet, offset, maxRows);
        }
    }

    @Override
    public void fetchRow(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) throws DBException {
        if (delegate != null) {
            delegate.fetchRow(session, resultSet);
            return;
        }
        if (planResultSet) {
            Object value = resultSet.getAttributeValue(0);
            try {
                String text = value instanceof SQLXML xml ? xml.getString() : CommonUtils.toString(value, null);
                if (!CommonUtils.isEmpty(text)) {
                    planData.add(text);
                }
            } catch (Exception e) {
                log.debug("Can't read the actual plan", e);
            }
        }
    }

    @Override
    public void fetchEnd(@NotNull DBCSession session, @NotNull DBCResultSet resultSet) throws DBException {
        if (delegate != null) {
            delegate.fetchEnd(session, resultSet);
        }
    }

    @NotNull
    @Override
    public DBCStatistics getStatistics() {
        return delegate != null ? delegate.getStatistics() : DBDDataReceiver.super.getStatistics();
    }

    @Override
    public void close() throws DBException {
        if (delegate != null) {
            delegate.close();
            return;
        }
        for (String data : planData) {
            ExecutionPlanView.Entry entry;
            try {
                DBCPlan plan = provider.parsePlan(query, data);
                entry = new ExecutionPlanView.Entry(plan.getQueryString(), plan, null, true);
            } catch (DBException e) {
                entry = new ExecutionPlanView.Entry(query, null, ErrorText.describe(e), true);
            }
            planConsumer.accept(entry);
        }
        planData.clear();
    }

    /**
     * Whether the last result set was a plan (not shown as a grid and not counted as rows).
     */
    boolean isPlanResultSet() {
        return planResultSet;
    }
}
