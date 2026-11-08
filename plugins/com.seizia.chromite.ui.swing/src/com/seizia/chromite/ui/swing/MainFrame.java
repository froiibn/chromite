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
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.ui.swing.internal.AiMessages;
import com.seizia.chromite.ui.swing.internal.BackupRestoreMessages;
import com.seizia.chromite.ui.swing.internal.CompareMessages;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import com.seizia.chromite.ui.swing.internal.ConnectionTransferMessages;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DashboardMessages;
import com.seizia.chromite.ui.swing.internal.DatabaseTasksMessages;
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages;
import com.seizia.chromite.ui.swing.internal.QueryCommandMessages;
import com.seizia.chromite.ui.swing.internal.QueryManagerMessages;
import com.seizia.chromite.ui.swing.internal.ScriptsMessages;
import com.seizia.chromite.ui.swing.internal.SettingsTransferMessages;
import com.seizia.chromite.ui.swing.internal.TerminalMessages;
import com.seizia.chromite.ui.swing.internal.ToolbarMessages;
import io.github.andrewauclair.moderndocking.DockingRegion;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvent;
import com.seizia.chromite.model.DBPEventListener;
import com.seizia.chromite.model.ai.agent.AIAgentServices;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPWorkspace;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.PrefUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Chromite Swing 메인 창 (SSMS식 배치).
 *
 * 설계: 가운데 문서 영역(문서 탭, 그 아래 Results / Messages)은 고정이고, 도구 창(Object Explorer, Registered Servers, Properties,
 * Query Library, Scripts)은 도킹 배치({@link ToolWindowDocking})에 놓인다. 기본 배치는 왼쪽 열에 Object Explorer(위)와 Registered Servers(아래),
 * 오른쪽 열에 Properties(Query Library와 Scripts {@link ScriptsPanel}는 처음에 숨김, 보이면 Properties 위)이고, Properties가 SSMS처럼 문서와 결과 옆에 세로로 길게 붙는다.
 * 도구 창은 머리말을 끌어 옮기거나 탭으로 합치거나 떠 있는 창으로 띄울 수 있다. Results는 쿼리 창 안에 있으므로 도킹하지 않고
 * 문서 영역의 분할 창 아래쪽이다({@link ToolWindow.SplitHost}).
 * <ul>
 *     <li>창 위치·크기·최대화 여부와 Results 크기는 이 번들의 설정 저장소에, 도킹 배치는 작업 공간의 배치 파일에 저장한다
 *     ({@link ChromiteTheme}, {@link ToolWindowDocking} 참고). 저장은 창을 닫을 때({@link #dispose()}) 한 번 한다.</li>
 *     <li>Results 크기는 창이 실제 크기를 가진 뒤(windowOpened 이후) 적용하고, 떠 있는 도구 창도 그때 연다.</li>
 *     <li>모양은 DataGrip Islands다: 메인 창 바탕은 캔버스({@code Chromite.island.canvas})이고 문서 탭·편집기와 Results는 각각 둥근 카드({@link IslandPanel}),
 *     도구 창(탭 묶음 포함)은 도킹 루트가 그리는 둥근 카드다. 카드 사이 틈은 분할선(6px, 선 없음)과 좌우 바깥 여백이다. 주 도구 막대와 상태 표시줄은 캔버스 위에 있다.</li>
 *     <li>메뉴는 SSMS 순서로 File(새 연결·쿼리, 파일, Exit), Edit({@link EditMenu}), View(도구 창 토글, Theme), Query(실행, 취소, 자동완성),
 *     Tools(Activity Monitor, Locks, Find Objects, Driver Manager {@link DriverManagerDialog}, Network Profiles {@link NetworkProfilesDialog}, Options {@link OptionsDialog}), Window(Reset Window Layout, 열린 문서 탭 목록), Help(About)이다. FlatLaf의 통합 제목 표시줄(Windows)에서
 *     메뉴 막대는 A단계처럼 제목 표시줄 안에 들어간다. Tools &gt; Options와 같은 설정을 보이는 메뉴 항목(Theme, Icon Style, Auto-Complete While Typing)은
 *     메뉴를 열 때 현재 값으로 맞춘다.
 *     도구 창 단축키는 SSMS와 같다(Object Explorer F8, Registered Servers Ctrl+Alt+G, Properties F4, Results Ctrl+R).</li>
 *     <li>Registered Servers({@link RegisteredServersPanel})의 "Object Explorer" 명령은 Object Explorer를 보이고 서버를 넣으며,
 *     "New Query"는 SQL 편집기만 연다.</li>
 *     <li>문서 탭: SQL 편집기 탭({@link SqlEditorPanel})과 도구 문서 탭. 문서가 하나도 없으면 문서 카드에 빈 화면({@link WelcomePanel})이 보인다. 편집기는 Object Explorer의 "New Query", File > New Query(Ctrl+N),
 *     도구 막대의 New Query로 연다. 제목은 SSMS처럼 {@code SQLQueryN.sql - 연결 이름}이고, 도구 설명은 전체 파일 경로와 연결·서버다.
 *     닫기 단추는 DataGrip처럼 선택한 탭과 마우스를 올린 탭에만 보이고({@link IslandTabbedPaneUI}), 가운데 단추로 눌러도 닫는다.</li>
 *     <li>Query 메뉴의 Execute(F5)와 Cancel Executing Query(Alt+Break)는 활성 편집기에 적용된다. 메뉴 단축키라 포커스가 결과 표에 있어도 동작한다.
 *     Complete(Ctrl+Space)는 활성 편집기의 자동완성을 띄우고, Auto-Complete While Typing은 글자 입력 뒤 자동완성 설정을 켜고 끈다({@link SqlCompletionPopup}).</li>
 *     <li>결과는 SSMS처럼 쿼리 창마다 따로 있다({@link QueryResultsView}). 아래 Results / Messages 탭에는 활성 편집기의 결과를 끼운다.
 *     편집기마다 마지막으로 본 탭(Results 또는 Messages)을 기억한다. 탭에서 빠져 있던 결과 컴포넌트는 테마 전환을 받지 못했을 수 있어, 끼울 때 Look and Feel을 맞춘다.
 *     쿼리 상태 줄({@link QueryStatusLine})도 같은 방식으로, 활성 편집기의 줄을 Results 카드의 아래쪽(SSMS 쿼리 창 맨 아래)에 끼운다.
 *     Results가 숨겨져 있으면 문서 카드의 아래쪽(편집기 아래)으로 옮긴다. 빈 문서 영역과 DDL 탭에는 없다.
 *     Terminal 탭(Query &gt; SQL Terminal, Ctrl+T)은 활성 쿼리 창이 터미널을 켰을 때만 넷째 탭으로 붙인다. 켜는 것이 창마다라서
 *     탭이 늘 있으면 꺼진 창에서 빈 탭이 되기 때문이다.</li>
 *     <li>Results 카드는 DataGrip처럼 활성 문서가 쿼리 창일 때만 보인다. 빈 문서 영역과 다른 문서(테이블 디자이너, ER 다이어그램, 도구 문서, DDL 탭)에서는
 *     숨겨 빈 화면·문서가 문서 영역을 다 쓴다. 사용자가 숨긴 것(Ctrl+R, View 메뉴, — 단추)은 전역이 아니라 그 쿼리 창에 기억하고
 *     ({@link #RESULTS_HIDDEN_KEY}), 실행·실행 계획은 그 쿼리 창의 Results를 다시 보인다. 활성 문서가 바뀔 때 {@link ToolWindow.SplitHost#update()}가
 *     이 규칙대로 보이거나 숨기므로 표시 여부는 저장하지 않고, 크기만 저장한다. 쿼리 창이 없으면 Ctrl+R과 View 메뉴의 Results는 꺼지고 도구 설명이 이유를 알린다.</li>
 *     <li>File > New Connection과 도구 막대의 New Connection은 Object Explorer의 같은 명령으로 연결 대화상자({@link ConnectionDialog})를 연다.</li>
 *     <li>상태 표시줄의 연결 수는 모든 프로젝트의 연결 레지스트리 이벤트({@link DBPEventListener}, 연결 추가·삭제)를 받아 다시 센다.
 *     같은 이벤트의 연결 변경(이름 바꾸기, 연결, 끊기)은 그 연결을 쓰는 편집기의 탭 제목과 기본 스키마 콤보에 알린다.</li>
 *     <li>SQL 파일(SSMS): File > Open File(Ctrl+O), Save(Ctrl+S), Save As, Recent Files(최근 10개, {@link RecentFiles}).
 *     연 파일은 스크립트에 묶은 연결({@link ScriptFiles}), 없으면 Object Explorer에서 고른 연결(없으면 활성 편집기의 연결, 그것도 없으면 연결 없음)로 연다.
 *     이미 열린 파일은 그 탭을 보여 준다. 스크립트 폴더에 저장한 파일은 묶은 연결이 없으면 쿼리 창의 연결에 묶고 Scripts 창을 다시 읽는다.
 *     파일 대화상자의 폴더는 실행 중에만 기억한다.</li>
 *     <li>고친 편집기 탭을 닫거나 앱을 끝낼 때(창 닫기, File > Exit) SSMS처럼 Save / Don't Save / Cancel을 묻는다.
 *     결과 표에 저장하지 않은 변경이 있는 편집기도 같이 묻고, Save면 결과 변경을 진행 대화상자에서 저장한다(실패하면 닫지 않고 오류는 Messages에).
 *     결과를 저장하는 중인 편집기는 닫지 않는다.
 *     그래서 창 닫기는 직접 처리하고({@code DO_NOTHING_ON_CLOSE}), 끝내기로 하면 dispose한다. 외부 종료 요청({@code IApplication.stop})은 묻지 않고 닫는다.
 *     열린 쿼리 창은 주기적으로, 그리고 dispose할 때 복원 파일에 저장되고, 다음 시작에서 창이 열린 뒤 복원할지 묻는다({@link QueryWindowRecovery}).</li>
 *     <li>Object Explorer의 스크립트 명령({@link ObjectExplorerPanel.QueryWindows}): 스크립트를 담은 새 편집기, "View DDL"의 읽기 전용 탭({@link DdlViewerPanel}).</li>
 *     <li>관리 도구 문서 탭({@link ToolDocument}): Activity Monitor({@link ActivityMonitorPanel}, 연결마다 하나, 이미 열려 있으면 그 탭)와
 *     Find Objects({@link ObjectSearchPanel}). Tools 메뉴와 Object Explorer 오른쪽 클릭 메뉴에서 연다. 탭을 닫거나 창을 닫으면 정리한다(타이머, 서버 연결).</li>
 *     <li>테이블 디자이너 탭({@link TableDesignerPanel}): Object Explorer의 Design·New Table…. 같은 테이블은 열린 탭을 보여 준다.
 *     Ctrl+S는 디자이너의 Save(스크립트 미리 보기 후 적용), 닫을 때·끝낼 때 저장하지 않은 변경을 디자이너마다 묻는다.</li>
 *     <li>주 도구 막대는 SSMS Standard 도구 막대 순서다: New Query(아이콘 + 글자), New Connection │ Open, Save, Save All │ Undo, Redo │
 *     Activity Monitor │ Registered Servers, Object Explorer, Query Library, Properties(도구 창 켜고 끄기, 보이면 눌린 모양) │ Find.
 *     단추는 메뉴와 같은 동작({@link Action})을 써서 켜고 끄기와 단축키가 메뉴와 같다. 좁은 창에서는 넘치는 단추가 넘침 메뉴로 간다({@link OverflowToolBar}).
 *     Save All(Ctrl+Shift+S)은 고친 쿼리 창을 Save와 같은 방법으로, 변경이 있는 테이블 디자이너를 디자이너의 Save로 저장한다.
 *     Activity Monitor(Ctrl+Alt+A)는 Object Explorer에서 고른 연결(없으면 활성 쿼리 창의 연결)에 열고, 둘 다 없으면 꺼지며 도구 설명이 이유를 알린다.</li>
 *     <li>도구 막대는 SSMS처럼 두 줄이다(2026-11-07 사용자 결정): 위는 Standard, 아래는 활성 쿼리 창에 적용하는 SQL Editor 도구 막대
 *     ({@link SqlEditorToolBar}: 실행, 중지, 계획, 연결·DB 콤보, 편집 명령, Query History). 쿼리 창마다의 도구 막대는 없다.
 *     실행·중지·계획 단추는 Query 메뉴와 같은 동작이고, 그 켜짐은 {@link #updateQueryActions()}가 활성 쿼리 창으로 정한다. 활성 문서가 바뀌면
 *     그 창의 연결·DB 콤보를 끼우고, 쿼리 창이 아니면 막대는 보인 채 모두 꺼진다.</li>
 * </ul>
 * 창을 닫으면 dispose되고, {@link ChromiteSwingApplication}이 windowClosed 이벤트로 앱을 종료한다.
 * 창 아이콘은 Java 런타임(JBR) 기본 아이콘 대신 Chromite 아이콘을 쓴다({@link ChromiteIcons}).
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 * - 2026-11-06 B단계: SSMS식 배치(Object Explorer, 문서 탭, Results/Messages, Properties), View/Help 메뉴, 테마 전환,
 *   창·도구 창 배치 저장, 알림 표시 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 C단계: SQL 편집기 탭, New Query(메뉴·도구 막대·Object Explorer), Query 메뉴(F5 실행, 취소),
 *   편집기별 Results / Messages 표시 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 D단계: New Connection(메뉴·도구 막대), 연결 추가·삭제 때 상태 표시줄 연결 수 갱신
 *   (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 E단계: SQL 파일 열기·저장·최근 파일, 닫을 때 저장 확인, 스크립트 편집기와 DDL 탭, 연결 변경을 편집기에 알림
 *   (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 * - 2026-11-06 F단계: Query > Complete(Ctrl+Space), Auto-Complete While Typing (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 * - 2026-11-06 G단계: 닫을 때·끝낼 때 결과 표의 저장하지 않은 변경도 묻기 (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 * - 2026-11-06 디자인 적용: 아이콘 도구 막대(New Query만 글자), 문서 탭 40·밑줄 4·아이콘, 결과 탭 32·아이콘, 1px 분할선,
 *   상태 표시줄 28, View > Theme > Icon Style (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 Islands: 캔버스 위 둥근 카드(Object Explorer, 문서, Results, Properties), 카드 사이 6px 틈의 분할선, 문서 탭 알약 모양 선택,
 *   주 도구 막대·상태 표시줄은 캔버스 (docs/chromite/design-spec.md "Islands 적용")
 * - 2026-11-06 P1: Edit·Tools·Window 메뉴(SSMS 순서) (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 P1: 쿼리 상태 줄을 결과 아래에 둠 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 P1: 문서 탭 닫기 단추를 선택·마우스 올린 탭에만, 가운데 단추로 닫기 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 DDL 탭에 객체의 연결을 넘김(구문 강조의 SQL 방언) (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 * - 2026-11-06 Registered Servers 도구 창(Object Explorer 위, Ctrl+Alt+G) (docs/chromite/swing-registered-servers.md)
 * - 2026-11-06 관리 도구: Tools &gt; Activity Monitor, Locks, Find Objects와 도구 문서 탭 (docs/chromite/swing-admin-tools.md)
 * - 2026-11-06 Execution plan 탭, Query 메뉴의 실행 계획(Ctrl+L, Ctrl+M)·Query History, Edit &gt; Advanced
 *   (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 테이블 디자이너: 디자이너 탭 열기·닫기·Ctrl+S·끝낼 때 확인 (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 Query Library: 오른쪽 열에서 Properties 위의 Query Library 도구 창(처음에는 숨김, Ctrl+Alt+T), Query &gt; Save to Query Library…,
 *   확장이 붙은 쿼리 창의 저장은 확장이 함 (docs/chromite/swing-query-library.md)
 * - 2026-11-06 Query Library ③: 창을 닫을 때 예약 실행 정지 (docs/chromite/swing-query-library.md "예약 실행")
 * - 2026-11-06 연결이 바뀌면(이름) 그 연결의 테이블 디자이너 탭 제목도 다시 씀(제목에 연결 이름) (docs/chromite/swing-table-designer.md)
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-06 ER 다이어그램: Object Explorer View Diagram의 도구 문서 탭({@link ErdDiagramPanel}) (docs/chromite/swing-erd.md)
 * - 2026-11-06 개체 정의 편집: Modify·New View 등의 스크립트 창(실행 뒤 Object Explorer 노드를 다시 읽음) (docs/chromite/swing-source-editor.md)
 * - 2026-11-06 주 도구 막대를 SSMS Standard 배치로 확장(Save All, Undo/Redo, Activity Monitor, 도구 창 켜고 끄기, Find, 넘침 메뉴),
 *   File &gt; Save All, Activity Monitor 단축키 Ctrl+Alt+A, 쿼리 창 도구 막대의 Query History (docs/chromite/swing-frontend.md "도구 막대")
 * - 2026-11-06 도구 창 도킹: 분할 창 배치를 {@link ToolWindowDocking}(ModernDocking)으로 바꿈, 기본 배치에서 Object Explorer를
 *   Registered Servers 위로, Reset Window Layout은 기본 도킹 배치 (docs/chromite/swing-frontend.md "도구 창 도킹")
 * - 2026-11-06 빈 문서 영역: Welcome 탭 삭제, 문서가 없으면 문서 카드에 {@link WelcomePanel} (docs/chromite/swing-frontend.md "빈 문서 영역")
 * - 2026-11-07 Results 자동 숨김: Results는 활성 문서가 쿼리 창일 때만 보이고 숨김은 쿼리 창마다 기억, 쿼리 창이 없으면 Results 토글을 끔,
 *   쓰지 않게 된 Results 자리 표시 내용 삭제 (docs/chromite/swing-frontend.md "Results 자동 숨김")
 * - 2026-11-07 밀도 B: 치수를 테마의 Chromite.size.* 키({@link UiSizes})에서 읽음 (docs/chromite/design-spec.md "치수 토큰")
 * - 2026-11-07 서버 대시보드: Tools &gt; Server Dashboard(Activity Monitor의 개요 탭) (docs/chromite/swing-dashboard.md)
 * - 2026-11-07 Tools &gt; Driver Manager… ({@link DriverManagerDialog}) (docs/chromite/swing-driver-manager.md)
 * - 2026-11-07 자동 업데이트: 제목 표시줄 단추와 상태 표시줄 진행({@link UpdateUi}), 끝낼 때 커밋하지 않은 트랜잭션을 묻기
 *   ({@link ExitTransactionCheck}) (docs/chromite/auto-update.md)
 * - 2026-11-07 Object Explorer·Properties의 기본 위치에 같은 열의 이웃(Registered Servers 위, Query Library 아래)을 둠 (docs/chromite/swing-frontend.md "Query Library 자리")
 * - 2026-11-07 Results 기본 높이 220 → 280(사용자 결정), {@link #DEFAULT_RESULTS_HEIGHT} (docs/chromite/swing-frontend.md "Results 자동 숨김")
 * - 2026-11-07 Tools &gt; Disk Usage·Server Logs(Activity Monitor의 탭, {@link AdminViewCommands}) (docs/chromite/swing-admin-tools.md)
 * - 2026-11-07 도구 막대 두 줄(사용자 결정): SQL Editor 도구 막대 {@link SqlEditorToolBar}를 Standard 아래에 두고 활성 쿼리 창을 따름,
 *   Include Actual Execution Plan을 선택 상태가 있는 동작으로(메뉴와 도구 막대 단추가 같이 씀), 실행·계획 켜짐을 쿼리 창의
 *   {@code canExecute}·{@code canCancel}·{@code canExplain}으로 (docs/chromite/swing-frontend.md "SQL Editor 도구 막대")
 * - 2026-11-07 쿼리 창이 활성이 되면 쿼리 상태 줄의 커밋 방식을 다시 읽음 (docs/chromite/swing-frontend.md "자동 커밋 상태")
 * - 2026-11-07 테마를 바꾼 뒤 문서 탭을 모두 닫고 새로 열면 탭 영역이 깨지던 문제: 빈 화면 전환을 컨테이너 리스너 대신
 *   {@link EditorTabbedPane}의 탭 추가·삭제가 끝난 뒤에 함 (docs/chromite/swing-frontend.md "빈 문서 영역")
 * - 2026-11-07 제목 표시줄 가운데의 창 제목("Chromite")을 그리지 않음(사용자 결정, 작업 표시줄·Alt+Tab 이름은 그대로)
 *   (docs/chromite/swing-frontend.md "제목 표시줄")
 * - 2026-11-07 열쇠로 찾아 여는 도구 문서 탭 {@link #openToolDocument}(SQL Server 에이전트 작업 활동·기록·로그) (docs/chromite/swing-sqlserver-agent.md)
 * - 2026-11-07 AI Assistant 도구 창(오른쪽 가장자리, 기본 숨김, View 메뉴·도구 막대 토글), AI 도구 확인 대화상자와 쿼리 창 연결 등록,
 *   쿼리 창 편집기의 "Ask AI About This Query" ({@link AiAssistantPanel}, docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 File 메뉴에 Import Connections… / Export Connections… ({@link ConnectionTransferCommands}, docs/chromite/connection-transfer.md)
 * - 2026-11-07 프로시저 디버거: Query &gt; Debug…(Modify 창의 루틴), 도구 문서의 닫기 전 확인({@link ToolDocument#confirmClose})을
 *   탭 닫기와 끝내기 전에 부름 (docs/chromite/procedure-debugger.md)
 * - 2026-11-07 Query Library 쿼리 창의 Save As는 확장의 {@link SqlEditorExtension#saveAs}(SQL을 평범한 .sql로 내보냄) (docs/chromite/swing-query-library.md "공유")
 * - 2026-11-07 Query &gt; Commit / Roll Back(SWT 단축키, SQL Editor 도구 막대 단추)·Transaction Log, Tools &gt; Open Transactions…
 *   ({@link OpenTransactionsDialog}) (docs/chromite/transactions.md)
 * - 2026-11-07 쿼리 창을 닫을 때 그 창의 자기 세션에 커밋하지 않은 트랜잭션이 있으면 저장 질문 뒤에 묻기(쿼리 창마다 별도 연결)
 *   (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-08 문서 탭 UI가 {@link ChromiteTabbedPaneUI}를 상속: 테마를 바꾼 뒤 숨겨진 탭 단추의 도구 설명이 사라지던 문제
 *   (docs/chromite/swing-followup-fixes.md "작은 화면 수정")
 * - 2026-11-08 Save As 덮어쓰기 확인은 바꾸기 / 취소 단추(취소가 기본), 결과 표 변경 저장 질문은 취소가 기본 (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-08 openSourceScript가 보일 위치(패키지 안 루틴, 컴파일 오류 줄)를 받음, showDdl이 DDL 형식을 받음 (docs/chromite/swing-routines.md)
 * - 2026-11-08 File > Import Connections를 하위 메뉴로: Chromite 파일에서… / 다른 도구에서… (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 * - 2026-11-08 Query &gt; Trace Execution…(Modify 창의 SQL Server 프로시저, {@link ExecutionTraceCommands}) (docs/chromite/sqlserver-execution-trace.md)
 * - 2026-11-08 View &gt; Compact Mode(촘촘한 모드, {@link UiDensity}) (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 * - 2026-11-08 Query History에서 연 바인딩 실행은 기록된 값으로 다시 바인딩(Execute는 묻지 않음)
 *   (docs/chromite/swing-query-library.md "매개변수 대화상자와 바인딩 하나로")
 * - 2026-11-08 Tools &gt; Compare Schemas… ({@link SchemaComparePanel}, docs/chromite/schema-compare.md)
 * - 2026-11-08 Query &gt; SQL Terminal(Ctrl+T)과 활성 쿼리 창이 켰을 때만 붙는 Terminal 탭 (docs/chromite/swing-sql-terminal.md)
 * - 2026-11-08 Scripts 도구 창(Ctrl+Alt+L, Properties 위, 기본 숨김), 스크립트에 묶은 연결로 파일 열기, 스크립트 폴더에 저장할 때 연결 묶기
 *   (docs/chromite/swing-scripts-explorer.md)
 * - 2026-11-08 Query 메뉴의 문장 명령: Execute Current Statement(Ctrl+Enter), Execute from Cursor(Alt+P), Count Rows,
 *   Evaluate Expression(Ctrl+Alt+'), Previous / Next Statement(Alt+PageUp/PageDown), Copy Current Statement, Variables…
 *   (docs/chromite/swing-query-commands.md)
 * - 2026-11-08 Execute, 문장 명령, 예상 계획의 단축키는 키보드 초점이 쿼리 창(편집기, 결과)에 있을 때만 동작. 다른 글자 칸(Chromite Agent 입력 등)의
 *   F5·Ctrl+Enter가 활성 쿼리 창을 실행하던 것 (docs/chromite/swing-query-commands.md "단축키는 쿼리 창 안에서만")
 * - 2026-11-08 탐색기 생산성: Object Details 문서 탭({@link ObjectDetailsPanel}, 같은 노드는 열린 탭), 활성 문서를 Object Explorer에 알림
 *   (Link with Editor, {@link #linkExplorerToDocument}), Query &gt; Select Connection…(Ctrl+9)·Select Database/Schema…(Ctrl+0)는
 *   걸러 고르는 목록({@link ObjectChooserDialog})으로 활성 쿼리 창의 콤보를 바꿈, Set as Default 뒤 그 연결의 쿼리 창 DB 콤보를 다시 읽음
 *   (docs/chromite/swing-explorer-productivity.md)
 * - 2026-11-08 편집 편의: Outline 도구 창({@link SqlOutlinePanel}, 기본 숨김, Ctrl+Alt+O), Query &gt; Pin Results(Ctrl+Shift+P),
 *   View &gt; Results Beside Editor·Maximize Results(Ctrl+Shift+T, 놓는 방향은 저장), 쿼리 창의 Ctrl+클릭을 Object Explorer에 보임
 *   (docs/chromite/swing-sql-editor-ergonomics.md)
 * - 2026-11-08 끝낼 때 실행 중인 작업 질문({@link RunningJobsCheck}: 기다리기 / 멈추고 끝내기 / 취소, 트랜잭션 질문 앞)
 *   (docs/chromite/connection-lifecycle.md "끝낼 때 실행 중인 작업")
 * - 2026-11-08 Database Tasks 도구 창(저장한 작업, {@link DatabaseTasksPanel}, 기본 숨김, Query Library와 같은 탭 묶음)과
 *   Query &gt; Run with Native Client…(저장한 쿼리 창 파일을 psql·mysql·sqlplus로 실행) (docs/chromite/swing-database-tasks.md)
 * - 2026-11-08 Tools &gt; Network Profiles… ({@link NetworkProfilesDialog}, docs/chromite/swing-conn-advanced.md "네트워크 프로필")
 * - 2026-11-08 쿼리 창 자동 저장과 다시 시작할 때 복원({@link QueryWindowRecovery}: 창이 열린 뒤 한 번 묻고, 닫을 때 마지막으로 저장),
 *   복원한 {@code SQLQueryN.sql} 이름 뒤로 새 창 번호를 넘김, Object Explorer의 Recent Scripts가 스크립트를 엶
 *   (docs/chromite/swing-query-window-recovery.md, docs/chromite/swing-scripts-explorer.md "연결별 최근 스크립트")
 * - 2026-11-08 Properties 창과 Object Details 속성 탭이 변경 스크립트를 쿼리 창으로 열 수 있게 쿼리 창 호스트를 넘김(속성 편집)
 *   (docs/chromite/swing-object-properties.md)
 * - 2026-11-08 Query &gt; Auto-Commit(확인 표시)과 SQL Editor 도구 막대의 커밋 방식 단추: 활성 쿼리 창 세션의 자동/수동 커밋 전환({@link CommitModeSwitch}, B-S18)
 *   (docs/chromite/transactions.md "커밋 방식 전환")
 * - 2026-11-08 File &gt; Export Settings… / Import Settings… / Undo Settings Import… ({@link SettingsTransferCommands},
 *   docs/chromite/settings-transfer.md)
 * - 2026-11-08 Query Manager 도구 창({@link QueryManagerPanel}, 메인 창 아래 가장자리, 기본 숨김): 쿼리 관리자가 기록한 모든 실행,
 *   문장은 새 쿼리 창에 넣기만 함 (docs/chromite/swing-query-manager.md)
 * - 2026-11-08 Include Actual Execution Plan: 활성 쿼리 창의 연결이 실제 계획을 주지 않으면 꺼지고 도구 설명이 이유를 말함
 *   (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로")
 */
final class MainFrame extends JFrame implements SqlEditorPanel.Host, ObjectExplorerPanel.QueryWindows, TableDesignerPanel.Host, QueryLibraryPanel.Host,
    QueryWindowRecovery.Host {

    private static final String PREF_BOUNDS = "mainWindow.bounds"; //$NON-NLS-1$
    private static final String PREF_MAXIMIZED = "mainWindow.maximized"; //$NON-NLS-1$
    /**
     * The Results card beside (right of) the documents instead of below them.
     */
    private static final String PREF_RESULTS_BESIDE = "results.beside"; //$NON-NLS-1$
    private static final Dimension DEFAULT_SIZE = new Dimension(1280, 800);
    /**
     * Default shares of the main window width for the left and right tool window columns: the documents keep 60% (a
     * query window stays usable at 1280 px with both columns shown).
     */
    private static final double DEFAULT_LEFT_SHARE = 0.22;
    private static final double DEFAULT_RIGHT_SHARE = 0.18;
    /**
     * Default share of the main window height for the Query Manager along the bottom edge.
     */
    private static final double DEFAULT_QUERY_MANAGER_SHARE = 0.3;
    /**
     * Height of the Results card until the user resizes it (user decision 2026-11-07: 280, before 220). A layout default
     * like {@link #DEFAULT_SIZE}, not a density token ({@link UiSizes}): the user's own size is saved and wins, and
     * Window &gt; Reset Window Layout returns to it.
     */
    private static final int DEFAULT_RESULTS_HEIGHT = 280;
    private static final String UPDATED_LAF_KEY = "chromite.updatedLookAndFeel"; //$NON-NLS-1$
    /**
     * Name of a new query window: {@code SQLQueryN.sql} as in SSMS.
     */
    private static final String QUERY_NAME_PREFIX = "SQLQuery"; //$NON-NLS-1$
    private static final String QUERY_NAME_SUFFIX = ".sql"; //$NON-NLS-1$
    /**
     * Client property of a query window whose results the user hid; kept on the editor so the choice lives and dies with it.
     */
    private static final String RESULTS_HIDDEN_KEY = "chromite.resultsHidden"; //$NON-NLS-1$
    /**
     * Client property of a document tab opened with {@link #openToolDocument}: its key.
     */
    private static final String TOOL_DOCUMENT_KEY = "chromite.toolDocumentKey"; //$NON-NLS-1$
    private static final String DOCUMENTS_CARD = "documents"; //$NON-NLS-1$
    private static final String WELCOME_CARD = "welcome"; //$NON-NLS-1$
    private static final int RESULTS_TAB = 0;
    private static final int MESSAGES_TAB = 1;
    private static final int PLAN_TAB = 2;
    /**
     * Index of the Terminal tab, which is there only while the active query window has Query &gt; SQL Terminal on
     * ({@link #updateTerminalTab}).
     */
    private static final int TERMINAL_TAB = 3;

    private final ObjectExplorerPanel explorerPanel;
    private final RegisteredServersPanel registeredServersPanel;
    private final QueryLibraryPanel queryLibraryPanel;
    private final ScriptsPanel scriptsPanel;
    private final QueryManagerPanel queryManagerPanel;
    private final AiAssistantPanel aiAssistantPanel;
    private final ToolWindow aiWindow;
    private final StatusBar statusBar;
    private final List<DBPDataSourceRegistry> listenedRegistries = new ArrayList<>();
    private final DBPEventListener registryListener = event -> {
        if ((event.getAction() == DBPEvent.Action.OBJECT_ADD || event.getAction() == DBPEvent.Action.OBJECT_REMOVE)
            && event.getObject() instanceof DBPDataSourceContainer) {
            // Registry events come from any thread
            SwingUtilities.invokeLater(this::updateStatusText);
        } else if (event.getAction() == DBPEvent.Action.OBJECT_UPDATE && event.getObject() instanceof DBPDataSourceContainer container) {
            SwingUtilities.invokeLater(() -> connectionUpdated(container));
        }
    };
    private final JTabbedPane documentTabs;
    /**
     * The document tabs, or the welcome content while no document is open.
     */
    private final JPanel documentCards = new JPanel(new CardLayout());
    private final JTabbedPane resultTabs;
    /**
     * Hold the active editor's query status line at the bottom of the query window (SSMS): inside the Results card below
     * the results, or at the bottom of the document card while the Results tool window is hidden.
     */
    private final JPanel resultsStatusHolder = new JPanel(new BorderLayout());
    private final JPanel documentStatusHolder = new JPanel(new BorderLayout());
    @Nullable
    private JComponent attachedStatusLine;
    private final ToolWindow resultsWindow;
    private final ToolWindow.SplitHost resultsSplit;
    private final ToolWindowDocking toolWindowDocking;
    private final Action newQueryAction;
    private final Action openFileAction;
    private final Action saveAction;
    private final Action saveAsAction;
    private final Action saveAllAction;
    private final Action activityMonitorAction;
    private final Action executeQueryAction;
    private final Action cancelQueryAction;
    private final Action completeAction;
    /**
     * Query &gt; Execute Current Statement (Ctrl+Enter), Execute from Cursor (Alt+P), Count Rows, Evaluate Expression: runs of
     * the active query window, enabled like Execute.
     */
    private final List<Action> statementRunActions;
    /**
     * Query &gt; Previous / Next Statement, Copy Current Statement: enabled with a query window.
     */
    private final List<Action> statementEditActions;
    /**
     * Query &gt; Variables…: the active window's {@code @set} variables, not while it runs.
     */
    private final Action variablesAction;
    private final Action estimatedPlanAction;
    /**
     * Query &gt; Include Actual Execution Plan and the SQL Editor tool bar's Actual Plan button: selected
     * ({@link Action#SELECTED_KEY}) while the active query window includes the actual plan.
     */
    private final Action actualPlanAction;
    /**
     * Query &gt; SQL Terminal (Ctrl+T, SSMS "Results to Text"): selected ({@link Action#SELECTED_KEY}) while the active
     * query window writes its runs to the Terminal tab.
     */
    private final Action terminalAction;
    /**
     * Query &gt; Debug…: debugs the routine of the active Modify window (enabled when the Query menu opens).
     */
    private final Action debugAction;
    /**
     * Query &gt; Trace Execution…: traces the SQL Server procedure of the active Modify window (enabled when the Query menu opens).
     */
    private final Action traceAction;
    /**
     * Query &gt; Run with Native Client…: runs the active query window's saved file with psql, mysql or sqlplus (enabled when
     * the Query menu opens).
     */
    private final Action nativeScriptAction;
    /**
     * Query &gt; Commit / Roll Back (also on the SQL Editor tool bar): enabled while the active query window shows an
     * open transaction that no statement is running in.
     */
    private final Action commitAction;
    private final Action rollbackAction;
    private final Action transactionLogAction;
    /**
     * Query &gt; Auto-Commit and the SQL Editor tool bar's commit mode button: switches the active query window's session
     * between auto-commit and manual commit; selected ({@link Action#SELECTED_KEY}) in auto-commit mode, enabled while
     * the window is connected and nothing of it runs ({@link SqlEditorPanel#canSwitchCommitMode}).
     */
    private final Action commitModeAction;
    /**
     * Query &gt; Pin Results and the View menu's results layout, for the active query window.
     */
    private final Action pinResultsAction;
    private final Action resultsBesideAction;
    private final Action maximizeResultsAction;
    private final SqlOutlinePanel outlinePanel;
    private final SqlEditorToolBar sqlEditorToolBar;
    private final EditMenu editMenu;
    /**
     * Saves the query windows periodically and on close, and offers them again at the next start.
     */
    private final QueryWindowRecovery recovery = new QueryWindowRecovery(this);
    private int queryCounter;
    /**
     * Folder of the last file dialog, for this run of the application.
     */
    @Nullable
    private Path lastDirectory;
    private boolean switchingResults;
    // View menu order; Object Explorer first
    private final List<ToolWindow> toolWindows;
    @Nullable
    private Rectangle normalBounds;
    private boolean layoutSaved;

    MainFrame(@NotNull String title, @NotNull String statusText) {
        super(title);
        setIconImages(ChromiteIcons.getWindowIcons());
        // The title stays the window's name for the taskbar and Alt+Tab, but the title bar does not paint it (user
        // decision 2026-11-07); dialogs keep their titles because this is set on the main window only
        getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_TITLE, false);
        // Closing asks to save modified query windows first (requestExit)
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        PropertiesPanel propertiesPanel = new PropertiesPanel(this, true);
        explorerPanel = new ObjectExplorerPanel(node -> {
            propertiesPanel.showNode(node);
            // Activity Monitor opens for the selected connection
            updateActivityMonitorAction();
        }, this);
        resultTabs = new JTabbedPane();
        // size.tab.tool with a 3px underline (size.tabUnderline), icons results.grid / results.messages
        resultTabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
        // The tabs get the active query window's components (activeEditorChanged); without one the Results card is hidden
        resultTabs.addTab(CoreMessages.main_results, UiIcons.get("results"), null); //$NON-NLS-1$
        resultTabs.addTab(CoreMessages.main_messages, UiIcons.get("messages"), null); //$NON-NLS-1$
        resultTabs.addTab(CoreMessages.main_execution_plan, UiIcons.get("plan"), null); //$NON-NLS-1$
        documentTabs = createDocumentTabs();

        JSplitPane documentSplit = createSplitPane(JSplitPane.VERTICAL_SPLIT);
        // Islands: the document tabs and their editors are one card; the query status line goes to its bottom only
        // while the Results card is hidden (attachStatusLine)
        JPanel documentArea = new JPanel(new BorderLayout());
        // No open document: the document area shows the welcome content instead of the tabs (updateDocumentCard)
        documentCards.add(documentTabs, DOCUMENTS_CARD);
        documentArea.add(documentCards, BorderLayout.CENTER);
        documentArea.add(documentStatusHolder, BorderLayout.SOUTH);
        documentSplit.setTopComponent(new IslandPanel(documentArea));
        JPanel resultsArea = new JPanel(new BorderLayout());
        resultsArea.setOpaque(false);
        resultsArea.add(resultTabs, BorderLayout.CENTER);
        resultsArea.add(resultsStatusHolder, BorderLayout.SOUTH);
        // The status line follows the Results card when it is hidden or shown again (Ctrl+R, hide button, another document)
        resultsArea.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                placeStatusLine();
            }
        });
        resultsStatusHolder.setVisible(false);
        documentStatusHolder.setVisible(false);
        // The document area (document card + results card) is the fixed center of the docking layout
        toolWindowDocking = new ToolWindowDocking(this, documentSplit);

        ToolWindow explorerWindow = new ToolWindow("objectExplorer", CoreMessages.main_object_explorer, //$NON-NLS-1$
            KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0));
        // Default layout: Object Explorer above Registered Servers in the left column (user decision 2026-11-06)
        toolWindowDocking.addToolWindow(explorerWindow, explorerPanel, true,
            ToolWindowDocking.Place.next("registeredServers", DockingRegion.NORTH, 0.6), //$NON-NLS-1$
            ToolWindowDocking.Place.edge(DockingRegion.WEST, DEFAULT_LEFT_SHARE));
        registeredServersPanel = new RegisteredServersPanel(new RegisteredServersPanel.Host() {
            @Override
            public void openQuery(@NotNull DBPDataSourceContainer dataSource) {
                MainFrame.this.openQuery(dataSource);
            }

            @Override
            public void showInObjectExplorer(@NotNull DBPDataSourceContainer dataSource) {
                explorerWindow.setShown(true);
                explorerPanel.showServer(dataSource, true);
            }
        });
        ToolWindow registeredServersWindow = new ToolWindow("registeredServers", CoreMessages.main_registered_servers, //$NON-NLS-1$
            KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        toolWindowDocking.addToolWindow(registeredServersWindow, registeredServersPanel, true,
            ToolWindowDocking.Place.next(explorerWindow.getId(), DockingRegion.SOUTH, 0.4),
            ToolWindowDocking.Place.edge(DockingRegion.WEST, DEFAULT_LEFT_SHARE));
        ToolWindow propertiesWindow = new ToolWindow("properties", CoreMessages.main_properties, //$NON-NLS-1$
            KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0));
        toolWindowDocking.addToolWindow(propertiesWindow, propertiesPanel, true,
            ToolWindowDocking.Place.next("queryLibrary", DockingRegion.SOUTH, 0.5), //$NON-NLS-1$
            ToolWindowDocking.Place.edge(DockingRegion.EAST, DEFAULT_RIGHT_SHARE));
        // The Results/Messages tabs are their own header; the hide button sits next to the tabs. The results stay in the
        // query window (below the documents), so they are not dockable
        resultsWindow = new ToolWindow("results", CoreMessages.main_results, //$NON-NLS-1$
            KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        // Shown only for the active query window, unless the user hid them there (user decision 2026-11-06, as in DataGrip)
        resultsSplit = new ToolWindow.SplitHost(resultsWindow, resultsArea, documentSplit, DEFAULT_RESULTS_HEIGHT, new ToolWindow.SplitHost.Context() {
            @Override
            public boolean isShown() {
                SqlEditorPanel editor = getActiveEditor();
                return editor != null && editor.getClientProperty(RESULTS_HIDDEN_KEY) == null;
            }

            @Override
            public void setShown(boolean shown) {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.putClientProperty(RESULTS_HIDDEN_KEY, shown ? null : Boolean.TRUE);
                }
            }
        });
        // The trailing component fills the rest of the tab row; keep the button at its right end
        JPanel resultTabsTrailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        resultTabsTrailing.setOpaque(false);
        resultTabsTrailing.add(resultsWindow.createHideButton());
        resultTabs.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT, resultTabsTrailing);
        queryLibraryPanel = new QueryLibraryPanel(this);
        // Query Library above Properties in the right column (SSMS docks its Template Browser on the right), hidden until opened;
        // a column of its own would narrow the documents
        ToolWindow libraryWindow = new ToolWindow("queryLibrary", CoreMessages.main_query_library, //$NON-NLS-1$
            KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        toolWindowDocking.addToolWindow(libraryWindow, queryLibraryPanel, false,
            ToolWindowDocking.Place.next(propertiesWindow.getId(), DockingRegion.NORTH, 0.5),
            ToolWindowDocking.Place.edge(DockingRegion.EAST, DEFAULT_RIGHT_SHARE));
        queryLibraryPanel.setShowWindow(() -> libraryWindow.setShown(true));
        scriptsPanel = new ScriptsPanel(new ScriptsPanel.Host() {
            @NotNull
            @Override
            public Component getComponent() {
                return MainFrame.this;
            }

            @NotNull
            @Override
            public List<SqlEditorPanel> getQueryWindows() {
                return getEditors();
            }

            @Override
            public void openScript(@NotNull Path file) {
                openFile(file);
            }
        });
        // Scripts above Properties in the right column, like SSMS's Solution Explorer; hidden until opened (Ctrl+Alt+L,
        // the Solution Explorer shortcut)
        ToolWindow scriptsWindow = new ToolWindow("scripts", ScriptsMessages.scripts_title, //$NON-NLS-1$
            KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        toolWindowDocking.addToolWindow(scriptsWindow, scriptsPanel, false,
            ToolWindowDocking.Place.next(propertiesWindow.getId(), DockingRegion.NORTH, 0.5),
            ToolWindowDocking.Place.edge(DockingRegion.EAST, DEFAULT_RIGHT_SHARE));
        // The statements of the active query window, in the right column like the SWT Outline view; hidden until opened
        outlinePanel = new SqlOutlinePanel();
        ToolWindow outlineWindow = new ToolWindow("outline", CoreMessages.main_outline, //$NON-NLS-1$
            KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        toolWindowDocking.addToolWindow(outlineWindow, outlinePanel, false,
            ToolWindowDocking.Place.next(propertiesWindow.getId(), DockingRegion.NORTH, 0.5),
            ToolWindowDocking.Place.edge(DockingRegion.EAST, DEFAULT_RIGHT_SHARE));
        aiWindow =new ToolWindow("aiAssistant", AiMessages.ai_title, //$NON-NLS-1$
            KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK));
        aiAssistantPanel = new AiAssistantPanel(new AiAssistantPanel.Host() {
            @Nullable
            @Override
            public SqlEditorPanel getActiveEditor() {
                return MainFrame.this.getActiveEditor();
            }

            @Override
            public void openSqlEditor(@Nullable DBPDataSourceContainer connection, @NotNull String sql, @Nullable String title) {
                SqlEditorPanel editor = openQueryEditor(connection, title == null || title.endsWith(".sql") ? title : title + ".sql"); //$NON-NLS-1$ //$NON-NLS-2$
                editor.setContent(SqlFileContent.newFile(sql), null);
            }

            @Override
            public void showAssistant() {
                aiWindow.setShown(true);
            }

            @Override
            public void openOptions() {
                OptionsDialog.open(MainFrame.this);
            }
        });
        // Its own column on the right edge, hidden until opened (a chat needs more width than the Properties column)
        toolWindowDocking.addToolWindow(aiWindow, aiAssistantPanel, false,
            ToolWindowDocking.Place.edge(DockingRegion.EAST, DEFAULT_RIGHT_SHARE + 0.07));
        // Tool calls of the in-app chat and of the MCP server ask in this window; the openInEditor tool opens query windows here
        AIAgentServices.setConfirmationHandler(new AiConfirmationDialog.Handler(() -> this));
        AIAgentServices.setEditorBridge((connection, sql, windowTitle) ->
            SwingUtilities.invokeLater(() -> aiAssistantPanel.openInEditor(connection, sql, windowTitle)));
        // Saved tasks (backups, restores, native script runs): a tab next to the Query Library, hidden until opened
        ToolWindow tasksWindow = new ToolWindow("databaseTasks", DatabaseTasksMessages.tasks_title, null); //$NON-NLS-1$
        toolWindowDocking.addToolWindow(tasksWindow, new DatabaseTasksPanel(), false,
            ToolWindowDocking.Place.next(libraryWindow.getId(), DockingRegion.CENTER, 0.5),
            ToolWindowDocking.Place.edge(DockingRegion.EAST, DEFAULT_RIGHT_SHARE));
        // Every statement the query manager recorded: a wide table along the bottom edge, like SWT's bottom views; hidden until
        // opened. Statements open in a new query window and never run
        queryManagerPanel = new QueryManagerPanel((connection, sql) -> {
            SqlEditorPanel editor = openQueryEditor(connection, null);
            editor.setContent(SqlFileContent.newFile(sql), null);
        });
        ToolWindow queryManagerWindow = new ToolWindow("queryManager", QueryManagerMessages.qm_title, null); //$NON-NLS-1$
        toolWindowDocking.addToolWindow(queryManagerWindow, queryManagerPanel, false,
            ToolWindowDocking.Place.edge(DockingRegion.SOUTH, DEFAULT_QUERY_MANAGER_SHARE));
        toolWindows = List.of(explorerWindow, registeredServersWindow, propertiesWindow, libraryWindow, scriptsWindow, outlineWindow, tasksWindow,
            queryManagerWindow, aiWindow, resultsWindow);

        newQueryAction = createAction(CoreMessages.main_new_query, CoreMessages.main_new_query_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK), this::newQuery);
        openFileAction = createAction(CoreMessages.main_open_file, CoreMessages.main_open_file_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), this::openFile);
        saveAction = createAction(CoreMessages.common_save, CoreMessages.main_save_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    saveEditor(editor, false);
                } else if (documentTabs.getSelectedComponent() instanceof TableDesignerPanel designer) {
                    designer.save();
                }
            });
        saveAsAction = createAction(CoreMessages.main_save_as, CoreMessages.main_save_as_tip, null, () -> {
            SqlEditorPanel editor = getActiveEditor();
            if (editor != null) {
                saveEditor(editor, true);
            }
        });
        saveAllAction = createAction(ToolbarMessages.toolbar_save_all, ToolbarMessages.toolbar_save_all_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), this::saveAll);
        activityMonitorAction = createAction(CoreMessages.main_activity_monitor, ToolbarMessages.toolbar_activity_monitor_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
            () -> openActivityMonitor(ActivityMonitorPanel.PROCESSES_TAB));
        executeQueryAction = createEditorAction(CoreMessages.main_execute, CoreMessages.main_execute_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), SqlEditorPanel::execute);
        cancelQueryAction = createAction(CoreMessages.main_cancel_query, null,
            KeyStroke.getKeyStroke(KeyEvent.VK_PAUSE, InputEvent.ALT_DOWN_MASK), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.cancel();
                }
            });
        completeAction = createAction(CoreMessages.main_complete, CoreMessages.main_complete_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.complete();
                }
            });
        // Ctrl+Enter, Alt+P and Ctrl+Alt+' are the SWT editor's keys
        statementRunActions = List.of(
            createEditorAction(QueryCommandMessages.query_execute_statement, QueryCommandMessages.query_execute_statement_tip,
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                editor -> editor.execute(SqlQueryCommands.ExecuteMode.STATEMENT)),
            createEditorAction(QueryCommandMessages.query_execute_from_cursor, QueryCommandMessages.query_execute_from_cursor_tip,
                KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.ALT_DOWN_MASK),
                editor -> editor.execute(SqlQueryCommands.ExecuteMode.FROM_CURSOR)),
            createEditorAction(QueryCommandMessages.query_row_count, QueryCommandMessages.query_row_count_tip, null,
                editor -> editor.execute(SqlQueryCommands.ExecuteMode.ROW_COUNT)),
            createEditorAction(QueryCommandMessages.query_evaluate_expression, QueryCommandMessages.query_evaluate_expression_tip,
                KeyStroke.getKeyStroke(KeyEvent.VK_QUOTE, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK),
                editor -> editor.execute(SqlQueryCommands.ExecuteMode.EXPRESSION)));
        // SWT used Alt+Up / Alt+Down, which the editor uses to move lines
        statementEditActions = List.of(
            createEditorAction(QueryCommandMessages.query_previous_statement, null,
                KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, InputEvent.ALT_DOWN_MASK), editor -> editor.selectStatement(false)),
            createEditorAction(QueryCommandMessages.query_next_statement, null,
                KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, InputEvent.ALT_DOWN_MASK), editor -> editor.selectStatement(true)),
            createEditorAction(QueryCommandMessages.query_copy_statement, null, null, SqlEditorPanel::copyCurrentStatement));
        variablesAction = createEditorAction(QueryCommandMessages.query_variables, QueryCommandMessages.query_variables_tip, null,
            SqlEditorPanel::showVariables);
        estimatedPlanAction = createEditorAction(CoreMessages.main_estimated_plan,
            CoreMessages.main_estimated_plan_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), SqlEditorPanel::explainEstimatedPlan);
        actualPlanAction = createAction(CoreMessages.main_actual_plan, CoreMessages.main_actual_plan_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.setIncludeActualPlan(!editor.isIncludeActualPlan());
                }
                // The menu item and the tool bar button show the active window's state (updateQueryActions)
                updateQueryActions();
            });
        // Menu items and toggle buttons write their state back to the action only if it has one
        actualPlanAction.putValue(Action.SELECTED_KEY, Boolean.FALSE);
        // Ctrl+T is SSMS's Results to Text, the nearest SSMS command to a console-style output
        terminalAction = createAction(TerminalMessages.terminal_action, TerminalMessages.terminal_action_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.setTerminalEnabled(!editor.isTerminalEnabled());
                    if (editor.isTerminalEnabled()) {
                        showTerminal(editor);
                    } else {
                        // The Terminal tab goes away; the tab the window showed before it comes back
                        selectResultTab(editor);
                    }
                }
                updateQueryActions();
            });
        terminalAction.putValue(Action.SELECTED_KEY, Boolean.FALSE);
        // The SWT shortcuts: Ctrl+Alt+Shift+K / R on Windows, Ctrl+4 / Ctrl+8 elsewhere (core plugin.xml key bindings)
        boolean windows = RuntimeUtils.isWindows();
        int windowsModifiers = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;
        commitAction = createAction(CoreMessages.main_commit, CoreMessages.main_commit_tip,
            windows ? KeyStroke.getKeyStroke(KeyEvent.VK_K, windowsModifiers) : KeyStroke.getKeyStroke(KeyEvent.VK_4, InputEvent.CTRL_DOWN_MASK),
            () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.endOpenTransaction(true);
                }
            });
        rollbackAction = createAction(CoreMessages.main_rollback, CoreMessages.main_rollback_tip,
            windows ? KeyStroke.getKeyStroke(KeyEvent.VK_R, windowsModifiers) : KeyStroke.getKeyStroke(KeyEvent.VK_8, InputEvent.CTRL_DOWN_MASK),
            () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.endOpenTransaction(false);
                }
            });
        transactionLogAction = createAction(CoreMessages.main_transaction_log, CoreMessages.main_transaction_log_tip, null, () -> {
            SqlEditorPanel editor = getActiveEditor();
            if (editor != null) {
                editor.showTransactionLog();
            }
        });
        commitModeAction = createAction(LifecycleMessages.commit_mode_auto, null, null, () -> {
            SqlEditorPanel editor = getActiveEditor();
            if (editor != null) {
                editor.switchCommitMode();
            }
            // A check box item selects itself when clicked: show the window's actual mode again
            updateQueryActions();
        });
        commitModeAction.putValue(Action.SELECTED_KEY, Boolean.FALSE);
        // SWT keys: Ctrl+Shift+P pins the result tab, Ctrl+Shift+T maximizes the results
        pinResultsAction = createAction(CoreMessages.main_pin_results, CoreMessages.main_pin_results_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), () -> {
                SqlEditorPanel editor = getActiveEditor();
                if (editor != null) {
                    editor.pinResults();
                }
            });
        resultsBesideAction = createAction(CoreMessages.main_results_beside, CoreMessages.main_results_beside_tip, null, () -> {
            resultsSplit.setBeside(!resultsSplit.isBeside(), true);
            updateQueryActions();
        });
        resultsBesideAction.putValue(Action.SELECTED_KEY, Boolean.FALSE);
        maximizeResultsAction = createAction(CoreMessages.main_maximize_results, CoreMessages.main_maximize_results_tip,
            KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), () -> {
                if (getActiveEditor() == null) {
                    return;
                }
                boolean maximize = !resultsSplit.isMaximized();
                if (maximize) {
                    resultsWindow.setShown(true);
                }
                resultsSplit.setMaximized(maximize);
                updateQueryActions();
            });
        maximizeResultsAction.putValue(Action.SELECTED_KEY, Boolean.FALSE);
        debugAction = DebugCommands.createAction(this, () -> {
            SqlEditorPanel editor = getActiveEditor();
            return editor != null ? editor.getSourceObject() : null;
        }, this);
        traceAction = ExecutionTraceCommands.createAction(this, () -> {
            SqlEditorPanel editor = getActiveEditor();
            return editor != null ? editor.getSourceObject() : null;
        }, this);
        nativeScriptAction = createAction(BackupRestoreMessages.native_script_editor_menu, BackupRestoreMessages.native_script_editor_menu_tip, null, () -> {
            SqlEditorPanel editor = getActiveEditor();
            if (editor != null) {
                BackupRestoreCommands.runEditorScript(this, editor);
            }
        });
        sqlEditorToolBar = new SqlEditorToolBar(executeQueryAction, cancelQueryAction, estimatedPlanAction, actualPlanAction,
            commitAction, rollbackAction, commitModeAction, this::openQueryHistory);
        editMenu = new EditMenu(this::getActiveTextArea);
        documentTabs.addChangeListener(e -> activeEditorChanged());
        resultTabs.addChangeListener(e -> {
            SqlEditorPanel editor = getActiveEditor();
            if (editor != null && !switchingResults) {
                editor.getResultsView().setMessagesTabSelected(resultTabs.getSelectedIndex() == MESSAGES_TAB);
                editor.setPlanTabSelected(resultTabs.getSelectedIndex() == PLAN_TAB);
                editor.setTerminalTabSelected(resultTabs.getSelectedIndex() == TERMINAL_TAB);
            }
        });
        updateQueryActions();
        documentCards.add(new WelcomePanel(List.of(CoreMessages.main_welcome_hint, CoreMessages.main_welcome_next),
            List.of(newQueryAction, openFileAction, registeredServersWindow.getToggleAction())), WELCOME_CARD);
        updateDocumentCard();

        setJMenuBar(createMenuBar());

        JPanel content = new CanvasPanel(new BorderLayout());
        // Two tool bar rows as in SSMS: Standard, then SQL Editor (user decision 2026-11-07)
        JPanel toolBars = new JPanel(new BorderLayout());
        toolBars.setOpaque(false);
        toolBars.add(createToolBar(registeredServersWindow, explorerWindow, libraryWindow, propertiesWindow), BorderLayout.NORTH);
        toolBars.add(sqlEditorToolBar.getComponent(), BorderLayout.SOUTH);
        content.add(toolBars, BorderLayout.NORTH);
        content.add(toolWindowDocking.getComponent(), BorderLayout.CENTER);
        statusBar = new StatusBar(statusText);
        content.add(statusBar, BorderLayout.SOUTH);
        UpdateUi.install(this, statusBar);
        setContentPane(content);
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            registry.addDataSourceListener(registryListener);
            listenedRegistries.add(registry);
        }

        restoreLayout();
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                rememberNormalBounds();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                rememberNormalBounds();
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestExit();
            }

            @Override
            public void windowOpened(WindowEvent e) {
                // Split panes have their real size only after the window is shown and laid out
                SwingUtilities.invokeLater(() -> {
                    resultsSplit.applySize();
                    // Floating tool windows open after the main window, so they stay in front of it
                    toolWindowDocking.restoreFloatingWindows();
                    explorerPanel.focusTree();
                    // The query windows of the last session, once the window is there to show them
                    recovery.start();
                });
            }
        });
    }

    /**
     * Shows a non-modal notification in the lower right corner (used by {@link SwingPlatformUI#showNotification}).
     */
    void showNotification(@NotNull String title, @Nullable String message, @NotNull NotificationToast.Kind kind, @Nullable Runnable feedback) {
        NotificationToast.show(this, title, message, kind, feedback);
    }

    @Override
    public void dispose() {
        if (!layoutSaved && isDisplayable()) {
            layoutSaved = true;
            saveLayout();
            // While the query windows are still open (also when the application is stopped from outside, without questions)
            recovery.stop();
            // Also closes the floating tool windows
            toolWindowDocking.dispose();
        }
        explorerPanel.dispose();
        registeredServersPanel.dispose();
        queryLibraryPanel.dispose();
        queryManagerPanel.dispose();
        aiAssistantPanel.dispose();
        AIAgentServices.setConfirmationHandler(null);
        AIAgentServices.setEditorBridge(null);
        editMenu.dispose();
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof ToolDocument document) {
                document.dispose();
            }
        }
        for (DBPDataSourceRegistry registry : listenedRegistries) {
            registry.removeDataSourceListener(registryListener);
        }
        listenedRegistries.clear();
        super.dispose();
    }

    /**
     * Status bar text: the number of connections in all projects of the workspace.
     */
    @NotNull
    static String describeWorkspace(@NotNull DBPWorkspace workspace) {
        int connections = 0;
        for (DBPProject project : workspace.getProjects()) {
            connections += project.getDataSourceRegistry().getDataSources().size();
        }
        return NLS.bind(connections == 1 ? CoreMessages.main_status_connection : CoreMessages.main_status_connections, connections);
    }

    private void updateStatusText() {
        if (isDisplayable()) {
            statusBar.setText(describeWorkspace(DBWorkbench.getPlatform().getWorkspace()));
        }
    }

    @NotNull
    private static JSplitPane createSplitPane(int orientation) {
        JSplitPane splitPane = new LineSplitPane(orientation);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        return splitPane;
    }

    /**
     * An action on the active query window (Execute, the statement commands, the estimated plan); nothing happens without
     * one. Its shortcut acts only while the keyboard focus is in the query window ({@link #isShortcutFromOutside}); the
     * menu item and the tool bar button always act.
     */
    @NotNull
    private Action createEditorAction(
        @NotNull String name,
        @Nullable String description,
        @Nullable KeyStroke accelerator,
        @NotNull Consumer<SqlEditorPanel> task
    ) {
        return createAction(name, description, accelerator, () -> {
            SqlEditorPanel editor = getActiveEditor();
            if (editor != null && !isShortcutFromOutside(editor, accelerator)) {
                task.accept(editor);
            }
        });
    }

    /**
     * Whether the action runs because its shortcut was pressed while the keyboard focus is outside the query window.
     * Menu shortcuts work in the whole main window, so without this F5 or Ctrl+Enter typed in another text field (the
     * Chromite Agent input, which many chat inputs send with, Object Explorer's filter, the Query Library) would run the
     * active window's statements. The query window is its editor and its results (the result tabs show the active
     * window's results, plan and terminal). A shortcut is told from a menu or button click by the event being handled:
     * the key event of that key stroke. Choosing the item in the open menu with the keyboard is another key (Enter).
     */
    private boolean isShortcutFromOutside(@NotNull SqlEditorPanel editor, @Nullable KeyStroke accelerator) {
        if (accelerator == null || !(EventQueue.getCurrentEvent() instanceof KeyEvent keyEvent)
            || !accelerator.equals(KeyStroke.getKeyStrokeForEvent(keyEvent))) {
            return false;
        }
        // The component the key went to: the focus owner when the key was pressed
        Component focus = keyEvent.getComponent() != null ? keyEvent.getComponent() : KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focus == null || !SwingUtilities.isDescendingFrom(focus, editor) && !SwingUtilities.isDescendingFrom(focus, resultTabs);
    }

    @NotNull
    private static Action createAction(@NotNull String name, @Nullable String description, @Nullable KeyStroke accelerator, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, description);
        action.putValue(Action.ACCELERATOR_KEY, accelerator);
        return action;
    }

    /**
     * SSMS standard tool bar: [New Query (icon and text)] [New Connection] | [Open File] [Save] [Save All] | [Undo] [Redo] |
     * [Activity Monitor] | [Registered Servers] [Object Explorer] [Query Library] [Properties] | [Find]; the other buttons
     * are icons with tool tips that name the menu shortcut (size.toolbar.main 32 = 24px buttons + 4px above and below;
     * 28 = 22 + 3 in compact mode).
     */
    @NotNull
    private JComponent createToolBar(
        @NotNull ToolWindow registeredServersWindow,
        @NotNull ToolWindow explorerWindow,
        @NotNull ToolWindow libraryWindow,
        @NotNull ToolWindow propertiesWindow
    ) {
        JToolBar toolBar = new OverflowToolBar();
        // On the Islands canvas (MainToolbar.background = main-window-bg)
        toolBar.setOpaque(false);
        toolBar.setBorder(UiSizes.createPadding(UiSizes.MAIN_TOOL_BAR_PADDING));
        toolBar.add(UiIcons.createToolButton(newQueryAction, "new-query")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(explorerPanel.getNewConnectionAction(), "new-connection")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(openFileAction, "open")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(saveAction, "save")); //$NON-NLS-1$
        toolBar.add(UiIcons.createActionButton(saveAllAction, "save-all")); //$NON-NLS-1$
        toolBar.addSeparator();
        // Edit menu actions: they act on the focused component, which keeps the focus (tool bar buttons don't take it)
        toolBar.add(createIconButton(editMenu.getUndoAction(), "undo", ToolbarMessages.toolbar_undo_tip)); //$NON-NLS-1$
        toolBar.add(createIconButton(editMenu.getRedoAction(), "redo", ToolbarMessages.toolbar_redo_tip)); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(UiIcons.createActionButton(activityMonitorAction, "activity-monitor")); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(createToolWindowButton(registeredServersWindow, "registered-servers", ToolbarMessages.toolbar_registered_servers_tip)); //$NON-NLS-1$
        toolBar.add(createToolWindowButton(explorerWindow, "object-explorer", ToolbarMessages.toolbar_object_explorer_tip)); //$NON-NLS-1$
        toolBar.add(createToolWindowButton(libraryWindow, "library", ToolbarMessages.toolbar_query_library_tip)); //$NON-NLS-1$
        toolBar.add(createToolWindowButton(propertiesWindow, "properties", ToolbarMessages.toolbar_properties_tip)); //$NON-NLS-1$
        toolBar.add(createToolWindowButton(aiWindow, "ai-assistant", AiMessages.ai_toolbar_tip)); //$NON-NLS-1$
        toolBar.addSeparator();
        toolBar.add(createIconButton(editMenu.getFindAction(), "search", ToolbarMessages.toolbar_find_tip)); //$NON-NLS-1$
        return toolBar;
    }

    /**
     * Icon button for an action without a tool tip of its own (the Edit menu actions); the tool tip names the shortcut.
     */
    @NotNull
    private static JButton createIconButton(@NotNull Action action, @NotNull String iconId, @NotNull String toolTip) {
        JButton button = UiIcons.createActionButton(action, iconId);
        button.setToolTipText(toolTip);
        return button;
    }

    /**
     * Toggle button of a tool window: pressed while the window is shown, like its View menu item (same action).
     */
    @NotNull
    private static JToggleButton createToolWindowButton(@NotNull ToolWindow toolWindow, @NotNull String iconId, @NotNull String toolTip) {
        JToggleButton button = new JToggleButton(toolWindow.getToggleAction());
        button.setHideActionText(true);
        button.setIcon(UiIcons.get(iconId));
        button.setToolTipText(toolTip);
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        button.putClientProperty(FlatClientProperties.STYLE, "arc: 6"); //$NON-NLS-1$
        button.setFocusable(false);
        return button;
    }

    //////////////////////////////////////////////////////////
    // Query editors

    /**
     * File > New Query and the tool bar: the connection selected in Object Explorer, else the one of the active editor.
     */
    private void newQuery() {
        DBPDataSourceContainer dataSource = explorerPanel.getSelectedDataSource();
        if (dataSource == null) {
            SqlEditorPanel activeEditor = getActiveEditor();
            dataSource = activeEditor != null ? activeEditor.getDataSourceContainer() : null;
        }
        if (dataSource == null) {
            JOptionPane.showMessageDialog(this, CoreMessages.common_select_connection_first,
                CoreMessages.main_new_query, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        openQuery(dataSource);
    }

    @Override
    public void openQuery(@NotNull DBPDataSourceContainer dataSource) {
        openQueryEditor(dataSource, null);
    }

    @Override
    public void openScript(@NotNull DBPDataSourceContainer dataSource, @NotNull String script, long maxRows, boolean execute) {
        SqlEditorPanel editor = openQueryEditor(dataSource, null);
        editor.setContent(SqlFileContent.newFile(script), null);
        editor.setMaxRows(maxRows);
        if (execute) {
            editor.execute();
        }
    }

    @Override
    public void openSourceScript(@NotNull DBPDataSourceContainer dataSource, @NotNull String script, @NotNull DBNDatabaseNode refreshNode,
                                 @Nullable SqlEditorPanel.SourcePosition position) {
        SqlEditorPanel editor = openQueryEditor(dataSource, null);
        editor.setContent(SqlFileContent.newFile(script), null);
        editor.setExecutionFinishedListener(() -> SourceScripts.refreshAfterRun(refreshNode));
        // Modify: Query > Debug… and Trace Execution… use the routine of the window (a New window's node is its folder: neither applies)
        editor.setSourceObject(refreshNode.getObject());
        if (position != null) {
            // After the new tab is laid out, so the text scrolls to the place
            SwingUtilities.invokeLater(() -> editor.showSourcePosition(position));
        }
    }

    @Override
    public void showDdl(@Nullable DBPDataSourceContainer dataSource, @NotNull String objectName, @NotNull String ddl,
                        @Nullable DdlViewerPanel.Formats formats) {
        DdlViewerPanel viewer = new DdlViewerPanel(dataSource, objectName, ddl, formats);
        viewer.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        documentTabs.addTab(viewer.getTitle(), UiIcons.get("file-sql"), viewer); //$NON-NLS-1$
        int index = documentTabs.indexOfComponent(viewer);
        documentTabs.setToolTipTextAt(index, viewer.getTitle());
        documentTabs.setSelectedIndex(index);
    }

    @Override
    public void openActivityMonitor(@NotNull DBPDataSourceContainer dataSource, int tab) {
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof ActivityMonitorPanel monitor && monitor.getContainer() == dataSource) {
                documentTabs.setSelectedIndex(i);
                monitor.showTab(tab);
                return;
            }
        }
        ActivityMonitorPanel monitor = new ActivityMonitorPanel(dataSource, this);
        addToolDocument(monitor);
        monitor.showTab(tab);
    }

    @Override
    public void openObjectSearch(@Nullable DBNNode selectedNode) {
        addToolDocument(new ObjectSearchPanel(selectedNode, node -> {
            // Object Explorer is the first tool window
            toolWindows.get(0).setShown(true);
            explorerPanel.revealNode(node);
        }));
    }

    @Override
    public void viewDiagram(@NotNull DBSObject root) {
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof ErdDiagramPanel diagram && diagram.getRoot() == root) {
                documentTabs.setSelectedIndex(i);
                return;
            }
        }
        addToolDocument(new ErdDiagramPanel(root, this, node -> {
            // Object Explorer is the first tool window
            toolWindows.get(0).setShown(true);
            explorerPanel.revealNode(node);
        }));
    }

    @NotNull
    @Override
    public ToolDocument openToolDocument(@NotNull String key, @NotNull Supplier<ToolDocument> factory) {
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof ToolDocument document
                && key.equals(((JComponent) document).getClientProperty(TOOL_DOCUMENT_KEY))) {
                documentTabs.setSelectedIndex(i);
                return document;
            }
        }
        ToolDocument document = factory.get();
        ((JComponent) document).putClientProperty(TOOL_DOCUMENT_KEY, key);
        addToolDocument(document);
        return document;
    }

    @Override
    public void openObjectDetails(@NotNull DBNDatabaseNode node) {
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof ObjectDetailsPanel details && details.getNode() == node) {
                documentTabs.setSelectedIndex(i);
                return;
            }
        }
        addToolDocument(new ObjectDetailsPanel(node, target -> {
            // Object Explorer is the first tool window
            toolWindows.get(0).setShown(true);
            explorerPanel.revealNode(target);
        }, this));
    }

    @Override
    public void connectionDefaultsChanged(@NotNull DBPDataSourceContainer dataSource) {
        // The query windows of the connection read their database picker again (those sharing the main session changed)
        connectionUpdated(dataSource);
    }

    @Override
    public void openScriptFile(@NotNull Path file) {
        openFile(file);
    }

    /**
     * Tells Object Explorer which object and connection the active document is about (Link with Editor): a query window's
     * routine (Modify) or default database/schema, a designed table, a diagram's root, a details document's node, an
     * Activity Monitor's connection.
     */
    private void linkExplorerToDocument() {
        Component document = documentTabs.getSelectedComponent();
        DBSObject object = null;
        DBPDataSourceContainer container = null;
        if (document instanceof SqlEditorPanel editor) {
            container = editor.getDataSourceContainer();
            object = editor.getSourceObject();
            if (object == null && editor.getSchemaCombo().getSelectedItem() instanceof DBSObject schema) {
                object = schema;
            }
        } else if (document instanceof TableDesignerPanel designer) {
            object = designer.getSession().getTable();
        } else if (document instanceof ErdDiagramPanel diagram) {
            object = diagram.getRoot();
        } else if (document instanceof ObjectDetailsPanel details) {
            object = details.getNode().getObject();
            container = details.getNode().getDataSourceContainer();
        } else if (document instanceof ActivityMonitorPanel monitor) {
            container = monitor.getContainer();
        }
        if (container == null && object != null && object.getDataSource() != null) {
            container = object.getDataSource().getContainer();
        }
        explorerPanel.activeDocumentChanged(object, container);
    }

    /**
     * Query &gt; Select Connection… (Ctrl+9, SWT): chooses the active query window's connection from a filtered list; the
     * window's connection picker then changes it (asking about its own session's transaction first).
     */
    private void selectActiveConnection() {
        SqlEditorPanel editor = getActiveEditor();
        if (editor == null) {
            JOptionPane.showMessageDialog(this, ObjectExplorerMessages.oe_choose_no_editor, ObjectExplorerMessages.oe_select_connection,
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<DBPDataSourceContainer> connections = new ArrayList<>();
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project != null) {
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                if (!container.isHidden() && !container.isTemporary()) {
                    connections.add(container);
                }
            }
        }
        connections.sort(Comparator.comparing(DBPDataSourceContainer::getName, String.CASE_INSENSITIVE_ORDER));
        DBPDataSourceContainer chosen = ObjectChooserDialog.choose(this, ObjectExplorerMessages.oe_select_connection, connections,
            editor.getDataSourceContainer(), DBPDataSourceContainer::getName, container -> NodeIcons.getIcon(container.getDriver().getIcon()));
        if (chosen != null && chosen != editor.getDataSourceContainer()) {
            JComboBox<DBPDataSourceContainer> combo = editor.getConnectionCombo();
            if (combo.getModel() instanceof DefaultComboBoxModel<DBPDataSourceContainer> model && model.getIndexOf(chosen) < 0) {
                // Created since the picker was filled
                model.addElement(chosen);
            }
            combo.setSelectedItem(chosen);
        }
    }

    /**
     * Query &gt; Select Database/Schema… (Ctrl+0, SWT): chooses the active query window's default database or schema from
     * the window's own picker list; the picker then changes it (the same transaction question as choosing it there).
     */
    private void selectActiveSchema() {
        SqlEditorPanel editor = getActiveEditor();
        if (editor == null) {
            JOptionPane.showMessageDialog(this, ObjectExplorerMessages.oe_choose_no_editor, ObjectExplorerMessages.oe_select_schema,
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<Object> combo = editor.getSchemaCombo();
        List<DBSObject> objects = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i) instanceof DBSObject object) {
                objects.add(object);
            }
        }
        if (!combo.isEnabled() || objects.isEmpty()) {
            JOptionPane.showMessageDialog(this, ObjectExplorerMessages.oe_choose_schema_unavailable, ObjectExplorerMessages.oe_select_schema,
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        DBSObject current = combo.getSelectedItem() instanceof DBSObject selected ? selected : null;
        DBSObject chosen = ObjectChooserDialog.choose(this, ObjectExplorerMessages.oe_select_schema, objects, current,
            DBSObject::getName, object -> {
                DBNDatabaseNode node = DBWorkbench.getPlatform().getNavigatorModel().getNodeByObject(object);
                return node != null ? NodeIcons.getNodeIcon(node, false) : null;
            });
        if (chosen != null && chosen != current) {
            combo.setSelectedItem(chosen);
        }
    }

    private void addToolDocument(@NotNull ToolDocument document) {
        JComponent component = (JComponent) document;
        component.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        documentTabs.addTab(document.getTitle(), document.getIcon(), component);
        int index = documentTabs.indexOfComponent(component);
        documentTabs.setToolTipTextAt(index, document.getToolTip());
        documentTabs.setSelectedIndex(index);
    }

    /**
     * Tools menu and the tool bar: the Activity Monitor of the connection selected in Object Explorer (or of the active editor).
     */
    private void openActivityMonitor(int tab) {
        DBPDataSourceContainer dataSource = explorerPanel.getSelectedDataSource();
        if (dataSource == null) {
            SqlEditorPanel activeEditor = getActiveEditor();
            dataSource = activeEditor != null ? activeEditor.getDataSourceContainer() : null;
        }
        if (dataSource == null) {
            JOptionPane.showMessageDialog(this, CoreMessages.common_select_connection_first,
                CoreMessages.main_activity_monitor, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        openActivityMonitor(dataSource, tab);
    }

    @Override
    public void designTable(@NotNull DBSEntity table) {
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof TableDesignerPanel designer && designer.getSession().getTable() == table) {
                documentTabs.setSelectedIndex(i);
                return;
            }
        }
        addDesigner(TableDesignerPanel.open(this, table));
    }

    @Override
    public void newTable(@NotNull DBNDatabaseFolder folder) {
        addDesigner(TableDesignerPanel.createTable(this, folder));
    }

    private void addDesigner(@Nullable TableDesignerPanel designer) {
        if (designer == null) {
            return;
        }
        designer.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        documentTabs.addTab(designer.getTitle(), UiIcons.get("table"), designer); //$NON-NLS-1$
        int index = documentTabs.indexOfComponent(designer);
        documentTabs.setToolTipTextAt(index, designer.getToolTip());
        documentTabs.setSelectedIndex(index);
    }

    @Override
    public void designerChanged(@NotNull TableDesignerPanel designer) {
        int index = documentTabs.indexOfComponent(designer);
        if (index >= 0) {
            documentTabs.setTitleAt(index, designer.getTitle());
            documentTabs.setToolTipTextAt(index, designer.getToolTip());
        }
        // Modified or saved: Save All
        saveAllAction.setEnabled(hasModifiedDocuments());
    }

    /**
     * Adds a query window tab.
     *
     * @param dataSource connection of the window, or null (chosen in its tool bar)
     * @param fileName   name of the window, or null for the next {@code SQLQueryN.sql}; a given {@code SQLQueryN.sql} (a restored
     *                   window) moves the counter past N
     */
    @NotNull
    private SqlEditorPanel openQueryEditor(@Nullable DBPDataSourceContainer dataSource, @Nullable String fileName) {
        if (fileName == null) {
            queryCounter++;
            fileName = QUERY_NAME_PREFIX + queryCounter + QUERY_NAME_SUFFIX;
        } else if (fileName.startsWith(QUERY_NAME_PREFIX) && fileName.endsWith(QUERY_NAME_SUFFIX)) {
            // A window restored after a restart keeps its SQLQueryN name: the next new window does not take it again
            int number = CommonUtils.toInt(fileName.substring(QUERY_NAME_PREFIX.length(), fileName.length() - QUERY_NAME_SUFFIX.length()), 0);
            queryCounter = Math.max(queryCounter, number);
        }
        SqlEditorPanel editor = new SqlEditorPanel(dataSource, fileName, this);
        aiAssistantPanel.installEditorActions(editor);
        // Link with Editor follows the window's default database/schema (chosen, or read again into a new picker model)
        editor.getSchemaCombo().addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && editor == getActiveEditor()) {
                linkExplorerToDocument();
            }
        });
        editor.getSchemaCombo().addPropertyChangeListener("model", e -> { //$NON-NLS-1$
            if (editor == getActiveEditor()) {
                linkExplorerToDocument();
            }
        });
        editor.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        documentTabs.addTab(editor.getTitle(), editor.getTabIcon(), editor);
        int index = documentTabs.indexOfComponent(editor);
        documentTabs.setToolTipTextAt(index, editor.getToolTip());
        documentTabs.setSelectedIndex(index);
        SwingUtilities.invokeLater(editor::focusEditor);
        return editor;
    }

    private void closeDocumentTab(int index) {
        Component component = documentTabs.getComponentAt(index);
        if (component instanceof SqlEditorPanel editor) {
            if (!confirmClose(editor)) {
                return;
            }
            editor.dispose();
            documentTabs.remove(editor);
        } else if (component instanceof DdlViewerPanel) {
            documentTabs.removeTabAt(index);
        } else if (component instanceof ToolDocument document) {
            if (!document.confirmClose()) {
                return;
            }
            document.dispose();
            // By component: the question may have run events that moved the tab
            documentTabs.remove(component);
        } else if (component instanceof TableDesignerPanel designer) {
            documentTabs.setSelectedComponent(designer);
            if (!designer.confirmClose()) {
                return;
            }
            designer.dispose();
            documentTabs.remove(designer);
        }
    }

    @NotNull
    private List<SqlEditorPanel> getEditors() {
        List<SqlEditorPanel> editors = new ArrayList<>();
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof SqlEditorPanel editor) {
                editors.add(editor);
            }
        }
        return editors;
    }

    private void connectionUpdated(@NotNull DBPDataSourceContainer container) {
        for (SqlEditorPanel editor : getEditors()) {
            if (editor.getDataSourceContainer() == container) {
                editor.connectionChanged();
            }
        }
        // Table designer tabs name their connection too (renamed connection)
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof TableDesignerPanel designer
                && designer.getSession().getTable().getDataSource().getContainer() == container) {
                designerChanged(designer);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // SQL files

    private void openFile() {
        JFileChooser chooser = createSqlFileChooser(CoreMessages.main_open_file_title);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile().toPath());
        }
    }

    /**
     * Opens a SQL file in a new query window, or shows the window that has it open already.
     */
    private void openFile(@NotNull Path path) {
        Path file = path.toAbsolutePath().normalize();
        lastDirectory = file.getParent();
        for (SqlEditorPanel editor : getEditors()) {
            if (file.equals(editor.getFile())) {
                documentTabs.setSelectedComponent(editor);
                RecentFiles.add(file);
                return;
            }
        }
        if (!Files.isRegularFile(file)) {
            RecentFiles.remove(file);
            JOptionPane.showMessageDialog(this, NLS.bind(CoreMessages.main_file_not_found, file), CoreMessages.main_open_file_title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        SqlFileContent content;
        try {
            content = SqlFileContent.read(file);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(CoreMessages.main_open_file_title, NLS.bind(CoreMessages.main_cant_open, file), e);
            return;
        }
        // A script linked to a connection (Scripts tool window) opens with it, wherever it is opened from
        DBPDataSourceContainer dataSource = ScriptFiles.getLink(file).connection();
        if (dataSource == null) {
            dataSource = explorerPanel.getSelectedDataSource();
        }
        if (dataSource == null) {
            SqlEditorPanel activeEditor = getActiveEditor();
            dataSource = activeEditor != null ? activeEditor.getDataSourceContainer() : null;
        }
        SqlEditorPanel editor = openQueryEditor(dataSource, file.getFileName().toString());
        editor.setContent(content, file);
        RecentFiles.add(file);
    }

    /**
     * Saves a query window to its file, or asks for a file (Save As, or never saved).
     *
     * @return false if it was not saved (canceled or failed)
     */
    private boolean saveEditor(@NotNull SqlEditorPanel editor, boolean saveAs) {
        // A Query Library query is saved to the library (with its header); Save As exports its SQL as a plain file
        if (editor.getExtension() != null) {
            return saveAs ? editor.getExtension().saveAs(editor) : editor.getExtension().save(editor);
        }
        Path file = editor.getFile();
        if (saveAs || file == null) {
            JFileChooser chooser = createSqlFileChooser(saveAs ? CoreMessages.main_save_as_title : CoreMessages.common_save);
            File directory = file != null ? file.getParent().toFile() : chooser.getCurrentDirectory();
            chooser.setSelectedFile(new File(directory, editor.getFileName()));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return false;
            }
            Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            if (!selected.getFileName().toString().contains(".")) {
                selected = selected.resolveSibling(selected.getFileName() + ".sql"); //$NON-NLS-1$
            }
            if (!selected.equals(file) && Files.exists(selected) && !SwingPlatformUI.confirmChange(this,
                NLS.bind(CoreMessages.main_file_exists, selected.getFileName()), CoreMessages.main_save_as_title,
                CoreMessages.common_replace, JOptionPane.WARNING_MESSAGE)) {
                return false;
            }
            file = selected;
        }
        SqlFileContent content = editor.getContent();
        try {
            content.write(file);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError(CoreMessages.common_save, NLS.bind(CoreMessages.main_cant_save, file), e);
            return false;
        }
        editor.saved(file, content);
        lastDirectory = file.getParent();
        RecentFiles.add(file);
        if (ScriptFiles.findRoot(file) != null) {
            // A script saved into the script folder remembers the connection it was written for, unless the user linked
            // it already (an explicit link is not replaced by the connection of whichever window saves it)
            DBPDataSourceContainer connection = editor.getDataSourceContainer();
            if (connection != null && ScriptFiles.getLink(file).dataSourceId() == null) {
                ScriptFiles.setLink(file, connection);
            }
            scriptsPanel.reload();
        }
        return true;
    }

    /**
     * File &gt; Save All and the tool bar: every modified query window is saved like Save (a query that was never saved
     * asks for its file), every table designer with changes with its own Save (change script preview). Each document is
     * brought to the front while it is saved; the first one that is not saved (canceled or failed) stops the others.
     */
    private void saveAll() {
        Component selected = documentTabs.getSelectedComponent();
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            Component component = documentTabs.getComponentAt(i);
            if (component instanceof SqlEditorPanel editor && editor.isDirty()) {
                documentTabs.setSelectedComponent(editor);
                if (!saveEditor(editor, false)) {
                    return;
                }
            } else if (component instanceof TableDesignerPanel designer && designer.isDirty()) {
                documentTabs.setSelectedComponent(designer);
                if (!designer.save()) {
                    return;
                }
            }
        }
        if (selected != null && documentTabs.indexOfComponent(selected) >= 0) {
            documentTabs.setSelectedComponent(selected);
        }
    }

    private boolean hasModifiedDocuments() {
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            Component component = documentTabs.getComponentAt(i);
            if (component instanceof SqlEditorPanel editor && editor.isDirty()
                || component instanceof TableDesignerPanel designer && designer.isDirty()) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private JFileChooser createSqlFileChooser(@NotNull String title) {
        JFileChooser chooser = new JFileChooser(lastDirectory != null ? lastDirectory.toFile() : null);
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter(CoreMessages.main_sql_files, "sql")); //$NON-NLS-1$
        return chooser;
    }

    /**
     * Asks to save a modified query window before it is closed, then what to do with the uncommitted transaction of its
     * own session, which closes with the window ({@link SqlEditorPanel#confirmEndSession}).
     *
     * @return true if the window may be closed
     */
    private boolean confirmClose(@NotNull SqlEditorPanel editor) {
        if (!confirmResultChanges(editor)) {
            return false;
        }
        if (editor.isDirty()) {
            documentTabs.setSelectedComponent(editor);
            boolean saved = switch (askSave(NLS.bind(CoreMessages.main_save_changes, editor.getFileName()), false)) {
                case 0 -> saveEditor(editor, false);
                case 1 -> true;
                default -> false;
            };
            if (!saved) {
                return false;
            }
        }
        return editor.confirmEndSession();
    }

    /**
     * Asks to save the unsaved changes in a query window's result grids, like its text (Save / Don't Save / Cancel).
     *
     * @return true if the window may be closed
     */
    private boolean confirmResultChanges(@NotNull SqlEditorPanel editor) {
        if (!checkNotSavingResults(editor)) {
            return false;
        }
        if (!editor.hasPendingResultChanges()) {
            return true;
        }
        documentTabs.setSelectedComponent(editor);
        return switch (askSave(NLS.bind(CoreMessages.main_save_result_changes, editor.getFileName(), describeResultChanges(editor)), true)) {
            case 0 -> editor.saveResultChanges();
            case 1 -> true;
            default -> false;
        };
    }

    /**
     * A query window whose results are being saved stays open until the save is over.
     */
    private boolean checkNotSavingResults(@NotNull SqlEditorPanel editor) {
        if (!editor.isSavingResults()) {
            return true;
        }
        documentTabs.setSelectedComponent(editor);
        JOptionPane.showMessageDialog(this, NLS.bind(CoreMessages.main_results_being_saved, editor.getFileName()),
            GeneralUtils.getProductName(), JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    @NotNull
    private static String describeResultChanges(@NotNull SqlEditorPanel editor) {
        int changes = editor.getPendingResultChangeCount();
        return NLS.bind(changes == 1 ? CoreMessages.main_result_change : CoreMessages.main_result_changes, editor.getFileName(), changes);
    }

    /**
     * Window closing, File > Exit and an update's [Restart now]: asks to save the modified query windows and the unsaved
     * changes of their result grids (SSMS lists them in one question), then lets the tool documents end their own
     * sessions ({@link ToolDocument#confirmClose}: the debugger), then what to do with jobs that still run
     * ({@link RunningJobsCheck}), then with uncommitted transactions ({@link ExitTransactionCheck}), then closes the window.
     */
    private void requestExit() {
        // Table designers ask one by one: saving shows each change script
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof TableDesignerPanel designer && designer.isDirty()) {
                documentTabs.setSelectedComponent(designer);
                if (!designer.confirmClose()) {
                    return;
                }
            }
        }
        List<SqlEditorPanel> modified = new ArrayList<>();
        List<SqlEditorPanel> resultsModified = new ArrayList<>();
        for (SqlEditorPanel editor : getEditors()) {
            if (!checkNotSavingResults(editor)) {
                return;
            }
            if (editor.isDirty()) {
                modified.add(editor);
            }
            if (editor.hasPendingResultChanges()) {
                resultsModified.add(editor);
            }
        }
        if (!modified.isEmpty() || !resultsModified.isEmpty()) {
            StringBuilder message = new StringBuilder(CoreMessages.main_save_following);
            for (SqlEditorPanel editor : modified) {
                message.append('\n').append(editor.getFile() != null ? editor.getFile().toString() : editor.getFileName());
            }
            for (SqlEditorPanel editor : resultsModified) {
                message.append('\n').append(describeResultChanges(editor));
            }
            int answer = askSave(message.toString(), !resultsModified.isEmpty());
            if (answer == 0) {
                for (SqlEditorPanel editor : resultsModified) {
                    documentTabs.setSelectedComponent(editor);
                    if (!editor.saveResultChanges()) {
                        return;
                    }
                }
                for (SqlEditorPanel editor : modified) {
                    documentTabs.setSelectedComponent(editor);
                    if (!saveEditor(editor, false)) {
                        return;
                    }
                }
            } else if (answer != 1) {
                return;
            }
        }
        // Tool documents that end sessions of their own (the debugger's) ask first, one by one
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof ToolDocument document) {
                documentTabs.setSelectedIndex(i);
                if (!document.confirmClose()) {
                    return;
                }
            }
        }
        // Running queries, exports and tasks: wait for them, stop them, or keep Chromite open (before the transactions,
        // which can be ended only once their statements stopped)
        if (!RunningJobsCheck.confirm(this)) {
            return;
        }
        // Closing ends every session: open transactions are committed or rolled back only as the user says
        if (!ExitTransactionCheck.confirm(this)) {
            return;
        }
        dispose();
    }

    /**
     * @param resultChanges Save also writes result grid changes to the database (DML, without a preview when the connection
     *                      does not ask for one): Cancel is then the default button, so that Enter does not run it
     * @return 0 Save, 1 Don't Save, anything else Cancel
     */
    private int askSave(@NotNull String message, boolean resultChanges) {
        String[] options = {CoreMessages.common_save, CoreMessages.common_dont_save, CoreMessages.common_cancel};
        return JOptionPane.showOptionDialog(this, message, GeneralUtils.getProductName(), JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE, null, options, resultChanges ? options[2] : options[0]);
    }

    /**
     * File > Recent Files, rebuilt each time the File menu opens.
     */
    private void fillRecentFilesMenu(@NotNull JMenu recentMenu) {
        recentMenu.removeAll();
        List<Path> files = RecentFiles.get();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            JMenuItem item = new JMenuItem((i + 1) + " " + file);
            if (i < 9) {
                item.setMnemonic(KeyEvent.VK_1 + i);
            }
            item.addActionListener(e -> openFile(file));
            recentMenu.add(item);
        }
        recentMenu.setEnabled(!files.isEmpty());
    }

    //////////////////////////////////////////////////////////
    // Query Library

    @NotNull
    @Override
    public Component getComponent() {
        return this;
    }

    @Nullable
    @Override
    public DBPDataSourceContainer getCurrentConnection() {
        DBPDataSourceContainer dataSource = explorerPanel.getSelectedDataSource();
        SqlEditorPanel activeEditor = getActiveEditor();
        return dataSource != null ? dataSource : activeEditor != null ? activeEditor.getDataSourceContainer() : null;
    }

    @NotNull
    @Override
    public List<SqlEditorPanel> getQueryWindows() {
        return getEditors();
    }

    @Nullable
    @Override
    public SqlEditorPanel getActiveQueryWindow() {
        return getActiveEditor();
    }

    @Override
    public void showQueryWindow(@NotNull SqlEditorPanel editor) {
        documentTabs.setSelectedComponent(editor);
    }

    @NotNull
    @Override
    public SqlEditorPanel openQueryWindow(@Nullable DBPDataSourceContainer container, @NotNull String fileName) {
        return openQueryEditor(container, fileName);
    }

    @Nullable
    private SqlEditorPanel getActiveEditor() {
        return documentTabs.getSelectedComponent() instanceof SqlEditorPanel editor ? editor : null;
    }

    /**
     * Text of the active document tab (query window or DDL tab), for Edit &gt; Find / Replace.
     */
    @Nullable
    private SqlEditorPanel.SqlTextArea getActiveTextArea() {
        Component component = documentTabs.getSelectedComponent();
        if (component instanceof SqlEditorPanel editor) {
            return editor.getTextArea();
        }
        return component instanceof DdlViewerPanel viewer ? viewer.getTextArea() : null;
    }

    private void activeEditorChanged() {
        SqlEditorPanel editor = getActiveEditor();
        switchingResults = true;
        try {
            // No query window: the tabs let go of the last editor's components (the Results card is hidden)
            attachResultTab(RESULTS_TAB, editor != null ? editor.getResultsView().getResultsComponent() : null);
            attachResultTab(MESSAGES_TAB, editor != null ? editor.getResultsView().getMessagesComponent() : null);
            attachResultTab(PLAN_TAB, editor != null ? editor.getPlanView().getComponent() : null);
        } finally {
            switchingResults = false;
        }
        selectResultTab(editor);
        // Before the status line, which goes below the results only while they are shown
        resultsSplit.update();
        attachStatusLine(editor != null ? editor.getStatusLine() : null);
        outlinePanel.setTextArea(getActiveTextArea());
        sqlEditorToolBar.setActiveEditor(editor);
        if (editor != null) {
            // A window that shares the connection's main context shares its commit mode with the other windows
            editor.refreshTransactionMode();
        }
        updateQueryActions();
        linkExplorerToDocument();
    }

    /**
     * Shows the Terminal tab of the window (or none) and brings back the result tab the window showed last.
     */
    private void selectResultTab(@Nullable SqlEditorPanel editor) {
        switchingResults = true;
        try {
            updateTerminalTab(editor);
            resultTabs.setSelectedIndex(editor != null && editor.isTerminalEnabled() && editor.isTerminalTabSelected() ? TERMINAL_TAB
                : editor != null && editor.isPlanTabSelected() ? PLAN_TAB
                : editor != null && editor.getResultsView().isMessagesTabSelected() ? MESSAGES_TAB : RESULTS_TAB);
        } finally {
            switchingResults = false;
        }
    }

    /**
     * The Terminal tab is there only while the active query window has Query &gt; SQL Terminal on, with that window's
     * terminal. Call with {@link #switchingResults} set: removing the selected tab selects another one.
     */
    private void updateTerminalTab(@Nullable SqlEditorPanel editor) {
        if (editor != null && editor.isTerminalEnabled()) {
            if (resultTabs.getTabCount() <= TERMINAL_TAB) {
                resultTabs.addTab(TerminalMessages.terminal_tab, UiIcons.get("terminal"), null); //$NON-NLS-1$
            }
            attachResultTab(TERMINAL_TAB, editor.getTerminalView().getComponent());
        } else if (resultTabs.getTabCount() > TERMINAL_TAB) {
            resultTabs.removeTabAt(TERMINAL_TAB);
        }
    }

    /**
     * Puts an editor's results component into the Results / Messages tab. Components of inactive editors are not in
     * the window and miss theme switches, so their look and feel is brought up to date here (also for the status line).
     */
    private void attachResultTab(int index, @Nullable JComponent component) {
        if (resultTabs.getComponentAt(index) == component) {
            return;
        }
        if (component != null) {
            updateLookAndFeel(component);
        }
        resultTabs.setComponentAt(index, component);
    }

    /**
     * Brings a component that was out of the window (and missed theme switches) up to the current look and feel; also
     * used by {@link SqlEditorToolBar} for the pickers it puts in.
     */
    static void updateLookAndFeel(@NotNull JComponent component) {
        LookAndFeel lookAndFeel = UIManager.getLookAndFeel();
        if (component.getClientProperty(UPDATED_LAF_KEY) != lookAndFeel) {
            SwingUtilities.updateComponentTreeUI(component);
            component.putClientProperty(UPDATED_LAF_KEY, lookAndFeel);
        }
    }

    /**
     * Shows the active editor's query status line at the bottom of the query window; other documents (DDL) and the empty document area
     * have none.
     */
    private void attachStatusLine(@Nullable JComponent statusLine) {
        if (statusLine == attachedStatusLine) {
            return;
        }
        attachedStatusLine = statusLine;
        if (statusLine != null) {
            updateLookAndFeel(statusLine);
        }
        placeStatusLine();
    }

    /**
     * Puts the status line below the results (SSMS), or below the editor while the Results card is hidden.
     */
    private void placeStatusLine() {
        JPanel target = resultsWindow != null && resultsWindow.isShown() ? resultsStatusHolder : documentStatusHolder;
        for (JPanel holder : List.of(resultsStatusHolder, documentStatusHolder)) {
            boolean show = holder == target && attachedStatusLine != null;
            if (show && (holder.getComponentCount() == 0 || holder.getComponent(0) != attachedStatusLine)) {
                holder.removeAll();
                holder.add(attachedStatusLine, BorderLayout.CENTER);
            } else if (!show) {
                holder.removeAll();
            }
            holder.setVisible(show);
            holder.revalidate();
            holder.repaint();
        }
    }

    private void updateQueryActions() {
        SqlEditorPanel editor = getActiveEditor();
        // Also the SQL Editor tool bar's buttons
        executeQueryAction.setEnabled(editor != null && editor.canExecute());
        cancelQueryAction.setEnabled(editor != null && editor.canCancel());
        statementRunActions.forEach(action -> action.setEnabled(editor != null && editor.canExecute()));
        statementEditActions.forEach(action -> action.setEnabled(editor != null));
        variablesAction.setEnabled(editor != null && !editor.isExecuting());
        completeAction.setEnabled(editor != null);
        estimatedPlanAction.setEnabled(editor != null && editor.canExplain());
        // A connection that gives no actual plans (they would need a second run of the statements) has the toggle off,
        // with the reason as its tool tip; before connecting it is not known yet
        boolean actualPlanUnsupported = editor != null && Boolean.FALSE.equals(editor.getActualPlanSupport());
        actualPlanAction.setEnabled(editor != null && !actualPlanUnsupported);
        actualPlanAction.putValue(Action.SELECTED_KEY, editor != null && !actualPlanUnsupported && editor.isIncludeActualPlan());
        actualPlanAction.putValue(Action.SHORT_DESCRIPTION,
            actualPlanUnsupported ? EditorMessages.editor_sql_actual_plan_unsupported : CoreMessages.main_actual_plan_tip);
        if (sqlEditorToolBar != null) {
            sqlEditorToolBar.setActualPlanUnsupported(actualPlanUnsupported);
        }
        terminalAction.setEnabled(editor != null);
        terminalAction.putValue(Action.SELECTED_KEY, editor != null && editor.isTerminalEnabled());
        // Ending the transaction needs it idle: a running statement or script of this window would be refused
        QMTransactionState transaction = editor == null ? null : editor.getOpenTransaction();
        boolean canEndTransaction = transaction != null && !transaction.isExecuting() && !editor.canCancel();
        commitAction.setEnabled(canEndTransaction);
        rollbackAction.setEnabled(canEndTransaction);
        transactionLogAction.setEnabled(editor != null);
        Boolean autoCommit = editor == null ? null : editor.getAutoCommit();
        commitModeAction.setEnabled(editor != null && editor.canSwitchCommitMode());
        commitModeAction.putValue(Action.SELECTED_KEY, Boolean.TRUE.equals(autoCommit));
        commitModeAction.putValue(Action.SHORT_DESCRIPTION, CommitModeSwitch.describe(autoCommit, editor != null && editor.isSharedSession()));
        sqlEditorToolBar.setCommitMode(autoCommit);
        pinResultsAction.setEnabled(editor != null);
        resultsBesideAction.putValue(Action.SELECTED_KEY, resultsSplit.isBeside());
        maximizeResultsAction.setEnabled(editor != null);
        maximizeResultsAction.putValue(Action.SELECTED_KEY, resultsSplit.isMaximized());
        saveAction.setEnabled(editor != null || documentTabs.getSelectedComponent() instanceof TableDesignerPanel);
        saveAsAction.setEnabled(editor != null);
        // Results belong to a query window: without one, Ctrl+R and the View menu item are disabled and say why
        Action resultsToggle = resultsWindow.getToggleAction();
        resultsToggle.setEnabled(editor != null);
        resultsToggle.putValue(Action.SHORT_DESCRIPTION, editor != null ? null : CoreMessages.main_results_unavailable_tip);
        saveAllAction.setEnabled(hasModifiedDocuments());
        updateActivityMonitorAction();
        editMenu.activeDocumentChanged();
    }

    /**
     * Activity Monitor needs a connection: the one selected in Object Explorer, or the active query window's. Without one
     * it is disabled and its tool tip says what to do.
     */
    private void updateActivityMonitorAction() {
        // The Object Explorer selection listener may run while the window is being built
        if (activityMonitorAction == null) {
            return;
        }
        boolean enabled = getCurrentConnection() != null;
        activityMonitorAction.setEnabled(enabled);
        activityMonitorAction.putValue(Action.SHORT_DESCRIPTION,
            enabled ? ToolbarMessages.toolbar_activity_monitor_tip : ToolbarMessages.toolbar_activity_monitor_disabled_tip);
    }

    @Override
    public void showResults(@NotNull SqlEditorPanel editor, boolean messages) {
        if (editor != getActiveEditor()) {
            return;
        }
        editor.getResultsView().setMessagesTabSelected(messages);
        editor.setPlanTabSelected(false);
        editor.setTerminalTabSelected(false);
        resultTabs.setSelectedIndex(messages ? MESSAGES_TAB : RESULTS_TAB);
        resultsWindow.setShown(true);
    }

    @Override
    public void revealNode(@NotNull DBNDatabaseNode node) {
        // Object Explorer is the first tool window
        toolWindows.get(0).setShown(true);
        explorerPanel.revealNode(node);
    }

    @Override
    public void showPlan(@NotNull SqlEditorPanel editor) {
        if (editor != getActiveEditor()) {
            return;
        }
        editor.setPlanTabSelected(true);
        editor.setTerminalTabSelected(false);
        resultTabs.setSelectedIndex(PLAN_TAB);
        resultsWindow.setShown(true);
    }

    @Override
    public void showTerminal(@NotNull SqlEditorPanel editor) {
        if (editor != getActiveEditor()) {
            return;
        }
        if (!editor.isTerminalEnabled()) {
            // Turned off while a run that started with it was going on: its output still goes to the (hidden) terminal
            showResults(editor, false);
            return;
        }
        editor.setPlanTabSelected(false);
        editor.setTerminalTabSelected(true);
        selectResultTab(editor);
        resultsWindow.setShown(true);
    }

    /**
     * Query History: opens a recorded statement in a new query window of its connection. A bound statement (Query
     * Library) binds its recorded values again: Execute runs with them, a later run asks with them filled in.
     */
    private void openHistoryQuery(@Nullable DBPDataSourceContainer container, @NotNull QueryHistory.Entry entry, boolean execute) {
        SqlEditorPanel editor = openQueryEditor(container, null);
        editor.setContent(SqlFileContent.newFile(entry.query()), null);
        if (entry.parameters() != null) {
            editor.setRecordedParameters(new QueryParameterBinder.Recorded(entry.parameters()), execute);
        }
        if (execute) {
            editor.execute();
        }
        toFront();
    }

    @Override
    public void executionStateChanged(@NotNull SqlEditorPanel editor) {
        if (editor == getActiveEditor()) {
            updateQueryActions();
        }
    }

    @Override
    public void titleChanged(@NotNull SqlEditorPanel editor) {
        int index = documentTabs.indexOfComponent(editor);
        if (index >= 0) {
            documentTabs.setTitleAt(index, editor.getTitle());
            documentTabs.setToolTipTextAt(index, editor.getToolTip());
            documentTabs.setIconAt(index, editor.getTabIcon());
        }
        // Modified or saved (Save All), or another connection (Activity Monitor, Link with Editor)
        saveAllAction.setEnabled(hasModifiedDocuments());
        updateActivityMonitorAction();
        if (editor == getActiveEditor()) {
            linkExplorerToDocument();
        }
    }

    /**
     * Query &gt; Query History and the SQL Editor tool bar's Query History button.
     */
    private void openQueryHistory() {
        QueryHistoryDialog.open(this, this::openHistoryQuery);
    }

    /**
     * Center document area: the document tabs (all closable). Without tabs the area shows {@link WelcomePanel}.
     */
    @NotNull
    private JTabbedPane createDocumentTabs() {
        JTabbedPane tabs = new EditorTabbedPane(this::updateDocumentCard);
        // One row that scrolls, like SSMS; wrapped rows would move the selected tab's row around
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // DataGrip editor tabs: size.tab.editor; the selected tab is an Islands pill (IslandTabbedPaneUI), no underline
        tabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.DOCUMENT_TABS_STYLE);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK, (IntConsumer) this::closeDocumentTab);
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                // Middle click closes a tab, as in DataGrip and SSMS
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    int index = tabs.indexAtLocation(e.getX(), e.getY());
                    if (index >= 0 && tabs.getComponentAt(index) instanceof JComponent component
                        && Boolean.TRUE.equals(component.getClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE))) {
                        closeDocumentTab(index);
                    }
                }
            }
        });
        return tabs;
    }

    @NotNull
    private JMenuBar createMenuBar() {
        JMenuItem exitItem = new JMenuItem(CoreMessages.main_menu_exit);
        exitItem.setMnemonic(KeyEvent.VK_X);
        exitItem.addActionListener(e -> requestExit());

        JMenu recentMenu = new JMenu(CoreMessages.main_menu_recent_files);
        recentMenu.setMnemonic(KeyEvent.VK_R);
        JMenu fileMenu = new JMenu(CoreMessages.main_menu_file);
        fileMenu.setMnemonic(KeyEvent.VK_F);
        fileMenu.add(new JMenuItem(explorerPanel.getNewConnectionAction()));
        fileMenu.add(new JMenuItem(newQueryAction));
        fileMenu.addSeparator();
        fileMenu.add(new JMenuItem(openFileAction));
        fileMenu.add(recentMenu);
        fileMenu.addSeparator();
        fileMenu.add(new JMenuItem(saveAction));
        fileMenu.add(new JMenuItem(saveAsAction));
        fileMenu.add(new JMenuItem(saveAllAction));
        fileMenu.addSeparator();
        JMenu importConnectionsMenu = new JMenu(ConnectionTransferMessages.transfer_menu_import_group);
        JMenuItem importConnectionsItem = new JMenuItem(ConnectionTransferMessages.transfer_menu_import_file);
        importConnectionsItem.addActionListener(e -> ConnectionTransferCommands.importConnections(this));
        importConnectionsMenu.add(importConnectionsItem);
        JMenuItem importOtherToolItem = new JMenuItem(ConnectionTransferMessages.transfer_menu_import_other);
        importOtherToolItem.addActionListener(e -> ConnectionTransferCommands.importFromOtherTool(this));
        importConnectionsMenu.add(importOtherToolItem);
        fileMenu.add(importConnectionsMenu);
        JMenuItem exportConnectionsItem = new JMenuItem(ConnectionTransferMessages.transfer_menu_export);
        exportConnectionsItem.addActionListener(e -> ConnectionTransferCommands.exportConnections(this, null));
        fileMenu.add(exportConnectionsItem);
        fileMenu.addSeparator();
        JMenuItem exportSettingsItem = new JMenuItem(SettingsTransferMessages.settings_menu_export);
        exportSettingsItem.addActionListener(e -> SettingsTransferCommands.exportSettings(this));
        fileMenu.add(exportSettingsItem);
        JMenuItem importSettingsItem = new JMenuItem(SettingsTransferMessages.settings_menu_import);
        importSettingsItem.addActionListener(e -> SettingsTransferCommands.importSettings(this, () -> scriptsPanel.reload()));
        fileMenu.add(importSettingsItem);
        JMenuItem undoSettingsItem = new JMenuItem(SettingsTransferMessages.settings_menu_undo);
        undoSettingsItem.addActionListener(e -> SettingsTransferCommands.undoImport(this, () -> scriptsPanel.reload()));
        fileMenu.add(undoSettingsItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        onMenuSelected(fileMenu, () -> fillRecentFilesMenu(recentMenu));
        fillRecentFilesMenu(recentMenu);

        JMenu queryMenu = new JMenu(CoreMessages.main_menu_query);
        queryMenu.setMnemonic(KeyEvent.VK_Q);
        queryMenu.add(new JMenuItem(executeQueryAction));
        queryMenu.add(new JMenuItem(cancelQueryAction));
        queryMenu.addSeparator();
        statementRunActions.forEach(action -> queryMenu.add(new JMenuItem(action)));
        queryMenu.addSeparator();
        statementEditActions.forEach(action -> queryMenu.add(new JMenuItem(action)));
        queryMenu.addSeparator();
        queryMenu.add(new JMenuItem(completeAction));
        JCheckBoxMenuItem autoCompleteItem = new JCheckBoxMenuItem(CoreMessages.main_auto_complete, SqlCompletionPopup.isAutoActivateOnLetters());
        autoCompleteItem.setToolTipText(CoreMessages.main_auto_complete_tip);
        autoCompleteItem.addActionListener(e -> SqlCompletionPopup.setAutoActivateOnLetters(autoCompleteItem.isSelected()));
        queryMenu.add(autoCompleteItem);
        queryMenu.addSeparator();
        queryMenu.add(new JMenuItem(estimatedPlanAction));
        queryMenu.add(new JCheckBoxMenuItem(actualPlanAction));
        queryMenu.add(new JCheckBoxMenuItem(terminalAction));
        queryMenu.addSeparator();
        queryMenu.add(new JMenuItem(debugAction));
        queryMenu.add(new JMenuItem(traceAction));
        queryMenu.add(new JMenuItem(nativeScriptAction));
        queryMenu.addSeparator();
        // SWT shortcuts of the active connection and schema selectors
        JMenuItem selectConnectionItem = new JMenuItem(ObjectExplorerMessages.oe_select_connection);
        selectConnectionItem.setToolTipText(ObjectExplorerMessages.oe_select_connection_tip);
        selectConnectionItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_9, InputEvent.CTRL_DOWN_MASK));
        selectConnectionItem.addActionListener(e -> selectActiveConnection());
        queryMenu.add(selectConnectionItem);
        JMenuItem selectSchemaItem = new JMenuItem(ObjectExplorerMessages.oe_select_schema);
        selectSchemaItem.setToolTipText(ObjectExplorerMessages.oe_select_schema_tip);
        selectSchemaItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK));
        selectSchemaItem.addActionListener(e -> selectActiveSchema());
        queryMenu.add(selectSchemaItem);
        queryMenu.addSeparator();
        queryMenu.add(new JMenuItem(commitAction));
        queryMenu.add(new JMenuItem(rollbackAction));
        queryMenu.add(new JMenuItem(transactionLogAction));
        queryMenu.add(new JCheckBoxMenuItem(commitModeAction));
        queryMenu.addSeparator();
        queryMenu.add(new JMenuItem(pinResultsAction));
        queryMenu.addSeparator();
        JMenuItem historyItem = new JMenuItem(CoreMessages.main_query_history, UiIcons.get("history")); //$NON-NLS-1$
        historyItem.setToolTipText(CoreMessages.main_query_history_tip);
        historyItem.addActionListener(e -> openQueryHistory());
        queryMenu.add(historyItem);
        queryMenu.add(new JMenuItem(queryLibraryPanel.getSaveToLibraryAction()));
        queryMenu.add(new JMenuItem(variablesAction));
        // Tools > Options changes the same setting
        onMenuSelected(queryMenu, () -> {
            autoCompleteItem.setSelected(SqlCompletionPopup.isAutoActivateOnLetters());
            SqlEditorPanel editor = getActiveEditor();
            debugAction.setEnabled(editor != null && DebugCommands.canDebug(editor.getSourceObject()));
            traceAction.setEnabled(editor != null && ExecutionTraceCommands.canTrace(editor.getSourceObject()));
            nativeScriptAction.setEnabled(editor != null && BackupRestoreCommands.findEditorTarget(editor) != null);
        });

        JMenu viewMenu = new JMenu(CoreMessages.main_menu_view);
        viewMenu.setMnemonic(KeyEvent.VK_V);
        for (ToolWindow toolWindow : toolWindows) {
            viewMenu.add(new JCheckBoxMenuItem(toolWindow.getToggleAction()));
        }
        viewMenu.add(new JCheckBoxMenuItem(resultsBesideAction));
        viewMenu.add(new JCheckBoxMenuItem(maximizeResultsAction));
        viewMenu.addSeparator();
        viewMenu.add(createThemeMenu());
        viewMenu.add(createCompactModeItem());

        JMenuItem aboutItem = new JMenuItem(NLS.bind(CoreMessages.main_about, GeneralUtils.getProductName()));
        aboutItem.setMnemonic(KeyEvent.VK_A);
        aboutItem.addActionListener(e -> AboutDialog.open(this));

        JMenu helpMenu = new JMenu(CoreMessages.main_menu_help);
        helpMenu.setMnemonic(KeyEvent.VK_H);
        helpMenu.add(aboutItem);

        JMenuItem optionsItem = new JMenuItem(CoreMessages.main_options);
        optionsItem.setMnemonic(KeyEvent.VK_O);
        optionsItem.addActionListener(e -> OptionsDialog.open(this));
        JMenuItem activityMonitorItem = new JMenuItem(activityMonitorAction);
        activityMonitorItem.setMnemonic(KeyEvent.VK_A);
        JMenuItem locksItem = new JMenuItem(CoreMessages.main_locks);
        locksItem.setMnemonic(KeyEvent.VK_L);
        locksItem.addActionListener(e -> openActivityMonitor(ActivityMonitorPanel.LOCKS_TAB));
        JMenuItem dashboardItem = new JMenuItem(DashboardMessages.dashboard_command);
        dashboardItem.setToolTipText(DashboardMessages.dashboard_command_tip);
        dashboardItem.addActionListener(e -> openActivityMonitor(ActivityMonitorPanel.OVERVIEW_TAB));
        JMenuItem openTransactionsItem = new JMenuItem(CoreMessages.main_open_transactions);
        openTransactionsItem.setToolTipText(CoreMessages.main_open_transactions_tip);
        openTransactionsItem.addActionListener(e -> OpenTransactionsDialog.open(this));
        JMenuItem findObjectsItem = new JMenuItem(CoreMessages.main_find_objects);
        findObjectsItem.setMnemonic(KeyEvent.VK_F);
        findObjectsItem.addActionListener(e -> openObjectSearch(explorerPanel.getSelectedNode()));
        JMenuItem compareSchemasItem = new JMenuItem(CompareMessages.compare_command);
        compareSchemasItem.setToolTipText(CompareMessages.compare_command_tip);
        compareSchemasItem.addActionListener(e -> SchemaComparePanel.open(this, explorerPanel.getSelectedNode()));
        JMenuItem driverManagerItem = new JMenuItem(DriverManagerMessages.drivermgr_menu);
        driverManagerItem.setToolTipText(DriverManagerMessages.drivermgr_menu_tip);
        driverManagerItem.addActionListener(e -> DriverManagerDialog.open(this));
        JMenuItem networkProfilesItem = new JMenuItem(ConnectionMessages.profiles_menu);
        networkProfilesItem.setToolTipText(ConnectionMessages.profiles_menu_tip);
        networkProfilesItem.addActionListener(e -> NetworkProfilesDialog.open(this, DBWorkbench.getPlatform().getWorkspace().getActiveProject()));
        JMenu toolsMenu = new JMenu(CoreMessages.main_menu_tools);
        toolsMenu.setMnemonic(KeyEvent.VK_T);
        toolsMenu.add(activityMonitorItem);
        toolsMenu.add(locksItem);
        toolsMenu.add(dashboardItem);
        AdminViewCommands.addMenuItems(toolsMenu, this::openActivityMonitor);
        toolsMenu.add(openTransactionsItem);
        toolsMenu.add(findObjectsItem);
        toolsMenu.add(compareSchemasItem);
        toolsMenu.addSeparator();
        toolsMenu.add(driverManagerItem);
        toolsMenu.add(networkProfilesItem);
        toolsMenu.add(optionsItem);

        JMenu windowMenu = new JMenu(CoreMessages.main_menu_window);
        windowMenu.setMnemonic(KeyEvent.VK_W);
        onMenuSelected(windowMenu, () -> fillWindowMenu(windowMenu));
        fillWindowMenu(windowMenu);

        // SSMS order
        JMenuBar menuBar = new JMenuBar();
        menuBar.add(fileMenu);
        editMenu.getMenu().addSeparator();
        editMenu.getMenu().add(SqlEditCommands.createAdvancedMenu(this::getActiveTextArea));
        menuBar.add(editMenu.getMenu());
        menuBar.add(viewMenu);
        menuBar.add(queryMenu);
        menuBar.add(toolsMenu);
        menuBar.add(windowMenu);
        menuBar.add(helpMenu);
        return menuBar;
    }

    /**
     * Window menu (SSMS): Reset Window Layout, then the open document tabs (the active one checked), rebuilt each
     * time the menu opens.
     */
    private void fillWindowMenu(@NotNull JMenu windowMenu) {
        windowMenu.removeAll();
        JMenuItem resetItem = new JMenuItem(CoreMessages.main_reset_layout);
        resetItem.setMnemonic(KeyEvent.VK_R);
        resetItem.addActionListener(e -> resetWindowLayout());
        windowMenu.add(resetItem);
        if (documentTabs.getTabCount() > 0) {
            windowMenu.addSeparator();
        }
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            int index = i;
            // No tab icon: the radio mark shows the active tab
            JRadioButtonMenuItem item = new JRadioButtonMenuItem((i + 1) + " " + documentTabs.getTitleAt(i), i == documentTabs.getSelectedIndex());
            if (i < 9) {
                item.setMnemonic(KeyEvent.VK_1 + i);
            }
            item.setToolTipText(documentTabs.getToolTipTextAt(i));
            item.addActionListener(e -> {
                if (index < documentTabs.getTabCount()) {
                    documentTabs.setSelectedIndex(index);
                }
            });
            windowMenu.add(item);
        }
    }

    /**
     * Window &gt; Reset Window Layout: the default docking layout (floating tool windows docked again, Query Library
     * hidden) and the results with their default height, shown again in every query window (still hidden without one).
     */
    private void resetWindowLayout() {
        toolWindowDocking.resetLayout();
        for (int i = 0; i < documentTabs.getTabCount(); i++) {
            if (documentTabs.getComponentAt(i) instanceof SqlEditorPanel editor) {
                editor.putClientProperty(RESULTS_HIDDEN_KEY, null);
            }
        }
        resultsSplit.setBeside(false, false);
        resultsSplit.reset();
        updateQueryActions();
    }

    /**
     * The welcome content while no document tab is open (it comes back when the last one is closed). Called once a tab
     * add or remove has finished ({@link EditorTabbedPane}), because the card switch lays out the tab pane at once.
     */
    private void updateDocumentCard() {
        ((CardLayout) documentCards.getLayout()).show(documentCards, documentTabs.getTabCount() == 0 ? WELCOME_CARD : DOCUMENTS_CARD);
    }

    private static void onMenuSelected(@NotNull JMenu menu, @NotNull Runnable task) {
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                task.run();
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
    }

    @NotNull
    private static JMenu createThemeMenu() {
        JMenu themeMenu = new JMenu(CoreMessages.main_menu_theme);
        themeMenu.setMnemonic(KeyEvent.VK_T);
        ButtonGroup group = new ButtonGroup();
        ChromiteTheme current = ChromiteTheme.getCurrent();
        List<JRadioButtonMenuItem> themeItems = new ArrayList<>();
        for (ChromiteTheme theme : ChromiteTheme.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(theme.getLabel(), theme == current);
            item.setMnemonic(theme.getLabel().charAt(0));
            item.addActionListener(e -> {
                if (ChromiteTheme.getCurrent() != theme) {
                    theme.apply();
                }
            });
            group.add(item);
            themeMenu.add(item);
            themeItems.add(item);
        }
        themeMenu.addSeparator();
        JMenu iconStyleMenu = new JMenu(CoreMessages.main_menu_icon_style);
        iconStyleMenu.setMnemonic(KeyEvent.VK_I);
        ButtonGroup iconStyleGroup = new ButtonGroup();
        UiIconStyle currentIconStyle = UiIconStyle.getCurrent();
        for (UiIconStyle iconStyle : UiIconStyle.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(iconStyle.getLabel(), iconStyle == currentIconStyle);
            item.addActionListener(e -> {
                if (UiIconStyle.getCurrent() != iconStyle) {
                    iconStyle.apply();
                }
            });
            iconStyleGroup.add(item);
            iconStyleMenu.add(item);
        }
        themeMenu.add(iconStyleMenu);
        // Tools > Options changes the same settings
        onMenuSelected(themeMenu, () -> {
            ChromiteTheme theme = ChromiteTheme.getCurrent();
            UiIconStyle iconStyle = UiIconStyle.getCurrent();
            for (int i = 0; i < themeItems.size(); i++) {
                themeItems.get(i).setSelected(ChromiteTheme.values()[i] == theme);
            }
            for (int i = 0; i < iconStyleMenu.getItemCount(); i++) {
                iconStyleMenu.getItem(i).setSelected(UiIconStyle.values()[i] == iconStyle);
            }
        });
        return themeMenu;
    }

    /**
     * View &gt; Compact Mode: density C (lower rows, tool bars, tabs and status lines) instead of the default density B,
     * switched at runtime and saved ({@link UiDensity}).
     */
    @NotNull
    private static JMenuItem createCompactModeItem() {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(CoreMessages.main_menu_compact_mode, UiDensity.isCompact());
        item.setMnemonic(KeyEvent.VK_C);
        item.addActionListener(e -> {
            if (item.isSelected() != UiDensity.isCompact()) {
                UiDensity.apply(item.isSelected());
            }
        });
        return item;
    }

    private void rememberNormalBounds() {
        if (isShowing() && (getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) {
            normalBounds = getBounds();
        }
    }

    /**
     * Restores the window bounds and the tool windows from the preferences. Saved bounds that are no longer
     * on any screen (e.g. a disconnected monitor) are ignored.
     */
    private void restoreLayout() {
        DBPPreferenceStore preferences = getPreferences();
        Rectangle bounds = parseBounds(preferences.getString(PREF_BOUNDS));
        if (bounds != null && isOnScreen(bounds)) {
            setBounds(bounds);
        } else {
            setSize(DEFAULT_SIZE);
            setLocationRelativeTo(null);
        }
        normalBounds = getBounds();
        if (preferences.getBoolean(PREF_MAXIMIZED)) {
            setExtendedState(Frame.MAXIMIZED_BOTH);
        }
        toolWindowDocking.restoreLayout(preferences);
        // The size saved for the results is their width when they are beside the documents
        resultsSplit.setBeside(preferences.getBoolean(PREF_RESULTS_BESIDE), false);
        resultsSplit.load(preferences);
    }

    private void saveLayout() {
        DBPPreferenceStore preferences = getPreferences();
        Rectangle bounds = normalBounds != null ? normalBounds : getBounds();
        preferences.setValue(PREF_BOUNDS, bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        preferences.setValue(PREF_MAXIMIZED, (getExtendedState() & Frame.MAXIMIZED_BOTH) != 0);
        toolWindowDocking.saveLayout(preferences);
        resultsSplit.save(preferences);
        preferences.setValue(PREF_RESULTS_BESIDE, resultsSplit.isBeside());
        PrefUtils.savePreferenceStore(preferences);
    }

    @Nullable
    private static Rectangle parseBounds(@Nullable String value) {
        if (CommonUtils.isEmpty(value)) {
            return null;
        }
        String[] parts = value.split(","); //$NON-NLS-1$
        if (parts.length != 4) {
            return null;
        }
        try {
            Rectangle bounds = new Rectangle(
                Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
            return bounds.width >= 400 && bounds.height >= 300 ? bounds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isOnScreen(@NotNull Rectangle bounds) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = device.getDefaultConfiguration().getBounds();
            Rectangle visible = screen.intersection(bounds);
            if (visible.width >= 100 && visible.height >= 50) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * Document tabs whose tab row has the editor background (bg.editor), like the DataGrip editor tabs.
     * <ul>
     *     <li>The background is set after every look and feel change as a plain (non-UIResource) color: the scrolling
     *     tab row copies it only from a background change event, which a FlatLaf style value does not always fire when
     *     the theme is switched at runtime (the tabs then kept the panel color).</li>
     *     <li>Opaque, because FlatLaf tabbed panes are not: the empty part of the tab row would show the parent's panel color.</li>
     *     <li>The UI is {@link IslandTabbedPaneUI} (Islands pill tabs, close buttons of the selected and the hovered tab only).</li>
     *     <li>Tab adds and removes are reported ({@code tabsChanged}, the empty document area) after {@code insertTab} /
     *     {@code removeTabAt} has finished, not from a {@code ContainerListener}. A container event comes in the middle of
     *     the add, and {@code BasicTabbedPaneUI} keeps its per-tab title views in step from its own container listener,
     *     which {@code updateUI} adds again behind every listener added before. After a theme switch, the card switch
     *     (a synchronous layout) ran before the UI knew the new tab: {@code ArrayIndexOutOfBoundsException}, and the tab
     *     pane stayed broken. All adds and removes ({@code addTab}, {@code add}, {@code remove}, {@code removeAll}) go
     *     through these two methods.</li>
     * </ul>
     */
    private static final class EditorTabbedPane extends JTabbedPane {
        private final Runnable tabsChanged;

        EditorTabbedPane(@NotNull Runnable tabsChanged) {
            this.tabsChanged = tabsChanged;
            setOpaque(true);
        }

        @Override
        public void insertTab(String title, Icon icon, Component component, String tip, int index) {
            super.insertTab(title, icon, component, tip, index);
            tabsChanged.run();
        }

        @Override
        public void removeTabAt(int index) {
            super.removeTabAt(index);
            tabsChanged.run();
        }

        @Override
        public void updateUI() {
            setUI(new IslandTabbedPaneUI());
            Color background = UIManager.getColor("Chromite.editorBackground"); //$NON-NLS-1$
            if (background != null) {
                setBackground(new Color(background.getRGB()));
            }
        }
    }

    /**
     * Islands editor tabs (EditorTabs.* of ManyIslands*.theme.json): the selected tab is a rounded pill with a 1px
     * border (Chromite.island.tabSelected*, or tabInactive* when the tabs don't have the focus), 6px below and above the
     * tab row edges (tabInsets -6); a hovered tab gets a pill of tab-bg-hovered. No underline.
     * The pill is painted as the tab border, which FlatLaf paints before the close button and the title.
     */
    private static final class IslandTabbedPaneUI extends ChromiteTabbedPaneUI {
        @Override
        protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
            // The tab row background is the tabbed pane's own (opaque) background; pills are painted in paintTabBorder
        }

        @Override
        protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h, boolean isSelected) {
            boolean focused = isTabbedPaneOrChildFocused();
            Color fill;
            Color border = null;
            if (isSelected) {
                fill = UIManager.getColor(focused ? "Chromite.island.tabSelectedBackground" : "Chromite.island.tabInactiveBackground"); //$NON-NLS-1$ //$NON-NLS-2$
                border = UIManager.getColor(focused ? "Chromite.island.tabSelectedBorderColor" : "Chromite.island.tabInactiveBorderColor"); //$NON-NLS-1$ //$NON-NLS-2$
            } else if (tabIndex == getRolloverTab()) {
                fill = UIManager.getColor("Chromite.island.tabHoverBackground"); //$NON-NLS-1$
            } else {
                return;
            }
            int arc = UIScale.scale(UIManager.getInt("Chromite.island.tabArc")); //$NON-NLS-1$
            int insetY = UiSizes.get(UiSizes.DOCUMENT_TAB_PILL_INSET);
            int insetX = UIScale.scale(2);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float px = x + insetX;
                float py = y + insetY;
                float pw = w - 2 * insetX;
                float ph = h - 2 * insetY;
                if (fill != null) {
                    g2.setColor(fill);
                    g2.fill(new RoundRectangle2D.Float(px, py, pw, ph, arc, arc));
                }
                if (border != null) {
                    g2.setColor(border);
                    g2.draw(new RoundRectangle2D.Float(px + 0.5f, py + 0.5f, pw - 1, ph - 1, arc, arc));
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        protected void paintTabSelection(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h) {
            // No underline in Islands: the pill shows the selection
        }

        /**
         * DataGrip: the close button (×) shows on the selected tab and on the tab under the mouse only. Its space stays
         * reserved on every closable tab, so the tabs don't change width when the mouse moves over them. A click on a
         * hidden button can't happen: the tab under the mouse is the hovered one, whose button is shown.
         */
        @Override
        protected void paintTabCloseButton(Graphics g, int tabIndex, int x, int y, int w, int h) {
            if (tabIndex == tabPane.getSelectedIndex() || tabIndex == getRolloverTab()) {
                super.paintTabCloseButton(g, tabIndex, x, y, w, h);
            }
        }
    }

    /**
     * Islands canvas (main-window-bg): the background around the cards, under the main tool bar and the status bar.
     */
    private static class CanvasPanel extends JPanel {
        CanvasPanel(@NotNull LayoutManager layout) {
            super(layout);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color canvas = UIManager.getColor("Chromite.island.canvas"); //$NON-NLS-1$
            if (canvas != null) {
                setBackground(canvas);
            }
        }
    }

    /**
     * Split pane between islands: its divider is the 6px canvas gap between two cards (size.divider, Island.borderWidth),
     * draggable but without a line or FlatLaf's grip dots ({@code SplitPaneDivider.style = plain} paints nothing).
     * The split pane paints the canvas color, so the gap shows the canvas; set again on every look and feel change.
     */
    private static final class LineSplitPane extends JSplitPane {
        LineSplitPane(int orientation) {
            super(orientation);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color canvas = UIManager.getColor("Chromite.island.canvas"); //$NON-NLS-1$
            if (canvas != null) {
                setBackground(canvas);
            }
            if (getUI() instanceof BasicSplitPaneUI splitPaneUI && splitPaneUI.getDivider() != null) {
                splitPaneUI.getDivider().setBorder(BorderFactory.createEmptyBorder());
            }
        }
    }

    /**
     * Status bar (size.statusBar) on the Islands canvas, without a separator line (StatusBar.background =
     * main-window-bg, topBorderWidth 0); the background is set again on theme changes.
     */
    private static final class StatusBar extends JPanel {
        private final JLabel statusLabel;

        StatusBar(@NotNull String statusText) {
            super(new BorderLayout());
            statusLabel = new JLabel(statusText);
            statusLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(12), 0, UIScale.scale(12)));
            statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            add(statusLabel, BorderLayout.WEST);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, UiSizes.get(UiSizes.STATUS_BAR_HEIGHT));
        }

        void setText(@NotNull String text) {
            statusLabel.setText(text);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color canvas = UIManager.getColor("Chromite.island.canvas"); //$NON-NLS-1$
            if (canvas != null) {
                setBackground(canvas);
            }
        }
    }
}
