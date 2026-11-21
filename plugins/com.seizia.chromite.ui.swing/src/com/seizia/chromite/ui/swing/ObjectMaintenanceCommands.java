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
import com.seizia.chromite.ext.altibase.extension.AltibaseExecutionSettings;
import com.seizia.chromite.ext.altibase.extension.AltibaseTableTtl;
import com.seizia.chromite.ext.altibase.model.AltibaseDataFile4Disk;
import com.seizia.chromite.ext.altibase.model.AltibaseJob;
import com.seizia.chromite.ext.altibase.model.AltibaseTablespace;
import com.seizia.chromite.ext.mssql.model.SQLServerDatabaseFile;
import com.seizia.chromite.ext.oracle.model.OracleDataFile;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.admin.objects.ObjectMaintenanceActions;
import com.seizia.chromite.model.admin.oracle.OracleCompileErrors;
import com.seizia.chromite.model.navigator.DBNDatabaseItem;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNEvent;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.NavigatorMessages;
import com.seizia.chromite.ui.swing.internal.ObjectMaintenanceMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Object Explorer commands Truncate Table…, TTL… (where the server offers a table TTL), Enable Trigger, Disable Trigger, Refresh Materialized View, Compile and the Altibase
 * replication commands Start, Stop and Synchronize, and the Altibase tablespace commands Bring Online, Take Offline,
 * Autoextend Settings… and Data File Settings…, and the SQL Server commands Set as Default Filegroup, Make Read-Only, Make
 * Read-Write and File Settings… on the selected objects.
 *
 * 설계: {@link ExplorerObjectCommands}(삭제·이름 바꾸기)와 같은 나눔이다. 탐색기에는 메뉴 항목과 갱신 호출만 두고, 문 만들기·실행은 모델 번들의
 * {@link ObjectMaintenanceActions}(모델 도구 처리기의 SQL)가 맡는다. 흐름도 같다: 확인 → 스크립트 미리 보기({@link ScriptPreviewDialog#confirmChange},
 * 취소가 기본 단추) → 열린 트랜잭션 질문({@link ExitTransactionCheck#confirmObjectChange}, 사용자 규칙 "트랜잭션 안전") → 실행.
 * <ul>
 *     <li>TRUNCATE는 #120의 규칙(쿼리 창에서 TRUNCATE는 늘 확인)을 따라 미리 보기 전에 경고 확인(Truncate / 취소, 취소가 기본)을 한 번 더 한다.
 *     행이 모두 지워지고 되돌릴 수 없다는 것과, DDL을 바로 커밋하는 DB(MySQL·MariaDB·Oracle, {@link ObjectMaintenanceActions#isDdlCommitting})는
 *     암시적 커밋이라 롤백할 수 없다는 것을 적는다. 여러 테이블이면 목록을 보인다.</li>
 *     <li>트리거·구체화된 뷰는 데이터를 지우지 않으므로 미리 보기 창이 확인이다(삭제 미리 보기와 같은 머리글).</li>
 *     <li>메뉴 항목은 선택한 객체가 모두 그 명령을 지원하고 한 연결에 있을 때만 보인다(SQL Server 에이전트 메뉴와 같은 방식).
 *     트리거는 모델이 읽은 상태가 이미 그 상태면 그 항목을 끈다.</li>
 *     <li>트리거를 바꾼 뒤에는 트리거 폴더를 다시 읽어 상태(속성)가 맞게 한다(SWT 도구의 {@code needsRefreshOnFinish}).</li>
 *     <li>Compile(Oracle 저장 코드·뷰·형식, Altibase 뷰·프로시저·패키지): 실행 뒤 객체의 유효 상태를 다시 읽고, Oracle은 컴파일 오류를
 *     {@link CompileErrorsDialog}로 보인다(오류에서 Modify 창으로 간다). 오류가 없으면 성공을 알린다(SWT {@code CompileHandler}와 같음).
 *     미리 보기와 열린 트랜잭션 질문은 다른 명령과 같다(Oracle·Altibase의 ALTER … COMPILE도 DDL이라 암시적으로 커밋한다).</li>
 *     <li>제한: 읽기 전용 연결과 메타데이터 변경 제한이면 실행하지 않고 알린다({@link ExplorerObjectCommands}와 같은 판단).</li>
 *     <li>Altibase 복제(Replications 아래 복제 노드): 시작·중지·동기화. 원격 서버에 영향을 주므로 TRUNCATE처럼 미리 보기 전에 무엇을 하는지
 *     묻는다(명령 단추 / 취소, 취소가 기본). 모델이 읽은 시작 여부로 이미 시작한 복제의 시작·동기화, 멈춘 복제의 중지를 끈다.
 *     실행 뒤(실패해도) 복제 노드를 다시 읽어 시작 여부와 송신·수신 목록을 맞춘다(SWT 도구의 {@code needsRefreshOnFinish}).</li>
 *     <li>Altibase 계열 테이블스페이스: 온라인·오프라인은 트리거 사용/사용 안 함과 같다(지금 상태와 같은 항목은 꺼짐, 미리 보기가 확인).
 *     자동 확장 설정·데이터 파일 설정은 미리 보기 전에 지금 값으로 채운 설정 대화상자({@link AltibaseTablespaceDialogs})를 보이고, 바뀐 것이
 *     없으면 속성 편집처럼 실행할 문장이 없다고 알린다. 한 개를 고를 때만 보인다. 실행 뒤(실패해도) 테이블스페이스 노드를 다시 읽어
 *     상태·크기·파일 목록을 맞춘다.</li>
 *     <li>Oracle 테이블스페이스: 온라인·오프라인, 읽기 전용·읽기 쓰기는 같은 모양이다(읽기 전용·읽기 쓰기는 온라인 읽기 쓰기·읽기 전용일 때만 켜짐).
 *     데이터 파일 설정은 {@link OracleStorageDialogs}가 묻는다.</li>
 *     <li>SQL Server 파일 그룹·파일: 기본 파일 그룹으로·읽기 전용·읽기 쓰기는 트리거 사용/사용 안 함과 같다(지금 상태와 같은 항목과 파일이 없는
 *     파일 그룹의 항목은 꺼짐). 파일 설정은 지금 값으로 채운 설정 대화상자({@link SQLServerStorageDialogs})를 보인다(데이터 파일 설정과 같음).
 *     실행 뒤(실패해도) 그 노드를 다시 읽는다: 파일 그룹은 데이터베이스의 파일 그룹 행을 모두 다시 읽어 이전 기본 파일 그룹도 맞추고, 파일은
 *     자기 행을 다시 읽는다.</li>
 *     <li>Altibase 계열 테이블의 TTL…: 연결의 서버 확장이 그 테이블에 TTL을 줄 때만 보인다({@link AltibaseTableTtl}, 제품 이름을 보지 않음).
 *     지금 TTL로 채운 설정 대화상자({@link AltibaseTableTtlDialog}) 뒤는 자동 확장 설정과 같은 흐름이고, 실행 뒤(실패해도) 테이블 노드를
 *     다시 읽어 속성(TTL 묶음)과 DDL이 새 값을 보이게 한다. 한 개를 고를 때만 보인다.</li>
 *     <li>Altibase 계열 작업(Global metadata &gt; Jobs): 작업 사용 / 작업 사용 안 함은 트리거 사용/사용 안 함과 같다(모델이 읽은
 *     {@code IS_ENABLE}과 같은 항목은 꺼짐, 미리 보기가 확인). 작업 설정…은 지금 값으로 채운 대화상자({@link AltibaseJobDialogs}) 뒤
 *     자동 확장 설정과 같은 흐름이고, 한 개를 고를 때만 보인다. 실행 뒤(실패해도) 작업 노드를 다시 읽고 선택을 다시 보여 속성 보기가 새 값을
 *     보이게 한다(작업 노드는 자식이 없어 트리가 다시 읽지 않고, 같은 노드라 선택 사건도 없다. 폴더를 다시 읽으면 선택이 풀렸다).
 *     큐 압축은 큐 노드에서 미리 보기가 확인이다. 권한이 없으면 서버 오류가 다른 명령과 같은 오류 창에 보인다.</li>
 *     <li>서버 확장의 백그라운드 실행 객체({@link AltibaseExecutionSettings}를 구현한 객체, 제품 이름을 보지 않음): 사용 / 사용 안 함은 작업과
 *     같고(지금 상태의 항목은 꺼짐), 실행 설정…은 지금 값으로 채운 대화상자({@link AltibaseExecutionSettingsDialog}) 뒤 같은 흐름이다. 실행 뒤
 *     노드를 다시 읽고 선택을 다시 보인다(작업과 같은 이유).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-explorer-object-actions.md "유지 관리 명령")
 * - 2026-11-08 Compile 명령과 컴파일 결과(SWT 감사 O11, A6), Altibase TRUNCATE·구체화된 뷰 새로 고침은 모델 쪽 지원으로 켜짐(A5)
 *   (docs/chromite/swing-routines.md)
 * - 2026-11-08 Altibase 복제 시작·중지·동기화(SWT 감사 D-A7) (docs/chromite/altibase.md "복제 시작·중지·동기화")
 * - 2026-11-15 Altibase 계열 테이블스페이스 온라인·오프라인, 자동 확장 설정, 데이터 파일 설정(앱 변경 #126,
 *   docs/chromite/altibase.md "테이블스페이스 관리")
 * - 2026-11-15 Oracle 테이블스페이스 온라인·오프라인, 읽기 전용·읽기 쓰기({@link OracleStorageDialogs}의 데이터 파일 설정 포함, 앱 변경 #127,
 *   docs/chromite/oracle-storage.md)
 * - 2026-11-15 SQL Server 기본 파일 그룹, 파일 그룹 읽기 전용·읽기 쓰기, 파일 설정(앱 변경 #128, docs/chromite/mssql-storage.md)
 * - 2026-11-16 스크립트 미리 보기와 열린 트랜잭션 질문이 탐색기 패널 대신 창 가운데에 뜸(다른 확인·알림과 같음, 앱 변경 #132,
 *   docs/chromite/swing-create-objects.md "대화상자 크기와 위치")
 * - 2026-11-17 테이블 TTL…(서버 확장이 주는 중립 능력, 앱 변경 #152, docs/chromite/altibase.md "테이블 TTL 자리")
 * - 2026-11-21 Altibase 계열 작업 사용·사용 안 함·설정, 큐 압축(앱 변경 #154, docs/chromite/altibase.md "작업 만들기·바꾸기·삭제", "큐")
 * - 2026-11-22 서버 확장의 백그라운드 실행 객체 사용·사용 안 함·실행 설정(앱 변경 #156, docs/chromite/altibase.md "확장 객체의 명령 자리")
 */
final class ObjectMaintenanceCommands {

    private static final Log log = Log.getLog(ObjectMaintenanceCommands.class);

    /**
     * Rows of the table list in the truncate confirmation before it scrolls.
     */
    private static final int LIST_ROWS = 10;

    private final ObjectExplorerPanel explorer;
    private final Supplier<List<DBNNode>> selection;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final Map<ObjectMaintenanceActions.Action, JMenuItem> items = new EnumMap<>(ObjectMaintenanceActions.Action.class);

    ObjectMaintenanceCommands(
        @NotNull ObjectExplorerPanel explorer,
        @NotNull Supplier<List<DBNNode>> selection,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        this.explorer = explorer;
        this.selection = selection;
        this.queryWindows = queryWindows;
        addItem(ObjectMaintenanceActions.Action.TRUNCATE, ObjectMaintenanceMessages.objmaint_truncate,
            ObjectMaintenanceMessages.objmaint_truncate_tip);
        addItem(ObjectMaintenanceActions.Action.TABLE_TTL, ObjectMaintenanceMessages.objmaint_ttl, ObjectMaintenanceMessages.objmaint_ttl_tip);
        addItem(ObjectMaintenanceActions.Action.ENABLE_TRIGGER, ObjectMaintenanceMessages.objmaint_enable_trigger,
            ObjectMaintenanceMessages.objmaint_enable_trigger_tip);
        addItem(ObjectMaintenanceActions.Action.DISABLE_TRIGGER, ObjectMaintenanceMessages.objmaint_disable_trigger,
            ObjectMaintenanceMessages.objmaint_disable_trigger_tip);
        addItem(ObjectMaintenanceActions.Action.REFRESH_MATERIALIZED_VIEW, ObjectMaintenanceMessages.objmaint_refresh_mview,
            ObjectMaintenanceMessages.objmaint_refresh_mview_tip);
        addItem(ObjectMaintenanceActions.Action.COMPILE, ObjectMaintenanceMessages.objmaint_compile, ObjectMaintenanceMessages.objmaint_compile_tip);
        addItem(ObjectMaintenanceActions.Action.START_REPLICATION, ObjectMaintenanceMessages.objmaint_repl_start,
            ObjectMaintenanceMessages.objmaint_repl_start_tip);
        addItem(ObjectMaintenanceActions.Action.STOP_REPLICATION, ObjectMaintenanceMessages.objmaint_repl_stop,
            ObjectMaintenanceMessages.objmaint_repl_stop_tip);
        addItem(ObjectMaintenanceActions.Action.SYNC_REPLICATION, ObjectMaintenanceMessages.objmaint_repl_sync,
            ObjectMaintenanceMessages.objmaint_repl_sync_tip);
        addItem(ObjectMaintenanceActions.Action.TABLESPACE_ONLINE, ObjectMaintenanceMessages.objmaint_tbs_online,
            ObjectMaintenanceMessages.objmaint_tbs_online_tip);
        addItem(ObjectMaintenanceActions.Action.TABLESPACE_OFFLINE, ObjectMaintenanceMessages.objmaint_tbs_offline,
            ObjectMaintenanceMessages.objmaint_tbs_offline_tip);
        addItem(ObjectMaintenanceActions.Action.TABLESPACE_AUTOEXTEND, ObjectMaintenanceMessages.objmaint_tbs_autoextend,
            ObjectMaintenanceMessages.objmaint_tbs_autoextend_tip);
        addItem(ObjectMaintenanceActions.Action.DATA_FILE_SETTINGS, ObjectMaintenanceMessages.objmaint_file_settings,
            ObjectMaintenanceMessages.objmaint_file_settings_tip);
        // Altibase-family jobs and queues (app change #154)
        addItem(ObjectMaintenanceActions.Action.ENABLE_JOB, ObjectMaintenanceMessages.objmaint_job_enable,
            ObjectMaintenanceMessages.objmaint_job_enable_tip);
        addItem(ObjectMaintenanceActions.Action.DISABLE_JOB, ObjectMaintenanceMessages.objmaint_job_disable,
            ObjectMaintenanceMessages.objmaint_job_disable_tip);
        addItem(ObjectMaintenanceActions.Action.JOB_SETTINGS, ObjectMaintenanceMessages.objmaint_job_settings,
            ObjectMaintenanceMessages.objmaint_job_settings_tip);
        addItem(ObjectMaintenanceActions.Action.COMPACT_QUEUE, ObjectMaintenanceMessages.objmaint_queue_compact,
            ObjectMaintenanceMessages.objmaint_queue_compact_tip);
        // Objects a server extension runs in the background (app change #156)
        addItem(ObjectMaintenanceActions.Action.ENABLE_EXECUTION, ObjectMaintenanceMessages.objmaint_exec_enable,
            ObjectMaintenanceMessages.objmaint_exec_enable_tip);
        addItem(ObjectMaintenanceActions.Action.DISABLE_EXECUTION, ObjectMaintenanceMessages.objmaint_exec_disable,
            ObjectMaintenanceMessages.objmaint_exec_disable_tip);
        addItem(ObjectMaintenanceActions.Action.EXECUTION_SETTINGS, ObjectMaintenanceMessages.objmaint_exec_settings,
            ObjectMaintenanceMessages.objmaint_exec_settings_tip);
        // Oracle (app change #127)
        addItem(ObjectMaintenanceActions.Action.TABLESPACE_READ_ONLY, ObjectMaintenanceMessages.objmaint_tbs_read_only,
            ObjectMaintenanceMessages.objmaint_tbs_read_only_tip);
        addItem(ObjectMaintenanceActions.Action.TABLESPACE_READ_WRITE, ObjectMaintenanceMessages.objmaint_tbs_read_write,
            ObjectMaintenanceMessages.objmaint_tbs_read_write_tip);
        // SQL Server filegroups and database files
        addItem(ObjectMaintenanceActions.Action.FILEGROUP_DEFAULT, ObjectMaintenanceMessages.objmaint_mssql_fg_default,
            ObjectMaintenanceMessages.objmaint_mssql_fg_default_tip);
        addItem(ObjectMaintenanceActions.Action.FILEGROUP_READ_ONLY, ObjectMaintenanceMessages.objmaint_mssql_fg_read_only,
            ObjectMaintenanceMessages.objmaint_mssql_fg_read_only_tip);
        addItem(ObjectMaintenanceActions.Action.FILEGROUP_READ_WRITE, ObjectMaintenanceMessages.objmaint_mssql_fg_read_write,
            ObjectMaintenanceMessages.objmaint_mssql_fg_read_write_tip);
        addItem(ObjectMaintenanceActions.Action.DATABASE_FILE_SETTINGS, ObjectMaintenanceMessages.objmaint_mssql_file_settings,
            ObjectMaintenanceMessages.objmaint_mssql_file_settings_tip);
    }

    private void addItem(@NotNull ObjectMaintenanceActions.Action action, @NotNull String label, @NotNull String tip) {
        JMenuItem item = new JMenuItem(label);
        item.setToolTipText(tip);
        item.addActionListener(e -> run(action));
        item.setVisible(false);
        items.put(action, item);
    }

    void addTo(@NotNull JPopupMenu menu) {
        for (JMenuItem item : items.values()) {
            menu.add(item);
        }
    }

    /**
     * Shows the items that apply to the whole selection. EDT, model data in memory only.
     */
    void update(@NotNull List<DBNNode> nodes) {
        List<DBSObject> objects = getObjects(nodes);
        for (Map.Entry<ObjectMaintenanceActions.Action, JMenuItem> entry : items.entrySet()) {
            ObjectMaintenanceActions.Action action = entry.getKey();
            // A settings dialog shows the values of one object
            boolean visible = objects != null && (!ObjectMaintenanceActions.needsSettings(action) || objects.size() == 1);
            boolean enabled = false;
            if (visible) {
                for (DBSObject object : objects) {
                    if (!ObjectMaintenanceActions.supports(action, object)) {
                        visible = false;
                        break;
                    }
                    enabled |= changesState(action, object);
                }
            }
            entry.getValue().setVisible(visible);
            entry.getValue().setEnabled(visible && enabled);
        }
    }

    /**
     * False only for a trigger whose state (as read) already is the one the command sets, for an Altibase replication
     * that is already started (Start, Synchronize) or stopped (Stop), and for an Altibase tablespace that is already
     * online (Bring Online) or offline (Take Offline), and for an Oracle tablespace that is not online read-write (Make
     * Read-Only) or read-only (Make Read-Write).
     * online (Bring Online) or offline (Take Offline), and for a SQL Server filegroup that already is the default,
     * read-only or read-write, or has no files.
     */
    private static boolean changesState(@NotNull ObjectMaintenanceActions.Action action, @NotNull DBSObject object) {
        Boolean enabled = ObjectMaintenanceActions.isTriggerEnabled(object);
        Boolean started = ObjectMaintenanceActions.isReplicationStarted(object);
        Boolean online = ObjectMaintenanceActions.isTablespaceOnline(object);
        Boolean jobEnabled = ObjectMaintenanceActions.isJobEnabled(object);
        Boolean executionEnabled = ObjectMaintenanceActions.isExecutionEnabled(object);
        return switch (action) {
            case ENABLE_TRIGGER -> !Boolean.TRUE.equals(enabled);
            case DISABLE_TRIGGER -> !Boolean.FALSE.equals(enabled);
            case ENABLE_JOB -> !Boolean.TRUE.equals(jobEnabled);
            case DISABLE_JOB -> !Boolean.FALSE.equals(jobEnabled);
            case ENABLE_EXECUTION -> !Boolean.TRUE.equals(executionEnabled);
            case DISABLE_EXECUTION -> !Boolean.FALSE.equals(executionEnabled);
            case START_REPLICATION, SYNC_REPLICATION -> !Boolean.TRUE.equals(started);
            case STOP_REPLICATION -> !Boolean.FALSE.equals(started);
            case TABLESPACE_ONLINE -> !Boolean.TRUE.equals(online);
            case TABLESPACE_OFFLINE -> !Boolean.FALSE.equals(online);
            // Oracle (app change #127)
            case TABLESPACE_READ_ONLY, TABLESPACE_READ_WRITE -> ObjectMaintenanceActions.changesTablespaceReadOnly(action, object);
            // SQL Server filegroups
            case FILEGROUP_DEFAULT, FILEGROUP_READ_ONLY, FILEGROUP_READ_WRITE -> ObjectMaintenanceActions.changesFilegroupState(action, object);
            default -> true;
        };
    }

    private void run(@NotNull ObjectMaintenanceActions.Action action) {
        List<DBNNode> nodes = selection.get();
        List<DBSObject> objects = getObjects(nodes);
        if (objects == null) {
            return;
        }
        objects.removeIf(object -> !ObjectMaintenanceActions.supports(action, object) || !changesState(action, object));
        if (objects.isEmpty()) {
            return;
        }
        String title = getTitle(action);
        DBPDataSourceContainer container = getContainer(objects);
        Window window = SwingUtilities.getWindowAncestor(explorer);
        if (container == null) {
            JOptionPane.showMessageDialog(window, NavigatorMessages.navigator_delete_different_connections, title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (container.isConnectionReadOnly() || objects.stream().anyMatch(DBUtils::isReadOnly)) {
            JOptionPane.showMessageDialog(window, SwingPlatformUI.createMessageComponent(
                NLS.bind(NavigatorMessages.navigator_change_restricted, container.getName())), title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        Object settings = null;
        if (ObjectMaintenanceActions.needsSettings(action)) {
            settings = askSettings(action, objects.getFirst(), title);
            if (settings == null) {
                return;
            }
            // SQL Server File Settings answers with the same object
            if (settings == AltibaseTablespaceDialogs.UNCHANGED) {
                showNothingToRun(window, title);
                return;
            }
        }
        Object chosenSettings = settings;
        List<ObjectMaintenanceActions.Statement> statements;
        boolean[] ddlCommits = new boolean[1];
        try {
            statements = ProgressDialog.runTask(NavigatorMessages.navigator_change_progress, monitor -> {
                ddlCommits[0] = ObjectMaintenanceActions.isDdlCommitting(monitor, objects.getFirst());
                return ObjectMaintenanceActions.generateScript(monitor, action, objects, chosenSettings);
            });
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, null, e);
            return;
        }
        if (statements.isEmpty()) {
            showNothingToRun(window, title);
            return;
        }
        if (action == ObjectMaintenanceActions.Action.TRUNCATE && !confirmTruncate(window, objects, container, ddlCommits[0])) {
            return;
        }
        if (isReplicationAction(action) && !confirmReplication(window, action, objects, container)) {
            return;
        }
        StringBuilder script = new StringBuilder();
        for (ObjectMaintenanceActions.Statement statement : statements) {
            script.append(statement.sql()).append(";\n"); //$NON-NLS-1$
        }
        String header = NLS.bind(ddlCommits[0]
            ? NavigatorMessages.navigator_change_preview_header_ddl_commit
            : NavigatorMessages.navigator_change_preview_header, container.getName());
        ScriptPreviewDialog.Result result = ScriptPreviewDialog.confirmChange(window, title, header, script.toString());
        if (result == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            queryWindows.openScript(container, script.toString(), 0, false);
            return;
        }
        if (result != ScriptPreviewDialog.Result.EXECUTE || !ExitTransactionCheck.confirmObjectChange(window, container)) {
            return;
        }
        // The nodes are looked up before executing (the folder of a changed trigger is read again afterwards)
        Set<DBNDatabaseNode> refreshNodes = new LinkedHashSet<>();
        if (action == ObjectMaintenanceActions.Action.ENABLE_TRIGGER || action == ObjectMaintenanceActions.Action.DISABLE_TRIGGER) {
            for (DBNNode node : nodes) {
                if (node.getParentNode() instanceof DBNDatabaseNode parent) {
                    refreshNodes.add(parent);
                }
            }
        } else if (isReplicationAction(action) || isTablespaceAction(action) || action == ObjectMaintenanceActions.Action.TABLE_TTL
            || isJobAction(action) || isExecutionAction(action)) {
            // The replication node reads itself again (AltibaseReplication.refreshObject): started flag, senders, receivers.
            // A tablespace reads its catalog row (state, file count) and its files again (AltibaseTablespace.refreshObject).
            // A table node with a new TTL is read again so that its properties and DDL show it.
            // A job reads its catalog row again (AltibaseJob.refreshObject); the tree does not reload a node without
            // children, so the selection is shown again below (label and Properties view of the new object).
            // A server extension's background object reads its row again the same way (its refreshObject).
            for (DBNNode node : nodes) {
                if (node instanceof DBNDatabaseNode databaseNode) {
                    refreshNodes.add(databaseNode);
                }
            }
        } else if (isSqlServerStorageAction(action)) {
            // A filegroup reads the rows of all filegroups of its database again (the old default changes too); a file reads its row
            for (DBNNode node : nodes) {
                if (node instanceof DBNDatabaseNode databaseNode) {
                    refreshNodes.add(databaseNode);
                }
            }
        } else if (action == ObjectMaintenanceActions.Action.DATA_FILE_SETTINGS) {
            // The file's tablespace (its Data Files folder's parent) reads its row and files again
            for (DBNNode node : nodes) {
                if (node.getParentNode() != null && node.getParentNode().getParentNode() instanceof DBNDatabaseNode tablespaceNode) {
                    refreshNodes.add(tablespaceNode);
                }
            }
        }
        boolean executed = false;
        try {
            ProgressDialog.runTask(NavigatorMessages.navigator_change_execute_progress, monitor -> {
                ObjectMaintenanceActions.execute(monitor, statements);
                return null;
            });
            executed = true;
        } catch (DBInterruptedException e) {
            // Canceled between statements: the statements before stay executed
        } catch (ObjectMaintenanceActions.ExecutionException e) {
            DBWorkbench.getPlatformUI().showError(title, NLS.bind(ObjectMaintenanceMessages.objmaint_error_statement,
                DBUtils.getObjectFullName(e.getStatement().object(), DBPEvaluationContext.UI), e.getStatement().sql()), e);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, null, e);
        }
        // Also after a failure: statements of other databases before it may have run
        refreshNodes.forEach(this::refresh);
        if (isJobAction(action) || isExecutionAction(action)) {
            explorer.showSelectionAgain();
        }
        if (action == ObjectMaintenanceActions.Action.COMPILE) {
            showCompileResult(nodes, objects, executed);
        }
    }

    /**
     * After Compile: reads the objects' state again (also after a failure, an object before it may have been compiled)
     * and, when the compile ran, shows the Oracle compile errors or that it succeeded. Other databases report compile
     * errors as the statement's error (shown already).
     */
    private void showCompileResult(@NotNull List<DBNNode> nodes, @NotNull List<DBSObject> objects, boolean executed) {
        String title = getTitle(ObjectMaintenanceActions.Action.COMPILE);
        List<CompileErrorsDialog.Row> rows = new ArrayList<>();
        try {
            ProgressDialog.runTask(ObjectMaintenanceMessages.objmaint_compile_reading, monitor -> {
                ObjectMaintenanceActions.refreshObjectStates(monitor, objects);
                for (DBNNode node : nodes) {
                    if (node instanceof DBNDatabaseNode databaseNode && objects.contains(databaseNode.getObject())) {
                        String name = DBUtils.getObjectFullName(databaseNode.getObject(), DBPEvaluationContext.UI);
                        for (OracleCompileErrors.CompileError error
                            : ObjectMaintenanceActions.readCompileErrors(monitor, List.of(databaseNode.getObject()))) {
                            rows.add(new CompileErrorsDialog.Row(databaseNode, name, error));
                        }
                    }
                }
                return null;
            });
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, null, e);
            return;
        }
        if (!executed) {
            return;
        }
        if (rows.isEmpty()) {
            String message = objects.size() == 1
                ? NLS.bind(ObjectMaintenanceMessages.objmaint_compile_done_one, DBUtils.getObjectFullName(objects.getFirst(), DBPEvaluationContext.UI))
                : NLS.bind(ObjectMaintenanceMessages.objmaint_compile_done_many, objects.size());
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(explorer), message, title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        long failed = rows.stream().map(CompileErrorsDialog.Row::node).distinct().count();
        CompileErrorsDialog.show(explorer, title, NLS.bind(ObjectMaintenanceMessages.objmaint_compile_errors, rows.size(), failed),
            rows, queryWindows);
    }

    /**
     * The TRUNCATE warning: what is emptied, that it cannot be undone and, where TRUNCATE commits implicitly, that it
     * cannot be rolled back. Cancel is the default button.
     */
    private static boolean confirmTruncate(
        @NotNull Window window,
        @NotNull List<DBSObject> tables,
        @NotNull DBPDataSourceContainer container,
        boolean ddlCommits
    ) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        String question = tables.size() == 1
            ? NLS.bind(ObjectMaintenanceMessages.objmaint_truncate_question_one,
                DBUtils.getObjectFullName(tables.getFirst(), DBPEvaluationContext.UI), container.getName())
            : NLS.bind(ObjectMaintenanceMessages.objmaint_truncate_question_many, tables.size(), container.getName());
        panel.add(new JLabel(question), BorderLayout.NORTH);
        if (tables.size() > 1) {
            DefaultListModel<String> model = new DefaultListModel<>();
            for (DBSObject table : tables) {
                model.addElement(DBUtils.getObjectFullName(table, DBPEvaluationContext.UI));
            }
            JList<String> list = new JList<>(model);
            list.setVisibleRowCount(Math.min(LIST_ROWS, tables.size()));
            panel.add(new JScrollPane(list), BorderLayout.CENTER);
        }
        String note = ddlCommits
            ? NLS.bind(ObjectMaintenanceMessages.objmaint_truncate_note_commit, container.getDriver().getName())
            : ObjectMaintenanceMessages.objmaint_truncate_note;
        panel.add(SwingPlatformUI.createMessageComponent(note), BorderLayout.SOUTH);
        Object[] buttons = {ObjectMaintenanceMessages.objmaint_truncate_button, CoreMessages.common_cancel};
        // Cancel is the default button: Enter must not empty a table
        int answer = JOptionPane.showOptionDialog(window, panel, getTitle(ObjectMaintenanceActions.Action.TRUNCATE),
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, buttons, buttons[1]);
        return answer == 0;
    }

    private static boolean isReplicationAction(@NotNull ObjectMaintenanceActions.Action action) {
        return action == ObjectMaintenanceActions.Action.START_REPLICATION || action == ObjectMaintenanceActions.Action.STOP_REPLICATION
            || action == ObjectMaintenanceActions.Action.SYNC_REPLICATION;
    }

    private static boolean isTablespaceAction(@NotNull ObjectMaintenanceActions.Action action) {
        return action == ObjectMaintenanceActions.Action.TABLESPACE_ONLINE || action == ObjectMaintenanceActions.Action.TABLESPACE_OFFLINE
            || action == ObjectMaintenanceActions.Action.TABLESPACE_AUTOEXTEND
            // Oracle (app change #127)
            || action == ObjectMaintenanceActions.Action.TABLESPACE_READ_ONLY || action == ObjectMaintenanceActions.Action.TABLESPACE_READ_WRITE;
    }

    private static boolean isJobAction(@NotNull ObjectMaintenanceActions.Action action) {
        return action == ObjectMaintenanceActions.Action.ENABLE_JOB || action == ObjectMaintenanceActions.Action.DISABLE_JOB
            || action == ObjectMaintenanceActions.Action.JOB_SETTINGS;
    }

    private static boolean isExecutionAction(@NotNull ObjectMaintenanceActions.Action action) {
        return action == ObjectMaintenanceActions.Action.ENABLE_EXECUTION || action == ObjectMaintenanceActions.Action.DISABLE_EXECUTION
            || action == ObjectMaintenanceActions.Action.EXECUTION_SETTINGS;
    }

    private static boolean isSqlServerStorageAction(@NotNull ObjectMaintenanceActions.Action action) {
        return action == ObjectMaintenanceActions.Action.FILEGROUP_DEFAULT || action == ObjectMaintenanceActions.Action.FILEGROUP_READ_ONLY
            || action == ObjectMaintenanceActions.Action.FILEGROUP_READ_WRITE || action == ObjectMaintenanceActions.Action.DATABASE_FILE_SETTINGS;
    }

    /**
     * The settings dialog of a command that needs one, filled with the object's current values.
     *
     * @return the settings, {@link AltibaseTablespaceDialogs#UNCHANGED}, or null if canceled
     */
    @Nullable
    private Object askSettings(@NotNull ObjectMaintenanceActions.Action action, @NotNull DBSObject object, @NotNull String title) {
        if (action == ObjectMaintenanceActions.Action.TABLE_TTL && object instanceof DBSTable table) {
            AltibaseTableTtl ttl = AltibaseTableTtl.forTable(table);
            return ttl == null ? null : AltibaseTableTtlDialog.ask(explorer, title, table, ttl);
        } else if (action == ObjectMaintenanceActions.Action.JOB_SETTINGS && object instanceof AltibaseJob job) {
            return AltibaseJobDialogs.askJobSettings(explorer, title, job);
        } else if (action == ObjectMaintenanceActions.Action.EXECUTION_SETTINGS && object instanceof AltibaseExecutionSettings executed) {
            return AltibaseExecutionSettingsDialog.ask(explorer, title, executed);
        } else if (action == ObjectMaintenanceActions.Action.TABLESPACE_AUTOEXTEND && object instanceof AltibaseTablespace tablespace) {
            return AltibaseTablespaceDialogs.askAutoextend(explorer, title, tablespace);
        } else if (action == ObjectMaintenanceActions.Action.DATA_FILE_SETTINGS && object instanceof AltibaseDataFile4Disk file) {
            return AltibaseTablespaceDialogs.askDataFileSettings(explorer, title, file);
        } else if (action == ObjectMaintenanceActions.Action.DATA_FILE_SETTINGS && object instanceof OracleDataFile file) {
            // Oracle (app change #127)
            return OracleStorageDialogs.askDataFileSettings(explorer, title, file);
        } else if (action == ObjectMaintenanceActions.Action.DATABASE_FILE_SETTINGS && object instanceof SQLServerDatabaseFile file) {
            // SQL Server data and log files
            return SQLServerStorageDialogs.askFileSettings(explorer, title, file);
        }
        return null;
    }

    /**
     * The settings change nothing: told as for a property change without a statement.
     */
    private static void showNothingToRun(@NotNull Window window, @NotNull String title) {
        JOptionPane.showMessageDialog(window, NavigatorMessages.navigator_change_no_script, title, JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * What the replication command does to the remote server, before the script is shown. Cancel is the default button.
     */
    private static boolean confirmReplication(
        @NotNull Window window,
        @NotNull ObjectMaintenanceActions.Action action,
        @NotNull List<DBSObject> replications,
        @NotNull DBPDataSourceContainer container
    ) {
        String names = replications.stream().map(DBSObject::getName).collect(Collectors.joining(", ")); //$NON-NLS-1$
        String question = switch (action) {
            case START_REPLICATION -> ObjectMaintenanceMessages.objmaint_repl_start_question;
            case STOP_REPLICATION -> ObjectMaintenanceMessages.objmaint_repl_stop_question;
            default -> ObjectMaintenanceMessages.objmaint_repl_sync_question;
        };
        String button = switch (action) {
            case START_REPLICATION -> ObjectMaintenanceMessages.objmaint_repl_start_button;
            case STOP_REPLICATION -> ObjectMaintenanceMessages.objmaint_repl_stop_button;
            default -> ObjectMaintenanceMessages.objmaint_repl_sync_button;
        };
        Object[] buttons = {button, CoreMessages.common_cancel};
        // Cancel is the default button: Enter must not start, stop or synchronize a replication
        int answer = JOptionPane.showOptionDialog(window, SwingPlatformUI.createMessageComponent(NLS.bind(question, names, container.getName())),
            getTitle(action), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, buttons, buttons[1]);
        return answer == 0;
    }

    @NotNull
    private static String getTitle(@NotNull ObjectMaintenanceActions.Action action) {
        return switch (action) {
            case TRUNCATE -> ObjectMaintenanceMessages.objmaint_truncate_title;
            case TABLE_TTL -> ObjectMaintenanceMessages.objmaint_ttl_title;
            case ENABLE_TRIGGER -> ObjectMaintenanceMessages.objmaint_enable_trigger_title;
            case DISABLE_TRIGGER -> ObjectMaintenanceMessages.objmaint_disable_trigger_title;
            case REFRESH_MATERIALIZED_VIEW -> ObjectMaintenanceMessages.objmaint_refresh_mview_title;
            case COMPILE -> ObjectMaintenanceMessages.objmaint_compile_title;
            case START_REPLICATION -> ObjectMaintenanceMessages.objmaint_repl_start_title;
            case STOP_REPLICATION -> ObjectMaintenanceMessages.objmaint_repl_stop_title;
            case SYNC_REPLICATION -> ObjectMaintenanceMessages.objmaint_repl_sync_title;
            case TABLESPACE_ONLINE -> ObjectMaintenanceMessages.objmaint_tbs_online_title;
            case TABLESPACE_OFFLINE -> ObjectMaintenanceMessages.objmaint_tbs_offline_title;
            case TABLESPACE_AUTOEXTEND -> ObjectMaintenanceMessages.objmaint_tbs_autoextend_title;
            case DATA_FILE_SETTINGS -> ObjectMaintenanceMessages.objmaint_file_settings_title;
            // Altibase-family jobs and queues (app change #154)
            case ENABLE_JOB -> ObjectMaintenanceMessages.objmaint_job_enable_title;
            case DISABLE_JOB -> ObjectMaintenanceMessages.objmaint_job_disable_title;
            case JOB_SETTINGS -> ObjectMaintenanceMessages.objmaint_job_settings_title;
            case COMPACT_QUEUE -> ObjectMaintenanceMessages.objmaint_queue_compact_title;
            case ENABLE_EXECUTION -> ObjectMaintenanceMessages.objmaint_exec_enable_title;
            case DISABLE_EXECUTION -> ObjectMaintenanceMessages.objmaint_exec_disable_title;
            case EXECUTION_SETTINGS -> ObjectMaintenanceMessages.objmaint_exec_settings_title;
            // Oracle (app change #127)
            case TABLESPACE_READ_ONLY -> ObjectMaintenanceMessages.objmaint_tbs_read_only_title;
            case TABLESPACE_READ_WRITE -> ObjectMaintenanceMessages.objmaint_tbs_read_write_title;
            // SQL Server filegroups and database files
            case FILEGROUP_DEFAULT -> ObjectMaintenanceMessages.objmaint_mssql_fg_default_title;
            case FILEGROUP_READ_ONLY -> ObjectMaintenanceMessages.objmaint_mssql_fg_read_only_title;
            case FILEGROUP_READ_WRITE -> ObjectMaintenanceMessages.objmaint_mssql_fg_read_write_title;
            case DATABASE_FILE_SETTINGS -> ObjectMaintenanceMessages.objmaint_mssql_file_settings_title;
        };
    }

    /**
     * Reads the folder again: through Object Explorer when it shows it (as F5), otherwise in the navigator model only.
     */
    private void refresh(@NotNull DBNDatabaseNode node) {
        if (explorer.refreshNavigatorNode(node)) {
            return;
        }
        try {
            ProgressDialog.runTask(NavigatorMessages.navigator_change_execute_progress, monitor -> node.refreshNode(monitor, DBNEvent.FORCE_REFRESH));
        } catch (DBException e) {
            log.debug("Refresh of " + node.getNodeDisplayName() + " failed", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The database objects of the nodes, or null if a node is not one.
     */
    @Nullable
    private static List<DBSObject> getObjects(@NotNull List<DBNNode> nodes) {
        List<DBSObject> objects = new ArrayList<>();
        for (DBNNode node : nodes) {
            if (!(node instanceof DBNDatabaseItem item) || item.getObject() == null) {
                return null;
            }
            objects.add(item.getObject());
        }
        return objects.isEmpty() ? null : objects;
    }

    /**
     * The connection of all objects, or null if they belong to several.
     */
    @Nullable
    private static DBPDataSourceContainer getContainer(@NotNull List<DBSObject> objects) {
        DBPDataSourceContainer container = null;
        for (DBSObject object : objects) {
            DBPDataSource dataSource = object.getDataSource();
            if (dataSource == null || container != null && container != dataSource.getContainer()) {
                return null;
            }
            container = dataSource.getContainer();
        }
        return container;
    }
}
