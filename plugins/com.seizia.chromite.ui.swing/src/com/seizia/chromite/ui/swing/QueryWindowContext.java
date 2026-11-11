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

import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences.SeparateConnectionBehavior;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.util.function.Supplier;

/**
 * The execution context (server session) one query window runs in: its own isolated context, or the connection's main
 * context shared with the other query windows of the connection.
 *
 * 설계:
 * <ul>
 *     <li>SWT {@code SQLEditor}는 편집기마다 격리된 실행 문맥({@link DBSInstance#openIsolatedContext})을 열었다. 그래서 한 연결의 편집기들이
 *     트랜잭션을 나누지 않았다(창 A의 커밋이 창 B의 변경을 커밋하지 않음). Swing 쿼리 창은 모두 연결의 주 문맥을 써서 트랜잭션을 나눴다(SWT 대조 B-S22).
 *     이 클래스가 SWT와 같은 규칙으로 창마다 문맥을 연다.</li>
 *     <li>설정은 SWT와 같은 키 {@link #PREF_SEPARATE_CONNECTION}({@code database.editor.separate.connection})과 값({@link SeparateConnectionBehavior})이다.
 *     연결의 설정 저장소에서 읽으므로 연결별 값(연결 대화상자)이 없으면 전역 값(Options &gt; Query Execution)을 따른다. 기본값 {@code DEFAULT}는
 *     SWT {@code SQLEditorUtils.isOpenSeparateConnection}과 같이 "단일 연결" 연결과 내장 DB 드라이버만 빼고 창마다 연다.</li>
 *     <li>정하는 때: 창이 한 데이터 소스(연결 한 번)에서 처음 문맥을 얻을 때({@link #open}) 정하고 그 데이터 소스 동안 유지한다. 설정을 바꿔도 이미 정한 창은
 *     바뀌지 않는다. 바꾸면 그 창의 열린 트랜잭션이 다른 세션으로 옮겨진 것처럼 보이기 때문이다. 새 창, 다시 연결부터 적용된다.</li>
 *     <li>여는 때: 처음 실행할 때나 창이 연결될 때(창의 기본 DB 읽기, {@code SqlEditorPanel#refreshSchemas})다. 작업 스레드에서 연다.
 *     주 문맥에서 초기 상태(기본 카탈로그·스키마)를 복사한다(SWT와 같이 {@code initFrom}). 새 세션의 자동 커밋·격리 수준은 연결 설정을 따른다
 *     ({@code JDBCExecutionContext.connect}).</li>
 *     <li>읽기({@link #get}): EDT에서 1초마다 읽으므로 서버를 부르지 않고 잠그지 않는다(volatile). 아직 정하지 않았거나 연결이 끊겼으면 null이다.</li>
 *     <li>닫기({@link #release}): 창을 닫을 때 문맥을 넘겨받아 작업 스레드에서 닫는다({@link #closeLater}). 트랜잭션 질문은 부르는 쪽이
 *     먼저 한다({@code ExitTransactionCheck.confirmEndSession}). 연결을 끊으면 인스턴스가 모든 문맥을 닫으므로({@code JDBCRemoteInstance.shutdown}),
 *     다음에 다시 연결된 데이터 소스에서 새 문맥을 연다(끊긴 데이터 소스의 문맥은 버린다).</li>
 *     <li>문맥 이름은 SWT와 같은 {@code SQLEditor <파일 이름>}이다. 드라이버가 서버에 넘기는 클라이언트 이름에 들어가 서버의 세션 목록에서 창을 구별할 수 있다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 쿼리 창마다 별도 연결 (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-12 주석만: 쿼리 창이 연결을 바꾸는 경우를 지움(쿼리 창은 한 서버의 창, 앱 변경 #69) (docs/chromite/swing-frontend.md "쿼리 창은 한 서버의 창")
 */
final class QueryWindowContext {

    private static final Log log = Log.getLog(QueryWindowContext.class);

    /**
     * SWT {@code SQLPreferenceConstants.EDITOR_SEPARATE_CONNECTION}: a {@link SeparateConnectionBehavior} name, in the
     * platform store (Options) or the connection's store (connection dialog).
     */
    static final String PREF_SEPARATE_CONNECTION = "database.editor.separate.connection"; //$NON-NLS-1$

    /**
     * The data source the window decided for, and its own context (null: it shares the main context).
     */
    private record Binding(@NotNull DBPDataSource dataSource, @Nullable DBCExecutionContext own) {
    }

    private final Supplier<String> windowName;
    private final Object openLock = new Object();
    @Nullable
    private volatile Binding binding;

    /**
     * @param windowName the window's file name, for the context name
     */
    QueryWindowContext(@NotNull Supplier<String> windowName) {
        this.windowName = windowName;
    }

    /**
     * Whether query windows of the connection open their own session: the connection's value, else the global one; the
     * default leaves out "single connection" connections and embedded databases (SWT {@code SQLEditorUtils}).
     */
    static boolean isSeparate(@NotNull DBPDataSourceContainer container) {
        return switch (SeparateConnectionBehavior.parse(container.getPreferenceStore().getString(PREF_SEPARATE_CONNECTION))) {
            case ALWAYS -> true;
            case NEVER -> false;
            default -> !container.isForceUseSingleConnection() && !container.getDriver().isEmbedded();
        };
    }

    /**
     * The context the window runs in on this data source, opening the window's own session first when the setting says so
     * (job thread). Decides once per data source.
     */
    @NotNull
    DBCExecutionContext open(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSource dataSource) throws DBException {
        synchronized (openLock) {
            Binding current = binding;
            if (current == null || current.dataSource() != dataSource) {
                // First use, or the connection was opened again: the old data source closed its contexts when it disconnected
                DBCExecutionContext own = null;
                if (isSeparate(dataSource.getContainer())) {
                    DBSInstance instance = dataSource.getDefaultInstance();
                    own = instance.openIsolatedContext(monitor, "SQLEditor <" + windowName.get() + ">", //$NON-NLS-1$ //$NON-NLS-2$
                        DBUtils.getDefaultContext(instance, false));
                }
                current = new Binding(dataSource, own);
                binding = current;
            }
            DBCExecutionContext context = current.own() != null ? current.own() : DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
            if (context == null) {
                throw new DBException(NLS.bind(EditorMessages.editor_sql_no_context, dataSource.getContainer().getName()));
            }
            return context;
        }
    }

    /**
     * The context the window runs in now, without opening anything (any thread): null while the window has not used the
     * connected data source yet.
     */
    @Nullable
    DBCExecutionContext get(@Nullable DBPDataSourceContainer container) {
        DBPDataSource dataSource = container != null && container.isConnected() ? container.getDataSource() : null;
        Binding current = binding;
        if (dataSource == null || current == null || current.dataSource() != dataSource) {
            return null;
        }
        return current.own() != null ? current.own() : DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
    }

    /**
     * The window's own context on the connected data source, or null when it shares the main context or has none open.
     */
    @Nullable
    DBCExecutionContext getOwn(@Nullable DBPDataSourceContainer container) {
        DBPDataSource dataSource = container != null && container.isConnected() ? container.getDataSource() : null;
        Binding current = binding;
        return dataSource != null && current != null && current.dataSource() == dataSource ? current.own() : null;
    }

    /**
     * Forgets the window's context (the window closes) and closes its own session in a job.
     * The caller asked about its transaction before.
     */
    void release() {
        Binding current;
        synchronized (openLock) {
            current = binding;
            binding = null;
        }
        // A disconnect already closed the contexts of its data source
        if (current != null && current.own() != null && current.dataSource() == current.dataSource().getContainer().getDataSource()) {
            closeLater(current.own());
        }
    }

    private static void closeLater(@NotNull DBCExecutionContext context) {
        new AbstractJob("Close query window session " + context.getContextName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    context.close();
                } catch (Throwable e) {
                    log.debug("Error closing the query window session " + context.getContextName(), e);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Number of sessions Chromite holds on the connection now: the contexts of all its instances (main, metadata, query
     * windows, tools). Reads in-memory lists only.
     */
    static int countSessions(@NotNull DBPDataSource dataSource) {
        int count = 0;
        for (DBSInstance instance : dataSource.getAvailableInstances()) {
            count += instance.getAllContexts().length;
        }
        return count;
    }
}
