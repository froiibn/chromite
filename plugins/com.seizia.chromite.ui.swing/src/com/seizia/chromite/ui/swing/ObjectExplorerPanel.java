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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.model.admin.security.SecurityPrincipal;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import com.seizia.chromite.ui.swing.internal.BackupRestoreMessages;
import com.seizia.chromite.ui.swing.internal.CompareMessages;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DashboardMessages;
import com.seizia.chromite.ui.swing.internal.DataSearchMessages;
import com.seizia.chromite.ui.swing.internal.DbCopyMessages;
import com.seizia.chromite.ui.swing.internal.ErdMessages;
import com.seizia.chromite.ui.swing.internal.NavigatorMessages;
import com.seizia.chromite.ui.swing.internal.SchedulerMessages;
import com.seizia.chromite.ui.swing.internal.SecurityMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPHiddenObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNEvent;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNProject;
import com.seizia.chromite.model.navigator.DBNProjectDatabases;
import com.seizia.chromite.model.navigator.DBNUtils;
import com.seizia.chromite.model.navigator.INavigatorListener;
import com.seizia.chromite.model.navigator.meta.DBXTreeItem;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableWithReturn;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectFilter;
import com.seizia.chromite.model.struct.DBSParameter;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameterKind;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Object Explorer: 활성 프로젝트의 연결과 DB 객체를 보여 주는 트리.
 *
 * 설계: SWT 버전의 Database Navigator와 같은 탐색기 모델({@code DBWorkbench.getPlatform().getNavigatorModel()})을 그대로 쓴다.
 * 트리의 최상위는 SSMS처럼 사용자가 Object Explorer에 연 서버만의 평평한 목록이다({@link ObjectExplorerServers}, 연 순서, 연결 폴더 없음).
 * 등록된 서버 전체와 서버 그룹(연결 폴더)은 Registered Servers 창({@link RegisteredServersPanel})이 보여 준다.
 * 서버는 Registered Servers의 "Object Explorer"({@link #showServer}), 도구 막대의 Connect(등록된 서버 고르기), New Connection으로 들어오고,
 * Disconnect로 연결을 끊으면 SSMS처럼 목록에서 빠진다. 다른 곳(Registered Servers, 편집기)에서 연결을 끊으면 노드는 접힌 채 남는다.
 * <ul>
 *     <li>자식은 처음 펼칠 때 읽는다. 읽는 동안 "Loading…" 자식을 보여 주고, {@code DBNNode#getChildren}을
 *     {@link AbstractJob}(백그라운드 Job, {@link DBRProgressMonitor} 사용)에서 호출한다. EDT에서는 모델을 읽지 않는다
 *     (연결 목록처럼 메모리에 있는 자식도 같은 경로를 써서 경로를 하나로 둔다).</li>
 *     <li>SWT 탐색기 트리와 달리 탐색 불가 항목({@code navigable="false"}: 루틴 매개 변수, 인덱스·키 열, MySQL 변수·상태·엔진 등)도
 *     보여 준다({@link #hasTreeChildren}). SWT는 그것을 객체 편집기의 탭에서 보여 주지만 이 앱에는 객체 편집기가 없어 트리가 유일한 자리다.
 *     숨김 노드({@code DBPHiddenObject})는 SWT처럼 뺀다.</li>
 *     <li>연결 노드를 펼치면 {@code DBNDatabaseNode#getChildren} → {@code DBUtils.initDataSource} →
 *     {@code DBServiceConnections}({@link SwingConnectionService})로 연결한다. SWT 탐색기와 같은 흐름이다.</li>
 *     <li>결과는 {@link SwingUtilities#invokeLater}로 EDT에서 트리 모델에 반영한다. 노드마다 읽기 번호를 두어
 *     새로 고침 등으로 더 새 읽기가 시작되었으면 이전 결과는 버린다.</li>
 *     <li>오류는 {@code DBWorkbench.getPlatformUI().showError}({@link SwingPlatformUI})로 보여 주고 노드를 접어 다시 펼치면 다시 읽게 한다.
 *     연결 취소({@link DBInterruptedException})는 연결 서비스가 이미 알렸으므로 다시 알리지 않는다.</li>
 *     <li>새로 고침(도구 막대 단추, 오른쪽 클릭 메뉴, 트리에 초점이 있을 때 F5)은 {@code DBNNode#refreshNode} 뒤에
 *     자식을 다시 읽는다. 아직 펼친 적 없는 노드는 연결을 새로 열지 않도록 다시 그리기만 한다. F5는 SSMS처럼 초점에 따라 나뉜다:
 *     트리의 키 바인딩(초점 컴포넌트)이 메인 창 Query 메뉴의 F5(Execute, 창 전체 단축키)보다 먼저 처리되므로, 트리에서는 새로 고침, 그 밖에서는 실행이다.</li>
 *     <li>더블클릭은 Swing 트리의 기본 동작(펼치기/접기)만 한다. 객체 편집기는 다음 단계에서 만든다.</li>
 *     <li>오른쪽 클릭 메뉴 "New Query"는 선택한 노드가 속한 연결({@link DBNDatabaseNode#getDataSourceContainer()})로
 *     SQL 편집기를 연다(SSMS처럼 연결 아래 어느 노드에서나). 연결은 편집기가 실행할 때 필요하면 연다.</li>
 *     <li>연결 명령({@link ConnectionCommands}): New Connection(빈 곳에서도, 최상위에 만들고 이 창에 넣는다), Connect / Disconnect / Reconnect(연결 아래 어느 노드에서나),
 *     Edit Connection(Enter), Rename(F2), Delete(Delete 키, 확인 후)는 연결 노드에서.</li>
 *     <li>객체 스크립트(SSMS): "Select Top 1000 Rows"(테이블·뷰, 새 편집기에서 바로 실행), "Script as > CREATE To / SELECT To >
 *     New Query Editor Window / Clipboard", "View DDL"(읽기 전용 탭). SQL은 {@link ObjectScripts}(모델의 SQL 생성기)가 만들고,
 *     DB 메타데이터를 읽으므로 진행 대화상자({@link ProgressDialog})의 작업 스레드에서 만든다.</li>
 *     <li>모델 이벤트: 탐색기 모델({@link INavigatorListener}, 모델이 EDT에서 부름)을 듣고 다른 곳에서 일어난 변경을 반영한다.
 *     연결 목록이 바뀌면(추가·삭제·이름 변경, 연 서버 목록 변경) 목록을 다시 읽되 이미 있는 트리 노드와 펼친 상태는 그대로 둔다.
 *     연결이 끊기면({@code UNLOAD}) 그 연결을 접고 다시 펼칠 때 읽게 하고, 연결 상태가 바뀌면 아이콘을 다시 그린다.
 *     모델이 지운 DB 객체 노드는 트리에서 뺀다. 이 패널이 보낸 이벤트(새로 고침의 원천이 이 패널)는 이미 처리했으므로 무시한다.
 *     다른 곳에서 새로 만든 DB 객체({@code ADD}, 테이블 디자이너의 새 테이블·열)는 부모의 자식을 모델에서 다시 읽어 보여 주고,
 *     다른 곳에서 새로 읽은 노드({@code UPDATE}·{@code REFRESH}, 디자이너의 저장 뒤)는 그 노드와 펼쳐 둔 하위 노드의 자식을
 *     모델에서 다시 읽는다(DB는 다시 읽지 않고 펼친 상태는 유지).</li>
 *     <li>테이블 디자이너({@link TableDesignerPanel}): 테이블의 "Design", Tables 폴더의 "New Table…".</li>
 *     <li>ER 다이어그램({@link ErdDiagramPanel}): 스키마·테이블의 "View Diagram".</li>
 *     <li>개체 정의 편집({@link SourceEditCommands}): 프로시저·함수·뷰·트리거·패키지의 "Modify"와 "Script as &gt; ALTER To",
 *     뷰·프로시저·함수 폴더의 "New View… / New Procedure… / New Function…".</li>
 *     <li>모양(docs/chromite/design-spec.md "트리"): 머리말 아래 아이콘 도구 막대({@link #createToolBar}, SSMS Object Explorer 도구 막대 순서),
 *     아이콘 팩 아이콘({@link NodeIcons#getNodeIcon}), 이름 뒤 보조 글자(열·매개 변수 형식, 읽은 폴더의 객체 수, 연결 호스트),
 *     마우스를 올린 행의 둥근 바탕({@link ExplorerTree}). 행 높이·들여쓰기·선택 모양은 테마 properties의 FlatLaf 키다.</li>
 *     <li>Filter(SSMS): 고른 객체 폴더에 이름 마스크를 건다. DBeaver 탐색기 필터({@code DBSObjectFilter})를 그대로 써서 연결 설정에 저장된다.
 *     Collapse All은 펼친 노드를 모두 접는다.</li>
 *     <li>관리 도구: 오른쪽 클릭 메뉴의 Activity Monitor, Locks(선택한 노드의 연결), Find Objects…(선택한 노드의 연결과 스키마를 범위로).
 *     Find Objects 결과는 {@link #revealNode}로 트리를 펼쳐 고른다. Search Data…(스키마·카탈로그·"Tables" 폴더 하나, 또는 같은 부모의
 *     테이블 여러 개)는 값을 행에서 찾는 문서 탭({@link DataSearchPanel})을 연다.</li>
 *     <li>Tasks &gt; Import Data… / Export Data…(SSMS 데이터베이스 Tasks 메뉴): 테이블에서 가져오기({@link DataImportWizard}),
 *     테이블·뷰 또는 그것들을 담은 스키마·폴더에서 내보내기({@link DataExportWizard}). Copy Tables…는 같은 원본을 다른 연결·데이터베이스로 복사한다
 *     ({@link DataExportWizard#openForCopy}). 세 명령은 데이터베이스 노드와 데이터베이스가 하나뿐인 연결 노드에서도 켜진다({@link #getTransferObject}).</li>
 *     <li>Tasks &gt; Back Up… / Restore…(SSMS 데이터베이스 Tasks 메뉴): 데이터베이스 백업·복원({@link BackupRestoreCommands}).</li>
 *     <li>보안({@link SecurityCommands}): 로그인·사용자·역할의 Properties와 Delete, Logins·Roles·Users 폴더의 New Login/Role/User…,
 *     테이블·뷰·시퀀스·루틴의 Permissions…</li>
 *     <li>SQL Server 에이전트({@link AgentCommands}): 에이전트 노드·폴더·작업·경고·운영자의 명령(그 노드에서만 보임), 작업·경고·운영자의
 *     Properties와 Delete, 두 번 클릭으로 작업 활동 모니터·에이전트 로그·속성 열기, 에이전트 노드의 서비스 상태 보조 글자.</li>
 *     <li>작업 스케줄러({@link SchedulerCommands}): Oracle 스케줄러·pg_cron·MySQL 이벤트의 명령(그 노드에서만 보임), 작업·이벤트의
 *     Properties와 Delete, 두 번 클릭으로 실행 기록·실행 중인 작업·속성 열기, 스케줄러 상태·사용 안 함 보조 글자.</li>
 *     <li>DB 객체 작업({@link ExplorerObjectCommands}): 객체 관리자로 하는 Delete(Delete 키)·Rename(F2)과 스크립트 미리 보기, Copy Name(Ctrl+C,
 *     트리의 전송 처리기)·Copy Qualified Name(Ctrl+Shift+C), Generate SQL 메뉴. 위의 연결·보안·에이전트·스케줄러 갈래가 먼저다.</li>
 *     <li>여러 개 선택: 트리는 여러 노드를 고를 수 있다. Delete, 이름 복사, Generate SQL은 선택 전체에 적용하고, 나머지 명령은
 *     {@link #getSelectedNavigatorNode}가 null이 되어 꺼진다. 오른쪽 클릭이 고른 행 위면 선택을 그대로 둔다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 C단계: "New Query" 메뉴, 선택한 연결 조회 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 D단계: 연결 명령 메뉴와 단축키, 탐색기 모델 이벤트로 트리 갱신 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 E단계: Select Top 1000 Rows, Script as(CREATE·SELECT), View DDL (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 * - 2026-11-06 디자인 적용: 아이콘 도구 막대(New Connection, Connect, Disconnect, Filter, Refresh, Collapse All), Filter·Collapse All 명령,
 *   아이콘 팩 노드 아이콘과 연결 상태 점, 보조 글자, 마우스 올림 표시 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 P1: 트리에 초점이 있을 때 F5 = 새로 고침(SSMS) (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 View DDL에 객체의 연결을 넘김(DDL 탭의 구문 강조가 연결의 SQL 방언을 따름) (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 * - 2026-11-06 Registered Servers: 연 서버만 보이는 평평한 목록(연결 폴더 노드 삭제), Disconnect로 목록에서 빼기, 도구 막대 Connect = 서버 고르기,
 *   New Connection으로 만든 서버 넣기 (docs/chromite/swing-registered-servers.md)
 * - 2026-11-06 관리 도구: Activity Monitor·Locks·Find Objects 메뉴, 노드까지 펼쳐 고르기(revealNode) (docs/chromite/swing-admin-tools.md)
 * - 2026-11-06 revealNode: 결과의 서버가 이 창에 없으면 먼저 넣고 서버 목록을 다시 읽은 뒤 펼침(연 서버만 보이는 목록) (docs/chromite/swing-admin-tools.md)
 * - 2026-11-06 데이터 전송: Tasks &gt; Import Data… / Export Data… 메뉴 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 고급 연결 설정: 연결 노드 바탕에 연결 색 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 연결 색을 노드 바탕 대신 이름 알약({@link ConnectionPill}, Registered Servers와 같음), 보조 글자를 이름의 실제 기준선에 맞춤
 *   (docs/chromite/swing-registered-servers.md "연결 색 알약")
 * - 2026-11-06 테이블 디자이너: Design·New Table… 메뉴, 다른 곳에서 추가·새로 읽은 DB 객체 노드 반영 (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 New Connection으로 만든 서버가 들어오지 않던 문제: 탐색기 노드가 생기기 전(레지스트리 이벤트 지연)에도 목록에 넣고,
 *   고를 서버를 연결로 기억해 ADD 이벤트에서 고름 (docs/chromite/swing-registered-servers.md)
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-06 ER 다이어그램: View Diagram 메뉴, Select Top 행 수를 다이어그램 메뉴와 같이 씀 (docs/chromite/swing-erd.md)
 * - 2026-11-06 개체 정의 편집: Modify, Script as &gt; ALTER To, New View/Procedure/Function 메뉴 (docs/chromite/swing-source-editor.md)
 * - 2026-11-06 백업·복원: Tasks &gt; Back Up… / Restore… 메뉴 (docs/chromite/swing-backup-restore.md)
 * - 2026-11-06 테이블 복사: Tasks &gt; Copy Tables… 메뉴, 내보내기 마법사에 쿼리 창 열기를 넘김 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 보안 편집기: Properties·New Login/Role/User…·Permissions… 메뉴, 주체 Delete, 실행 뒤 폴더 새로 고침(refreshNavigatorNode)
 *   (docs/chromite/swing-security.md)
 * - 2026-11-06 Import Data… / Export Data… / Copy Tables…를 데이터베이스 노드와 데이터베이스가 하나인 연결 노드에서도 켬, 도구 설명
 *   (docs/chromite/swing-data-transfer.md "데이터베이스 노드")
 * - 2026-11-07 밀도 B: 치수를 테마의 Chromite.size.* 키({@link UiSizes})에서 읽음 (docs/chromite/design-spec.md "치수 토큰")
 * - 2026-11-07 탐색 불가 항목(프로시저·함수 매개 변수, MySQL/MariaDB 시스템 정보 폴더의 변수·상태·엔진 등)을 트리에 보여 줌,
 *   매개 변수 뒤 보조 글자는 형식 (docs/chromite/swing-frontend.md "Object Explorer 탐색 불가 항목")
 * - 2026-11-07 서버 대시보드: Server Dashboard 메뉴(Activity Monitor의 개요 탭) (docs/chromite/swing-dashboard.md)
 * - 2026-11-07 도구 막대를 {@link OverflowToolBar}로: 좁힌 도구 창에서 들어가지 않는 단추는 넘침 메뉴로 (docs/chromite/swing-frontend.md "도구 창 최소 폭")
 * - 2026-11-07 Disk Usage·Server Logs 명령(Activity Monitor의 탭, {@link AdminViewCommands}) (docs/chromite/swing-admin-tools.md)
 * - 2026-11-07 테이블 저장 방식(메모리/디스크/휘발성) 글자색과 툴팁({@link TableStorageStyle}) (docs/chromite/altibase.md)
 * - 2026-11-07 SQL Server 에이전트: 에이전트 메뉴 항목, Properties·Delete 갈래, 두 번 클릭, 보조 글자, 도구 문서 열기
 *   ({@link QueryWindows#openToolDocument}) (docs/chromite/swing-sqlserver-agent.md)
 * - 2026-11-07 Maintenance… 명령(테이블·인덱스·스키마·데이터베이스, {@link MaintenanceCommands}) (docs/chromite/swing-admin-tools.md)
 * - 2026-11-07 작업 스케줄러(Oracle 스케줄러, pg_cron, MySQL 이벤트): 메뉴 항목, Properties·Delete 갈래, 두 번 클릭, 보조 글자
 *   (docs/chromite/swing-job-schedulers.md)
 * - 2026-11-07 Disconnect를 열린 트랜잭션 때문에 취소하면 서버를 창에서 빼지 않음 (docs/chromite/idle-transactions.md "연결 끊기 전 질문")
 * - 2026-11-07 함수·프로시저의 Debug… 메뉴({@link DebugCommands}, docs/chromite/procedure-debugger.md)
 * - 2026-11-07 DB 객체 Delete·Rename, Copy Name·Copy Qualified Name, Generate SQL 메뉴, 여러 개 선택({@link ExplorerObjectCommands})
 *   (docs/chromite/swing-explorer-object-actions.md)
 * - 2026-11-08 테이블 잘라내기·트리거 사용/사용 안 함·구체화된 뷰 새로 고침 메뉴({@link ObjectMaintenanceCommands})
 *   (docs/chromite/swing-explorer-object-actions.md "유지 관리 명령")
 * - 2026-11-08 SQL Server 프로시저의 Trace Execution… 메뉴({@link ExecutionTraceCommands}, docs/chromite/sqlserver-execution-trace.md)
 * - 2026-11-08 Search Data… 메뉴(여러 테이블의 값 찾기, {@link DataSearchPanel}) (docs/chromite/swing-data-search.md)
 * - 2026-11-08 Compare Schemas… 메뉴({@link SchemaComparePanel}, docs/chromite/schema-compare.md)
 * - 2026-11-08 연결 노드의 Change Password… 메뉴(연결한 사용자의 비밀번호 바꾸기, docs/chromite/swing-conn-advanced.md "비밀번호 바꾸기")
 */
final class ObjectExplorerPanel extends JPanel {

    private static final Log log = Log.getLog(ObjectExplorerPanel.class);

    private static final String LOADING_TEXT = CoreMessages.common_loading;
    /**
     * Rows of "Select Top N Rows", as SSMS.
     */
    static final int SELECT_TOP_ROWS = 1000;

    /**
     * The main window side: document tabs for queries, scripts and tools.
     */
    interface QueryWindows {
        /**
         * Opens an empty query window for the connection ("New Query").
         */
        void openQuery(@NotNull DBPDataSourceContainer dataSource);

        /**
         * Opens a query window with a generated script.
         *
         * @param maxRows rows the window fetches at least (0: the connection's maximum)
         * @param execute run the script right away
         */
        void openScript(@NotNull DBPDataSourceContainer dataSource, @NotNull String script, long maxRows, boolean execute);

        /**
         * Shows an object definition in a read-only tab ("View DDL").
         *
         * @param dataSource connection of the object (its SQL dialect highlights the text)
         */
        void showDdl(@Nullable DBPDataSourceContainer dataSource, @NotNull String objectName, @NotNull String ddl);

        /**
         * Opens (or shows) the Activity Monitor of the connection at a tab ({@link ActivityMonitorPanel#OVERVIEW_TAB},
         * {@link ActivityMonitorPanel#PROCESSES_TAB}, {@link ActivityMonitorPanel#LOCKS_TAB}).
         */
        void openActivityMonitor(@NotNull DBPDataSourceContainer dataSource, int tab);

        /**
         * Opens the Find Objects tab for the selected node (its connection and schema), or for no node.
         */
        void openObjectSearch(@Nullable DBNNode selectedNode);

        /**
         * Opens the table designer of a table ("Design").
         */
        void designTable(@NotNull DBSEntity table);

        /**
         * Opens the table designer for a new table in a "Tables" folder ("New Table").
         */
        void newTable(@NotNull DBNDatabaseFolder folder);

        /**
         * Opens the ER diagram of a schema (its tables) or of a table (with the related tables) ("View Diagram").
         */
        void viewDiagram(@NotNull DBSObject root);

        /**
         * Opens a query window with an object's ALTER script or a new object's template ("Modify", "New View").
         *
         * @param refreshNode node read again from the database after each run of the window (the object, or the folder)
         */
        void openSourceScript(@NotNull DBPDataSourceContainer dataSource, @NotNull String script, @NotNull DBNDatabaseNode refreshNode);

        /**
         * Shows the document tab with a key, creating it first if none is open (SQL Server Agent job activity, history, log).
         *
         * @return the open or created document
         */
        @NotNull
        ToolDocument openToolDocument(@NotNull String key, @NotNull Supplier<ToolDocument> factory);
    }

    private final DefaultTreeModel treeModel;
    private final JTree tree;
    private final ExplorerNode rootNode;
    @Nullable
    private final DBNProject projectNode;
    /**
     * The servers opened in this window (null without an active project).
     */
    @Nullable
    private final ObjectExplorerServers servers;
    private final Action connectServerAction;
    private final Action refreshAction;
    private final Action collapseAllAction;
    private final Action filterAction;
    private final Action newQueryAction;
    private final Action newConnectionAction;
    private final Action connectAction;
    private final Action disconnectAction;
    private final Action reconnectAction;
    private final Action editConnectionAction;
    private final Action changePasswordAction;
    private final Action renameAction;
    private final Action deleteAction;
    private final Action selectTopAction;
    private final Action viewDdlAction;
    private final Action activityMonitorAction;
    private final Action locksAction;
    private final Action dashboardAction;
    private final List<Action> adminViewActions;
    private final Action maintenanceAction;
    private final Action findObjectsAction;
    private final Action searchDataAction;
    private final Action compareSchemasAction;
    private final Action designAction;
    private final Action newTableAction;
    private final Action viewDiagramAction;
    private final Action debugAction;
    private final Action traceAction;
    private final Action principalPropertiesAction;
    private final Action newPrincipalAction;
    private final Action objectPermissionsAction;
    private final JMenu scriptAsMenu;
    private final JMenu createToMenu;
    private final JMenu selectToMenu;
    private final JMenu tasksMenu;
    private final Action importDataAction;
    private final Action exportDataAction;
    private final SourceEditCommands sourceEditCommands;
    private final AgentCommands agentCommands;
    private final SchedulerCommands schedulerCommands;
    private final ExplorerObjectCommands objectCommands;
    private final ObjectMaintenanceCommands maintenanceCommands;
    private final Action backUpAction;
    private final Action restoreAction;
    private final Action copyTablesAction;
    private final INavigatorListener navigatorListener = this::navigatorNodeChanged;
    /**
     * A connection to select once the connection list has been read again (a connection created in this window). The
     * connection, not its navigator node: a just created connection has no node until the registry's delayed
     * {@code OBJECT_ADD} event reaches the navigator model, and the {@code ADD} event then reads the list again.
     */
    @Nullable
    private DBPDataSourceContainer pendingReveal;
    /**
     * Expand {@link #pendingReveal} too (it connects): a server opened from Registered Servers or the Connect chooser.
     */
    private boolean expandReveal;
    /**
     * A node to expand down to and select once the children on its path have been read ({@link #revealNode}).
     */
    @Nullable
    private DBNNode revealTarget;
    private boolean disposed;

    /**
     * @param selectionListener receives the selected navigator node (or null)
     * @param queryWindows      opens query windows ("New Query", scripts) and DDL tabs
     */
    ObjectExplorerPanel(@NotNull Consumer<DBNNode> selectionListener, @NotNull QueryWindows queryWindows) {
        super(new BorderLayout());

        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        projectNode = project == null ? null :
            DBWorkbench.getPlatform().getNavigatorModel().getRoot().getProjectNode(project);
        servers = projectNode == null ? null : new ObjectExplorerServers(project.getDataSourceRegistry());
        rootNode = new ExplorerNode(projectNode != null ? projectNode : CoreMessages.common_no_active_project);
        treeModel = new DefaultTreeModel(rootNode);

        tree = new ExplorerTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new ExplorerCellRenderer());
        // Several objects for Delete, Copy Name and Generate SQL; the other commands need one node (getSelectedNavigatorNode)
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.putClientProperty(FlatClientProperties.TREE_WIDE_SELECTION, true);
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof ExplorerNode node && node.state == LoadState.NOT_LOADED) {
                    loadChildren(node, false);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.addTreeSelectionListener(e -> selectionListener.accept(getSelectedNavigatorNode()));

        refreshAction = new AbstractAction(CoreMessages.explorer_refresh) {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshSelection();
            }
        };
        refreshAction.putValue(Action.SHORT_DESCRIPTION, CoreMessages.explorer_refresh_tip);
        refreshAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        collapseAllAction = createAction(CoreMessages.explorer_collapse_all, () -> {
            for (int row = tree.getRowCount() - 1; row >= 0; row--) {
                tree.collapseRow(row);
            }
        });
        filterAction = createAction(CoreMessages.explorer_filter, this::filterSelectedFolder);
        filterAction.putValue(Action.SHORT_DESCRIPTION, CoreMessages.explorer_filter_tip);
        newQueryAction = new AbstractAction(CoreMessages.main_new_query) {
            @Override
            public void actionPerformed(ActionEvent e) {
                DBPDataSourceContainer dataSource = getSelectedDataSource();
                if (dataSource != null) {
                    queryWindows.openQuery(dataSource);
                }
            }
        };
        selectTopAction = createAction(NLS.bind(CoreMessages.explorer_select_top, SELECT_TOP_ROWS), () -> {
            DBPDataSourceContainer dataSource = getSelectedDataSource();
            if (getSelectedObject() instanceof DBSEntity entity && dataSource != null) {
                String script = generate(NLS.bind(CoreMessages.explorer_select_top, SELECT_TOP_ROWS),
                    monitor -> ObjectScripts.generateSelect(monitor, entity, SELECT_TOP_ROWS));
                if (script != null) {
                    queryWindows.openScript(dataSource, script, SELECT_TOP_ROWS, true);
                }
            }
        });
        viewDdlAction = createAction(CoreMessages.explorer_view_ddl, () -> {
            DBSObject object = getSelectedObject();
            if (object != null) {
                String ddl = generate(CoreMessages.explorer_view_ddl, monitor -> ObjectScripts.generateCreate(monitor, object));
                if (ddl != null) {
                    queryWindows.showDdl(getSelectedDataSource(), DBUtils.getObjectFullName(object, DBPEvaluationContext.UI), ddl);
                }
            }
        });
        activityMonitorAction = createAction(CoreMessages.main_activity_monitor, () -> runOnSelectedDataSource(
            dataSource -> queryWindows.openActivityMonitor(dataSource, ActivityMonitorPanel.PROCESSES_TAB)));
        activityMonitorAction.putValue(Action.SHORT_DESCRIPTION, CoreMessages.explorer_activity_monitor_tip);
        locksAction = createAction(CoreMessages.main_locks, () -> runOnSelectedDataSource(
            dataSource -> queryWindows.openActivityMonitor(dataSource, ActivityMonitorPanel.LOCKS_TAB)));
        locksAction.putValue(Action.SHORT_DESCRIPTION, CoreMessages.explorer_locks_tip);
        dashboardAction = createAction(DashboardMessages.dashboard_command, () -> runOnSelectedDataSource(
            dataSource -> queryWindows.openActivityMonitor(dataSource, ActivityMonitorPanel.OVERVIEW_TAB)));
        dashboardAction.putValue(Action.SHORT_DESCRIPTION, DashboardMessages.dashboard_command_tip);
        adminViewActions = AdminViewCommands.createActions(tab -> runOnSelectedDataSource(dataSource -> queryWindows.openActivityMonitor(dataSource, tab)));
        findObjectsAction = createAction(CoreMessages.main_find_objects, () -> queryWindows.openObjectSearch(getSelectedNavigatorNode()));
        findObjectsAction.putValue(Action.SHORT_DESCRIPTION, CoreMessages.explorer_find_objects_tip);
        searchDataAction = createAction(DataSearchMessages.datasearch_command, () -> {
            DataSearchPanel.Scope scope = DataSearchPanel.findScope(getSelectedNavigatorNodes());
            if (scope != null) {
                queryWindows.openToolDocument(scope.key(), () -> new DataSearchPanel(scope, queryWindows));
            }
        });
        searchDataAction.putValue(Action.SHORT_DESCRIPTION, DataSearchMessages.datasearch_command_tip);
        compareSchemasAction = createAction(CompareMessages.compare_command, () -> SchemaComparePanel.open(queryWindows, getSelectedNavigatorNode()));
        compareSchemasAction.putValue(Action.SHORT_DESCRIPTION, CompareMessages.compare_command_tip);
        designAction = createAction(CoreMessages.explorer_design, () -> {
            if (getSelectedObject() instanceof DBSEntity table) {
                queryWindows.designTable(table);
            }
        });
        designAction.putValue(Action.SHORT_DESCRIPTION, CoreMessages.explorer_design_tip);
        newTableAction = createAction(CoreMessages.explorer_new_table, () -> {
            if (getSelectedNavigatorNode() instanceof DBNDatabaseFolder folder) {
                queryWindows.newTable(folder);
            }
        });
        viewDiagramAction = createAction(ErdMessages.erd_view_diagram, () -> {
            DBSObject object = getSelectedObject();
            if (object != null) {
                queryWindows.viewDiagram(object);
            }
        });
        viewDiagramAction.putValue(Action.SHORT_DESCRIPTION, ErdMessages.erd_view_diagram_tip);
        agentCommands = new AgentCommands(this::getSelectedNavigatorNode, this, queryWindows);
        schedulerCommands = new SchedulerCommands(this::getSelectedNavigatorNode, this, queryWindows);
        principalPropertiesAction = createAction(SecurityMessages.security_properties, () -> {
            DBSObject object = getSelectedObject();
            if (AgentCommands.canOpenProperties(getSelectedNavigatorNode())) {
                agentCommands.openProperties(getSelectedNavigatorNode());
            } else if (SchedulerCommands.canOpenProperties(getSelectedNavigatorNode())) {
                schedulerCommands.openProperties(getSelectedNavigatorNode());
            } else if (object != null) {
                SecurityCommands.openProperties(this, object, queryWindows);
            }
        });
        principalPropertiesAction.putValue(Action.SHORT_DESCRIPTION, SecurityMessages.security_properties_tip);
        newPrincipalAction = createAction(SecurityMessages.security_new_login, () -> {
            if (getSelectedNavigatorNode() instanceof DBNDatabaseFolder folder) {
                SecurityCommands.newPrincipal(this, folder, queryWindows);
            }
        });
        objectPermissionsAction = createAction(SecurityMessages.security_permissions, () -> {
            DBSObject object = getSelectedObject();
            if (object != null) {
                SecurityCommands.openObjectPermissions(this, object, queryWindows);
            }
        });
        objectPermissionsAction.putValue(Action.SHORT_DESCRIPTION, SecurityMessages.security_permissions_tip);
        createToMenu = new JMenu(CoreMessages.explorer_create_to);
        createToMenu.add(createAction(CoreMessages.explorer_new_query_window, () -> scriptSelected(queryWindows, false, false)));
        createToMenu.add(createAction(CoreMessages.explorer_clipboard, () -> scriptSelected(queryWindows, false, true)));
        selectToMenu = new JMenu(CoreMessages.explorer_select_to);
        selectToMenu.add(createAction(CoreMessages.explorer_new_query_window, () -> scriptSelected(queryWindows, true, false)));
        selectToMenu.add(createAction(CoreMessages.explorer_clipboard, () -> scriptSelected(queryWindows, true, true)));
        sourceEditCommands = new SourceEditCommands(this::getSelectedNavigatorNode, queryWindows);
        debugAction = DebugCommands.createAction(this, this::getSelectedObject, queryWindows);
        traceAction = ExecutionTraceCommands.createAction(this, this::getSelectedObject, queryWindows);
        objectCommands = new ExplorerObjectCommands(this, this::getSelectedNavigatorNodes, queryWindows);
        tree.setTransferHandler(objectCommands.createTransferHandler());
        maintenanceCommands = new ObjectMaintenanceCommands(this, this::getSelectedNavigatorNodes, queryWindows);
        scriptAsMenu = new JMenu(CoreMessages.explorer_script_as);
        scriptAsMenu.add(createToMenu);
        scriptAsMenu.add(sourceEditCommands.getAlterToMenu());
        scriptAsMenu.add(selectToMenu);
        importDataAction = createAction(CoreMessages.explorer_import_data, () -> {
            DBSObject object = getTransferObject();
            if (object != null) {
                DataImportWizard.open(this, object);
            }
        });
        exportDataAction = createAction(CoreMessages.explorer_export_data, () -> {
            DBSObject object = getTransferObject();
            if (object != null) {
                DataExportWizard.openForObject(this, object, queryWindows);
            }
        });
        copyTablesAction = createAction(DbCopyMessages.explorer_copy_tables, () -> {
            DBSObject object = getTransferObject();
            if (object != null) {
                DataExportWizard.openForCopy(this, object, queryWindows);
            }
        });
        backUpAction = createAction(BackupRestoreMessages.backup_menu, () -> {
            DBSObject object = getSelectedObject();
            if (object != null) {
                BackupRestoreCommands.backUp(this, object, queryWindows);
            }
        });
        restoreAction = createAction(BackupRestoreMessages.restore_menu, () -> {
            DBSObject object = getSelectedObject();
            if (object != null) {
                BackupRestoreCommands.restore(this, object, queryWindows);
            }
        });
        maintenanceAction = MaintenanceCommands.createAction(this, this::getSelectedObject);
        tasksMenu = new JMenu(CoreMessages.explorer_tasks);
        tasksMenu.add(backUpAction);
        tasksMenu.add(restoreAction);
        tasksMenu.addSeparator();
        tasksMenu.add(importDataAction);
        tasksMenu.add(exportDataAction);
        tasksMenu.add(copyTablesAction);
        newConnectionAction = createAction(CoreMessages.explorer_new_connection, () -> {
            DBPDataSourceContainer created = ConnectionCommands.newConnection(this, null);
            if (created != null) {
                // The dialog connects it; only add and select it here
                showServer(created, false);
            }
        });
        newConnectionAction.putValue(Action.SHORT_DESCRIPTION, CoreMessages.explorer_new_connection_tip);
        connectServerAction = createAction(CoreMessages.explorer_connect_server, () -> {
            DBPDataSourceContainer chosen = projectNode == null ? null
                : RegisteredServerCommands.chooseServer(this, projectNode.getDatabases().getDataSourceRegistry());
            if (chosen != null) {
                showServer(chosen, true);
            }
        });
        connectServerAction.putValue(Action.SHORT_DESCRIPTION, CoreMessages.explorer_connect_server_tip);
        connectAction = createAction(CoreMessages.explorer_connect, () -> runOnSelectedDataSource(ConnectionCommands::connect));
        disconnectAction = createAction(CoreMessages.explorer_disconnect, () -> runOnSelectedDataSource(this::disconnectServer));
        disconnectAction.putValue(Action.SHORT_DESCRIPTION, CoreMessages.explorer_disconnect_tip);
        reconnectAction = createAction(CoreMessages.explorer_reconnect, () -> runOnSelectedDataSource(ConnectionCommands::reconnect));
        editConnectionAction = createAction(CoreMessages.explorer_edit_connection, () -> {
            if (getSelectedNavigatorNode() instanceof DBNDataSource node) {
                ConnectionCommands.edit(this, node.getDataSourceContainer());
            }
        });
        editConnectionAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        changePasswordAction = createAction(ConnectionMessages.commands_change_password_menu, () -> runOnSelectedDataSource(ConnectionCommands::changePassword));
        renameAction = createAction(CoreMessages.explorer_rename, () -> {
            if (getSelectedNavigatorNode() instanceof DBNDataSource node) {
                ConnectionCommands.rename(this, node);
            } else if (ExplorerObjectCommands.canRename(getSelectedNavigatorNode())) {
                objectCommands.rename(getSelectedNavigatorNode());
            }
        });
        renameAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0));
        deleteAction = createAction(CoreMessages.explorer_delete, () -> {
            if (getSelectedNavigatorNode() instanceof DBNDataSource node) {
                ConnectionCommands.delete(this, node.getDataSourceContainer());
            } else if (SecurityCommands.canOpenProperties(getSelectedObject())) {
                SecurityCommands.deletePrincipal(this, getSelectedObject(), queryWindows);
            } else if (AgentCommands.canDelete(getSelectedNavigatorNode())) {
                agentCommands.delete(getSelectedNavigatorNode());
            } else if (SchedulerCommands.canDelete(getSelectedNavigatorNode())) {
                schedulerCommands.delete(getSelectedNavigatorNode());
            } else if (ExplorerObjectCommands.canDelete(getSelectedNavigatorNodes())) {
                // Database objects through their object managers, also several selected ones
                objectCommands.delete(getSelectedNavigatorNodes());
            }
        });
        deleteAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
        updateActions();
        tree.addTreeSelectionListener(e -> updateActions());

        // Keys of the connection commands (shown in the menu); Enter on other nodes keeps expanding/collapsing them
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.editOrToggle", () -> { //$NON-NLS-1$
            if (getSelectedNavigatorNode() instanceof DBNDataSource) {
                editConnectionAction.actionPerformed(null);
            } else {
                TreePath path = tree.getSelectionPath();
                if (path != null) {
                    if (tree.isExpanded(path)) {
                        tree.collapsePath(path);
                    } else {
                        tree.expandPath(path);
                    }
                }
            }
        });
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "chromite.rename", () -> renameAction.actionPerformed(null)); //$NON-NLS-1$
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "chromite.delete", () -> deleteAction.actionPerformed(null)); //$NON-NLS-1$
        // Ctrl+C is the tree's own copy key (the transfer handler copies the names)
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "chromite.copyQualifiedName", //$NON-NLS-1$
            () -> objectCommands.getCopyQualifiedNameAction().actionPerformed(null));
        // SSMS: F5 in Object Explorer refreshes (elsewhere it executes the query, Query menu)
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "chromite.refresh", this::refreshSelection); //$NON-NLS-1$

        JPopupMenu popupMenu = new JPopupMenu();
        popupMenu.add(newConnectionAction);
        popupMenu.addSeparator();
        popupMenu.add(newQueryAction);
        popupMenu.add(newTableAction);
        popupMenu.add(sourceEditCommands.getNewViewAction());
        popupMenu.add(sourceEditCommands.getNewProcedureAction());
        popupMenu.add(sourceEditCommands.getNewFunctionAction());
        popupMenu.add(newPrincipalAction);
        agentCommands.addTo(popupMenu);
        schedulerCommands.addTo(popupMenu);
        popupMenu.add(designAction);
        popupMenu.add(sourceEditCommands.getModifyAction());
        popupMenu.add(debugAction);
        popupMenu.add(traceAction);
        popupMenu.add(objectPermissionsAction);
        popupMenu.add(selectTopAction);
        popupMenu.add(scriptAsMenu);
        popupMenu.add(objectCommands.getGenerateSqlMenu());
        popupMenu.add(viewDdlAction);
        popupMenu.add(viewDiagramAction);
        popupMenu.add(tasksMenu);
        popupMenu.add(maintenanceAction);
        maintenanceCommands.addTo(popupMenu);
        popupMenu.addSeparator();
        popupMenu.add(connectAction);
        popupMenu.add(disconnectAction);
        popupMenu.add(reconnectAction);
        popupMenu.addSeparator();
        popupMenu.add(activityMonitorAction);
        popupMenu.add(locksAction);
        popupMenu.add(dashboardAction);
        adminViewActions.forEach(popupMenu::add);
        popupMenu.add(findObjectsAction);
        popupMenu.add(searchDataAction);
        popupMenu.add(compareSchemasAction);
        popupMenu.addSeparator();
        popupMenu.add(editConnectionAction);
        popupMenu.add(changePasswordAction);
        popupMenu.add(objectCommands.getCopyNameAction());
        popupMenu.add(objectCommands.getCopyQualifiedNameAction());
        popupMenu.add(renameAction);
        popupMenu.add(deleteAction);
        popupMenu.addSeparator();
        popupMenu.add(filterAction);
        popupMenu.add(refreshAction);
        popupMenu.add(principalPropertiesAction);
        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // The connection state may have changed since the selection did
                updateActions();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        tree.setComponentPopupMenu(popupMenu);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // The context menu acts on the row under the mouse, as in SSMS; on empty space it offers New Connection
                if (SwingUtilities.isRightMouseButton(e)) {
                    TreePath path = tree.getClosestPathForLocation(e.getX(), e.getY());
                    if (path != null && tree.getPathBounds(path) != null && tree.getPathBounds(path).contains(e.getPoint())) {
                        // A row of a multi-selection keeps the selection (Delete, Copy Name, Generate SQL act on all of it)
                        if (!tree.isPathSelected(path)) {
                            tree.setSelectionPath(path);
                        }
                    } else {
                        tree.clearSelection();
                    }
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // SQL Server Agent leaves open on double click (job activity, agent log, job properties)
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    agentCommands.openDefault(getSelectedNavigatorNode());
                    // Job scheduler leaves and objects (run history, running jobs, job and event properties)
                    schedulerCommands.openDefault(getSelectedNavigatorNode());
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        add(createToolBar(), BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        if (projectNode != null) {
            DBWorkbench.getPlatform().getNavigatorModel().addListener(navigatorListener);
            loadChildren(rootNode, false);
        }
    }

    /**
     * Stops listening to the navigator model (the main window is closing).
     */
    void dispose() {
        if (projectNode != null && !disposed) {
            disposed = true;
            DBWorkbench.getPlatform().getNavigatorModel().removeListener(navigatorListener);
        }
    }

    @NotNull
    Action getNewConnectionAction() {
        return newConnectionAction;
    }

    /**
     * Adds a server to this window (at the end, as SSMS) if it is not there yet, then selects it.
     *
     * A connection just created (New Connection) has no navigator node yet: the registry sends its {@code OBJECT_ADD}
     * event from a delayed job ({@code DataSourceRegistry#notifyDataSourceListeners}), so it is listed and selected when
     * the navigator's {@code ADD} event reads the list again ({@link #navigatorNodeChanged}).
     *
     * @param expand also expand it, which connects it (Registered Servers "Object Explorer", Connect)
     */
    void showServer(@NotNull DBPDataSourceContainer dataSource, boolean expand) {
        if (projectNode == null || servers == null) {
            return;
        }
        servers.add(dataSource);
        pendingReveal = dataSource;
        expandReveal = expand;
        reloadConnectionList();
    }

    /**
     * Object Explorer Disconnect (SSMS): disconnects the server and removes it from this window.
     */
    private void disconnectServer(@NotNull DBPDataSourceContainer dataSource) {
        if (!ConnectionCommands.disconnect(dataSource)) {
            // Canceled because of an open transaction: the server stays connected and listed
            return;
        }
        if (servers != null && servers.remove(dataSource)) {
            reloadConnectionList();
        }
    }

    @NotNull
    private static Action createAction(@NotNull String name, @NotNull Runnable task) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
    }

    private void bindKey(@NotNull KeyStroke keyStroke, @NotNull String actionKey, @NotNull Runnable task) {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionKey);
        tree.getActionMap().put(actionKey, createAction(actionKey, task));
    }

    /**
     * "Script as > CREATE To / SELECT To": a new query window with the script, or the clipboard.
     */
    private void scriptSelected(@NotNull QueryWindows queryWindows, boolean select, boolean toClipboard) {
        DBSObject object = getSelectedObject();
        DBPDataSourceContainer dataSource = getSelectedDataSource();
        if (object == null || dataSource == null) {
            return;
        }
        String script = select
            ? (object instanceof DBSEntity entity ? generate(CoreMessages.explorer_script_select, monitor -> ObjectScripts.generateSelect(monitor, entity, 0)) : null)
            : generate(CoreMessages.explorer_script_create, monitor -> ObjectScripts.generateCreate(monitor, object));
        if (script == null) {
            return;
        }
        if (toClipboard) {
            DBWorkbench.getPlatformUI().copyTextToClipboard(script, false);
        } else {
            queryWindows.openScript(dataSource, script, 0, false);
        }
    }

    /**
     * Generates SQL in a job, waiting in a progress dialog (it reads metadata). Errors are shown; null if it
     * failed or was canceled.
     */
    @Nullable
    private String generate(@NotNull String title, @NotNull DBRRunnableWithReturn<String> generator) {
        try {
            return ProgressDialog.runTask(title, generator);
        } catch (DBInterruptedException e) {
            return null;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, CoreMessages.explorer_cant_generate, e);
            return null;
        }
    }

    /**
     * The database object of the selected node; null for connections and folders.
     */
    @Nullable
    private DBSObject getSelectedObject() {
        return getSelectedNavigatorNode() instanceof DBNDatabaseNode databaseNode && !(databaseNode instanceof DBNDataSource)
            ? databaseNode.getObject() : null;
    }

    /**
     * The object of Import Data…, Export Data… and Copy Tables…: the selected object, or the only database of a selected
     * connection (null when it has several or they are not read yet, see {@link DataTransferObjects#findConnectionDatabase}).
     */
    @Nullable
    private DBSObject getTransferObject() {
        DBSObject object = getSelectedObject();
        if (object == null && getSelectedNavigatorNode() instanceof DBNDataSource connection) {
            return DataTransferObjects.findConnectionDatabase(connection);
        }
        return object;
    }

    private void runOnSelectedDataSource(@NotNull Consumer<DBPDataSourceContainer> command) {
        DBPDataSourceContainer dataSource = getSelectedDataSource();
        if (dataSource != null) {
            command.accept(dataSource);
        }
    }

    private void updateActions() {
        DBPDataSourceContainer dataSource = getSelectedDataSource();
        boolean connectionNode = getSelectedNavigatorNode() instanceof DBNDataSource;
        boolean connected = dataSource != null && dataSource.isConnected();
        newConnectionAction.setEnabled(projectNode != null);
        connectServerAction.setEnabled(projectNode != null);
        newQueryAction.setEnabled(dataSource != null);
        DBSObject object = getSelectedObject();
        boolean canScript = ObjectScripts.canScriptCreate(object);
        boolean canSelect = ObjectScripts.canSelect(object);
        selectTopAction.setEnabled(canSelect);
        createToMenu.setEnabled(canScript);
        selectToMenu.setEnabled(canSelect);
        scriptAsMenu.setEnabled(canScript || canSelect);
        viewDdlAction.setEnabled(canScript);
        viewDiagramAction.setEnabled(ErdDiagramModel.canShow(object));
        DBSObject transferObject = getTransferObject();
        importDataAction.setEnabled(DataImportWizard.canImport(transferObject));
        exportDataAction.setEnabled(DataExportWizard.canExport(transferObject));
        backUpAction.setEnabled(BackupRestoreCommands.canBackUp(object));
        restoreAction.setEnabled(backUpAction.isEnabled());
        copyTablesAction.setEnabled(exportDataAction.isEnabled());
        DataTransferObjects.updateTaskTooltips(transferObject, importDataAction, exportDataAction, copyTablesAction);
        tasksMenu.setEnabled(importDataAction.isEnabled() || exportDataAction.isEnabled() || backUpAction.isEnabled());
        maintenanceAction.setEnabled(MaintenanceCommands.canMaintain(object));
        designAction.setEnabled(TableDesignSession.canDesign(object));
        newTableAction.setEnabled(getSelectedNavigatorNode() instanceof DBNDatabaseFolder folder && TableDesignSession.canCreateTable(folder));
        sourceEditCommands.update(getSelectedNavigatorNode());
        debugAction.setEnabled(DebugCommands.canDebug(object));
        traceAction.setEnabled(ExecutionTraceCommands.canTrace(object));
        connectAction.setEnabled(dataSource != null && !connected);
        // Also for a server that is no longer connected: Disconnect removes it from this window
        disconnectAction.setEnabled(dataSource != null);
        reconnectAction.setEnabled(connected);
        activityMonitorAction.setEnabled(dataSource != null);
        locksAction.setEnabled(dataSource != null);
        dashboardAction.setEnabled(dataSource != null);
        adminViewActions.forEach(action -> action.setEnabled(dataSource != null));
        findObjectsAction.setEnabled(projectNode != null);
        searchDataAction.setEnabled(DataSearchPanel.findScope(getSelectedNavigatorNodes()) != null);
        compareSchemasAction.setEnabled(projectNode != null);
        editConnectionAction.setEnabled(connectionNode);
        // Only databases that can change the connected user's password (ChangeUserPasswordPropertyTester)
        changePasswordAction.setEnabled(connectionNode && dataSource != null && ConnectionCommands.getPasswordManager(dataSource) != null);
        renameAction.setEnabled(connectionNode || ExplorerObjectCommands.canRename(getSelectedNavigatorNode()));
        boolean principal = SecurityCommands.canOpenProperties(object);
        boolean agentObject = AgentCommands.canDelete(getSelectedNavigatorNode());
        boolean schedulerObject = SchedulerCommands.canDelete(getSelectedNavigatorNode());
        deleteAction.setEnabled(connectionNode || principal || agentObject || schedulerObject
            || ExplorerObjectCommands.canDelete(getSelectedNavigatorNodes()));
        objectCommands.update(getSelectedNavigatorNodes());
        maintenanceCommands.update(getSelectedNavigatorNodes());
        principalPropertiesAction.setEnabled(principal || agentObject || schedulerObject);
        principalPropertiesAction.putValue(Action.SHORT_DESCRIPTION, agentObject ? AgentMessages.agent_properties_tip
            : schedulerObject ? SchedulerMessages.scheduler_properties_tip : SecurityMessages.security_properties_tip);
        agentCommands.update(getSelectedNavigatorNode());
        schedulerCommands.update(getSelectedNavigatorNode());
        SecurityPrincipal.Kind newPrincipalKind = SecurityCommands.getNewPrincipalKind(getSelectedNavigatorNode());
        newPrincipalAction.putValue(Action.NAME, SecurityCommands.getNewPrincipalLabel(newPrincipalKind));
        newPrincipalAction.setEnabled(newPrincipalKind != null);
        objectPermissionsAction.setEnabled(SecurityCommands.canEditPermissions(object));
        filterAction.setEnabled(getSelectedNavigatorNode() instanceof DBNDatabaseFolder folder && folder.getItemsMeta() != null);
    }

    /**
     * SSMS Object Explorer tool bar below the tool window header (32 high: 26px icon buttons + 3 above and below):
     * New Connection, Connect, Disconnect | Filter, Refresh | Collapse All. The buttons that don't fit a narrow tool window
     * go to the overflow menu.
     */
    @NotNull
    private JComponent createToolBar() {
        JToolBar toolBar = new OverflowToolBar();
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.TOOL_WINDOW_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createActionButton(newConnectionAction, "new-connection")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(connectServerAction, "connect")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(disconnectAction, "disconnect")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(filterAction, "filter")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(refreshAction, "refresh")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(collapseAllAction, "collapse-all")); //$NON-NLS-1$
        return toolBar;
    }

    /**
     * Filter (SSMS Object Explorer filter): shows only the objects of the selected folder whose names match a mask.
     * The filter is the connection's navigator filter of that object type (saved with the connection, like the SWT
     * navigator filters); a text without {@code %} matches names that contain it, an empty text removes the filter.
     */
    private void filterSelectedFolder() {
        TreePath path = tree.getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof ExplorerNode node)
            || !(node.getUserObject() instanceof DBNDatabaseFolder folder)) {
            return;
        }
        DBXTreeItem itemsMeta = folder.getItemsMeta();
        if (itemsMeta == null) {
            return;
        }
        DBSObjectFilter current = folder.getNodeFilter(itemsMeta, true);
        String currentMask = current != null && current.isEnabled() && current.hasSingleMask() ? current.getSingleMask() : "";
        Object input = JOptionPane.showInputDialog(this,
            NLS.bind(CoreMessages.explorer_filter_prompt, folder.getNodeDisplayName()),
            CoreMessages.explorer_filter_title, JOptionPane.PLAIN_MESSAGE, null, null, currentMask);
        if (input == null) {
            return;
        }
        String mask = input.toString().trim();
        DBSObjectFilter filter = null;
        if (!mask.isEmpty()) {
            filter = new DBSObjectFilter(mask.contains("%") ? mask : "%" + mask + "%", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        if (folder.setNodeFilter(itemsMeta, filter, true)) {
            loadChildren(node, true);
            tree.expandPath(path);
        }
    }

    /**
     * Reads a node again from the database as Refresh (F5) on it does, if the tree shows its children (security editors after
     * executing a change: a new, renamed or dropped login appears in its folder).
     *
     * @return false if the tree has not read the node's children (the caller refreshes the navigator model itself)
     */
    boolean refreshNavigatorNode(@NotNull DBNNode node) {
        ExplorerNode explorerNode = findExplorerNode(node);
        if (explorerNode == null || explorerNode.state == LoadState.NOT_LOADED) {
            return false;
        }
        loadChildren(explorerNode, true);
        return true;
    }

    /**
     * Expands the tree down to a navigator node and selects it (Find Objects). Children on the path that have not
     * been read yet are read first ({@link #loadChildren}); the walk goes on when they are shown ({@link #showChildren}).
     * The tree lists only the servers opened in this window, so a node of another server first opens that server here
     * (as Registered Servers "Object Explorer" does); the walk goes on when the server list is read again ({@link #mergeChildren}).
     */
    void revealNode(@NotNull DBNNode node) {
        revealTarget = node;
        if (node instanceof DBNDatabaseNode databaseNode && servers != null && servers.add(databaseNode.getDataSourceContainer())
            && rootNode.state != LoadState.NOT_LOADED) {
            reloadConnectionList();
            return;
        }
        continueReveal();
    }

    /**
     * One step of {@link #revealNode}: from the root, follows the tree child that is the target or one of its
     * navigator ancestors. Stops at a node whose children are still being read, or when the target is not in the tree
     * (e.g. hidden by a filter).
     */
    private void continueReveal() {
        DBNNode target = revealTarget;
        if (target == null) {
            return;
        }
        ExplorerNode current = rootNode;
        while (true) {
            if (current.state != LoadState.LOADED) {
                if (current.state == LoadState.NOT_LOADED) {
                    if (current == rootNode) {
                        loadChildren(rootNode, false);
                    } else {
                        // Expanding reads the children (tree will-expand listener)
                        tree.expandPath(new TreePath(current.getPath()));
                    }
                }
                return;
            }
            ExplorerNode next = null;
            for (int i = 0; i < current.getChildCount(); i++) {
                if (current.getChildAt(i) instanceof ExplorerNode child && child.getUserObject() instanceof DBNNode childNode
                    && (childNode == target || target.isChildOf(childNode))) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                revealTarget = null;
                return;
            }
            if (next.getUserObject() == target) {
                revealTarget = null;
                TreePath path = new TreePath(next.getPath());
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
                tree.requestFocusInWindow();
                return;
            }
            current = next;
        }
    }

    /**
     * Focus goes to the tree when the main window opens, so the keyboard works right away.
     */
    void focusTree() {
        tree.requestFocusInWindow();
    }

    /**
     * The connection of the selected node: the connection itself or any object under it.
     */
    @Nullable
    DBPDataSourceContainer getSelectedDataSource() {
        return getSelectedNavigatorNode() instanceof DBNDatabaseNode databaseNode ? databaseNode.getDataSourceContainer() : null;
    }

    /**
     * The selected navigator node, or null (Tools &gt; Find Objects scope).
     */
    @Nullable
    DBNNode getSelectedNode() {
        return getSelectedNavigatorNode();
    }

    /**
     * The selected navigator node; null when nothing or several nodes are selected, so that the commands for one node
     * (and the Properties window) are off with a multi-selection.
     */
    @Nullable
    private DBNNode getSelectedNavigatorNode() {
        TreePath path = tree.getSelectionCount() == 1 ? tree.getSelectionPath() : null;
        if (path != null && path.getLastPathComponent() instanceof ExplorerNode node && node.getUserObject() instanceof DBNNode navigatorNode) {
            return navigatorNode;
        }
        return null;
    }

    /**
     * All selected navigator nodes in tree order (not the "Loading…" placeholders).
     */
    @NotNull
    private List<DBNNode> getSelectedNavigatorNodes() {
        List<DBNNode> nodes = new ArrayList<>();
        int[] rows = tree.getSelectionRows();
        if (rows == null) {
            return nodes;
        }
        Arrays.sort(rows);
        for (int row : rows) {
            if (tree.getPathForRow(row).getLastPathComponent() instanceof ExplorerNode node && node.getUserObject() instanceof DBNNode navigatorNode) {
                nodes.add(navigatorNode);
            }
        }
        return nodes;
    }

    private void refreshSelection() {
        TreePath path = tree.getSelectionPath();
        ExplorerNode node = rootNode;
        if (path != null && path.getLastPathComponent() instanceof ExplorerNode selected) {
            // A "Loading..." placeholder stands for its parent
            node = selected.getUserObject() instanceof DBNNode ? selected : (ExplorerNode) selected.getParent();
        }
        if (node == null || !(node.getUserObject() instanceof DBNNode)) {
            return;
        }
        if (node.state == LoadState.NOT_LOADED) {
            // Never expanded: reading children would connect the data source, so only repaint the label and icon
            treeModel.nodeChanged(node);
            return;
        }
        loadChildren(node, node != rootNode);
    }

    /**
     * Reads the children of a node in a background job.
     *
     * @param refresh refresh the navigator node first ({@code DBNNode#refreshNode})
     */
    private void loadChildren(@NotNull ExplorerNode node, boolean refresh) {
        DBNNode navigatorNode = (DBNNode) node.getUserObject();
        int serial = ++node.loadSerial;
        node.state = LoadState.LOADING;
        if (refresh || node.getChildCount() != 1 || node.getFirstChild() instanceof ExplorerNode child && child.getUserObject() instanceof DBNNode) {
            // Replace the old children by the "Loading..." placeholder; an expanded node stays expanded
            node.removeAllChildren();
            node.add(new ExplorerNode(LOADING_TEXT));
            treeModel.nodeStructureChanged(node);
        }

        new AbstractJob("Load " + navigatorNode.getNodeDisplayName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    DBNNode loadNode = navigatorNode;
                    if (refresh) {
                        DBNNode refreshed = navigatorNode.refreshNode(monitor, ObjectExplorerPanel.this);
                        if (refreshed == null) {
                            // The object no longer exists
                            SwingUtilities.invokeLater(() -> removeNode(node, serial));
                            return Status.OK_STATUS;
                        }
                        loadNode = refreshed;
                    }
                    DBNNode[] children = readChildren(monitor, loadNode);
                    DBNNode finalLoadNode = loadNode;
                    SwingUtilities.invokeLater(() -> showChildren(node, serial, finalLoadNode, children));
                } catch (Throwable e) {
                    SwingUtilities.invokeLater(() -> showLoadError(node, serial, e));
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @Nullable
    private DBNNode[] readChildren(@NotNull DBRProgressMonitor monitor, @NotNull DBNNode node) throws DBException {
        if (node instanceof DBNProject project && servers != null) {
            // Root: the servers opened in this window, without folders or the project's other resources (scripts, bookmarks)
            return servers.getNodes(project.getDatabases());
        }
        DBNNode[] children = node.getChildren(monitor);
        if (ArrayUtils.isEmpty(children)) {
            return children;
        }
        // Non-navigable items stay (see hasTreeChildren); hidden nodes are left out as in the SWT tree
        List<DBNNode> shown = new ArrayList<>(children.length);
        for (DBNNode child : children) {
            if (!(child instanceof DBPHiddenObject hiddenObject && hiddenObject.isHidden())) {
                shown.add(child);
            }
        }
        // Without the navigable filter this only sorts, as the SWT tree does
        return DBNUtils.filterNavigableChildren(shown.toArray(new DBNNode[0]), false);
    }

    /**
     * True when the node has children to show in this tree, including non-navigable items ({@code navigable="false"}
     * in plugin.xml: routine parameters, index and key columns, MySQL system information such as variables, status
     * and engines). The SWT navigator leaves those out of its tree ({@code DBNNode#hasChildren(true)},
     * {@code DBNUtils#filterNavigableChildren}) because it shows them in the object editor's tabs; this application has
     * no object editor, so the tree is the only place they can be seen.
     * <p>
     * Only the navigable condition differs from {@code hasChildren(true)} ({@code DBNDatabaseNode#allowsNavigableChildren}):
     * {@code hasChildren(false)} would also look for the dynamic attributes of struct-typed columns and parameters,
     * which reads data types on the EDT and was never part of this tree.
     */
    private static boolean hasTreeChildren(@NotNull DBNNode node) {
        if (node instanceof DBNDatabaseNode databaseNode) {
            return !databaseNode.isDisposed() && databaseNode.getMeta().hasChildren(databaseNode, false);
        }
        return node.hasChildren(true);
    }

    /**
     * The text of a navigator node in this window (tree rows, the Properties title): its display name, except for an
     * unnamed return value parameter, for which the navigator gives the object's {@code toString} (a class name).
     */
    @NotNull
    static String getNodeText(@NotNull DBNNode node) {
        if (node instanceof DBNDatabaseNode databaseNode && isReturnValue(databaseNode.getObject())) {
            return NavigatorMessages.navigator_parameter_returns;
        }
        return CommonUtils.notEmpty(node.getNodeDisplayName());
    }

    /**
     * SQL Server lists a scalar function's return value as an unnamed parameter with {@code parameter_id} 0
     * (sys.parameters); other drivers name it "RETURN" in the model (MySQLCatalog, GenericProcedure).
     */
    private static boolean isReturnValue(@Nullable DBSObject object) {
        if (!(object instanceof DBSParameter parameter) || !CommonUtils.isEmpty(parameter.getName())) {
            return false;
        }
        return parameter instanceof DBSProcedureParameter procedureParameter && procedureParameter.getParameterKind() == DBSProcedureParameterKind.RETURN
            || parameter instanceof DBSAttributeBase attribute && attribute.getOrdinalPosition() == 0;
    }

    private void showChildren(@NotNull ExplorerNode node, int serial, @NotNull DBNNode loadedNode, @Nullable DBNNode[] children) {
        if (node.loadSerial != serial) {
            return;
        }
        // A refresh may be delegated to an ancestor (DBNNode#refreshNode); its subtree is then the one that changed
        ExplorerNode target = node;
        while (target != null && target.getUserObject() != loadedNode) {
            target = (ExplorerNode) target.getParent();
        }
        if (target == null) {
            target = node;
        }
        if (target != node) {
            node.state = LoadState.NOT_LOADED;
            target.loadSerial++;
        }
        target.removeAllChildren();
        if (children != null) {
            for (DBNNode child : children) {
                target.add(new ExplorerNode(child));
            }
        }
        target.state = LoadState.LOADED;
        treeModel.nodeStructureChanged(target);
        // Connecting may change the node icon (connected state)
        treeModel.nodeChanged(target);
        continueReveal();
    }

    private void showLoadError(@NotNull ExplorerNode node, int serial, @NotNull Throwable error) {
        if (node.loadSerial != serial) {
            return;
        }
        node.removeAllChildren();
        node.add(new ExplorerNode(LOADING_TEXT));
        node.state = LoadState.NOT_LOADED;
        revealTarget = null;
        treeModel.nodeStructureChanged(node);
        if (node != rootNode) {
            tree.collapsePath(new TreePath(node.getPath()));
        }
        DBNNode navigatorNode = (DBNNode) node.getUserObject();
        if (error instanceof DBInterruptedException) {
            // Connection canceled or failed: the connection service has already reported it
            log.debug("Loading of '" + navigatorNode.getNodeDisplayName() + "' interrupted: " + error.getMessage());
            return;
        }
        DBWorkbench.getPlatformUI().showError(
            CoreMessages.main_object_explorer,
            NLS.bind(CoreMessages.explorer_cant_read, navigatorNode.getNodeDisplayName()),
            error);
    }

    private void removeNode(@NotNull ExplorerNode node, int serial) {
        if (node.loadSerial == serial && node.getParent() != null) {
            treeModel.removeNodeFromParent(node);
        }
    }

    //////////////////////////////////////////////////////////
    // Navigator model events

    /**
     * Called by the navigator model on the EDT ({@code DBPPlatformUI.executeInMainThread}), in batches.
     */
    private void navigatorNodeChanged(@NotNull DBNEvent event) {
        if (projectNode == null || event.getSource() == this) {
            // Refreshes started here are already shown by loadChildren
            return;
        }
        DBNNode node = event.getNode();
        DBNProjectDatabases databases = projectNode.getDatabases();
        if (node == databases) {
            // Connection list rebuilt (STRUCT_REFRESH after a removal, folder changes)
            reloadConnectionList();
            return;
        }
        if (node instanceof DBNDataSource) {
            if (!isInProject(node, databases)) {
                return;
            }
            switch (event.getAction()) {
                case ADD -> {
                    if (event.getNodeChange() == DBNEvent.NodeChange.SELECT) {
                        // Created by this application with "reveal" (DBPDataSourceRegistry.addDataSource)
                        pendingReveal = ((DBNDataSource) node).getDataSourceContainer();
                    }
                    reloadConnectionList();
                }
                case REMOVE -> reloadConnectionList();
                case UPDATE -> connectionUpdated(node, event.getNodeChange());
            }
            return;
        }
        if (event.getAction() == DBNEvent.Action.ADD) {
            databaseNodeAdded(node);
            return;
        }
        ExplorerNode explorerNode = findExplorerNode(node);
        if (explorerNode == null) {
            return;
        }
        switch (event.getAction()) {
            case REMOVE -> {
                if (explorerNode.getParent() != null) {
                    treeModel.removeNodeFromParent(explorerNode);
                }
            }
            case UPDATE -> {
                if (event.getNodeChange() == DBNEvent.NodeChange.UNLOAD) {
                    resetNode(explorerNode);
                } else if (event.getNodeChange() == DBNEvent.NodeChange.REFRESH) {
                    // Refreshed elsewhere (a table designer after saving): show the children the model has now
                    reloadLoadedSubtree(explorerNode);
                }
                treeModel.nodeChanged(explorerNode);
            }
            default -> {
                // ADD is handled before the lookup: the new node has no tree node yet
            }
        }
    }

    /**
     * A database object node added elsewhere (a table designer's new table or column): its parent's children are read
     * again from the model if the parent is shown with its children.
     */
    private void databaseNodeAdded(@NotNull DBNNode node) {
        DBNNode parent = node.getParentNode();
        ExplorerNode parentNode = parent == null ? null : findExplorerNode(parent);
        if (parentNode != null && parentNode.state == LoadState.LOADED && findExplorerNode(node) == null) {
            reloadPreserving(parentNode);
        }
    }

    /**
     * Reads the children of a node and of its loaded descendants again from the model, keeping expansion.
     */
    private void reloadLoadedSubtree(@NotNull ExplorerNode node) {
        if (node.state != LoadState.LOADED) {
            return;
        }
        reloadPreserving(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof ExplorerNode child && child.getUserObject() instanceof DBNNode) {
                reloadLoadedSubtree(child);
            }
        }
    }

    private static boolean isInProject(@NotNull DBNNode node, @NotNull DBNProjectDatabases databases) {
        return node instanceof DBNDataSource dataSourceNode
            && dataSourceNode.getDataSourceContainer().getRegistry() == databases.getDataSourceRegistry();
    }

    private void connectionUpdated(@NotNull DBNNode node, @NotNull DBNEvent.NodeChange change) {
        ExplorerNode explorerNode = findExplorerNode(node);
        switch (change) {
            case UNLOAD -> {
                // Disconnected: the children were disposed; they are read again (connecting) when the node is expanded
                if (explorerNode != null) {
                    resetNode(explorerNode);
                }
            }
            case REFRESH -> {
                // Saved settings (rename, edit): the name and the position in the sorted list may have changed
                reloadConnectionList();
            }
            default -> {
                // BEFORE_LOAD, AFTER_LOAD, LOAD, STRUCT_REFRESH: connection state icon
            }
        }
        if (explorerNode != null) {
            treeModel.nodeChanged(explorerNode);
        }
        updateActions();
    }

    /**
     * Collapses a node and makes it read its children again the next time it is expanded. A node whose children
     * are being read is left alone: that read ends with the current state (e.g. a failed connect reports its error).
     */
    private void resetNode(@NotNull ExplorerNode node) {
        if (node.state == LoadState.LOADING) {
            return;
        }
        node.loadSerial++;
        node.state = LoadState.NOT_LOADED;
        node.removeAllChildren();
        if (node.getUserObject() instanceof DBNNode navigatorNode && hasTreeChildren(navigatorNode)) {
            node.add(new ExplorerNode(LOADING_TEXT));
        }
        treeModel.nodeStructureChanged(node);
        tree.collapsePath(new TreePath(node.getPath()));
    }

    /**
     * Reads the list of opened servers again, keeping the tree nodes that are still there with their children and
     * expansion state.
     */
    private void reloadConnectionList() {
        if (rootNode.state != LoadState.NOT_LOADED) {
            reloadPreserving(rootNode);
        }
    }

    private void reloadPreserving(@NotNull ExplorerNode node) {
        DBNNode navigatorNode = (DBNNode) node.getUserObject();
        int serial = ++node.loadSerial;
        new AbstractJob("Reload " + navigatorNode.getNodeDisplayName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    DBNNode[] children = readChildren(monitor, navigatorNode);
                    SwingUtilities.invokeLater(() -> mergeChildren(node, serial, children));
                } catch (Throwable e) {
                    log.debug("Error reloading '" + navigatorNode.getNodeDisplayName() + "'", e);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void mergeChildren(@NotNull ExplorerNode node, int serial, @Nullable DBNNode[] children) {
        if (node.loadSerial != serial || (node != rootNode && node.getParent() == null)) {
            return;
        }
        TreePath nodePath = new TreePath(node.getPath());
        List<TreePath> expanded = new ArrayList<>();
        Enumeration<TreePath> expandedPaths = tree.getExpandedDescendants(nodePath);
        if (expandedPaths != null) {
            expanded.addAll(Collections.list(expandedPaths));
        }
        TreePath selection = tree.getSelectionPath();

        Map<DBNNode, ExplorerNode> existing = new HashMap<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (node.getChildAt(i) instanceof ExplorerNode child && child.getUserObject() instanceof DBNNode childNode) {
                existing.put(childNode, child);
            }
        }
        node.removeAllChildren();
        if (children != null) {
            for (DBNNode child : children) {
                ExplorerNode explorerChild = existing.get(child);
                node.add(explorerChild != null ? explorerChild : new ExplorerNode(child));
            }
        }
        node.state = LoadState.LOADED;
        treeModel.nodeStructureChanged(node);

        for (TreePath path : expanded) {
            if (isAttached(path)) {
                tree.expandPath(path);
            }
        }
        if (selection != null && isAttached(selection)) {
            tree.setSelectionPath(selection);
        }
        if (pendingReveal != null) {
            for (int i = 0; i < node.getChildCount(); i++) {
                if (node.getChildAt(i) instanceof ExplorerNode child && child.getUserObject() instanceof DBNDataSource dataSourceNode
                    && dataSourceNode.getDataSourceContainer() == pendingReveal) {
                    pendingReveal = null;
                    TreePath path = new TreePath(child.getPath());
                    tree.setSelectionPath(path);
                    tree.scrollPathToVisible(path);
                    if (expandReveal) {
                        expandReveal = false;
                        tree.expandPath(path);
                    }
                    tree.requestFocusInWindow();
                    break;
                }
            }
        }
        // Find Objects may have opened the server of its result here (revealNode)
        continueReveal();
        updateActions();
    }

    /**
     * True when the path's node is still in the tree (it was not removed by a reload).
     */
    private boolean isAttached(@NotNull TreePath path) {
        return path.getLastPathComponent() instanceof ExplorerNode node && node.getRoot() == rootNode;
    }

    /**
     * The tree node of a navigator node, searched among the children read so far.
     */
    @Nullable
    private ExplorerNode findExplorerNode(@NotNull DBNNode navigatorNode) {
        Enumeration<TreeNode> nodes = rootNode.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            if (nodes.nextElement() instanceof ExplorerNode node && node.getUserObject() == navigatorNode) {
                return node;
            }
        }
        return null;
    }

    private enum LoadState {
        NOT_LOADED,
        LOADING,
        LOADED
    }

    /**
     * Tree node holding a navigator node, or a text for placeholders ("Loading…", "No active project").
     */
    private static final class ExplorerNode extends DefaultMutableTreeNode {
        private LoadState state = LoadState.NOT_LOADED;
        private int loadSerial;

        ExplorerNode(@NotNull Object userObject) {
            super(userObject);
            if (userObject instanceof DBNNode navigatorNode && hasTreeChildren(navigatorNode)) {
                // Placeholder child: makes the node expandable; replaced when the children are read
                add(new ExplorerNode(LOADING_TEXT));
            } else if (!(userObject instanceof DBNNode)) {
                state = LoadState.LOADED;
            }
        }

        /**
         * Display text; also used by the tree's type-to-select search.
         */
        @Override
        public String toString() {
            return getUserObject() instanceof DBNNode navigatorNode
                ? getNodeText(navigatorNode)
                : String.valueOf(getUserObject());
        }
    }

    /**
     * Tree with a hover highlight (bg.hover): the same rounded rectangle as the selection, painted between the
     * background and the rows. FlatLaf has no hover for trees.
     */
    private static final class ExplorerTree extends JTree {
        private int hoverRow = -1;

        ExplorerTree(@NotNull DefaultTreeModel model) {
            super(model);
            MouseAdapter hoverListener = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    setHoverRow(getRowAtY(e.getY()));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setHoverRow(-1);
                }
            };
            addMouseListener(hoverListener);
            addMouseMotionListener(hoverListener);
        }

        private int getRowAtY(int y) {
            int row = getClosestRowForLocation(0, y);
            Rectangle bounds = row >= 0 ? getRowBounds(row) : null;
            return bounds != null && y >= bounds.y && y < bounds.y + bounds.height ? row : -1;
        }

        private void setHoverRow(int row) {
            if (row != hoverRow) {
                repaintRow(hoverRow);
                hoverRow = row;
                repaintRow(row);
            }
        }

        private void repaintRow(int row) {
            Rectangle bounds = row >= 0 ? getRowBounds(row) : null;
            if (bounds != null) {
                repaint(0, bounds.y, getWidth(), bounds.height);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (ui == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                if (isOpaque()) {
                    g2.setColor(getBackground());
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
                paintHover(g2);
                ui.paint(g2, this);
            } finally {
                g2.dispose();
            }
        }

        private void paintHover(@NotNull Graphics2D g) {
            Rectangle bounds = hoverRow >= 0 && hoverRow < getRowCount() && !isRowSelected(hoverRow) ? getRowBounds(hoverRow) : null;
            Color color = UIManager.getColor("Chromite.Tree.hoverBackground"); //$NON-NLS-1$
            if (bounds == null || color == null) {
                return;
            }
            Insets selectionInsets = UIManager.getInsets("Tree.selectionInsets"); //$NON-NLS-1$
            Insets insets = UIScale.scale(selectionInsets != null ? selectionInsets : new Insets(0, 0, 0, 0));
            float arc = UIScale.scale((float) UIManager.getInt("Tree.selectionArc")); //$NON-NLS-1$
            Object[] oldHints = FlatUIUtils.setRenderingHints(g);
            g.setColor(color);
            g.fill(new RoundRectangle2D.Float(insets.left, bounds.y + insets.top, getWidth() - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom, arc, arc));
            FlatUIUtils.resetRenderingHints(g, oldHints);
        }
    }

    /**
     * Icon (icon pack, {@link NodeIcons#getNodeIcon}), name and a secondary text in text.secondary after it: the data
     * type of a column, the number of objects of a read folder, the host of a connection. A connection with a color
     * (custom or connection type, like SWT's navigator) gets it as a pill around its name ({@link ConnectionPill}, the
     * same as in Registered Servers), also when it is selected or under the mouse. A table whose model knows its storage
     * kind gets the color and tooltip of {@link TableStorageStyle}.
     */
    private static final class ExplorerCellRenderer extends DefaultTreeCellRenderer {
        @Nullable
        private String secondaryText;
        @Nullable
        private ConnectionPill pill;

        @Override
        public Component getTreeCellRendererComponent(
            JTree tree,
            Object value,
            boolean selected,
            boolean expanded,
            boolean leaf,
            int row,
            boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setIconTextGap(UiSizes.get(UiSizes.TREE_ICON_TEXT_GAP));
            secondaryText = null;
            pill = null;
            if (value instanceof ExplorerNode node && node.getUserObject() instanceof DBNNode navigatorNode) {
                if (navigatorNode instanceof DBNDataSource dataSourceNode) {
                    pill = ConnectionPill.of(dataSourceNode.getDataSourceContainer());
                    if (pill != null) {
                        // A pill keeps space.6 from the icon (connection color pill spec), not the denser icon-text gap
                        setIconTextGap(UiSizes.get(UiSizes.TREE_SECONDARY_GAP) + ConnectionPill.getPaddingX());
                    setForeground(pill.getTextColor(getForeground()));
                    }
                }
                setIcon(NodeIcons.getNodeIcon(navigatorNode, expanded));
                setToolTipText(navigatorNode.getNodeDescription());
                TableStorageStyle.apply(this, navigatorNode);
                secondaryText = getSecondaryText(node, navigatorNode);
            } else {
                // Placeholder text
                setIcon(null);
                setToolTipText(null);
                if (!selected) {
                    setForeground(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
                }
            }
            return this;
        }

        @Nullable
        private static String getSecondaryText(@NotNull ExplorerNode node, @NotNull DBNNode navigatorNode) {
            String agentText = AgentCommands.getSecondaryText(navigatorNode);
            if (agentText != null) {
                // SQL Server Agent: the service state, or "disabled" for jobs, alerts and operators
                return agentText;
            }
            String schedulerText = SchedulerCommands.getSecondaryText(navigatorNode);
            if (schedulerText != null) {
                // Job schedulers: the scheduler state, "disabled" for jobs and events, the cron schedule
                return schedulerText;
            }
            if (navigatorNode instanceof DBNDataSource dataSourceNode) {
                DBPConnectionConfiguration configuration = dataSourceNode.getDataSourceContainer().getConnectionConfiguration();
                String host = configuration.getHostName();
                if (CommonUtils.isEmpty(host)) {
                    return null;
                }
                return CommonUtils.isEmpty(configuration.getHostPort()) ? host : host + ":" + configuration.getHostPort(); //$NON-NLS-1$
            }
            if (navigatorNode instanceof DBNDatabaseFolder) {
                // Count of the objects read so far (not the "Loading…" placeholder)
                return node.state == LoadState.LOADED && node.getChildCount() > 0 ? String.valueOf(node.getChildCount()) : null;
            }
            if (navigatorNode instanceof DBNDatabaseNode databaseNode && databaseNode.getObject() instanceof DBSEntityAttribute attribute) {
                return CommonUtils.nullIfEmpty(attribute.getFullTypeName());
            }
            if (navigatorNode instanceof DBNDatabaseNode databaseNode && databaseNode.getObject() instanceof DBSParameter parameter) {
                // Routine parameter: its own type when it has one (with length, as columns), else its data type
                DBSTypedObject type = parameter instanceof DBSTypedObject typedParameter ? typedParameter : parameter.getParameterType();
                return CommonUtils.nullIfEmpty(type.getFullTypeName());
            }
            return null;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            if (size != null && pill != null) {
                // The pill's padding after the name (the padding before it is in the icon-text gap)
                size.width += ConnectionPill.getPaddingX();
            }
            if (size != null && secondaryText != null) {
                size.width += UiSizes.get(UiSizes.TREE_SECONDARY_GAP) + getFontMetrics(getFont()).stringWidth(secondaryText);
            }
            return size;
        }

        @Override
        public void paint(Graphics g) {
            Icon icon = getIcon();
            int textX = getInsets().left + (icon != null ? icon.getIconWidth() + getIconTextGap() : 0);
            int textWidth = getFontMetrics(getFont()).stringWidth(CommonUtils.notEmpty(getText()));
            // The label's own text baseline (its layout of icon and text), so the pill and the secondary text line up
            // with the name; negative when there is no text
            int baseline = getBaseline(getWidth(), getHeight());
            if (pill != null && baseline >= 0) {
                pill.paint(g, this, textX, textWidth, baseline);
            }
            super.paint(g);
            if (secondaryText == null || baseline < 0) {
                return;
            }
            int x = textX + textWidth + (pill != null ? ConnectionPill.getPaddingX() : 0) + UiSizes.get(UiSizes.TREE_SECONDARY_GAP);
            Color color = UIManager.getColor("Chromite.secondaryForeground"); //$NON-NLS-1$
            g.setColor(color != null ? color : getForeground());
            g.setFont(getFont());
            FlatUIUtils.drawString(this, g, secondaryText, x, baseline);
        }
    }
}
