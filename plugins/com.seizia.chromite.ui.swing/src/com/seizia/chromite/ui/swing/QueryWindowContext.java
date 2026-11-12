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
 *     바뀌지 않는다. 바꾸면 그 창의 열린 트랜잭션이 다른 세션으로 옮겨진 것처럼 보이기 때문이다. 새 창, 다시 연결, 연결 변경부터 적용된다.</li>
 *     <li>여는 때: 처음 실행할 때나 창이 연결될 때(창의 기본 DB 읽기, {@code SqlEditorPanel#refreshSchemas})다. 작업 스레드에서 연다.
 *     주 문맥에서 초기 상태(기본 카탈로그·스키마)를 복사한다(SWT와 같이 {@code initFrom}). 새 세션의 자동 커밋·격리 수준은 연결 설정을 따른다
 *     ({@code JDBCExecutionContext.connect}).</li>
 *     <li>읽기({@link #get}): EDT에서 1초마다 읽으므로 서버를 부르지 않고 잠그지 않는다(volatile). 아직 정하지 않았거나 연결이 끊겼으면 null이다.</li>
 *     <li>닫기({@link #release}): 창을 닫거나 연결을 바꿀 때 문맥을 넘겨받아 작업 스레드에서 닫는다({@link #closeLater}). 트랜잭션 질문은 부르는 쪽이
 *     먼저 한다({@code ExitTransactionCheck.confirmEndSession}). 연결을 끊으면 인스턴스가 모든 문맥을 닫으므로({@code JDBCRemoteInstance.shutdown}),
 *     다음에 다시 연결된 데이터 소스에서 새 문맥을 연다(끊긴 데이터 소스의 문맥은 버린다).</li>
 *     <li>창의 서버에서만 연다({@link #setServer}, 앱 변경 #73 검토): 세션은 창의 지금 서버의 데이터 소스에서만 열린다. 창의 다른 서버를 위해 시작된 작업
 *     (연결 변경이나 창 닫기 전에 예약된 기본 DB 읽기·커밋 방식 읽기)이 늦게 {@link #open}에 오면 세션 없이 거절한다. 전에는 풀어 준 뒤라 옛 서버에
 *     새 자기 세션을 열고, 다음 서버의 {@link #open}이 묶음을 바꾸며 그 세션을 닫지 않아 연결을 끊을 때까지 주인 없는 세션이 남았다. 거절만 하고
 *     문맥을 닫거나 트랜잭션을 끝내지 않는다. 늦은 작업의 결과는 창이 이미 버린다(일련번호 확인). 연결 변경은 {@link #release}가 서버를 비우고
 *     창이 새 서버를 정하므로 그 사이에 온 작업도 거절된다.</li>
 *     <li>문맥 이름은 SWT와 같은 {@code SQLEditor <파일 이름>}이다. 드라이버가 서버에 넘기는 클라이언트 이름에 들어가 서버의 세션 목록에서 창을 구별할 수 있다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 쿼리 창마다 별도 연결 (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-12 주석만: 쿼리 창이 연결을 바꾸는 경우를 지움(쿼리 창은 한 서버의 창, 앱 변경 #69) (docs/chromite/swing-frontend.md "쿼리 창은 한 서버의 창")
 * - 2026-11-12 주석만: 연결 변경 명령이 다시 {@link #release}를 부름(앱 변경 #73) (docs/chromite/swing-frontend.md "쿼리 창의 연결 변경")
 * - 2026-11-12 창의 지금 서버({@link #setServer})에서만 세션을 엶: 연결 변경·창 닫기 뒤 늦게 온 작업이 옛 서버에 주인 없는 세션을 열던 것을 막음
 *   (docs/chromite/transactions.md "늦은 작업의 세션")
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
    // Guarded by openLock: the only server a session opens on; null while the window has none or after release
    @Nullable
    private DBPDataSourceContainer server;

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
     * The window's server now: {@link #open} opens a session only on its data source. The window sets it when it gets a
     * server (opened with one, chose one, changed its connection).
     */
    void setServer(@Nullable DBPDataSourceContainer container) {
        synchronized (openLock) {
            server = container;
        }
    }

    /**
     * The context the window runs in on this data source, opening the window's own session first when the setting says so
     * (job thread). Decides once per data source.
     *
     * @throws DBException also when the data source is not the window's server now (a job started for the server the window
     *                     had before it changed its connection or closed): no session opens for it
     */
    @NotNull
    DBCExecutionContext open(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSource dataSource) throws DBException {
        synchronized (openLock) {
            if (server == null || dataSource.getContainer() != server) {
                // Refuse only: nothing is closed or ended here
                throw new DBException("The query window '" + windowName.get() + "' does not run on '" + dataSource.getContainer().getName() //$NON-NLS-1$ //$NON-NLS-2$
                    + "' any more; no session is opened there"); //$NON-NLS-1$
            }
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
     * Forgets the window's context (the window closes or changes its connection) and closes its own session in a job.
     * The caller asked about its transaction before. No session opens again until the window sets its next server.
     */
    void release() {
        Binding current;
        synchronized (openLock) {
            current = binding;
            binding = null;
            server = null;
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
