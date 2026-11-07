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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvent;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNEvent;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLMetadataRefreshTargetResolver;
import com.seizia.chromite.model.sql.SQLMetadataRefreshTargetResolver.RefreshTarget;
import com.seizia.chromite.model.sql.SQLObjectOperation;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.model.sql.semantics.SQLObjectOperationRecognizer;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Object Explorer after DDL in a query window: reads again the schemas, databases or connections whose objects a
 * statement of the window created, changed or dropped.
 *
 * 설계:
 * <ul>
 *     <li>SWT {@code SQLMetadataRefreshCoordinator}(ui.editors.sql, 삭제됨)와 같은 모델 부품을 쓴다. 문장이 성공하면 LSM 파서로 바뀐
 *     개체를 찾고({@link SQLObjectOperationRecognizer#recognizeAll}) 그 개체를 담는 가장 좁은 그릇(스키마, DB, 연결 전체)을
 *     {@link SQLMetadataRefreshTargetResolver}로 정한다. 이름을 생략한 개체는 실행한 세션의 기본 DB/스키마에 있다.</li>
 *     <li>CREATE, ALTER, DROP, RENAME 낱말이 없는 문장은 파서를 부르지 않는다. 긴 스크립트의 SELECT·DML마다 파서를 돌리지 않기 위해서다.</li>
 *     <li>다시 읽기는 실행이 끝나고 세션에 열린 트랜잭션이 없을 때 한다. 수동 커밋 세션의 DDL은 탐색기의 메타데이터 연결에서
 *     커밋 전에 보이지 않으므로(PostgreSQL, SQL Server는 DDL도 트랜잭션 안), 지금 다시 읽으면 바뀌지 않은 목록을 읽는다.
 *     그래서 커밋하거나 롤백해 트랜잭션이 끝난 뒤(어디서 끝내든: Query 메뉴, 상태 줄, 유휴 트랜잭션 알림, 스크립트의 COMMIT) 읽는다.
 *     SWT는 같은 이유로 트랜잭션 조정기({@code SQLMetadataRefreshTransactionCoordinator})를 두었다.</li>
 *     <li>탐색기에 이미 있는 노드만 다시 읽는다({@link DBNNode#refreshNode}, 위쪽 노드를 먼저, 다시 읽은 노드 아래는 건너뜀). 노드가 아직 없으면
 *     모델의 캐시만 비운다({@link DBPRefreshableObject#refreshObject}). 펼치지 않은 탐색기 가지를 읽으려고 서버를 부르지 않기 위해서다.
 *     다시 읽은 뒤에는 세션의 기본 DB/스키마 객체를 다시 맞춘다(다시 읽은 그릇의 옛 객체를 가리키지 않게, SWT와 같음).</li>
 *     <li>설정은 두지 않는다(SWT의 "DDL 뒤 메타데이터 새로 고침" 알림·끄기 설정은 Swing에 없음). 실패는 기록만 하고 실행 결과를 바꾸지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-query-commands.md "DDL 뒤 탐색기 갱신")
 */
final class SqlMetadataRefresh {

    private static final Log log = Log.getLog(SqlMetadataRefresh.class);

    // Statements without these words do not change metadata; the others are parsed to find the objects
    private static final Pattern DDL_WORD = Pattern.compile("\\b(CREATE|ALTER|DROP|RENAME)\\b", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    // Guarded by this: the containers to read again, per session, until the run and the session's transaction end
    private final Map<DBCExecutionContext, Set<RefreshTarget>> pending = new LinkedHashMap<>();

    /**
     * A statement succeeded (job thread): remembers the containers of the objects it changed.
     */
    void collect(@NotNull DBCSession session, @NotNull SQLQuery query) {
        if (!DDL_WORD.matcher(query.getText()).find()) {
            return;
        }
        DBCExecutionContext context = session.getExecutionContext();
        try {
            DBPDataSource dataSource = context.getDataSource();
            SQLDialect dialect = dataSource.getSQLDialect();
            SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
            syntaxManager.init(dialect, dataSource.getContainer().getPreferenceStore());
            List<SQLObjectOperation> operations = SQLObjectOperationRecognizer.recognizeAll(dataSource, dialect, syntaxManager, query);
            if (operations.isEmpty()) {
                return;
            }
            List<RefreshTarget> targets = new ArrayList<>();
            for (SQLObjectOperation operation : operations) {
                targets.add(SQLMetadataRefreshTargetResolver.createTarget(session.getProgressMonitor(), context, operation));
            }
            synchronized (this) {
                pending.computeIfAbsent(context, c -> new LinkedHashSet<>()).addAll(targets);
            }
        } catch (Throwable e) {
            log.debug("Can't find the objects a statement changed", e);
        }
    }

    /**
     * No run is going on and the window's session has no open transaction (EDT): reads the remembered containers again
     * in a job. Cheap when nothing is remembered.
     */
    void refreshPending() {
        Map<DBCExecutionContext, Set<RefreshTarget>> targets;
        synchronized (this) {
            if (pending.isEmpty()) {
                return;
            }
            targets = new LinkedHashMap<>(pending);
            pending.clear();
        }
        AbstractJob job = new AbstractJob("Refresh metadata after DDL") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                for (Map.Entry<DBCExecutionContext, Set<RefreshTarget>> entry : targets.entrySet()) {
                    try {
                        refresh(monitor, entry.getKey(), entry.getValue());
                    } catch (Throwable e) {
                        log.debug("Can't refresh the metadata after DDL", e);
                    }
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    /**
     * The window closes: nothing is read again for its session any more.
     */
    synchronized void clear() {
        pending.clear();
    }

    private static void refresh(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull Set<RefreshTarget> targets
    ) throws DBException {
        DBPDataSourceContainer container = context.getDataSource().getContainer();
        if (!context.isConnected() || !container.isConnected()) {
            return;
        }
        List<DBNDatabaseNode> nodes = new ArrayList<>();
        boolean cacheRefreshed = false;
        for (RefreshTarget target : targets) {
            DBSObject object = resolveTarget(monitor, context, target);
            if (object == null) {
                continue;
            }
            // Only a node Object Explorer has loaded; an unloaded branch reads the server when it is expanded
            DBNDatabaseNode node = DBNUtils.getNodeByObject(object);
            if (node != null && !node.isDisposed()) {
                if (!nodes.contains(node)) {
                    nodes.add(node);
                }
            } else if (object instanceof DBPRefreshableObject refreshable) {
                refreshable.refreshObject(monitor);
                cacheRefreshed = true;
            }
        }
        // Outer nodes first; a node inside a node that was read again is read with it
        nodes.sort(Comparator.comparingInt(SqlMetadataRefresh::getDepth));
        List<DBNDatabaseNode> refreshed = new ArrayList<>();
        for (DBNDatabaseNode node : nodes) {
            if (node.isDisposed() || refreshed.stream().anyMatch(node::isChildOf)) {
                continue;
            }
            node.refreshNode(monitor, DBNEvent.FORCE_REFRESH);
            refreshed.add(node);
        }
        if (cacheRefreshed) {
            container.fireEvent(new DBPEvent(DBPEvent.Action.OBJECT_UPDATE, container, DBPEvent.METADATA_REFRESH));
        }
        if (!refreshed.isEmpty() || cacheRefreshed) {
            // The session's default database and schema objects may belong to a container that was read again
            DBCExecutionContextDefaults<?, ?> defaults = context.getContextDefaults();
            if (defaults != null && context.isConnected()) {
                DBUtils.refreshContextDefaultsAndReflect(monitor, defaults, context);
            }
        }
    }

    /**
     * The object of a refresh target, as SWT resolved it: the default catalog or schema of the session when the name is
     * the default's, else looked up by name; the narrowest container found when a name is not found.
     */
    @Nullable
    private static DBSObject resolveTarget(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull RefreshTarget target
    ) throws DBException {
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, context.getDataSource());
        String catalogName;
        switch (target) {
            case RefreshTarget.DataSource ignored -> {
                return root;
            }
            case RefreshTarget.Catalog catalog -> catalogName = catalog.catalogName();
            case RefreshTarget.Schema schema -> catalogName = schema.catalogName();
        }
        DBCExecutionContextDefaults<?, ?> defaults = context.getContextDefaults();
        DBSCatalog defaultCatalog = defaults == null ? null : defaults.getDefaultCatalog();
        DBSObject catalog = defaultCatalog;
        if (catalogName != null && (defaultCatalog == null || !catalogName.equals(defaultCatalog.getName()))) {
            catalog = root == null ? null : root.getChild(monitor, catalogName);
        }
        if (target instanceof RefreshTarget.Catalog) {
            return catalog != null ? catalog : root;
        }
        if (catalogName != null && catalog == null) {
            return root;
        }
        String schemaName = ((RefreshTarget.Schema) target).schemaName();
        DBSSchema defaultSchema = defaults == null ? null : defaults.getDefaultSchema();
        if (defaultSchema != null && schemaName.equals(defaultSchema.getName()) && (catalogName == null || catalog == defaultCatalog)) {
            return defaultSchema;
        }
        DBSObjectContainer schemaContainer = catalog instanceof DBSObjectContainer objectContainer ? objectContainer : root;
        DBSObject schema = schemaContainer == null ? null : schemaContainer.getChild(monitor, schemaName);
        return schema != null ? schema : catalog != null ? catalog : root;
    }

    private static int getDepth(@NotNull DBNNode node) {
        int depth = 0;
        for (DBNNode parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            depth++;
        }
        return depth;
    }
}
