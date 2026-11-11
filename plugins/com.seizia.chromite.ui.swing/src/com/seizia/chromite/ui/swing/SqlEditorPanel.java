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
import com.seizia.chromite.model.sql.exec.SQLActualPlanProvider;
import com.seizia.chromite.model.sql.exec.SQLQueryExecuteHandler;
import com.seizia.chromite.model.sql.exec.SQLQueryExecuteJob;
import com.seizia.chromite.model.sql.exec.SQLScriptBatches;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.model.admin.oracle.OracleCompileErrors;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.QueryCommandMessages;
import com.seizia.chromite.ui.swing.internal.RoutineMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextAreaEditorKit;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.GutterIconInfo;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPErrorAssistant;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.exec.output.DBCOutputSeverity;
import com.seizia.chromite.model.exec.output.DBCOutputWriter;
import com.seizia.chromite.model.exec.output.DBCServerOutputReader;
import com.seizia.chromite.model.exec.plan.DBCPlan;
import com.seizia.chromite.model.exec.plan.DBCQueryPlanner;
import com.seizia.chromite.model.impl.struct.ContextDefaultObjectsReader;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.sql.SQLIncludedScript;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryListener;
import com.seizia.chromite.model.sql.SQLQueryResult;
import com.seizia.chromite.model.sql.SQLScriptContext;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLScriptErrorHandling;
import com.seizia.chromite.model.sql.SqlJobResult;
import com.seizia.chromite.model.sql.data.SQLQueryDataContainer;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.DBPPlatformUI;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQL 편집기 탭(SSMS의 쿼리 창 하나): 편집기, 실행/취소, 결과({@link QueryResultsView}), 상태 줄.
 *
 * 설계:
 * <ul>
 *     <li>편집기는 RSyntaxTextArea(SQL 구문 강조)다. 색은 테마마다 XML 테마 파일({@code SqlEditorLight.xml},
 *     {@code SqlEditorDark.xml})에 사용자가 바꾼 색({@link EditorColors})을 덮어 정하고, 글꼴은 {@link EditorFont}다.
 *     Look and Feel이 바뀌면({@code updateUI}) 현재 테마의 색을 다시 적용한다. 단어 분류(키워드, 함수, 연산자)는 연결의 SQL 방언을 따른다
 *     ({@link SqlTokenClassifier}).</li>
 *     <li>F5(메인 창 Query 메뉴)와 메인 창 SQL Editor 도구 막대의 Execute 단추는 선택이 1자 이상이면 선택한 부분만, 아니면 전체를 실행한다
 *     (swing-frontend.md "쿼리 실행 사양"). 범위는 전체 텍스트 안의 위치로 넘겨 쿼리 위치가 편집기 위치와 같게 한다(오류 줄 번호).</li>
 *     <li>실행은 두 Job으로 나눈다. 준비 Job이 연결(필요하면 {@link SwingConnectionService}), 실행 문맥, 문장 나누기
 *     ({@link SQLScriptBatches}: SQL Server 등은 {@code GO} 배치 단위)를 하고, 실행 엔진 {@link SQLQueryExecuteJob}을 예약한다.
 *     엔진을 직접 실행하지 않고 예약하는 것은 {@code DataSourceJob}이 실행 중 연결을 잡아 두는 동작({@code acquire/release})을 그대로 쓰기 위해서다.
 *     EDT는 기다리지 않는다. Cancel은 지금 돌고 있는 Job을 취소한다(엔진 Job은 실행 중인 문장을 취소한다).</li>
 *     <li>엔진의 콜백({@link SQLQueryExecuteHandler}, {@link SQLQueryListener})은 Job 스레드에서 불리므로, 화면 변경은 모두
 *     {@link SwingUtilities#invokeLater}로 EDT에 넘긴다. 각 실행은 {@link Execution} 객체로 구분해, 끝난 실행의 늦은 결과가 다음 실행 화면에 섞이지 않게 한다.</li>
 *     <li>오류는 대화상자 대신 Messages 탭에 쓰고(오류 위치 = 편집기의 줄·열), 엔진의 "계속할까요" 질문
 *     ({@link SQLQueryExecuteHandler#handleExecutionError})에는 묻지 않고 멈춤으로 답한다. 오류로 멈추면 오류 앞의 결과 탭(Result1, Result2…)은
 *     남고 Messages 탭을 보여 준다. 오류 문장에는 결과 탭이 없다.
 *     Options &gt; Query Execution에서 "건너뛰고 계속"을 고르면 실패한 문장을 건너뛰고 다음 문장을 실행한다({@link SqlExecutionOptionsPage}).
 *     이때 다음 결과가 이어서 다음 번호의 결과 탭이 되고, 결과가 있으면 앞의 결과 탭을 그대로 두며 Messages 탭의 오류 점·개수로 알린다.
 *     어느 쪽이든 오류 뒤 세션의 트랜잭션은 커밋도 롤백도 하지 않는다(엔진 기본값 {@code STOP_ROLLBACK}은 수동 커밋 세션의 열린 트랜잭션을
 *     묻지 않고 롤백하므로 쓰지 않는다, 트랜잭션 안전 규칙).</li>
 *     <li>커밋 방식과 최대 행 수는 엔진 기본값(커밋 방식 {@code NO_COMMIT}, 연결의 최대 행 수 설정)을 쓴다.
 *     "Select Top N Rows"로 연 편집기는 N행까지 가져오도록 최대 행 수를 키운다({@link #setMaxRows}).</li>
 *     <li>실행 문맥(세션): 설정이 켜져 있으면(기본, SWT와 같음) 쿼리 창마다 자기 격리 문맥을 연다({@link QueryWindowContext}). 그래서 창마다 트랜잭션이
 *     따로다. 실행, 계획, 기본 DB/스키마, 커밋 방식과 열린 트랜잭션 표시, Commit / Roll Back, 트랜잭션 로그, 자동완성이 모두 이 창의 문맥을 쓴다
 *     ({@link #getExecutionContext}). 결과 표의 편집·저장과 {@code @export}는 실행한 문맥을 넘겨받으므로 따라온다. 설정이 꺼져 있거나 "단일 연결"
 *     연결이면 예전처럼 연결의 주 문맥을 같은 연결의 창들과 함께 쓴다. 자기 세션을 끝내는 동작(창 닫기, 연결 바꾸기, 다른 DB 인스턴스로 바꾸기)은
 *     커밋하지 않은 트랜잭션이 있으면 먼저 묻는다({@link #confirmEndSession}, 트랜잭션 안전 규칙).</li>
 *     <li>연결: 편집기는 연결 없이 열 수 있다(파일 열기). 연결 콤보(SSMS의 연결 바꾸기)로 연결을 고르거나 바꾸고,
 *     실행 중에는 바꿀 수 없다. 목록은 콤보를 열 때 활성 프로젝트의 연결로 다시 채운다(추가·삭제·이름 변경 반영).</li>
 *     <li>도구 막대(2026-11-07 사용자 결정, SSMS): 쿼리 창마다의 도구 막대는 없다. 실행·중지·계획·편집 명령은 메인 창의 SQL Editor 도구 막대
 *     ({@link SqlEditorToolBar}, 둘째 줄)가 활성 쿼리 창에 적용한다. 연결·DB 콤보는 이 창의 상태(모델, 리스너, 켜짐)라 이 창이 만들고 가지며,
 *     메인 창이 활성 창의 콤보를 그 도구 막대에 끼운다(결과 탭·상태 줄과 같은 방식). 단추의 켜짐은 {@link #canExecute()}, {@link #canCancel()},
 *     {@link #canExplain()}으로 메인 창이 정하고, 바뀔 때 {@link Host#executionStateChanged}로 알린다.</li>
 *     <li>기본 DB/스키마 콤보(SSMS의 "Available Databases"): SWT의 활성 스키마 선택({@code SelectActiveSchemaHandler})과 같은 모델 API를 쓴다.
 *     목록과 현재 값은 {@link ContextDefaultObjectsReader}, 바꾸기는 {@link DBExecUtils#setExecutionContextDefaults}다(둘 다 작업 스레드).
 *     이 창의 실행 문맥에 적용된다(창이 자기 세션을 쓰면 이 창에만, 주 문맥을 함께 쓰면 같은 연결의 그 창들에도). 연결되어 있지 않거나 문맥이 바꾸기를
 *     지원하지 않으면 끈다.</li>
 *     <li>파일: 편집기는 텍스트만 다루고, 파일의 문자 집합·BOM·줄바꿈은 {@link SqlFileContent}로 기억해 저장할 때 되살린다.
 *     파일 대화상자와 최근 파일 목록은 메인 창이 맡는다. 문서가 바뀌면 고침 표시를 켜고 탭 제목 끝에 {@code *}를 붙인다.</li>
 *     <li>자동완성: {@link SqlCompletionPopup}(Ctrl+Space, {@code .}·글자 입력 뒤 자동)이 편집기의 현재 연결로 제안을 계산한다.</li>
 *     <li>찾기·바꾸기: 편집기가 Ctrl+F, Ctrl+H, F3, Shift+F3을 직접 받아 {@link FindReplaceDialog}를 연다(Edit 메뉴와 같은 동작).</li>
 *     <li>상태 줄({@link QueryStatusLine}): 편집기마다 하나이고 메인 창이 활성 창의 것을 메인 창 상태 표시줄에 보인다. 실행 상태, 연결의 서버·사용자, 기본 DB/스키마 콤보의 값, 시간, 행 수를 넣는다.
 *     행 수는 고른 결과 탭의 행 수이고, 결과 탭이 아닌 탭이 앞에 있으면 실행에서 읽은 행 수다({@link #updateRowCount}).</li>
 *     <li>결과 편집: 결과 표에 저장하지 않은 변경이 있으면 다시 실행하기 전에 버릴지 묻고(실행하면 결과 표를 새로 만든다), 저장 중에는 실행하지 않는다.
 *     닫을 때·끝낼 때 묻기는 메인 창이 {@link #hasPendingResultChanges}, {@link #saveResultChanges}로 한다.</li>
 *     <li>실행 계획({@link ExecutionPlanView}, 메인 창의 Execution plan 탭): Ctrl+L은 선택(없으면 전체)의 문장마다 연결의 계획기로 예상 계획을 구한다
 *     ({@link SqlPlanExplainer}). Ctrl+M(쿼리 창마다 켜고 끔)을 켜고 실행하면 실제 계획을 함께 받는다. 연결이 실제 계획 어댑터
 *     ({@link SQLActualPlanProvider}: SQL Server, Oracle, Altibase)를 주면 실행 전에 창의 세션에서 켜고, 문장마다 계획을 받고(결과 집합은
 *     {@link ActualPlanReceiver}, 열린 문장은 엔진 처리기, 닫은 문장은 문장 끝 이벤트), 끝나면(오류·취소 포함) 세션 설정을 되돌린다.
 *     문장은 한 번만 실행된다. 어댑터가 없는 연결은 토글을 끈다({@link #getActualPlanSupport}): 실제 계획을 보려면 문장을 한 번 더 실행해야 해서다.</li>
 *     <li>쿼리 기록: 엔진의 문장 끝 이벤트마다 {@link QueryHistory}에 문장, 연결, 기본 DB/스키마, 시간, 행 수, 결과를 남긴다.</li>
 *     <li>매개변수: {@code :name}, {@code ${var}}에 값이 없으면 엔진이 {@link QueryParameterBinder.ScriptProvider}로 묻고 값을 SQL 글자로 넣는다.
 *     Query Library 쿼리(확장)와 기록에서 다시 연 바인딩 실행({@link #setRecordedParameters})은 실행 전에 묻고 값을 바인딩한다
 *     ({@link SqlParameterBinding}). 대화상자는 모두 {@link QueryParameterDialog}다.</li>
 *     <li>편집 명령: SQL 서식(Ctrl+K Ctrl+D, Ctrl+Shift+F), 대문자·소문자(Ctrl+Shift+U/L), 주석 넣기·빼기(Ctrl+K Ctrl+C / Ctrl+K Ctrl+U),
 *     블록 주석 토글(Ctrl+Shift+/), 자동 줄 바꿈(Ctrl+Alt+Shift+W), 들여쓰기({@link SqlEditCommands}, 글자 도구는 {@link SqlTextTools}),
 *     템플릿(이름 + Tab, Ctrl+K Ctrl+X 목록, {@link SqlTemplates})은 편집 영역({@link SqlTextArea})이 직접 키를 받는다.
 *     Ctrl+K로 시작하는 두 번 누르는 키는 편집 영역의 키 리스너가 두 번째 키를 받아 처리한다(키 바인딩보다 먼저 불려 Ctrl+C 복사 등을 막는다).
 *     SQL Editor 도구 막대의 주석·들여쓰기·서식 단추도 같은 명령({@link SqlTextArea#runCommand})을 부른다.</li>
 *     <li>실행 안전 확인({@link SqlExecutionSafety}): 연결의 스크립트 실행 제한은 실행을 시작하기 전에(EDT), WHERE 없는 UPDATE/DELETE와
 *     DROP 확인은 준비 Job이 문장을 나눈 뒤 엔진을 예약하기 전에 한 번 묻는다. 취소하면 아무 문장도 실행하지 않고 취소로 끝난다.</li>
 *     <li>서버 출력(Oracle·CUBRID {@code DBMS_OUTPUT}, Altibase {@code PRINT}): 문장이 끝날 때마다 엔진 Job 스레드에서 연결의
 *     {@link DBCServerOutputReader}로 읽어 그 문장의 메시지(경고 다음, 행 수 앞)로 Messages 탭에 쓴다. SWT의 별도 Output 패널 대신
 *     Messages 탭인 것은 SQL Server PRINT·PostgreSQL NOTICE가 이미 여기 있어 SSMS처럼 한 곳에 차례대로 보이게 하려는 것이다.
 *     읽으면 버퍼가 비워지므로 매번 읽는다(Oracle 버퍼 넘침, Altibase 메모리 증가 방지). 경고만 되풀이하는 읽기 도구는 실행 결과 없이 불러
 *     PRINT·NOTICE가 두 번 나오지 않는다(docs/chromite/swing-server-output.md). 문장을 실행하기 전에는
 *     {@link DBCServerOutputReader#syncServerOutput}으로 세션의 서버 쪽 켜짐을 지금 설정에 맞춘다(설정이 세션을 연 뒤 바뀐 경우).</li>
 *     <li>Oracle 컴파일 오류: 저장 단위·뷰를 만든 CREATE 문이 성공하면 그 세션에서 {@code ALL_ERRORS}를 읽어({@link OracleCompileErrors})
 *     편집기 줄·열로 바꾼 오류를 Messages 탭에 쓰고 그 줄을 오류 색으로 표시한다. Oracle은 컴파일 오류를 경고로만 알려 창이 성공으로 끝나기
 *     때문이다(SWT는 객체 편집기가 저장 뒤 오류 줄로 옮겼다). 오류가 있으면 실행을 오류로 끝낸다. 표시는 다음 실행에서 지운다.</li>
 *     <li>SQL 터미널(Query &gt; SQL Terminal, 쿼리 창마다 켜고 끔): 켜고 시작한 실행은 결과 표와 Messages 탭은 그대로 두고, 문장마다
 *     {@code SQL> 문장}, 결과 집합의 글자 표, 같은 메시지와 오류를 Terminal 탭({@link SqlTerminalView})에도 쓴다. 글자 표는 결과를 받는 Job
 *     스레드에서 만들어 두었다가 문장이 끝날 때 메시지와 함께 한 번에 EDT로 넘겨 순서가 섞이지 않는다. 실행 중에는 결과 탭 대신 Terminal 탭을
 *     앞으로 가져온다(SWT 터미널이 결과 탭 자동 포커스를 끈 것과 같음, docs/chromite/swing-sql-terminal.md).</li>
 *     <li>문장 명령(Query 메뉴: 현재 문장 Ctrl+Enter, 커서부터 Alt+P, 행 수, 식): F5와 같은 실행 경로에 무엇을 실행할지만
 *     ({@link SqlQueryCommands.ExecuteMode}) 넘긴다. 문장은 준비 Job이 연결의 방언으로 찾는다({@link SqlQueryCommands#extractElements}).
 *     이전·다음 문장과 현재 문장 복사는 실행하지 않으므로 EDT에서 바로 한다.</li>
 *     <li>{@code @set} 변수: 창마다 변수 문맥({@code windowVariables})을 하나 두고 실행마다 만드는 스크립트 문맥의 부모로 넘긴다.
 *     모델의 문맥은 값을 부모까지 올려 쓰고 부모에서 찾으므로 한 실행이 정한 값이 다음 실행에 남는다(SWT 편집기의 전역 문맥과 같음).
 *     창을 닫으면 사라지고 연결을 바꿔도 남는다. Query &gt; Variables…로 보고 고치고 지운다({@link QueryVariablesDialog}).</li>
 *     <li>{@code @include}: 실행의 스크립트 문맥에 창의 파일을 넘겨 상대 경로가 그 폴더에서 시작한다. 포함한 파일의 문장은 엔진이 끼워 실행하고,
 *     끼우기 전에 위험한 문을 묻고(이 창의 문장과 같은 확인), 오류 위치는 그 파일의 줄로 보인다({@code describeError}).</li>
 *     <li>DDL 뒤 탐색기 갱신({@link SqlMetadataRefresh}): 성공한 문장마다 바뀐 개체의 그릇을 모으고, 실행이 끝나고 세션에 열린 트랜잭션이
 *     없을 때(수동 커밋이면 커밋·롤백 뒤) Object Explorer를 다시 읽는다.</li>
 *     <li>편집 편의(docs/chromite/swing-sql-editor-ergonomics.md): 코드 접기({@link SqlFoldParser}), 커서가 있는 이름의 다른 자리 강조
 *     (RSyntaxTextArea의 같은 단어 표시), 이름 위 마우스 올림 설명과 Ctrl+클릭 이동({@link SqlObjectNavigation}, 이 창의 연결과 세션),
 *     결과 고정({@link #pinResults}).</li>
 *     <li>다시 시작 뒤 복원({@link QueryWindowRecovery}): 복원한 창은 연결하지 않고 열리고, 예전 기본 DB/스키마
 *     ({@link #setRestoredDefaults})는 창의 세션이 처음 열릴 때 적용한다. 세션을 여는 작업(실행, 계획, 기본 DB·커밋 방식 읽기)은 모두
 *     {@link #openWindowContext}를 거치므로 첫 실행이 예전 DB/스키마에서 돈다. 주 문맥을 함께 쓰는 창은 다른 창의 기본값을 바꾸지 않도록 적용하지 않는다.
 *     연결 전에는 DB 콤보(꺼짐)와 상태 줄에 복원할 이름을 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 * - 2026-11-06 E단계: 연결 없는 편집기, 연결·기본 스키마 콤보, 파일 내용과 고침 표시, 편집기별 최대 행 수
 *   (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 * - 2026-11-06 F단계: 자동완성 팝업 연결 (docs/chromite/swing-frontend.md "구현 현황 — F단계")
 * - 2026-11-06 G단계: 결과 표 편집 상태(다시 실행 전 확인, 저장 중 실행 막기, 닫을 때 저장) (docs/chromite/swing-frontend.md "구현 현황 — G단계")
 * - 2026-11-06 디자인 적용: 도구 막대 아이콘(초록 ▶ Execute, ■ 중지), 문서 탭 아이콘, 편집기 글꼴 JetBrains Mono(합성 글꼴)
 *   (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 P1: 편집기의 찾기 키(Ctrl+F, Ctrl+H, F3, Shift+F3) (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 P1: 상태 줄을 {@link QueryStatusLine}(SSMS 내용: 상태 아이콘, 서버, 사용자, DB/스키마, 시간, 행)으로 바꾸고 메인 창이 결과 아래에 둠
 *   (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 P1: 탭 도구 설명에 전체 경로와 연결·서버 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 편집기 글꼴(Cascadia Mono 13, Options에서 변경)과 SSMS 구문 색(사용자 색 설정), 방언 기반 단어 분류
 *   (docs/chromite/design-spec.md "편집기 글꼴과 SQL 색")
 * - 2026-11-06 데이터 전송: 쿼리의 첫 결과 표에 다시 실행할 쿼리를 넘김(Export Data) (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 실행 계획(Ctrl+L, Ctrl+M), 쿼리 기록, 매개변수 묻기, SQL 서식·편집 명령, 템플릿, 상태 줄 커서 위치
 *   (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 Query Library: 편집기 확장({@link SqlEditorExtension}: 머리말, 저장, 탭 아이콘, 매개변수 바인딩 {@link SqlParameterBinding})
 *   (docs/chromite/swing-query-library.md)
 * - 2026-11-06 Query Library와 데이터 전송·결과 뷰어를 합침: 값을 바인딩한 쿼리의 결과는 다시 읽지 않음(Export Data·필터·다음 페이지가
 *   값 없이 {@code ?}를 보내 실패하던 것) (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-06 매개변수 대화상자의 Cancel 뒤 상태 줄이 "Query executed successfully"이던 문제: 엔진이 알리는
 *   {@code SQLQueryExecuteJob#isCanceledByUser}로 취소 상태를 보임 (docs/chromite/swing-sql-editor-plus.md "매개변수")
 * - 2026-11-06 결과 표를 필터·정렬·다음 페이지로 다시 읽은 뒤 상태 줄 행 수를 지금 보이는 행으로 바꿈 (docs/chromite/swing-result-viewer.md)
 * - 2026-11-06 Query Library의 바인딩한 실행을 Query History에 값을 넣은 글자로 기록 (docs/chromite/swing-query-library.md "매개변수 바인딩")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-06 실행이 끝날 때 부르는 연결 지점({@link #setExecutionFinishedListener}, Modify 창이 Object Explorer 노드를 다시 읽음)
 *   (docs/chromite/swing-source-editor.md)
 * - 2026-11-06 도구 막대에 SSMS SQL Editor 단추 추가(Comment, Uncomment, Decrease/Increase Indent, Format SQL, Query History),
 *   좁은 창에서는 콤보를 줄이고 넘치는 단추를 넘침 메뉴로 ({@link OverflowToolBar}) (docs/chromite/swing-frontend.md "도구 막대")
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 밀도 B: 치수를 테마의 Chromite.size.* 키({@link UiSizes})에서 읽음 (docs/chromite/design-spec.md "치수 토큰")
 * - 2026-11-07 쿼리 창 도구 막대 삭제(사용자 결정: SSMS처럼 메인 창 둘째 줄의 SQL Editor 도구 막대 {@link SqlEditorToolBar}).
 *   실행·중지·계획 동작과 실제 계획 토글 단추, {@code Host#openQueryHistory}를 지우고, 연결·DB 콤보는 메인 창이 끼우도록 내줌
 *   (docs/chromite/swing-frontend.md "SQL Editor 도구 막대")
 * - 2026-11-07 상태 줄의 커밋 방식: 실행 컨텍스트의 실제 자동 커밋 상태를 작업 스레드에서 읽어 보임({@link #refreshTransactionMode}),
 *   칸을 누르면 연결 설정의 Auto-commit으로 안내 (docs/chromite/swing-frontend.md "자동 커밋 상태")
 * - 2026-11-07 상태 줄의 열린 트랜잭션: 실행 문맥의 쿼리 관리자 상태를 1초마다 읽어 보이고({@link #refreshOpenTransaction}),
 *   메뉴의 Commit / Roll back은 보여 준 트랜잭션만 끝냄({@link SwingIdleTransactionService#endTransaction}) (docs/chromite/idle-transactions.md)
 * - 2026-11-07 Query 메뉴·SQL Editor 도구 막대·단축키의 Commit / Roll Back({@link #endOpenTransaction(boolean)}, 상태 줄 메뉴와 같은 경로)과
 *   Transaction Log({@link #showTransactionLog}). 열린 트랜잭션이 바뀌면 메인 창이 명령의 켜짐을 다시 정함 (docs/chromite/transactions.md)
 * - 2026-11-07 실행 안전 확인: 스크립트 실행 제한, 위험한 문 확인 (docs/chromite/swing-sql-exec-safety.md)
 * - 2026-11-07 서버 출력: 문장마다 {@link DBCServerOutputReader}를 읽어 Messages 탭에 씀(Oracle DBMS_OUTPUT이 안 보이고 버퍼가 차던 것,
 *   Altibase PRINT가 메모리에 쌓이던 것) (docs/chromite/swing-server-output.md)
 * - 2026-11-07 원본 객체({@link #setSourceObject}): Modify 창의 루틴을 Query &gt; Debug…가 디버깅 (docs/chromite/procedure-debugger.md)
 * - 2026-11-07 오류 처리 설정(Options &gt; Query Execution)을 엔진에 넘김. 오류로 멈춘 스크립트가 수동 커밋 세션의 열린 트랜잭션을
 *   묻지 않고 롤백하던 것을 고침(엔진 기본값 {@code STOP_ROLLBACK}) (docs/chromite/swing-options.md)
 * - 2026-11-07 쿼리 창마다 별도 연결({@link QueryWindowContext}): 실행·계획·기본 스키마·트랜잭션 표시와 명령·자동완성이 창의 문맥을 쓰고,
 *   창 닫기·연결 바꾸기·DB 인스턴스 바꾸기 전에 그 세션의 트랜잭션을 물음. 상태 줄 커밋 방식 칸의 도구 설명에 세션 수.
 *   자동완성이 창을 만들 때의 연결을 계속 쓰던 것(생성자 매개 변수를 잡은 람다)을 지금 연결로 고침 (docs/chromite/transactions.md)
 * - 2026-11-08 원본 객체를 Query &gt; Trace Execution…도 씀(주석) (docs/chromite/sqlserver-execution-trace.md)
 * - 2026-11-08 촘촘한 모드: 도구 막대 선택 상자의 여백을 치수 키로 정하고, 높이를 UI가 바뀔 때 다시 정함
 *   (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 * - 2026-11-08 매개변수 대화상자를 하나로({@link QueryParameterDialog}, 엔진 제공자는 {@link QueryParameterBinder.ScriptProvider}),
 *   값을 바인딩한 결과도 바인딩의 데이터 컨테이너로 다시 읽음(필터·서버 정렬·다음 페이지·Export Data), 기록에 {@code :name} 글자와 값을 남기고
 *   기록에서 연 창은 그 값으로 다시 바인딩({@link #setRecordedParameters}) (docs/chromite/swing-query-library.md "매개변수 대화상자와 바인딩 하나로")
 * - 2026-11-08 문장마다 실행 전에 서버 출력 켜짐을 설정에 맞춤({@code syncServerOutput}). Oracle DBMS_OUTPUT을 끄거나 켜도 열린 세션은
 *   다시 연결할 때까지 그대로였던 것 (docs/chromite/model-fixes.md "작은 모델·안전·라이선스 수정")
 * - 2026-11-08 Oracle CREATE 뒤 컴파일 오류 줄 표시(SWT 감사 O12), 위치 보이기({@link #showSourcePosition}: Go to Source Code,
 *   Go to Error) (docs/chromite/swing-routines.md)
 * - 2026-11-08 SQL 터미널: 창마다 켜고 끄는 Terminal 탭에 문장과 결과를 글자로 씀({@link SqlTerminalView}, docs/chromite/swing-sql-terminal.md)
 * - 2026-11-08 SQL 터미널: 값을 바인딩한 실행(Query Library)은 문장 아래에 값을 {@code -- :name = 값} 줄로 씀(기록 미리 보기와 같은 형식,
 *   docs/chromite/swing-sql-terminal.md)
 * - 2026-11-08 Scripts 도구 창에서 이름을 바꾼 파일을 따라가는 {@link #fileMoved} (docs/chromite/swing-scripts-explorer.md)
 * - 2026-11-08 문장 명령(현재 문장, 커서부터, 행 수, 식, 이전·다음 문장, 현재 문장 복사), 창 단위 {@code @set} 변수와 변수 대화상자,
 *   {@code @include}(창의 파일 기준 상대 경로, 포함 파일의 위험한 문 확인과 오류 줄), DDL 뒤 탐색기 갱신 (docs/chromite/swing-query-commands.md)
 * - 2026-11-08 편집 편의: 코드 접기, 같은 이름 강조, 마우스 올림 설명·Ctrl+클릭({@link Host#revealNode}), 블록 주석 토글·글자 도구·자동 줄 바꿈·접기
 *   명령({@link SqlTextArea.Command}), 결과 고정. 다시 실행 전 버리기 묻기는 고정하지 않은 결과만 셈 (docs/chromite/swing-sql-editor-ergonomics.md)
 * - 2026-11-08 스마트 커밋({@link SmartCommit}): 엔진 처리기가 설정을 따르고, 문장이 세션을 수동 커밋으로 바꾸면 커밋·롤백 뒤 되돌릴 세션으로 기억
 *   (docs/chromite/connection-lifecycle.md "스마트 커밋"). 쿼리 관리자가 본 커밋 방식이 바뀌면 상태 줄의 커밋 방식을 다시 읽음(실행 밖의 전환)
 * - 2026-11-08 오류 위치로 가기(SWT {@code GoToErrorAction}): Messages의 오류가 머리글의 줄·열 위치를 함께 갖고, 두 번 누르면 그 자리로 가서
 *   줄을 오류 색으로 표시({@code ErrorReport}, {@link QueryResultsView.Listener#goToSource}) (docs/chromite/swing-grid-navigation.md)
 * - 2026-11-08 다시 시작 뒤 복원한 창의 기본 DB/스키마({@link #setRestoredDefaults}, {@link #getDefaultObjectNames}): 세션을 처음 열 때
 *   적용하도록 세션 열기를 {@link #openWindowContext} 하나로 모음 (docs/chromite/swing-query-window-recovery.md)
 * - 2026-11-08 결과 행에서 SQL 만들기: 결과가 만든 문장을 메인 창이 새 쿼리 창에 엶({@link Host#openScript}, 실행하지 않음)
 *   (docs/chromite/swing-result-generate-sql.md)
 * - 2026-11-08 배치 오류: 한 쿼리(GO 배치)의 오류를 모두 Messages에 각자의 줄 머리글로 쓰고, 오류가 있어도 앞뒤 문장의 행 수를 씀
 *   ({@link SQLQueryResult#getErrors()}, docs/chromite/sql-batch-errors.md)
 * - 2026-11-08 Query &gt; Auto-Commit(B-S18): 창 세션의 커밋 방식을 바꿈({@link #switchCommitMode}, {@link CommitModeSwitch}). 실행·계획·결과 저장 중에는
 *   끄고({@link #canSwitchCommitMode}), 상태 줄이 보이는 방식이 바뀌면 메인 창이 명령을 다시 정함 (docs/chromite/transactions.md "커밋 방식 전환")
 * - 2026-11-08 실제 계획을 Oracle·Altibase로 넓힘(어댑터의 실행 단위 {@link SQLActualPlanProvider.Run}). 어댑터가 없는 연결에서 성공한 SELECT를
 *   계획기의 ANALYZE로 다시 실행하던 방식을 지움(사용자 결정: 실제 계획은 문장을 한 번만 실행할 때만, 그 밖의 연결은 토글을 끔)
 *   (docs/chromite/swing-sql-editor-plus.md "실제 계획 토글을 Altibase·Oracle로")
 * - 2026-11-08 배치 결과 다시 읽기: 결과마다 데이터 컨테이너가 그 결과를 만든 문장만 갖게 함({@code restrictToResultSet}, 쿼리 안의
 *   결과 집합 번호). 첫 결과 집합만 Export Data 쿼리로 넘기던 것을 모든 결과로 넓힘(다시 읽을 수 있는지는 컨테이너가 정함)
 *   (docs/chromite/sql-batch-reread.md)
 * - 2026-11-08 주석만: 상태 줄의 열린 트랜잭션은 DB의 답으로 정함(자동 커밋 포함, 확인할 수 없음 따로) (docs/chromite/idle-transactions.md "트랜잭션 상태")
 * - 2026-11-08 결과 집합마다 탭(앱 변경 #7): 결과 탭 줄은 {@link QueryResultsView}가 계획·터미널 탭까지 가짐(창이 고른 탭을 따로 기억하던
 *   {@code planTabSelected}·{@code terminalTabSelected} 삭제), 실행 시작은 Messages, 첫 결과만 앞으로, 오류로 멈췄거나 결과가 없을 때만 끝에 Messages,
 *   상태 줄 행 수는 고른 결과 탭 (docs/chromite/swing-frontend.md "결과 탭")
 * - 2026-11-08 상태 줄은 메인 창 상태 표시줄에 보임(앱 변경 #2, 주석만) (docs/chromite/swing-frontend.md "상태 표시줄의 쿼리 창 칸")
 * - 2026-11-08 고침 상태가 바뀌면 {@link #DIRTY_PROPERTY} 속성 변경을 알림: Query Library 머리말의 저장 단추가 따름
 *   (docs/chromite/swing-query-library.md "머리말 저장 단추")
 * - 2026-11-08 연결 콤보 폭 260 → 220(앱 변경 #3), 연결·DB 콤보의 도구 설명에 고른 항목의 전체 이름(긴 이름은 콤보에서 말줄임표)
 *   (docs/chromite/swing-frontend.md "SQL Editor 도구 막대")
 * - 2026-11-09 편집기 키를 플랫폼 명령 키로({@link UiKeys}, macOS Command, Replace ⌥⌘F, 블록 주석 ⌥⌘/), 두 키 조합의 글자는
 *   {@link SqlTextArea#getChordText}가 만듦 (docs/chromite/shortcuts.md)
 * - 2026-11-09 상태 줄의 조회만 한 트랜잭션: 메뉴의 트랜잭션 끝내기({@link SwingIdleTransactionService#endReadOnlyTransaction}),
 *   자동으로 끝낸 뒤의 짧은 알림 (docs/chromite/idle-transactions.md "조회만 한 트랜잭션 끝내기")
 * - 2026-11-11 세션의 현재 DB/스키마를 문장 뒤(조회·DML 제외)와 실행 끝, 실행 밖 트랜잭션 끝에 서버에서 다시 읽음({@link #readSessionDefaults},
 *   앱 변경 #33: USE 뒤 DB 선택이 바뀌지 않았다. SWT의 실행 뒤 다시 읽기가 빠져 있었다)
 *   (docs/chromite/swing-sql-editor-session-ui.md "현재 DB 따라가기")
 * - 2026-11-11 실행 중 표시: 상태 칸 "실행 중 2/3 · 00:00:12"(경과 시간 칸은 끝난 뒤), 탭 스피너, 실행 중·오류 문장의 줄 강조와 줄 번호 옆 아이콘
 *   (아이콘 열을 켬), 쓰지 않게 된 editor_sql_executing 삭제(앱 변경 #44) (docs/chromite/swing-sql-editor-session-ui.md "실행 중 표시")
 * - 2026-11-12 {@link #setFileName}: 저장 전 창의 이름 바꾸기(문서 탭 메뉴, 앱 변경 #42) (docs/chromite/swing-frontend.md "문서 탭 메뉴")
 */
final class SqlEditorPanel extends JPanel {

    private static final Log log = Log.getLog(SqlEditorPanel.class);

    private static final DateTimeFormatter COMPLETION_TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * The main window side of an editor.
     */
    interface Host {
        /**
         * The results of the editor changed; brings a result tab or the Messages tab to the front, and shows the
         * Results card if it is the active editor.
         */
        void showResults(@NotNull SqlEditorPanel editor, boolean messages);

        /**
         * Execution or a plan started or finished, or a stop was requested: what can run now changed (Query menu and
         * SQL Editor tool bar state, {@link #canExecute()}).
         */
        void executionStateChanged(@NotNull SqlEditorPanel editor);

        /**
         * Shows a database object of the editor's text in Object Explorer (Ctrl+click on its name).
         */
        void revealNode(@NotNull DBNDatabaseNode node);

        /**
         * The tab title or tool tip changed (file name, connection, modified marker).
         */
        void titleChanged(@NotNull SqlEditorPanel editor);

        /**
         * Brings the Execution plan tab of the editor to the front.
         */
        void showPlan(@NotNull SqlEditorPanel editor);

        /**
         * Brings the Terminal tab of the editor to the front (a run while Query &gt; SQL Terminal is on).
         */
        void showTerminal(@NotNull SqlEditorPanel editor);

        /**
         * Opens a script in a new query window of a connection (statements made from a result).
         *
         * @param maxRows rows to read if it runs, 0 for the default
         * @param execute run it right away
         */
        void openScript(@NotNull DBPDataSourceContainer dataSource, @NotNull String script, long maxRows, boolean execute);
    }

    private static final String NOT_AVAILABLE = "< N/A >"; //$NON-NLS-1$
    /**
     * Bound property of the modified state ({@link #isDirty()}), for components of the window that follow it (the Save
     * button of a Query Library header).
     */
    static final String DIRTY_PROPERTY = "dirty"; //$NON-NLS-1$
    /**
     * Widths of the connection and database/schema pickers, also of the empty pickers shown without a query window so
     * that the tool bar does not move. The connection picker stays wider than the database/schema one (user decision
     * 2026-11-08: 260 before); longer names end in an ellipsis and are whole in the tool tip ({@link #updatePickerToolTips}).
     */
    static final int CONNECTION_PICKER_WIDTH = 220;
    static final int SCHEMA_PICKER_WIDTH = 200;
    private static final int PICKER_MIN_WIDTH = 100;

    private final Host host;
    // EDT only
    @Nullable
    private DBPDataSourceContainer dataSourceContainer;
    private String fileName;
    @Nullable
    private Path file;
    /**
     * Format (charset, BOM, line separator) of the file; its text is not kept up to date.
     */
    private SqlFileContent fileFormat;
    private boolean dirty;
    private long maxRows;
    private final JComboBox<DBPDataSourceContainer> connectionCombo = new JComboBox<>();
    private final JComboBox<Object> schemaCombo = new JComboBox<>();
    private boolean updatingCombos;
    private boolean schemaChangeSupported;
    private int schemaSerial;
    // The next read of the pickers first reads the session's database/schema from the server (refreshOpenTransaction)
    private final AtomicBoolean sessionReadPending = new AtomicBoolean();
    private final Object sessionReadLock = new Object();
    // EDT only: a newer read of the auto-commit state replaces older ones still running
    private int transactionSerial;
    @Nullable
    private SchemaState schemaState;
    private final SqlTextArea textArea;
    private final SqlCompletionPopup completion;
    // Before the results view, whose tab row has the Execution plan tab
    private final ExecutionPlanView planView = new ExecutionPlanView();
    private final QueryResultsView resultsView = new QueryResultsView(new QueryResultsView.Listener() {
        @Override
        public void showMessages() {
            host.showResults(SqlEditorPanel.this, true);
        }

        @Override
        public void resultsEditStateChanged() {
            updateActions();
        }

        @Override
        public void resultsRowsChanged() {
            // Another tab was selected, or a grid was read again after the run (filter, sort, more rows)
            updateRowCount();
        }

        @Override
        public void goToSource(int offset) {
            // Go to Error (SWT GoToErrorAction): the position the error names, its line marked
            int length = textArea.getDocument().getLength();
            int position = Math.clamp(offset, 0, length);
            int line = 0;
            try {
                line = textArea.getLineOfOffset(position);
            } catch (BadLocationException e) {
                log.debug("Error position out of the text: " + position); //$NON-NLS-1$
            }
            showSourcePosition(new SourcePosition(position, position, List.of(line)));
        }

        @Override
        public void openScript(@NotNull DBPDataSourceContainer container, @NotNull String script) {
            // Generate SQL from a result: never run here
            host.openScript(container, script, 0, false);
        }
    }, planView.getComponent());
    // EDT only: rows the last run read (all its results), shown while no result tab is selected; null while not known yet
    @Nullable
    private Long runRows;
    // EDT only: Query > Include Actual Execution Plan for this window
    private boolean includeActualPlan;
    // EDT only
    private boolean explaining;
    // EDT only: Query > SQL Terminal for this window; the terminal keeps its output across runs
    private boolean terminalEnabled;
    private final SqlTerminalView terminalView = new SqlTerminalView();
    private final QueryStatusLine statusLine = new QueryStatusLine();
    private final Timer elapsedTimer;
    private final Timer transactionTimer;
    /**
     * The open transaction the status line shows (EDT only), null when there is none.
     */
    @Nullable
    private QMTransactionState openTransaction;
    // The commit mode (manual = true) the query manager last reported for the window's session, or null
    @Nullable
    private Boolean shownTransactionMode;
    // EDT only: the commit mode the status line shows (auto-commit = true), null while unknown or not connected
    @Nullable
    private Boolean shownAutoCommit;
    // EDT only: Query > Auto-Commit is switching the session's mode
    private boolean switchingCommitMode;
    private final Object jobLock = new Object();
    // EDT only
    @Nullable
    private Execution execution;
    @Nullable
    private SqlEditorExtension extension;
    // EDT only: the values of a bound run reopened from the query history (its ":name" parameters are bound again), or null
    @Nullable
    private QueryParameterBinder.Recorded recordedParameters;
    // EDT only: the next run uses the recorded values without asking (History: Execute)
    private boolean runRecordedWithoutAsking;
    @Nullable
    private Runnable executionFinishedListener;
    // EDT only: the object whose definition this window edits (Modify), or null
    @Nullable
    private DBSObject sourceObject;
    // EDT only: line highlights of compile errors (cleared when a run starts)
    private final List<Object> errorLineMarks = new ArrayList<>();
    // EDT only: the statement that runs now (line highlights and the gutter icon), and the statements that failed in
    // the last run (kept until the next run)
    private final List<Object> runningStatementMarks = new ArrayList<>();
    @Nullable
    private GutterIconInfo runningStatementIcon;
    private final List<Object> failedStatementMarks = new ArrayList<>();
    private final List<GutterIconInfo> failedStatementIcons = new ArrayList<>();
    // The tab icon while the window runs
    private final SpinnerIcon tabSpinner = new SpinnerIcon();
    // The window's session: its own context or the connection's main context
    private final QueryWindowContext windowContext = new QueryWindowContext(this::getFileName);
    // The @set variables of the window: the parent of each run's script context, so what a run sets stays for the next runs
    private final SQLScriptContext windowVariables;
    // Object Explorer containers to read again after DDL of this window's runs
    private final SqlMetadataRefresh metadataRefresh = new SqlMetadataRefresh();
    /**
     * The default database/schema a restored window had before the restart ({@link QueryWindowRecovery}), applied to its own
     * session when the session first opens ({@link #openWindowContext}); null when there is none or it was applied. Guarded
     * by the lock: a run waits until another job has applied it.
     */
    @Nullable
    private DefaultObjectNames restoredDefaults;
    private final Object restoredDefaultsLock = new Object();

    /**
     * @param dataSourceContainer connection of the query window, or null to choose it later with the connection picker
     * @param fileName            name of the new query ({@code SQLQueryN.sql}) until it is saved to a file
     */
    SqlEditorPanel(@Nullable DBPDataSourceContainer dataSourceContainer, @NotNull String fileName, @NotNull Host host) {
        super(new BorderLayout());
        this.dataSourceContainer = dataSourceContainer;
        this.fileName = fileName;
        this.fileFormat = SqlFileContent.newFile("");
        this.host = host;
        // Variable names are normalized with the dialect of the window's session (job thread: QueryWindowContext reads are lock-free)
        this.windowVariables = new SQLScriptContext(null, this::getExecutionContext, null, Writer.nullWriter(), null);

        textArea = new SqlTextArea();
        // The connection and session at the time of the request (the connection picker may change them)
        completion = new SqlCompletionPopup(textArea, this::getDataSourceContainer, this::getExecutionContext);
        // Hover documentation and Ctrl+click of names, with the connection and session at the time of the request
        SqlObjectNavigation.install(textArea, new SqlObjectNavigation.Host() {
            @Nullable
            @Override
            public DBPDataSourceContainer getDataSourceContainer() {
                return SqlEditorPanel.this.getDataSourceContainer();
            }

            @Nullable
            @Override
            public DBCExecutionContext getExecutionContext() {
                return SqlEditorPanel.this.getExecutionContext();
            }

            @Override
            public void revealNode(@NotNull DBNDatabaseNode node) {
                host.revealNode(node);
            }

            @Override
            public void showMessage(@NotNull String message) {
                if (execution == null) {
                    statusLine.setState(QueryStatusLine.State.READY, message);
                }
            }
        });
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        // The icon column beside the line numbers: the statement that runs now and the ones that failed
        scrollPane.setIconRowHeaderEnabled(true);
        // The gutter takes its colors from the theme too, so the theme is applied once the scroll pane exists
        textArea.applyTheme();

        createPickers();
        add(scrollPane, BorderLayout.CENTER);

        elapsedTimer = new Timer(250, e -> {
            if (execution != null) {
                showRunState();
            }
        });
        // The open transaction segment counts up; the query manager's state is in memory, so a read each second is cheap
        transactionTimer = new Timer(1000, e -> refreshOpenTransaction());
        transactionTimer.start();
        statusLine.setOpenTransactionActions(new QueryStatusLine.OpenTransactionActions() {
            @Override
            public void end(@NotNull QMTransactionState transaction, boolean commit) {
                endOpenTransaction(transaction, commit);
            }

            @Override
            public void endReadOnly(@NotNull QMTransactionState transaction) {
                DBCExecutionContext context = getExecutionContext();
                if (context != null) {
                    SwingIdleTransactionService.endReadOnlyTransaction(SqlEditorPanel.this, context, transaction.getTransactionStartTime(),
                        SqlEditorPanel.this::refreshOpenTransaction);
                }
            }

            @Override
            public void showLog() {
                showTransactionLog();
            }
        });
        statusLine.setConnection(dataSourceContainer);
        // The commit mode segment only leads to the connection's Auto-commit setting (user decision 2026-11-07)
        statusLine.setTransactionAction(() -> {
            if (this.dataSourceContainer != null) {
                ConnectionCommands.editAutoCommit(this, this.dataSourceContainer);
            }
        });
        textArea.addCaretListener(e -> updateCaretPosition());
        updateCaretPosition();
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                setDirty(true);
                if (execution != null) {
                    execution.edits.add(new int[]{e.getOffset(), e.getLength()});
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                setDirty(true);
                if (execution != null) {
                    execution.edits.add(new int[]{e.getOffset(), -e.getLength()});
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Attribute changes do not modify the text
            }
        });
        reloadConnectionItems();
        refreshSchemas();
    }

    /**
     * SSMS-like tab title: {@code SQLQuery1.sql - connection*} ({@code *} while modified).
     */
    @NotNull
    String getTitle() {
        return fileName + " - " + (dataSourceContainer != null ? dataSourceContainer.getName() : EditorMessages.editor_common_not_connected) + (dirty ? "*" : "");
    }

    /**
     * Tab tool tip: the full file path (or the name of a query that was never saved) and the connection with its server.
     */
    @NotNull
    String getToolTip() {
        String location = file != null ? file.toString() : NLS.bind(EditorMessages.editor_sql_tip_not_saved, fileName);
        String connection = dataSourceContainer == null ? EditorMessages.editor_sql_tip_not_connected
            : NLS.bind(EditorMessages.editor_sql_tip_connection, dataSourceContainer.getName(), QueryStatusLine.describeServerDetails(dataSourceContainer));
        return "<html>" + CommonUtils.escapeHtml(location) + "<br>" + CommonUtils.escapeHtml(connection) + "</html>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Nullable
    DBPDataSourceContainer getDataSourceContainer() {
        return dataSourceContainer;
    }

    @Nullable
    Path getFile() {
        return file;
    }

    /**
     * File name for Save As: the file's name, or {@code SQLQueryN.sql} for a query that was never saved.
     */
    @NotNull
    String getFileName() {
        return fileName;
    }

    boolean isDirty() {
        return dirty;
    }

    /**
     * The editor text in the format of its file (a new query: UTF-8, platform line separator).
     */
    @NotNull
    SqlFileContent getContent() {
        return fileFormat.withText(textArea.getText());
    }

    /**
     * Replaces the text without marking the editor modified: a file that was opened, or a generated script.
     *
     * @param file the file the content was read from, or null for a script that is not a file yet
     */
    void setContent(@NotNull SqlFileContent content, @Nullable Path file) {
        textArea.setText(content.text());
        textArea.setCaretPosition(0);
        textArea.discardAllEdits();
        fileFormat = content;
        if (file != null) {
            this.file = file;
            this.fileName = file.getFileName().toString();
        }
        boolean wasDirty = dirty;
        dirty = false;
        host.titleChanged(this);
        firePropertyChange(DIRTY_PROPERTY, wasDirty, false);
    }

    /**
     * The text was written to a file (Save, Save As).
     */
    void saved(@NotNull Path file, @NotNull SqlFileContent content) {
        this.file = file;
        this.fileName = file.getFileName().toString();
        this.fileFormat = content;
        boolean wasDirty = dirty;
        dirty = false;
        host.titleChanged(this);
        firePropertyChange(DIRTY_PROPERTY, wasDirty, false);
    }

    /**
     * The window's file was renamed or its folder renamed (Scripts tool window): the window keeps its text and modified
     * state, and saves to the new file.
     */
    void fileMoved(@NotNull Path file) {
        this.file = file;
        this.fileName = file.getFileName().toString();
        host.titleChanged(this);
    }

    /**
     * Rename of a window that was never saved (document tab menu): its name changes, in the tab title and as the file
     * name Save proposes.
     */
    void setFileName(@NotNull String fileName) {
        this.fileName = fileName;
        host.titleChanged(this);
    }

    /**
     * Makes this window a window of another document kind (a Query Library query): its header goes above the editor,
     * and saving, the tab icon and parameter binding come from the extension.
     */
    void setExtension(@NotNull SqlEditorExtension extension) {
        this.extension = extension;
        JComponent header = extension.getHeader();
        if (header != null) {
            add(header, BorderLayout.NORTH);
        }
        host.titleChanged(this);
    }

    @Nullable
    SqlEditorExtension getExtension() {
        return extension;
    }

    /**
     * Makes the window bind the {@code :name} parameters of its text with the values of a run reopened from the query
     * history (asked with those values first, unless {@code withoutAsking} for the next run).
     */
    void setRecordedParameters(@NotNull QueryParameterBinder.Recorded recorded, boolean withoutAsking) {
        this.recordedParameters = recorded;
        this.runRecordedWithoutAsking = withoutAsking;
    }

    /**
     * Called in the EDT whenever a run of this window has ended, also after errors and cancels (a Modify window reads its
     * object again in Object Explorer).
     */
    void setExecutionFinishedListener(@Nullable Runnable listener) {
        this.executionFinishedListener = listener;
    }

    /**
     * The object whose definition the window edits (Modify), for Query &gt; Debug… and Trace Execution…; null for other windows.
     */
    @Nullable
    DBSObject getSourceObject() {
        return sourceObject;
    }

    void setSourceObject(@Nullable DBSObject sourceObject) {
        this.sourceObject = sourceObject;
    }

    /**
     * A place in the text to show: the selection and the lines to mark as compile errors.
     *
     * @param start      selection start (caret when {@code end == start})
     * @param errorLines 0-based lines marked with the error color
     */
    record SourcePosition(int start, int end, @NotNull List<Integer> errorLines) {
    }

    /**
     * Selects a range, scrolls to it and marks error lines (Go to Source Code, Go to Error). The marks stay until the
     * next run or the next call.
     */
    void showSourcePosition(@NotNull SourcePosition position) {
        markErrorLines(position.errorLines());
        int length = textArea.getDocument().getLength();
        int start = Math.max(0, Math.min(position.start(), length));
        int end = Math.max(start, Math.min(position.end(), length));
        textArea.setCaretPosition(start);
        textArea.moveCaretPosition(end);
        textArea.requestFocusInWindow();
    }

    /**
     * Marks lines with the error color, replacing earlier marks (EDT).
     */
    private void markErrorLines(@NotNull List<Integer> lines) {
        clearErrorLines();
        addErrorLines(lines);
    }

    /**
     * Marks more lines with the error color, keeping the earlier marks (EDT).
     */
    private void addErrorLines(@NotNull List<Integer> lines) {
        Color red = UIManager.getColor("Chromite.status.error"); //$NON-NLS-1$
        Color base = red != null ? red : Color.RED;
        Color color = new Color(base.getRed(), base.getGreen(), base.getBlue(), 50);
        for (int line : lines) {
            if (line >= 0 && line < textArea.getLineCount()) {
                try {
                    errorLineMarks.add(textArea.addLineHighlight(line, color));
                } catch (BadLocationException e) {
                    log.debug("Error line out of the text: " + line); //$NON-NLS-1$
                }
            }
        }
    }

    private void clearErrorLines() {
        errorLineMarks.forEach(textArea::removeLineHighlight);
        errorLineMarks.clear();
    }

    /**
     * The state cell while a run goes on (EDT): the spinner and "Executing 2/3 · 00:00:12", or the preparation or cancel
     * text with the elapsed time.
     */
    private void showRunState() {
        Execution run = execution;
        if (run == null) {
            return;
        }
        String elapsed = formatElapsed(System.currentTimeMillis() - run.startTime, false);
        int count = run.elements.size();
        String text;
        if (run.stateText != null) {
            text = NLS.bind(EditorMessages.editor_sql_state_elapsed, run.stateText, elapsed);
        } else if (count > 1 && run.statementNumber > 0) {
            text = NLS.bind(EditorMessages.editor_sql_executing_statement, new Object[]{run.statementNumber, count, elapsed});
        } else {
            text = NLS.bind(EditorMessages.editor_sql_executing_elapsed, elapsed);
        }
        statusLine.setState(QueryStatusLine.State.EXECUTING, text);
    }

    /**
     * Marks the statement that starts now (EDT): background of its lines and an icon beside the first line number,
     * replacing the previous statement's marks. The editor does not scroll to it: the user may be editing elsewhere.
     *
     * @param start statement start in the run's text
     * @param end   statement end in the run's text
     */
    private void showRunningStatement(@NotNull Execution run, int start, int end) {
        clearRunningStatement();
        addStatementMarks(run, start, end, "Component.accentColor", UiIcons.get("run"), runningStatementMarks, //$NON-NLS-1$ //$NON-NLS-2$
            icon -> runningStatementIcon = icon);
    }

    private void clearRunningStatement() {
        runningStatementMarks.forEach(textArea::removeLineHighlight);
        runningStatementMarks.clear();
        if (runningStatementIcon != null) {
            RSyntaxUtilities.getGutter(textArea).removeTrackingIcon(runningStatementIcon);
            runningStatementIcon = null;
        }
    }

    /**
     * Marks a statement that failed with the error color (EDT); the mark stays until the next run. The Messages tab's
     * error goes to the same place (Go to Error).
     */
    private void markFailedStatement(@NotNull Execution run, int start, int end) {
        addStatementMarks(run, start, end, "Chromite.status.error", UiIcons.get("error"), failedStatementMarks, //$NON-NLS-1$ //$NON-NLS-2$
            failedStatementIcons::add);
    }

    /**
     * Removes the marks of the last run (EDT).
     */
    private void clearStatementMarks() {
        clearRunningStatement();
        failedStatementMarks.forEach(textArea::removeLineHighlight);
        failedStatementMarks.clear();
        Gutter gutter = RSyntaxUtilities.getGutter(textArea);
        failedStatementIcons.forEach(gutter::removeTrackingIcon);
        failedStatementIcons.clear();
    }

    private void addStatementMarks(
        @NotNull Execution run,
        int start,
        int end,
        @NotNull String colorKey,
        @NotNull Icon icon,
        @NotNull List<Object> marks,
        @NotNull Consumer<GutterIconInfo> iconConsumer
    ) {
        // The run's offsets, moved by what was typed since it started
        int length = textArea.getDocument().getLength();
        int from = Math.min(mapOffset(run, start), length);
        int to = Math.min(Math.max(from, mapOffset(run, end)), length);
        Color base = UIManager.getColor(colorKey);
        if (base == null) {
            base = Color.GRAY;
        }
        // Light enough to read the text and the error lines' color under it, as the compile error lines
        Color color = new Color(base.getRed(), base.getGreen(), base.getBlue(), 40);
        try {
            int firstLine = textArea.getLineOfOffset(from);
            // A statement ends before the line break: its last line is the line of its last character
            int lastLine = textArea.getLineOfOffset(Math.max(from, to - 1));
            for (int line = firstLine; line <= lastLine; line++) {
                marks.add(textArea.addLineHighlight(line, color));
            }
            iconConsumer.accept(RSyntaxUtilities.getGutter(textArea).addLineTrackingIcon(firstLine, icon));
        } catch (BadLocationException e) {
            log.debug("Statement out of the text: " + start); //$NON-NLS-1$
        }
    }

    /**
     * Where an offset of the run's text is now, after the edits made since the run started.
     */
    private static int mapOffset(@NotNull Execution run, int offset) {
        int mapped = offset;
        for (int[] edit : run.edits) {
            int at = edit[0];
            int length = edit[1];
            if (length >= 0) {
                if (mapped >= at) {
                    mapped += length;
                }
            } else if (mapped > at) {
                mapped = Math.max(at, mapped + length);
            }
        }
        return mapped;
    }

    /**
     * Something besides the text changed (the extension's header fields): the window needs saving.
     */
    void markModified() {
        setDirty(true);
    }

    private void setDirty(boolean dirty) {
        if (this.dirty != dirty) {
            this.dirty = dirty;
            host.titleChanged(this);
            firePropertyChange(DIRTY_PROPERTY, !dirty, dirty);
        }
    }

    /**
     * Row limit of this query window ("Select Top N Rows"): it fetches at least this many rows, more if the
     * connection's maximum is higher. 0 uses the connection's maximum.
     */
    void setMaxRows(long maxRows) {
        this.maxRows = maxRows;
    }

    /**
     * The connection was changed elsewhere (renamed, connected, disconnected): title and default schema.
     */
    void connectionChanged() {
        host.titleChanged(this);
        connectionCombo.repaint();
        // A new name
        updatePickerToolTips();
        statusLine.setConnection(dataSourceContainer);
        refreshSchemas();
        // Connected or disconnected: whether the connection gives actual plans is known after connecting
        host.executionStateChanged(this);
    }

    @NotNull
    QueryResultsView getResultsView() {
        return resultsView;
    }

    /**
     * Query &gt; Pin Results: the selected result tab (or the one selected last) is pinned and the next runs leave it.
     */
    void pinResults() {
        if (execution != null) {
            // The run is still adding result tabs
            UIManager.getLookAndFeel().provideErrorFeedback(this);
            return;
        }
        switch (resultsView.pinResults()) {
            case NOTHING_TO_PIN -> statusLine.setState(QueryStatusLine.State.READY, EditorMessages.editor_results_nothing_to_pin);
            case ALREADY_PINNED -> statusLine.setState(QueryStatusLine.State.READY, EditorMessages.editor_results_already_pinned);
            // The pinned tab is in front; the Results card comes back if it was hidden
            case PINNED -> host.showResults(this, false);
        }
    }

    /**
     * Query &gt; Include Actual Execution Plan (Ctrl+M), per query window.
     */
    boolean isIncludeActualPlan() {
        return includeActualPlan;
    }

    void setIncludeActualPlan(boolean include) {
        includeActualPlan = include;
        statusLine.setState(QueryStatusLine.State.READY, include
            ? EditorMessages.editor_sql_actual_plan_on : EditorMessages.editor_sql_actual_plan_off);
    }

    /**
     * Whether the window's connection gives actual plans while the statements run once ({@link SQLActualPlanProvider}),
     * or null while it is not connected: the data source makes the adapter, so it is known after connecting.
     */
    @Nullable
    Boolean getActualPlanSupport() {
        DBPDataSourceContainer container = dataSourceContainer;
        DBPDataSource dataSource = container == null || !container.isConnected() ? null : container.getDataSource();
        return dataSource == null ? null : DBUtils.getAdapter(SQLActualPlanProvider.class, dataSource) != null;
    }

    boolean isExplaining() {
        return explaining;
    }

    /**
     * Query &gt; SQL Terminal, per query window: runs are also written as text to the Terminal tab
     * ({@link SqlTerminalView}).
     */
    boolean isTerminalEnabled() {
        return terminalEnabled;
    }

    /**
     * Turns the terminal on or off for the next runs; a run that is going on keeps the choice it started with. The
     * Terminal tab is in the window's tab row only while the terminal is on.
     */
    void setTerminalEnabled(boolean enabled) {
        terminalEnabled = enabled;
        resultsView.setTerminalComponent(enabled ? terminalView.getComponent() : null);
    }

    /**
     * Rows in the status line: the selected result tab's rows, or the rows the last run read while another tab
     * (Messages, plan, terminal) is in front.
     */
    private void updateRowCount() {
        long tabRows = resultsView.getRowCount();
        Long rows = tabRows >= 0 ? Long.valueOf(tabRows) : runRows;
        statusLine.setRows(rows == null ? null
            : NLS.bind(rows == 1 ? EditorMessages.editor_sql_rows_one : EditorMessages.editor_sql_rows_many, rows));
    }

    /**
     * Ln / Col of the caret in the status line (one-based, tabs count as one column).
     */
    private void updateCaretPosition() {
        int caret = textArea.getCaretPosition();
        try {
            int line = textArea.getLineOfOffset(caret);
            statusLine.setCaret(line + 1, caret - textArea.getLineStartOffset(line) + 1);
        } catch (BadLocationException e) {
            statusLine.setCaret(-1, -1);
        }
    }

    /**
     * The query status line; the main window shows the active editor's line in its status bar.
     */
    @NotNull
    QueryStatusLine getStatusLine() {
        return statusLine;
    }

    boolean isExecuting() {
        return execution != null;
    }

    /**
     * Unsaved changes in the result grids (closing the window asks to save them).
     */
    boolean hasPendingResultChanges() {
        return resultsView.getChangeCount() > 0;
    }

    int getPendingResultChangeCount() {
        return resultsView.getChangeCount();
    }

    boolean isSavingResults() {
        return resultsView.isSaving();
    }

    /**
     * Saves the result grids' changes, waiting in a progress dialog.
     *
     * @return false if a save failed (the error is in the Messages tab)
     */
    boolean saveResultChanges() {
        return resultsView.saveChanges();
    }

    void focusEditor() {
        textArea.requestFocusInWindow();
    }

    /**
     * The editor text area, for Edit &gt; Find / Replace.
     */
    @NotNull
    SqlTextArea getTextArea() {
        return textArea;
    }

    /**
     * Query > Complete (Ctrl+Space): code completion proposals at the caret.
     */
    void complete() {
        textArea.requestFocusInWindow();
        completion.complete();
    }

    /**
     * Query &gt; Copy Current Statement: the statement at the caret (or the selection) to the clipboard, as it is in the
     * editor.
     */
    void copyCurrentStatement() {
        SQLScriptElement element = SqlQueryCommands.getActiveStatement(dataSourceContainer, textArea);
        if (element == null) {
            statusLine.setState(QueryStatusLine.State.READY, QueryCommandMessages.query_no_statement);
            return;
        }
        String text = textArea.getText();
        int start = element.getOffset();
        int end = start + element.getLength();
        String statement = start >= 0 && end <= text.length() && end > start ? text.substring(start, end) : element.getText();
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(statement), null);
        statusLine.setState(QueryStatusLine.State.READY, QueryCommandMessages.query_copy_statement_done);
    }

    /**
     * Query &gt; Previous / Next Statement: selects the statement before or after the one at the caret.
     */
    void selectStatement(boolean next) {
        if (!SqlQueryCommands.selectStatement(dataSourceContainer, textArea, next)) {
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
        }
    }

    /**
     * Query &gt; Variables…: the {@code @set} variables this window keeps between runs. Not while a run changes them.
     */
    void showVariables() {
        if (execution == null) {
            QueryVariablesDialog.open(this, fileName, windowVariables);
        }
    }

    /**
     * The tab is closing: stop a running query and close the window's own session (the main window asked about its
     * transaction first, {@link #confirmEndSession}).
     */
    void dispose() {
        cancel();
        completion.cancel();
        elapsedTimer.stop();
        tabSpinner.stop();
        transactionTimer.stop();
        metadataRefresh.clear();
        windowContext.release();
    }

    /**
     * The execution context (session) the window runs in now, without opening one: its own context, or the connection's
     * main context when it shares it; null while not connected or before the window used the connection.
     */
    @Nullable
    DBCExecutionContext getExecutionContext() {
        return windowContext.get(dataSourceContainer);
    }

    /**
     * Names of a default catalog (database) and schema; either may be null.
     */
    record DefaultObjectNames(@Nullable String catalog, @Nullable String schema) {
        /**
         * {@code catalog.schema}, or the one that is set.
         */
        @NotNull
        String label() {
            return catalog != null && schema != null ? catalog + "." + schema : CommonUtils.notEmpty(schema != null ? schema : catalog); //$NON-NLS-1$
        }
    }

    /**
     * The default database/schema of the window now: the one its session shows, or the restored one that is not applied
     * yet (the window has not connected since the restart); null when unknown. EDT.
     */
    @Nullable
    DefaultObjectNames getDefaultObjectNames() {
        DBSObject object = schemaState != null ? schemaState.reader().getDefaultObject() : null;
        if (object == null) {
            synchronized (restoredDefaultsLock) {
                return restoredDefaults;
            }
        }
        DBSObject parent = object.getParentObject();
        return object instanceof DBSSchema
            ? new DefaultObjectNames(parent instanceof DBSCatalog ? parent.getName() : null, object.getName())
            : object instanceof DBSCatalog ? new DefaultObjectNames(object.getName(), null) : null;
    }

    /**
     * A window restored after a restart ({@link QueryWindowRecovery}) gets its default database/schema back when its own
     * session opens, which is when the user runs something or the connection is connected already. Nothing connects here.
     * EDT.
     */
    void setRestoredDefaults(@NotNull DefaultObjectNames names) {
        synchronized (restoredDefaultsLock) {
            restoredDefaults = names;
        }
        // A read started by the constructor (connected connection) may have opened the session before the names were set
        refreshSchemas();
    }

    /**
     * Opens (or gets) the window's session ({@link QueryWindowContext#open}) and gives it the restored default
     * database/schema first, so that the first run after a restart runs where the window ran before. Every job that
     * opens the session comes here (run, plan, the default schema and commit mode reads). Job thread.
     */
    @NotNull
    private DBCExecutionContext openWindowContext(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSource dataSource) throws DBException {
        DBCExecutionContext context = windowContext.open(monitor, dataSource);
        synchronized (restoredDefaultsLock) {
            DefaultObjectNames names = restoredDefaults;
            restoredDefaults = null;
            DBCExecutionContextDefaults<?, ?> contextDefaults = context.getContextDefaults();
            if (names == null || contextDefaults == null) {
                return context;
            }
            // The connection's main context is shared with the other windows of the connection: a restored window does not
            // change their default (it shows the shared one instead)
            if (context != windowContext.getOwn(dataSource.getContainer())) {
                log.debug("Restored default '" + names.label() + "' not applied: the window shares the main context of '"
                    + dataSource.getContainer().getName() + "'");
                return context;
            }
            DBSCatalog currentCatalog = contextDefaults.getDefaultCatalog();
            try {
                DBExecUtils.setExecutionContextDefaults(monitor, dataSource, context, names.catalog(),
                    currentCatalog != null ? currentCatalog.getName() : null, names.schema());
            } catch (DBException e) {
                // Dropped or renamed since: the window runs in the session's own default, which its picker shows
                log.warn("Can't restore the default '" + names.label() + "' of query window " + fileName, e);
            }
        }
        return context;
    }

    /**
     * Before the window's own session ends (the window closes, changes its connection or its database instance): asks
     * Commit / Roll back / Cancel when the session has a transaction that is not committed. Nothing to ask when the
     * window shares the connection's main context, which stays open. Call in the EDT.
     *
     * @return true when the session may end
     */
    boolean confirmEndSession() {
        DBCExecutionContext own = windowContext.getOwn(dataSourceContainer);
        return own == null || ExitTransactionCheck.confirmEndSession(this, own, fileName);
    }

    /**
     * Document tab icon: an SQL file icon for a query saved to a file, otherwise the logo of the connection's driver
     * (DataGrip console tabs).
     */
    @NotNull
    Icon getTabIcon() {
        if (execution != null) {
            return tabSpinner;
        }
        Icon extensionIcon = extension != null ? extension.getTabIcon() : null;
        if (extensionIcon != null) {
            return extensionIcon;
        }
        if (file == null && dataSourceContainer != null) {
            return NodeIcons.getIcon(dataSourceContainer.getDriver().getIcon());
        }
        return UiIcons.get("file-sql"); //$NON-NLS-1$
    }

    /**
     * The connection and default database/schema pickers. They belong to this window (their models and listeners are its
     * state); the main window shows the active window's pickers in its SQL Editor tool bar ({@link SqlEditorToolBar}).
     */
    private void createPickers() {
        connectionCombo.setRenderer(new ConnectionRenderer());
        sizePicker(connectionCombo, CONNECTION_PICKER_WIDTH);
        connectionCombo.addActionListener(e -> {
            if (!updatingCombos && connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer selected && selected != dataSourceContainer) {
                // The window's own session on the old connection ends
                if (!confirmEndSession()) {
                    updatingCombos = true;
                    try {
                        connectionCombo.setSelectedItem(dataSourceContainer);
                    } finally {
                        updatingCombos = false;
                    }
                    return;
                }
                windowContext.release();
                dataSourceContainer = selected;
                statusLine.setState(QueryStatusLine.State.READY, EditorMessages.editor_status_ready);
                statusLine.setConnection(selected);
                host.titleChanged(this);
                refreshSchemas();
                // The Actual Plan toggle depends on the connection
                host.executionStateChanged(this);
            }
        });
        connectionCombo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // Connections may have been added, removed or renamed since
                reloadConnectionItems();
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        schemaCombo.setRenderer(new SchemaRenderer());
        sizePicker(schemaCombo, SCHEMA_PICKER_WIDTH);
        schemaCombo.addActionListener(e -> {
            if (!updatingCombos && schemaCombo.getSelectedItem() instanceof DBSObject selected) {
                changeDefaultObject(selected);
            }
        });
        // The items are reloaded with new models (setModel fires no item event)
        for (JComboBox<?> picker : List.of(connectionCombo, schemaCombo)) {
            picker.addItemListener(e -> updatePickerToolTips());
            picker.addPropertyChangeListener("model", e -> updatePickerToolTips()); //$NON-NLS-1$
        }
        updatePickerToolTips();
    }

    /**
     * Tool tips of the pickers with the whole name of the selected connection and database/schema: the pickers have a
     * fixed width and their renderers cut longer names with an ellipsis. The opened list is as wide as its items.
     */
    private void updatePickerToolTips() {
        connectionCombo.setToolTipText(connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer container
            ? NLS.bind(EditorMessages.editor_sql_connection_selected_tip, container.getName()) : EditorMessages.editor_sql_connection_tip);
        schemaCombo.setToolTipText(schemaCombo.getSelectedItem() instanceof DBSObject object
            ? NLS.bind(EditorMessages.editor_sql_schema_selected_tip, object.getName()) : EditorMessages.editor_sql_schema_tip);
    }

    /**
     * Connection picker of this window, shown in the main window's SQL Editor tool bar while this window is active.
     */
    @NotNull
    JComboBox<DBPDataSourceContainer> getConnectionCombo() {
        return connectionCombo;
    }

    /**
     * Default database/schema picker of this window, shown like {@link #getConnectionCombo()}.
     */
    @NotNull
    JComboBox<Object> getSchemaCombo() {
        return schemaCombo;
    }

    /**
     * Fixed width of a picker in the SQL Editor tool bar. In a narrow main window the pickers give way (down to
     * {@link #PICKER_MIN_WIDTH}) before the tool bar's buttons overflow ({@link OverflowToolBar}).
     * The padding comes from the density (the control margin in density B, 24 high pickers in compact mode); the height is set again
     * whenever the picker gets a new UI (theme or density change, {@link UiDensity}).
     */
    static void sizePicker(@NotNull JComboBox<?> combo, int width) {
        combo.putClientProperty(FlatClientProperties.STYLE, "padding: $" + UiSizes.EDITOR_TOOL_BAR_COMBO_PADDING); //$NON-NLS-1$
        setPickerSize(combo, width);
        combo.addPropertyChangeListener("UI", e -> setPickerSize(combo, width)); //$NON-NLS-1$
    }

    private static void setPickerSize(@NotNull JComboBox<?> combo, int width) {
        // The height the picker's UI computes, not the one fixed for the previous UI
        combo.setPreferredSize(null);
        Dimension size = new Dimension(width, combo.getPreferredSize().height);
        combo.setPreferredSize(size);
        combo.setMaximumSize(size);
        OverflowToolBar.setShrinkable(combo, PICKER_MIN_WIDTH);
    }

    //////////////////////////////////////////////////////////
    // Execution (EDT)

    /**
     * Executes the selection if at least one character is selected, otherwise the whole editor text.
     */
    void execute() {
        execute(SqlQueryCommands.ExecuteMode.SCRIPT);
    }

    /**
     * Executes what the mode says: F5's selection or whole text, or a statement command of the Query menu (the statement
     * at the caret, from the caret, its row count, an expression). The statement commands find their statements in the
     * preparation job, with the connection's dialect ({@link SqlQueryCommands#extractElements}).
     */
    void execute(@NotNull SqlQueryCommands.ExecuteMode mode) {
        if (execution != null || explaining) {
            return;
        }
        if (resultsView.isSaving()) {
            statusLine.setState(QueryStatusLine.State.READY, EditorMessages.editor_sql_saving_changes);
            return;
        }
        String text = textArea.getText();
        int selectionStart = textArea.getSelectionStart();
        int selectionEnd = textArea.getSelectionEnd();
        int offset = 0;
        int length = text.length();
        if (mode != SqlQueryCommands.ExecuteMode.SCRIPT) {
            // The caret and the selection, possibly empty
            offset = selectionStart;
            length = selectionEnd - selectionStart;
        } else if (selectionEnd - selectionStart >= 1) {
            offset = selectionStart;
            length = selectionEnd - selectionStart;
        }
        // The text the run can execute: F5's range, the selection, or the whole text for a statement found later
        String scope = mode == SqlQueryCommands.ExecuteMode.SCRIPT || length > 0 ? text.substring(offset, offset + length) : text;
        if (scope.isBlank()) {
            statusLine.setState(QueryStatusLine.State.READY, EditorMessages.editor_sql_nothing_to_execute);
            return;
        }
        DBPDataSourceContainer container = dataSourceContainer;
        if (container == null) {
            JOptionPane.showMessageDialog(this, EditorMessages.editor_sql_choose_connection,
                EditorMessages.editor_common_execute, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!SqlExecutionSafety.checkExecutePermission(this, container)) {
            return;
        }
        // Pinned results stay, so only the current ones are replaced
        int pendingChanges = resultsView.getCurrentChangeCount();
        if (pendingChanges > 0) {
            String[] options = {EditorMessages.editor_sql_discard_and_execute, EditorMessages.editor_common_cancel};
            if (JOptionPane.showOptionDialog(this,
                NLS.bind(pendingChanges == 1 ? EditorMessages.editor_sql_discard_confirm_one : EditorMessages.editor_sql_discard_confirm_many,
                    pendingChanges),
                EditorMessages.editor_common_execute, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]) != 0) {
                return;
            }
        }
        // Parameter values (Query Library, a bound run from the history): asked here in the EDT, bound in the job threads.
        // Other windows: the engine asks for the values it needs while the script runs (ScriptProvider)
        SqlParameterBinding binding = SqlParameterBinding.NONE;
        if (extension != null) {
            binding = extension.prepareExecution(this, container, scope);
            if (binding == null) {
                return;
            }
        } else if (recordedParameters != null) {
            boolean ask = !runRecordedWithoutAsking;
            runRecordedWithoutAsking = false;
            binding = recordedParameters.prepareExecution(this, getFileName(), container, scope, ask);
            if (binding == null) {
                return;
            }
        }

        long connectionMaxRows = container.getPreferenceStore().getInt(ModelPreferences.RESULT_SET_MAX_ROWS);
        Execution run = new Execution(container, text, offset, length,
            maxRows > 0 ? Math.max(maxRows, connectionMaxRows) : connectionMaxRows, maxRows > 0);
        // A connection known to give no actual plans has the toggle disabled: the run does not ask for one
        run.includeActualPlan = isIncludeActualPlan() && !Boolean.FALSE.equals(getActualPlanSupport());
        run.terminal = terminalEnabled;
        run.binding = binding;
        run.mode = mode;
        run.file = file;
        execution = run;
        resultsView.clear();
        clearErrorLines();
        clearStatementMarks();
        if (run.includeActualPlan) {
            planView.clear(EditorMessages.editor_sql_waiting_actual_plan);
        }
        // The state cell shows the elapsed time while running; the elapsed segment shows the run's time when it ends
        run.stateText = container.isConnected() ? null : EditorMessages.editor_sql_connecting;
        showRunState();
        statusLine.setElapsed(null);
        // The tab shows a spinner while the window runs, also when another tab is in front
        tabSpinner.start();
        host.titleChanged(this);
        runRows = null;
        statusLine.setRows(null);
        elapsedTimer.start();
        updateActions();
        // No result yet: Messages until the run's first result tab comes to the front
        showRunOutput(run, true);

        AbstractJob prepareJob = new AbstractJob("Prepare query") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                prepareAndSchedule(run, monitor);
                return Status.OK_STATUS;
            }
        };
        synchronized (jobLock) {
            run.job = prepareJob;
        }
        prepareJob.schedule();
    }

    /**
     * Cancels the running job: the preparation (connecting) or the query execution.
     */
    void cancel() {
        Execution run = execution;
        if (run == null) {
            return;
        }
        synchronized (jobLock) {
            run.cancelRequested = true;
            if (run.job != null) {
                run.job.cancel();
            }
        }
        run.stateText = EditorMessages.editor_sql_canceling;
        showRunState();
        // Stop is disabled once it was pressed
        host.executionStateChanged(this);
    }

    /**
     * Whether Execute can start now (the main window's Query menu and SQL Editor tool bar ask).
     */
    boolean canExecute() {
        return execution == null && !explaining && !resultsView.isSaving();
    }

    /**
     * Whether a run is going on that was not asked to stop yet.
     */
    boolean canCancel() {
        return execution != null && !execution.cancelRequested;
    }

    /**
     * Whether Display Estimated Execution Plan can start now.
     */
    boolean canExplain() {
        return execution == null && !explaining;
    }

    private void updateActions() {
        // The connection and its defaults stay as they are while a query runs on them
        connectionCombo.setEnabled(execution == null);
        schemaCombo.setEnabled(execution == null && schemaChangeSupported);
        host.executionStateChanged(this);
    }

    /**
     * Called in the EDT when the run is over (the job finished, or preparation stopped early).
     */
    private void finishExecution(@NotNull Execution run, @NotNull IStatus status) {
        if (execution != run) {
            return;
        }
        execution = null;
        elapsedTimer.stop();
        // The statement marks of errors stay until the next run; the running one goes
        clearRunningStatement();
        tabSpinner.stop();
        host.titleChanged(this);
        long elapsed = System.currentTimeMillis() - run.startTime;

        if (status.getSeverity() == IStatus.ERROR && !run.hasErrors) {
            // Errors outside of a query (e.g. opening the session); query errors were already written by the listener
            run.hasErrors = true;
            resultsView.appendError(ErrorText.describe(status));
            if (run.terminal) {
                terminalView.appendError(ErrorText.describe(status));
                terminalView.append("");
            }
        }
        boolean canceled = run.cancelRequested || status.getSeverity() == IStatus.CANCEL;
        if (canceled) {
            resultsView.appendMessage(EditorMessages.editor_sql_canceled_by_user);
            if (run.terminal) {
                terminalView.append(EditorMessages.editor_sql_canceled_by_user);
                terminalView.append("");
            }
        }
        resultsView.appendMessage("");
        resultsView.appendMessage(NLS.bind(EditorMessages.editor_sql_completion_time, OffsetDateTime.now().format(COMPLETION_TIME_FORMAT)));

        if (run.hasErrors) {
            statusLine.setState(QueryStatusLine.State.ERROR, EditorMessages.editor_sql_completed_with_errors);
        } else if (canceled) {
            statusLine.setState(QueryStatusLine.State.CANCELED, EditorMessages.editor_sql_query_canceled);
        } else {
            statusLine.setState(QueryStatusLine.State.SUCCESS, EditorMessages.editor_sql_executed_successfully);
        }
        // The run may have connected: server version and the connected icon
        statusLine.setConnection(run.dataSourceContainer);
        statusLine.setElapsed(formatElapsed(elapsed, true));
        runRows = run.rowsFetched.get();
        updateRowCount();
        refreshOpenTransaction();
        updateActions();
        // As in SSMS: an error that stopped the run (or no results at all) brings the Messages tab to the front; the result
        // tabs before the error stay. A run that skipped failed statements keeps its results in front (Messages shows the
        // errors' count). Otherwise the tab the user is looking at stays
        boolean messages = run.hasErrors && !run.continueOnError || resultsView.getGridCount() == 0;
        if (messages || run.terminal) {
            showRunOutput(run, messages);
        }
        finishActualPlan(run, canceled);
        // The run may have connected, or changed the default database (USE)
        refreshSchemas();
        if (executionFinishedListener != null) {
            executionFinishedListener.run();
        }
    }

    /**
     * Brings the output of a run to the front: the Terminal tab when the run writes to the terminal, else a result tab
     * or the Messages tab.
     */
    private void showRunOutput(@NotNull Execution run, boolean messages) {
        if (run.terminal) {
            host.showTerminal(this);
        } else {
            host.showResults(this, messages);
        }
    }

    /**
     * Include Actual Execution Plan: the plans arrived while the statements ran; says why there is none otherwise.
     */
    private void finishActualPlan(@NotNull Execution run, boolean canceled) {
        if (!run.includeActualPlan) {
            return;
        }
        if (!run.planChecked) {
            // The run stopped before it reached the connection
            planView.clear(EditorMessages.editor_sql_plan_did_not_run);
            return;
        }
        if (run.actualPlan == null) {
            planView.clear(run.actualPlanError != null
                ? NLS.bind(EditorMessages.editor_sql_cant_include_actual_plan, run.actualPlanError)
                : EditorMessages.editor_sql_actual_plan_unsupported);
            return;
        }
        if (!planView.hasContent()) {
            planView.clear(canceled ? EditorMessages.editor_sql_plan_canceled : EditorMessages.editor_sql_plan_none_returned);
        }
    }

    /**
     * Query &gt; Display Estimated Execution Plan (Ctrl+L): the plans of the selected statements, or of the whole script.
     */
    void explainEstimatedPlan() {
        if (execution != null || explaining) {
            return;
        }
        String text = textArea.getText();
        int offset = 0;
        int length = text.length();
        if (textArea.getSelectionEnd() - textArea.getSelectionStart() >= 1) {
            offset = textArea.getSelectionStart();
            length = textArea.getSelectionEnd() - offset;
        }
        if (text.substring(offset, offset + length).isBlank()) {
            statusLine.setState(QueryStatusLine.State.READY, EditorMessages.editor_sql_nothing_to_explain);
            return;
        }
        DBPDataSourceContainer container = dataSourceContainer;
        if (container == null) {
            JOptionPane.showMessageDialog(this, EditorMessages.editor_sql_choose_connection,
                EditorMessages.editor_sql_estimated_plan, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        explaining = true;
        updateActions();
        planView.clear(container.isConnected() ? EditorMessages.editor_sql_making_estimated_plan : EditorMessages.editor_sql_connecting);
        host.showPlan(this);
        int finalOffset = offset;
        int finalLength = length;
        new AbstractJob("Prepare execution plan") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                String problem = null;
                try {
                    if (!container.isConnected()) {
                        DBUtils.initDataSource(monitor, container, null);
                    }
                    DBPDataSource dataSource = container.getDataSource();
                    DBCQueryPlanner planner = SqlPlanExplainer.getPlanner(container.isConnected() ? dataSource : null);
                    // The window's session: its default schema, and its open transaction for planners that execute
                    DBCExecutionContext context = dataSource == null || !container.isConnected() ? null : openWindowContext(monitor, dataSource);
                    if (dataSource == null || context == null) {
                        problem = NLS.bind(EditorMessages.editor_sql_not_connected_to, container.getName());
                    } else if (planner == null) {
                        problem = EditorMessages.editor_sql_no_plans;
                    } else {
                        List<SQLQuery> queries = new ArrayList<>();
                        for (SQLScriptElement element : SQLScriptBatches.extractQueries(dataSource, text, finalOffset, finalLength)) {
                            if (element instanceof SQLQuery query) {
                                queries.add(query);
                            }
                        }
                        if (queries.isEmpty()) {
                            problem = EditorMessages.editor_sql_no_statements;
                        } else {
                            SwingUtilities.invokeLater(() -> {
                                planView.clear(EditorMessages.editor_sql_making_estimated_plan);
                                statusLine.setConnection(container);
                            });
                            SqlPlanExplainer.explain(context, planner, queries, planView::addEntry, () -> {
                                explaining = false;
                                updateActions();
                            });
                            return Status.OK_STATUS;
                        }
                    }
                } catch (Throwable e) {
                    log.debug("Execution plan preparation failed", e);
                    problem = ErrorText.describe(e);
                }
                String message = problem;
                SwingUtilities.invokeLater(() -> {
                    explaining = false;
                    planView.clear(message);
                    updateActions();
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    //////////////////////////////////////////////////////////
    // Execution (job threads)

    private void prepareAndSchedule(@NotNull Execution run, @NotNull DBRProgressMonitor monitor) {
        DBPDataSourceContainer container = run.dataSourceContainer;
        try {
            if (!container.isConnected()) {
                // Reports connection errors itself (SwingConnectionService)
                DBUtils.initDataSource(monitor, container, null);
            }
            DBPDataSource dataSource = container.getDataSource();
            if (dataSource == null || !container.isConnected()) {
                stopPreparation(run, run.cancelRequested ? Status.CANCEL_STATUS
                    : errorStatus(NLS.bind(EditorMessages.editor_sql_not_connected_to_short, container.getName())));
                return;
            }
            // Opens the window's own session on the first run (QueryWindowContext); fails with editor_sql_no_context
            DBCExecutionContext context = openWindowContext(monitor, dataSource);
            List<SQLScriptElement> extracted = SqlQueryCommands.extractElements(run.mode, dataSource, run.text, run.offset, run.length);
            if (extracted == null || extracted.isEmpty() && run.mode != SqlQueryCommands.ExecuteMode.SCRIPT) {
                stopPreparation(run, errorStatus(extracted == null ? QueryCommandMessages.query_no_statement : QueryCommandMessages.query_not_a_query));
                return;
            }
            List<SQLScriptElement> elements = run.binding.prepare(monitor, context, extracted);
            if (elements.isEmpty()) {
                stopPreparation(run, Status.OK_STATUS);
                return;
            }
            run.elements = List.copyOf(elements);
            // Asked here, not in execute(): the statements are known only after splitting with the connection's dialect
            if (!SqlExecutionSafety.confirmDangerousStatements(this, dataSource, run.text, elements, null)) {
                stopPreparation(run, Status.CANCEL_STATUS);
                return;
            }
            if (run.includeActualPlan) {
                prepareActualPlan(run, monitor, dataSource, context);
            }
            // Parameters without a value are asked for in a dialog (SWT: SQLEditorParametersProvider). The window's variables
            // are the parent: @set values stay for the next runs; the window's file is where @include starts relative paths
            SQLScriptContext scriptContext = new SQLScriptContext(windowVariables, () -> context, run.file, new MessagesWriter(run),
                new QueryParameterBinder.ScriptProvider(this));
            SQLQueryExecuteJob job = new SQLQueryExecuteJob(
                "Execute query", context, null, elements, scriptContext,
                new QueryListener(run, context), new QueryHandler(run, context, scriptContext));
            run.engine = job;
            if (run.customMaxRows) {
                job.setResultSetLimit(0, run.maxRows);
            }
            // Stop or skip on errors; never the engine default STOP_ROLLBACK (see class doc)
            SQLScriptErrorHandling errorHandling = SqlExecutionOptionsPage.getEngineErrorHandling(container.getPreferenceStore());
            run.continueOnError = errorHandling == SQLScriptErrorHandling.IGNORE;
            job.setErrorHandling(errorHandling);
            job.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    // Also after errors and cancels: the connection must not keep returning plans
                    disableActualPlan(run);
                    // Canceling the parameter dialog (or the execution confirmation) stops the script without an error
                    // and with an OK job status
                    IStatus result = job.isCanceledByUser() ? Status.CANCEL_STATUS : event.getResult();
                    SwingUtilities.invokeLater(() -> finishExecution(run, result != null ? result : Status.OK_STATUS));
                }
            });
            synchronized (jobLock) {
                if (run.cancelRequested) {
                    disableActualPlan(run);
                    stopPreparation(run, Status.CANCEL_STATUS);
                    return;
                }
                run.job = job;
                job.schedule();
            }
            post(run, () -> {
                if (!run.cancelRequested) {
                    run.stateText = null;
                    showRunState();
                }
            });
        } catch (Throwable e) {
            log.debug("Query preparation failed", e);
            // The job that restores the session settings did not start
            disableActualPlan(run);
            stopPreparation(run, errorStatus(ErrorText.describe(e)));
        }
    }

    /**
     * Include Actual Execution Plan, before the run (job thread): turns actual plans on in the window's session. When
     * that fails the statements still run as asked, without a plan (the error is in the Messages tab).
     */
    private void prepareActualPlan(
        @NotNull Execution run,
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull DBCExecutionContext context
    ) {
        SQLActualPlanProvider provider = DBUtils.getAdapter(SQLActualPlanProvider.class, dataSource);
        if (provider != null) {
            try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Execution plan settings")) {
                run.actualPlanContext = context;
                run.actualPlan = provider.startRun(session);
            } catch (Throwable e) {
                log.debug("Can't turn the actual plan on", e);
                run.actualPlanError = ErrorText.describe(e);
                post(run, () -> resultsView.appendError(NLS.bind(EditorMessages.editor_sql_cant_include_actual_plan, ErrorText.describe(e))));
            }
        }
        run.planChecked = true;
    }

    /**
     * Restores the session settings the actual plan changed, after the run (also after errors and cancels) and once.
     */
    private void disableActualPlan(@NotNull Execution run) {
        SQLActualPlanProvider.Run actualPlan = run.actualPlan;
        DBCExecutionContext context = run.actualPlanContext;
        if (actualPlan == null || context == null || !run.actualPlanFinished.compareAndSet(false, true)) {
            return;
        }
        try (DBCSession session = context.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Execution plan settings")) {
            actualPlan.finish(session);
        } catch (Throwable e) {
            log.warn("Can't turn the actual plan off", e);
            post(run, () -> resultsView.appendError(NLS.bind(EditorMessages.editor_sql_cant_restore_actual_plan, ErrorText.describe(e))));
        }
    }

    /**
     * Shows the actual plan of a statement read while the script runs (job thread), or why it could not be read.
     */
    private void addActualPlan(@NotNull Execution run, @NotNull String query, @NotNull ActualPlanReader reader) {
        ExecutionPlanView.Entry entry;
        try {
            DBCPlan plan = reader.read();
            if (plan == null) {
                return;
            }
            entry = new ExecutionPlanView.Entry(query, plan, null, true);
        } catch (Throwable e) {
            log.debug("Can't read the actual plan", e);
            entry = new ExecutionPlanView.Entry(query, null, ErrorText.describe(e), true);
        }
        ExecutionPlanView.Entry finalEntry = entry;
        post(run, () -> planView.addEntry(finalEntry));
    }

    @FunctionalInterface
    private interface ActualPlanReader {
        @Nullable
        DBCPlan read() throws Exception;
    }

    private void stopPreparation(@NotNull Execution run, @NotNull IStatus status) {
        SwingUtilities.invokeLater(() -> finishExecution(run, status));
    }

    @NotNull
    private static IStatus errorStatus(@NotNull String message) {
        return new Status(IStatus.ERROR, ChromiteSwingActivator.PLUGIN_ID, message);
    }

    /**
     * Runs a UI update in the EDT if the run is still the current one.
     */
    private void post(@NotNull Execution run, @NotNull Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (execution == run) {
                update.run();
            }
        });
    }

    /**
     * Messages of one finished query: warnings and PRINT output, server output (DBMS_OUTPUT), rows affected, then the
     * time, or every error (one per failed statement of a batch) with its position in the editor.
     */
    private void reportQueryResult(@NotNull Execution run, @NotNull DBCSession session, @NotNull SQLQueryResult result) {
        List<String> messages = new ArrayList<>();
        for (Throwable warning : CommonUtils.safeCollection(result.getWarnings())) {
            // SQL Server PRINT and RAISERROR (low severity), PostgreSQL NOTICE etc. arrive as SQLWarning
            messages.add(CommonUtils.notEmpty(warning.getMessage()));
        }
        readServerOutput(session, messages);
        // A batch (SQL Server GO batch) can fail in several statements: each error with its own line
        List<Throwable> errors = result.getErrors();
        List<CompileErrorLine> compileErrors = errors.isEmpty() ? readCompileErrors(run, session, result.getStatement()) : List.of();
        if (compileErrors.stream().anyMatch(CompileErrorLine::error)) {
            // The unit exists but is invalid: the run reports errors and brings the Messages tab to the front
            run.hasErrors = true;
        }
        // Counts also before errors: the statements of a batch before (and after) a failed one ran
        boolean anyCount = false;
        List<ActualPlanReceiver> planReceivers = run.planReceivers.getOrDefault(result.getStatement(), List.of());
        int resultSetIndex = 0;
        for (SQLQueryResult.ExecuteResult executeResult : result.getExecuteResults()) {
            if (executeResult.isResultSet() && resultSetIndex < planReceivers.size()
                && planReceivers.get(resultSetIndex++).isPlanResultSet()) {
                // The actual plan of the statement, shown in the Execution plan tab
                continue;
            }
            Long count = executeResult.isResultSet() ? executeResult.getRowCount() : executeResult.getUpdateCount();
            if (count == null) {
                continue;
            }
            anyCount = true;
            String line = NLS.bind(count == 1 ? EditorMessages.editor_sql_rows_affected_one : EditorMessages.editor_sql_rows_affected_many, count);
            if (executeResult.isResultSet() && run.maxRows > 0 && count >= run.maxRows) {
                line = NLS.bind(EditorMessages.editor_sql_rows_affected_max, line, run.maxRows);
            }
            messages.add(line);
        }
        List<ErrorReport> errorReports = new ArrayList<>();
        if (errors.isEmpty()) {
            if (!anyCount && !run.cancelRequested) {
                // A canceled statement ends without an error and without counts; it did not complete
                messages.add(EditorMessages.editor_sql_commands_completed);
            }
            messages.add(NLS.bind(EditorMessages.editor_sql_execution_time, result.getQueryTime()));
        } else {
            run.hasErrors = true;
            for (Throwable error : errors) {
                errorReports.add(describeError(run, session, result.getStatement(), error));
                run.reportedErrors.add(error);
            }
        }
        // SQL terminal: the statement, its result sets as text tables, then the same messages as the Messages tab
        String terminalText = null;
        if (run.terminal) {
            StringBuilder terminal = new StringBuilder(SqlTerminalView.formatStatement(run.binding.getHistoryText(result.getStatement())));
            // A bound run (Query Library) keeps its :name markers: the values follow as the history preview shows them
            List<QueryHistory.Parameter> boundParameters = run.binding.getHistoryParameters(result.getStatement());
            if (boundParameters != null && !boundParameters.isEmpty()) {
                terminal.append('\n').append(QueryHistory.Parameter.toCommentLines(boundParameters));
            }
            for (String table : run.terminalTables.getOrDefault(result.getStatement(), List.of())) {
                terminal.append('\n').append(table);
            }
            for (String message : messages) {
                terminal.append('\n').append(message);
            }
            // Oracle compile errors, as the Messages tab shows them after the messages
            for (CompileErrorLine compileError : compileErrors) {
                terminal.append('\n').append(compileError.text());
            }
            terminalText = terminal.toString();
        }
        String finalTerminalText = terminalText;
        post(run, () -> {
            messages.forEach(resultsView::appendMessage);
            List<Integer> errorLines = new ArrayList<>();
            for (CompileErrorLine compileError : compileErrors) {
                if (compileError.error()) {
                    resultsView.appendError(compileError.text());
                } else {
                    resultsView.appendMessage(compileError.text());
                }
                if (compileError.line() >= 0 && compileError.error()) {
                    errorLines.add(compileError.line());
                }
            }
            addErrorLines(errorLines);
            for (ErrorReport report : errorReports) {
                resultsView.appendError(report.text(), report.offset());
            }
            if (finalTerminalText != null) {
                terminalView.append(finalTerminalText);
                for (ErrorReport report : errorReports) {
                    terminalView.appendError(report.text());
                }
                terminalView.append("");
            }
        });
    }

    /**
     * A message line about a compile error, and its 0-based editor line (-1: none).
     */
    private record CompileErrorLine(@NotNull String text, int line, boolean error) {
    }

    /**
     * Oracle: the compile errors ({@code ALL_ERRORS}) of the stored unit or view a successful CREATE statement made, with
     * their lines in the editor. Oracle reports them only as a warning (ORA-17110), so the window would say the run
     * succeeded while the object is invalid (SWT showed them in the object editor after saving). Empty for other
     * statements and databases. Job thread; reads in the statement's session.
     */
    @NotNull
    private static List<CompileErrorLine> readCompileErrors(@NotNull Execution run, @NotNull DBCSession session, @NotNull SQLQuery query) {
        if (!(session.getDataSource() instanceof OracleDataSource) || !(session instanceof JDBCSession jdbcSession)) {
            return List.of();
        }
        String text = query.getText();
        OracleCompileErrors.Unit unit = OracleCompileErrors.parseCreate(text);
        if (unit == null) {
            return List.of();
        }
        List<CompileErrorLine> lines = new ArrayList<>();
        try {
            String owner = unit.owner() != null ? unit.owner() : OracleCompileErrors.readCurrentSchema(jdbcSession);
            List<OracleCompileErrors.CompileError> errors = OracleCompileErrors.read(jdbcSession, owner, unit.name(), List.of(unit.type()));
            if (errors.isEmpty()) {
                return List.of();
            }
            boolean anyError = errors.stream().anyMatch(OracleCompileErrors.CompileError::error);
            lines.add(new CompileErrorLine(NLS.bind(anyError ? RoutineMessages.routine_compile_errors_header : RoutineMessages.routine_compile_warnings_header,
                unit.type(), owner + "." + unit.name()), -1, anyError)); //$NON-NLS-1$
            // The statement's text starts at its offset in the run's text (the editor text when the run started)
            boolean aligned = run.text.startsWith(text, query.getOffset());
            for (OracleCompileErrors.CompileError error : errors) {
                int offset = query.getOffset() + OracleCompileErrors.getErrorOffset(text, unit.sourceStart(), error);
                int line = aligned ? lineOf(run.text, offset) : lineOf(run.text, query.getOffset());
                int column = aligned ? offset - run.text.lastIndexOf('\n', offset - 1) : 0;
                String message = column > 0 ? NLS.bind(RoutineMessages.routine_compile_error_position, new Object[] {line, column, error.text()})
                    : NLS.bind(RoutineMessages.routine_compile_error_line, line, error.text());
                lines.add(new CompileErrorLine(message, line - 1, error.error()));
            }
        } catch (SQLException e) {
            log.debug("Can't read the compile errors", e); //$NON-NLS-1$
        }
        return lines;
    }

    /**
     * Server output of the finished statement (Oracle and CUBRID {@code DBMS_OUTPUT}, Altibase {@code PRINT}), read in the
     * job thread after every statement, also after errors and cancels. Reading drains the server buffer (Oracle
     * {@code GET_LINE}) or the data source's message buffer (Altibase), so it does not fill up (ORU-10027) or grow while
     * the connection is open. Off when the connection's server output preference is off.
     */
    private static void readServerOutput(@NotNull DBCSession session, @NotNull List<String> messages) {
        DBCExecutionContext context = session.getExecutionContext();
        DBCServerOutputReader reader = DBUtils.getAdapter(DBCServerOutputReader.class, context.getDataSource());
        if (reader == null || !reader.isServerOutputEnabled()) {
            return;
        }
        try {
            // No execution result and no statement: readers that only repeat the statement's SQLWarning (PostgreSQL, the
            // default reader) write nothing, so PRINT and NOTICE are not shown twice
            reader.readServerOutput(session.getProgressMonitor(), context, null, null, new DBCOutputWriter() {
                @Override
                public void println(@Nullable DBCOutputSeverity severity, @Nullable String message) {
                    // PUT_LINE(NULL) is an empty line
                    messages.add(CommonUtils.notEmpty(message));
                }

                @Override
                public void flush() {
                }
            });
        } catch (Throwable e) {
            log.debug("Can't read the server output", e);
        }
    }

    /**
     * Turns the server output on or off on this session when the setting changed after the session opened (Oracle and
     * CUBRID {@code DBMS_OUTPUT}, Altibase {@code PRINT} callback), before the statement runs: a statement run after turning it on is read, and a session
     * turned off no longer buffers output that nobody reads.
     */
    private static void syncServerOutput(@NotNull DBCSession session) {
        DBCExecutionContext context = session.getExecutionContext();
        DBCServerOutputReader reader = DBUtils.getAdapter(DBCServerOutputReader.class, context.getDataSource());
        if (reader == null) {
            return;
        }
        try {
            reader.syncServerOutput(session.getProgressMonitor(), context);
        } catch (Throwable e) {
            log.debug("Can't turn the server output on or off", e);
        }
    }

    /**
     * An error's Messages text and where it is in the window's text.
     *
     * @param offset the error position in the editor text at the run (Go to Error), or -1 when it is in an included file
     */
    private record ErrorReport(@NotNull String text, int offset) {
    }

    /**
     * SSMS-like error header ({@code Msg 208, State 42S02, Line 3, Column 15}) followed by the error messages.
     * The position comes from the dialect's error assistant when it can locate the error, else it is the query start.
     */
    @NotNull
    private static ErrorReport describeError(@NotNull Execution run, @NotNull DBCSession session, @NotNull SQLQuery query, @NotNull Throwable error) {
        // A statement of an included file: its offsets are in that file's text
        SQLQueryExecuteJob engine = run.engine;
        SQLIncludedScript included = engine == null ? null : run.includedScripts.get(engine.getCurrentScriptContext());
        String text = included != null ? included.getText() : run.text;
        int errorOffset = -1;
        int line = -1;
        int column = -1;
        try {
            DBPErrorAssistant errorAssistant = DBUtils.getAdapter(DBPErrorAssistant.class, session.getDataSource());
            DBPErrorAssistant.ErrorPosition[] positions = errorAssistant == null ? null :
                errorAssistant.getErrorPosition(session.getProgressMonitor(), session.getExecutionContext(), query.getText(), error);
            if (positions != null && positions.length > 0) {
                DBPErrorAssistant.ErrorPosition position = positions[0];
                if (position.line < 0) {
                    if (position.position >= 0) {
                        errorOffset = query.getOffset() + position.position;
                    }
                } else {
                    // Line within the query, position within that line (both zero-based)
                    line = lineOf(text, query.getOffset()) + position.line;
                    column = position.position >= 0 ? position.position + 1 : -1;
                }
            }
        } catch (Throwable e) {
            log.debug("Can't get error position", e);
        }
        if (errorOffset >= 0 && errorOffset <= text.length()) {
            line = lineOf(text, errorOffset);
            column = errorOffset - text.lastIndexOf('\n', errorOffset - 1);
        } else if (line < 0) {
            line = lineOf(text, query.getOffset());
        }

        StringBuilder header = new StringBuilder();
        SQLException sqlException = findSqlException(error);
        if (sqlException != null) {
            header.append(NLS.bind(EditorMessages.editor_sql_error_msg, sqlException.getErrorCode()));
            if (!CommonUtils.isEmpty(sqlException.getSQLState())) {
                header.append(NLS.bind(EditorMessages.editor_sql_error_state, sqlException.getSQLState()));
            }
            header.append(", ");
        } else {
            header.append(EditorMessages.editor_sql_error_generic);
        }
        if (included != null) {
            header.append(NLS.bind(QueryCommandMessages.query_include_error_file, included.getFile().getFileName())).append(", ");
        }
        header.append(NLS.bind(EditorMessages.editor_sql_error_line, line));
        if (column > 0) {
            header.append(NLS.bind(EditorMessages.editor_sql_error_column, column));
        }
        // The position the header names, in the window's text (Go to Error); an included file's is not in the window
        int sourceOffset = -1;
        if (included == null) {
            sourceOffset = errorOffset >= 0 && errorOffset <= text.length() ? errorOffset
                : Math.min(text.length(), offsetOfLine(text, line) + Math.max(column - 1, 0));
        }
        // The header already shows the error code and state
        return new ErrorReport(header + "\n" + ErrorText.describeMessages(error), sourceOffset);
    }

    /**
     * The offset where a one-based line starts.
     */
    private static int offsetOfLine(@NotNull String text, int line) {
        int offset = 0;
        for (int current = 1; current < line; current++) {
            int next = text.indexOf('\n', offset);
            if (next < 0) {
                return text.length();
            }
            offset = next + 1;
        }
        return offset;
    }

    @Nullable
    private static SQLException findSqlException(@NotNull Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /**
     * One-based line number of an offset.
     */
    private static int lineOf(@NotNull String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    @NotNull
    private static String formatElapsed(long millis, boolean withMillis) {
        long seconds = millis / 1000;
        String text = String.format("%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60); //$NON-NLS-1$
        return withMillis ? text + String.format(".%03d", millis % 1000) : text; //$NON-NLS-1$
    }

    /**
     * State of one F5 run. Fields written in job threads are only read in the EDT after the job is done
     * (the done event is posted after all their updates), except the cancel flag which is guarded by {@code jobLock}.
     */
    private static final class Execution {
        final DBPDataSourceContainer dataSourceContainer;
        final String text;
        final int offset;
        final int length;
        final long maxRows;
        final boolean customMaxRows;
        final long startTime = System.currentTimeMillis();
        final AtomicLong rowsFetched = new AtomicLong();
        final List<Throwable> reportedErrors = new CopyOnWriteArrayList<>();
        volatile boolean hasErrors;
        // Options: failed statements are skipped and the run goes on (set in the preparation job)
        volatile boolean continueOnError;
        volatile boolean cancelRequested;
        @Nullable
        Job job;
        // Include Actual Execution Plan: set in the EDT before the run, the rest in the preparation job
        boolean includeActualPlan;
        volatile boolean planChecked;
        // Actual plans of this run in the window's session; null when the connection has none or they could not be turned on
        @Nullable
        volatile SQLActualPlanProvider.Run actualPlan;
        @Nullable
        volatile String actualPlanError;
        // The window's session the actual plans were turned on in
        @Nullable
        volatile DBCExecutionContext actualPlanContext;
        // The session settings were restored (the job's end and a cancel before it was scheduled can both ask)
        final AtomicBoolean actualPlanFinished = new AtomicBoolean();
        // Receivers of each query's result sets in fetch order (job thread only): plan result sets are not counted as rows
        final Map<SQLQuery, List<ActualPlanReceiver>> planReceivers = new IdentityHashMap<>();
        // Set in the EDT before the jobs start
        SqlParameterBinding binding = SqlParameterBinding.NONE;
        boolean terminal;
        SqlQueryCommands.ExecuteMode mode = SqlQueryCommands.ExecuteMode.SCRIPT;
        // The window's file at the start (relative @include paths start in its folder), null for a query never saved
        @Nullable
        Path file;
        // The engine, set before it is scheduled
        @Nullable
        volatile SQLQueryExecuteJob engine;
        // The files the run's @include commands read, by their script context (job thread only)
        final Map<SQLScriptContext, SQLIncludedScript> includedScripts = new IdentityHashMap<>();
        // SQL terminal (job thread only): the text tables of each query's result sets, written when the query ends
        final Map<SQLQuery, List<String>> terminalTables = new IdentityHashMap<>();
        // Grid result sets of each query so far (job thread only), counted again when the query starts again (GO 2)
        final Map<SQLQuery, Integer> gridResultSets = new IdentityHashMap<>();
        // The session's default database/schema was read after the last statement (job thread only)
        boolean sessionDefaultsRead;
        // The statements of the run (set in the preparation job), and the one that runs now (1-based, 0 before the first)
        volatile List<SQLScriptElement> elements = List.of();
        volatile int statementNumber;
        // State text while preparing (connecting) or canceling, null while executing (EDT only)
        @Nullable
        String stateText;
        // Edits of the window's text since the run started, oldest first, to find a statement's place now (EDT only):
        // {offset, length}, a negative length for a removal
        final List<int[]> edits = new ArrayList<>();

        Execution(
            @NotNull DBPDataSourceContainer dataSourceContainer,
            @NotNull String text,
            int offset,
            int length,
            long maxRows,
            boolean customMaxRows
        ) {
            this.dataSourceContainer = dataSourceContainer;
            this.text = text;
            this.offset = offset;
            this.length = length;
            this.maxRows = maxRows;
            this.customMaxRows = customMaxRows;
        }
    }

    /**
     * Result receivers and user interaction of the execution engine (called in the job thread).
     */
    private final class QueryHandler implements SQLQueryExecuteHandler {
        private final Execution run;
        private final DBCExecutionContext context;
        private final SQLScriptContext scriptContext;

        QueryHandler(@NotNull Execution run, @NotNull DBCExecutionContext context, @NotNull SQLScriptContext scriptContext) {
            this.run = run;
            this.context = context;
            this.scriptContext = scriptContext;
        }

        @Nullable
        @Override
        public DBDDataReceiver getDataReceiver(@NotNull SQLQuery query, int resultSetNumber) {
            SQLActualPlanProvider.Run actualPlan = run.actualPlan;
            if (actualPlan != null) {
                // Plan result sets go to the Execution plan tab, the others to the grids
                ActualPlanReceiver receiver = new ActualPlanReceiver(actualPlan, query.getText(), () -> createGridReceiver(query),
                    entry -> post(run, () -> planView.addEntry(entry)));
                run.planReceivers.computeIfAbsent(query, q -> new ArrayList<>()).add(receiver);
                return receiver;
            }
            return createGridReceiver(query);
        }

        @NotNull
        private DBDDataReceiver createGridReceiver(@NotNull SQLQuery query) {
            // Natively bound values (Query Library) are bound again when the result is read again (Export Data, filters,
            // next pages): the binding makes the data container
            SQLQueryDataContainer dataContainer = run.binding.createDataContainer(() -> context, query, scriptContext, log);
            // A batch (GO, several selected statements) is one query: the result is read again with the statement that
            // produced it only. Grid receivers are made in the order of the result sets; plan result sets have none.
            int resultSetNumber = run.gridResultSets.merge(query, 1, Integer::sum) - 1;
            dataContainer.restrictToResultSet(resultSetNumber);
            return new ResultSetGridReceiver(dataContainer, fetched -> {
                run.rowsFetched.addAndGet(fetched.data().rows().size());
                if (run.terminal) {
                    // Formatted here, off the EDT; written with the query's messages when it ends (reportQueryResult)
                    run.terminalTables.computeIfAbsent(query, q -> new ArrayList<>()).add(SqlTerminalView.formatTable(fetched.data()));
                }
                post(run, () -> {
                    // The run's first result comes to the front; later ones leave the tab the user is looking at
                    boolean first = resultsView.getGridCount() == 0;
                    resultsView.addGrid(fetched.withExportQuery(dataContainer), query.getText());
                    runRows = run.rowsFetched.get();
                    updateRowCount();
                    if (first) {
                        showRunOutput(run, false);
                    }
                });
            });
        }

        @NotNull
        @Override
        public DBPPlatformUI.UserResponse handleExecutionError(@NotNull String task, @NotNull Throwable error, boolean queue) {
            // The error is shown in the Messages tab instead of a Stop/Retry/Ignore dialog; the script stops (decision: see class doc)
            if (!run.reportedErrors.contains(error)) {
                // Failed before the query was sent (parameters, pragmas, control commands)
                run.hasErrors = true;
                post(run, () -> {
                    resultsView.appendError(ErrorText.describe(error));
                    if (run.terminal) {
                        terminalView.appendError(ErrorText.describe(error));
                        terminalView.append("");
                    }
                });
            }
            return DBPPlatformUI.UserResponse.STOP;
        }

        @Override
        public boolean confirmIncludedScript(@NotNull SQLIncludedScript script) {
            // Its statements report errors at lines of the file (describeError), and are checked like the run's own ones
            run.includedScripts.put(script.getScriptContext(), script);
            return SqlExecutionSafety.confirmDangerousStatements(SqlEditorPanel.this, context.getDataSource(), script.getText(),
                script.getScriptElements(), String.valueOf(script.getFile().getFileName()));
        }

        @Override
        public boolean isSmartAutoCommit() {
            // A data-changing statement switches an auto-commit session to manual commit (SmartCommit)
            return SmartCommit.isEnabled(run.dataSourceContainer);
        }

        @Override
        public boolean hasBoundParameters(@NotNull SQLQuery query) {
            return run.binding.isBound(query);
        }

        @Override
        public void afterStatementExecuted(@NotNull DBCSession session, @NotNull DBCStatement statement, @NotNull SQLQuery query) {
            // Altibase: the driver gives the statement's plan only while it is open
            SQLActualPlanProvider.Run actualPlan = run.actualPlan;
            if (actualPlan != null && !run.cancelRequested) {
                addActualPlan(run, query.getText(), () -> actualPlan.readOpenStatementPlan(session, statement, query.getText()));
            }
        }

        @Override
        public void bindParameters(@NotNull DBCStatement statement, @NotNull SQLQuery query) throws DBCException {
            run.binding.bind(statement, query);
        }
    }

    /**
     * Query events of the execution engine (called in the job thread).
     */
    private final class QueryListener implements SQLQueryListener {
        private final Execution run;
        private final DBCExecutionContext context;
        // The session's commit mode before the current statement, for smart commit
        private boolean wasAutoCommit;

        QueryListener(@NotNull Execution run, @NotNull DBCExecutionContext context) {
            this.run = run;
            this.context = context;
        }

        @Override
        public void onStartQuery(@NotNull DBCSession session, @NotNull SQLQuery query) {
            run.gridResultSets.remove(query);
            wasAutoCommit = SmartCommit.isAutoCommit(session.getExecutionContext());
            syncServerOutput(session);
            // The statement's place in the window's text; a statement of an included file has none, and the include
            // command stays the running statement
            if (!isIncludedStatement(run)) {
                int start = query.getOffset();
                int end = start + query.getLength();
                int number = getStatementNumber(run, start);
                post(run, () -> {
                    run.statementNumber = number;
                    showRunningStatement(run, start, end);
                    showRunState();
                });
            }
        }

        @Override
        public void onEndQuery(@NotNull DBCSession session, @NotNull SQLQueryResult result, @NotNull DBCStatistics statistics) {
            // Oracle: read first, before anything else runs on the session (server output, smart commit)
            SQLActualPlanProvider.Run actualPlan = run.actualPlan;
            if (actualPlan != null && result.getError() == null && !run.cancelRequested) {
                String text = result.getStatement().getText();
                addActualPlan(run, text, () -> actualPlan.readStatementPlan(session, text));
            }
            if (SmartCommit.isEnabled(run.dataSourceContainer)) {
                SmartCommit.afterStatement(session.getExecutionContext(), wasAutoCommit);
            }
            // A statement that may change the session's database or schema (USE, SET search_path, ALTER SESSION, a batch, a
            // procedure call, COMMIT/ROLLBACK of a SET): read the session's real value now, so that the history entry and
            // the next statements' display have it. Plain queries and DML do not change it and cost no extra round trip.
            run.sessionDefaultsRead = result.getError() == null && !isPlainDataStatement(result.getStatement());
            if (run.sessionDefaultsRead) {
                readSessionDefaults(session.getProgressMonitor(), context);
            }
            reportQueryResult(run, session, result);
            recordHistory(run, context, result);
            if (result.getError() == null) {
                metadataRefresh.collect(session, result.getStatement());
            } else if (!isIncludedStatement(run)) {
                int start = result.getStatement().getOffset();
                int end = start + result.getStatement().getLength();
                post(run, () -> markFailedStatement(run, start, end));
            }
        }

        @Override
        public void onEndSqlJob(@NotNull DBCSession session, @NotNull SqlJobResult result) {
            // After the last statement, unless it was read just now: a failed batch can still have run its USE, and a
            // SELECT can call a function that changes the search path (PostgreSQL set_config)
            if (!run.sessionDefaultsRead) {
                readSessionDefaults(session.getProgressMonitor(), context);
            }
        }
    }

    /**
     * Whether the statement that runs now comes from a file of an {@code @include} command (job thread).
     */
    private static boolean isIncludedStatement(@NotNull Execution run) {
        SQLQueryExecuteJob engine = run.engine;
        return engine != null && run.includedScripts.containsKey(engine.getCurrentScriptContext());
    }

    /**
     * The 1-based number of the run's statement that starts at an offset of the run's text: a statement repeated by
     * {@code GO n} keeps its number.
     */
    private static int getStatementNumber(@NotNull Execution run, int offset) {
        int number = 0;
        for (SQLScriptElement element : run.elements) {
            if (element.getOffset() <= offset) {
                number++;
            }
        }
        return Math.max(number, 1);
    }

    /**
     * Whether a statement is a query or data change, which cannot change the session's default database or schema.
     */
    private static boolean isPlainDataStatement(@NotNull SQLQuery query) {
        return switch (query.getType()) {
            case SELECT, INSERT, UPDATE, DELETE, MERGE -> true;
            default -> false;
        };
    }

    /**
     * Reads the session's current database and schema from the server into its execution context (job thread, in the
     * session's own connection), as SWT did after each run when "Refresh active schema after SQL execution" was on. The
     * pickers, the status line, completion and the Agent read the context's cached values, so they show what the session
     * really uses after USE, SET search_path or ALTER SESSION SET CURRENT_SCHEMA; a USE inside a stored procedure ends with
     * the procedure and the read value stays. Failures are logged by {@link DBUtils#refreshContextDefaultsAndReflect}
     * and keep the values known before.
     */
    private static void readSessionDefaults(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext context) {
        DBCExecutionContextDefaults<?, ?> defaults = context.getContextDefaults();
        if (defaults != null && context.isConnected()) {
            DBUtils.refreshContextDefaultsAndReflect(monitor, defaults, context);
        }
    }

    /**
     * Adds a finished statement to the query history (job thread).
     */
    private static void recordHistory(@NotNull Execution run, @NotNull DBCExecutionContext context, @NotNull SQLQueryResult result) {
        try {
            long rows = -1;
            List<ActualPlanReceiver> planReceivers = run.planReceivers.getOrDefault(result.getStatement(), List.of());
            int resultSetIndex = 0;
            for (SQLQueryResult.ExecuteResult executeResult : result.getExecuteResults()) {
                if (executeResult.isResultSet() && resultSetIndex < planReceivers.size()
                    && planReceivers.get(resultSetIndex++).isPlanResultSet()) {
                    continue;
                }
                Long count = executeResult.isResultSet() ? executeResult.getRowCount() : executeResult.getUpdateCount();
                if (count != null) {
                    rows = Math.max(rows, 0) + count;
                }
            }
            QueryHistory.Status status = result.getError() != null ? QueryHistory.Status.ERROR
                : run.cancelRequested ? QueryHistory.Status.CANCELED : QueryHistory.Status.SUCCESS;
            DBPDataSourceContainer container = run.dataSourceContainer;
            QueryHistory.add(new QueryHistory.Entry(
                System.currentTimeMillis() - result.getQueryTime(),
                container.getId(),
                container.getName(),
                getDefaultObjectName(context),
                // Bound values (Query Library): the text with its ":name" markers and the values; the statement itself has "?" markers
                run.binding.getHistoryText(result.getStatement()),
                result.getQueryTime(),
                rows,
                status,
                result.getError() == null ? null : ErrorText.describe(result.getError()),
                run.binding.getHistoryParameters(result.getStatement())));
        } catch (Throwable e) {
            log.debug("Can't record the query history", e);
        }
    }

    /**
     * Name of the default catalog and schema of the context, e.g. {@code sales.dbo} (cached values, no server call).
     */
    @Nullable
    private static String getDefaultObjectName(@NotNull DBCExecutionContext context) {
        DBCExecutionContextDefaults<?, ?> defaults = context.getContextDefaults();
        if (defaults == null) {
            return null;
        }
        DBSCatalog catalog = defaults.getDefaultCatalog();
        DBSSchema schema = defaults.getDefaultSchema();
        if (catalog != null && schema != null) {
            return catalog.getName() + "." + schema.getName(); //$NON-NLS-1$
        }
        return catalog != null ? catalog.getName() : schema != null ? schema.getName() : null;
    }

    /**
     * Output of script commands ({@code @echo} etc.) goes to the Messages tab, and to the terminal when it is on.
     */
    private final class MessagesWriter extends Writer {
        private final Execution run;

        MessagesWriter(@NotNull Execution run) {
            this.run = run;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            String text = new String(buffer, offset, length);
            if (!text.isBlank()) {
                post(run, () -> {
                    resultsView.appendMessage(text.stripTrailing());
                    if (run.terminal) {
                        terminalView.append(text.stripTrailing());
                    }
                });
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    //////////////////////////////////////////////////////////
    // Connection and default schema (EDT)

    /**
     * Fills the connection combo with the connections of the active project (and the current one, if it is elsewhere).
     */
    private void reloadConnectionItems() {
        List<DBPDataSourceContainer> containers = new ArrayList<>();
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project != null) {
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                if (!container.isHidden() && !container.isTemporary()) {
                    containers.add(container);
                }
            }
        }
        if (dataSourceContainer != null && !containers.contains(dataSourceContainer)) {
            containers.add(dataSourceContainer);
        }
        containers.sort(Comparator.comparing(DBPDataSourceContainer::getName, String.CASE_INSENSITIVE_ORDER));
        updatingCombos = true;
        try {
            DefaultComboBoxModel<DBPDataSourceContainer> model = new DefaultComboBoxModel<>();
            containers.forEach(model::addElement);
            model.setSelectedItem(dataSourceContainer);
            connectionCombo.setModel(model);
        } finally {
            updatingCombos = false;
        }
    }

    /**
     * Reads the default catalog/schema of the window's session and the ones it can change to, in a job. The window's
     * own session opens here when the window gets a connected connection (QueryWindowContext).
     */
    private void refreshSchemas() {
        int serial = ++schemaSerial;
        DBPDataSourceContainer container = dataSourceContainer;
        // Every connection change ends here (choosing a connection, connect, disconnect, the end of a run): the
        // highlighting follows the connection's SQL dialect, and the status line its commit mode
        textArea.setDataSourceContainer(container);
        refreshTransactionMode();
        DBPDataSource dataSource = container != null && container.isConnected() ? container.getDataSource() : null;
        if (dataSource == null) {
            showSchemas(serial, null);
            return;
        }
        new AbstractJob("Read default schema of " + container.getName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                SchemaState state = null;
                DBCExecutionContext context = null;
                try {
                    context = openWindowContext(monitor, dataSource);
                } catch (DBException e) {
                    log.debug("Can't open the session of the query window on '" + container.getName() + "'", e);
                }
                if (context != null && context.getContextDefaults() != null) {
                    // One read at a time: the job that takes a pending session read does it before any later job reads
                    // the cached values (the jobs of two quick calls may start in either order)
                    synchronized (sessionReadLock) {
                        if (sessionReadPending.getAndSet(false)) {
                            readSessionDefaults(monitor, context);
                        }
                    }
                    ContextDefaultObjectsReader reader = new ContextDefaultObjectsReader(dataSource, context);
                    try {
                        reader.run(monitor);
                        state = new SchemaState(dataSource, context, reader);
                    } catch (InvocationTargetException e) {
                        log.debug("Can't read the default schema of '" + container.getName() + "'", e.getTargetException());
                    } catch (InterruptedException e) {
                        // Canceled
                    }
                }
                SchemaState finalState = state;
                SwingUtilities.invokeLater(() -> showSchemas(serial, finalState));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Shows the commit mode (auto-commit or manual) of the execution context this window runs in (its own session, or
     * the connection's main context), as the SWT auto-commit tool bar item reads it
     * ({@code DataSourceAutoCommitHandler#updateElement}). The context caches the mode, but its first read after
     * connecting asks the driver, so it is read in a job; the job also opens the window's own session when the window
     * gets a connected connection. Hidden while not connected. Also called by the main window when this window becomes
     * active: a window that shares the main context shares its mode with the other windows of the connection.
     */
    void refreshTransactionMode() {
        int serial = ++transactionSerial;
        refreshOpenTransaction();
        DBPDataSourceContainer container = dataSourceContainer;
        DBPDataSource dataSource = container != null && container.isConnected() ? container.getDataSource() : null;
        if (dataSource == null) {
            showAutoCommit(null);
            return;
        }
        new AbstractJob("Read commit mode of " + container.getName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Boolean autoCommit = null;
                DBCTransactionManager txnManager = null;
                try {
                    txnManager = DBUtils.getTransactionManager(openWindowContext(monitor, dataSource));
                } catch (DBException e) {
                    log.debug("Can't open the session of the query window on '" + container.getName() + "'", e);
                }
                if (txnManager != null) {
                    try {
                        autoCommit = txnManager.isAutoCommit();
                    } catch (DBCException e) {
                        log.debug("Can't read the auto-commit state of '" + container.getName() + "'", e);
                    }
                }
                Boolean finalAutoCommit = autoCommit;
                SwingUtilities.invokeLater(() -> {
                    if (serial == transactionSerial) {
                        showAutoCommit(finalAutoCommit);
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Shows the commit mode on the status line; the main window updates Query &gt; Auto-Commit and the tool bar button
     * when it changed.
     */
    private void showAutoCommit(@Nullable Boolean autoCommit) {
        statusLine.setAutoCommit(autoCommit);
        if (!Objects.equals(shownAutoCommit, autoCommit)) {
            shownAutoCommit = autoCommit;
            host.executionStateChanged(this);
        }
    }

    /**
     * The commit mode of the window's session as the status line shows it (auto-commit = true), or null while unknown.
     */
    @Nullable
    Boolean getAutoCommit() {
        return shownAutoCommit;
    }

    /**
     * Whether the window runs in the connection's main context, shared with the other windows of the connection,
     * instead of a session of its own.
     */
    boolean isSharedSession() {
        return getExecutionContext() != null && windowContext.getOwn(dataSourceContainer) == null;
    }

    /**
     * Whether Query &gt; Auto-Commit can switch the session's mode now: the mode is known and nothing of this window runs
     * in the session (a run, a plan, a result save, a statement of the open transaction). Never mid-execution.
     */
    boolean canSwitchCommitMode() {
        return shownAutoCommit != null && !switchingCommitMode && execution == null && !explaining && !resultsView.isSaving()
            && (openTransaction == null || !openTransaction.isExecuting());
    }

    /**
     * Query &gt; Auto-Commit (menu, SQL Editor tool bar): switches the window's session to the other commit mode, asking
     * first as the transaction rule says ({@link CommitModeSwitch}). The connection's saved default does not change.
     */
    void switchCommitMode() {
        DBCExecutionContext context = getExecutionContext();
        Boolean autoCommit = shownAutoCommit;
        if (context == null || autoCommit == null || !canSwitchCommitMode()) {
            return;
        }
        switchingCommitMode = true;
        host.executionStateChanged(this);
        try {
            CommitModeSwitch.switchMode(this, context, !autoCommit, isSharedSession(), fileName);
        } finally {
            switchingCommitMode = false;
            // The actual mode, also after a cancel or an error; a shared session's other windows follow refreshOpenTransaction
            refreshTransactionMode();
            host.executionStateChanged(this);
        }
    }

    /**
     * Shows the open transaction of the execution context this window runs in (its own session, or the connection's
     * main context shared by the windows that do not have one): shown while the database holds something for it or it
     * was begun explicitly, also in auto-commit mode, and as unverified when the database could not be asked
     * ({@link QMTransactionState#isOpen()}). Reads only the in-memory state (the database was asked when the execution
     * job ended), so it runs in the EDT. When the
     * transaction opens, ends, changes or starts or stops running a statement, the main window updates Query &gt;
     * Commit / Roll Back. Also the session line of the commit mode's tool tip (sessions open on the connection), and for a
     * while after Chromite ended a read-only transaction by itself (connection type setting), a note saying so.
     */
    private void refreshOpenTransaction() {
        DBCExecutionContext context = getExecutionContext();
        statusLine.setSessions(context == null ? null
            : NLS.bind(windowContext.getOwn(dataSourceContainer) != null ? EditorMessages.editor_status_session_own
                : EditorMessages.editor_status_session_shared, QueryWindowContext.countSessions(context.getDataSource())));
        QMTransactionState transaction = context == null ? null : QMUtils.getTransactionState(context);
        QMTransactionState shown = transaction != null && transaction.isOpen() ? transaction : null;
        QMTransactionState previous = openTransaction;
        openTransaction = shown;
        statusLine.setOpenTransaction(shown, shown == null && context != null ? QMUtils.getReadOnlyEndTime(context) : 0);
        if (shown == null && execution == null) {
            // DDL of the window's runs is visible to Object Explorer's metadata session once it is committed
            metadataRefresh.refreshPending();
            if (previous != null) {
                // Ended outside a run (status line, Query menu, idle transaction notice): a rolled back SET search_path is
                // undone (PostgreSQL). Runs read the session themselves (QueryListener)
                sessionReadPending.set(true);
                refreshSchemas();
            }
        }
        if (previous == null ? shown != null
            : shown == null || previous.getTransactionStartTime() != shown.getTransactionStartTime() || previous.isExecuting() != shown.isExecuting()) {
            host.executionStateChanged(this);
        }
        // The commit mode changed outside this window's execution (smart commit going back to auto-commit after a commit
        // or rollback, the connection's Auto-commit applied to open sessions): read it again. Set first, so that the
        // read, which calls this method, does not start another one.
        Boolean mode = transaction == null ? null : transaction.isTransactionMode();
        Boolean previousMode = shownTransactionMode;
        shownTransactionMode = mode;
        if (mode != null && previousMode != null && !mode.equals(previousMode)) {
            refreshTransactionMode();
        }
    }

    /**
     * The open transaction of the window's execution context as the status line shows it, or null.
     */
    @Nullable
    QMTransactionState getOpenTransaction() {
        return openTransaction;
    }

    /**
     * Query &gt; Commit / Roll Back (menu, SQL Editor tool bar, shortcut): ends the open transaction shown now, through
     * the same path as the status line's menu.
     */
    void endOpenTransaction(boolean commit) {
        QMTransactionState transaction = openTransaction;
        if (transaction != null) {
            endOpenTransaction(transaction, commit);
        }
    }

    /**
     * Commit / Roll back of the status line's open transaction menu, for the transaction shown when the menu opened
     * (also of the Query menu, for the transaction shown now).
     */
    private void endOpenTransaction(@NotNull QMTransactionState transaction, boolean commit) {
        DBCExecutionContext context = getExecutionContext();
        if (context != null) {
            SwingIdleTransactionService.endTransaction(this, context, transaction.getTransactionStartTime(), commit, this::refreshOpenTransaction);
        }
    }

    /**
     * Query &gt; Transaction Log and the status line's open transaction menu: the statements of the window's
     * execution context's transaction. Says so when the window is not connected (the command stays enabled: the
     * Query menu is not updated when a window connects).
     */
    void showTransactionLog() {
        DBCExecutionContext context = getExecutionContext();
        if (context != null) {
            TransactionLogDialog.open(this, context);
        } else {
            JOptionPane.showMessageDialog(this, EditorMessages.editor_txn_log_not_connected,
                NLS.bind(EditorMessages.editor_txn_log_title, fileName), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void showSchemas(int serial, @Nullable SchemaState state) {
        if (serial != schemaSerial) {
            return;
        }
        schemaState = state;
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        Object selected = NOT_AVAILABLE;
        boolean supported = false;
        DBCExecutionContextDefaults<?, ?> contextDefaults = state == null ? null : state.context().getContextDefaults();
        Collection<? extends DBSObject> objects = state == null ? null : state.reader().getObjectList();
        if (contextDefaults != null && objects != null) {
            DBSObject defaultObject = state.reader().getDefaultObject();
            for (DBSObject object : objects) {
                if (object == null || DBUtils.getAdapter(DBSObjectContainer.class, object) == null) {
                    continue;
                }
                model.addElement(object);
                // A default schema selects its catalog when only catalogs are listed (as SelectActiveSchemaHandler)
                if (defaultObject != null && (object == defaultObject || object == defaultObject.getParentObject())) {
                    selected = object;
                }
            }
            supported = model.getSize() > 0 && (contextDefaults.supportsCatalogChange() || contextDefaults.supportsSchemaChange());
        }
        DefaultObjectNames restored;
        synchronized (restoredDefaultsLock) {
            restored = restoredDefaults;
        }
        if (state == null && restored != null) {
            // A restored window that has not connected yet: the default it gets when it does (the picker stays disabled)
            selected = restored.label();
            model.addElement(selected);
        } else if (model.getSize() == 0) {
            model.addElement(NOT_AVAILABLE);
        } else if (selected == NOT_AVAILABLE) {
            model.insertElementAt(NOT_AVAILABLE, 0);
        }
        updatingCombos = true;
        try {
            model.setSelectedItem(selected);
            schemaCombo.setModel(model);
        } finally {
            updatingCombos = false;
        }
        schemaChangeSupported = supported;
        schemaCombo.setEnabled(execution == null && supported);
        statusLine.setDatabase(selected instanceof DBSObject object ? object.getName() : selected != NOT_AVAILABLE ? selected.toString() : null);
    }

    /**
     * Makes a catalog or schema the default of the window's context, with the same arguments as the SWT active schema
     * selector ({@code SelectActiveSchemaHandler}).
     */
    private void changeDefaultObject(@NotNull DBSObject object) {
        SchemaState state = schemaState;
        if (state == null || object == state.reader().getDefaultObject()) {
            return;
        }
        // A database that is an instance of its own (PostgreSQL), or a schema in one, needs another server session: the
        // window's own context reconnects to it (PostgreExecutionContext.setDefaultCatalog), which ends its transaction
        DBSInstance instance = object instanceof DBSInstance self ? self : DBUtils.getParentOfType(DBSInstance.class, object);
        if (instance != null && instance != state.context().getOwnerInstance()
            && state.context() == windowContext.getOwn(dataSourceContainer) && !confirmEndSession()) {
            showSchemas(schemaSerial, state);
            return;
        }
        String currentCatalogName = state.reader().getDefaultCatalogName();
        String newCatalogName = object instanceof DBSCatalog ? object.getName() : currentCatalogName;
        String newSchemaName = object instanceof DBSSchema ? object.getName() : null;
        // Reads that are still running show the old default; drop them
        schemaSerial++;
        schemaCombo.setEnabled(false);
        new AbstractJob("Change default schema") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    DBExecUtils.setExecutionContextDefaults(monitor, state.dataSource(), state.context(),
                        newCatalogName, currentCatalogName, newSchemaName);
                } catch (DBException e) {
                    DBWorkbench.getPlatformUI().showError(EditorMessages.editor_sql_change_schema_title,
                        NLS.bind(EditorMessages.editor_sql_change_schema_error, object.getName()), e);
                }
                SwingUtilities.invokeLater(SqlEditorPanel.this::refreshSchemas);
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Default catalog/schema read for the combo.
     */
    private record SchemaState(
        @NotNull DBPDataSource dataSource,
        @NotNull DBCExecutionContext context,
        @NotNull ContextDefaultObjectsReader reader
    ) {
    }

    /**
     * Connection items: driver icon and connection name.
     */
    private static final class ConnectionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DBPDataSourceContainer container) {
                setText(container.getName());
                setIcon(NodeIcons.getIcon(container.getDriver().getIcon()));
            } else {
                setText(EditorMessages.editor_sql_select_connection);
                setIcon(null);
            }
            return this;
        }
    }

    /**
     * Catalog/schema items with the navigator icons; the "not available" text has none.
     */
    private static final class SchemaRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DBSObject object) {
                setText(object.getName());
                setIcon(NodeIcons.getIcon(object instanceof DBSSchema ? DBIcon.TREE_SCHEMA : DBIcon.TREE_DATABASE));
            } else {
                setIcon(null);
            }
            return this;
        }
    }

    /**
     * SQL text area with the Chromite colors of the current theme and the editor font (also used read-only by
     * {@link DdlViewerPanel} and by the Options preview).
     */
    static final class SqlTextArea extends RSyntaxTextArea {
        static {
            // Before the first text area: its fold manager looks up the parser of the syntax style when the style is set
            SqlFoldParser.install();
        }

        /**
         * First key of the two-key chords (SSMS Ctrl+K, Ctrl+D; Cmd+K, Cmd+D on macOS).
         */
        private static final int CHORD_KEY = KeyEvent.VK_K;

        // EDT only; read by the token maker while painting
        private SqlTokenClassifier.Words words = SqlTokenClassifier.Words.STANDARD;
        // EDT only: connection for the dialect of the editing commands
        @Nullable
        private DBPDataSourceContainer container;
        // Ctrl+K (Cmd+K on macOS) was pressed; the next key completes the chord (Ctrl+K Ctrl+D etc.)
        private boolean chordPending;
        @Nullable
        private SqlTemplates.Session templateSession;

        SqlTextArea() {
            // Keywords, functions and types of the connection's dialect, SSMS operator words (SqlTokenClassifier)
            ((RSyntaxDocument) getDocument()).setTokenMakerFactory(new SqlTokenClassifier.Factory(() -> words));
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
            // Statements, BEGIN/END and CASE blocks, parentheses and block comments (SqlFoldParser, registered above)
            setCodeFoldingEnabled(true);
            // Other occurrences of the name at the caret (the theme's markOccurrencesHighlight color)
            setMarkOccurrences(true);
            setTabSize(4);
            // SSMS find keys (Edit menu, the same platform keys: Replace is Option+Cmd+F on macOS); bound here so they win over
            // the text area's own keys (Ctrl+H deletes backwards)
            bindKey(UiKeys.command(KeyEvent.VK_F), "chromite.find", //$NON-NLS-1$
                () -> FindReplaceDialog.open(this, false));
            bindKey(UiKeys.command(KeyEvent.VK_H), "chromite.replace", //$NON-NLS-1$
                () -> FindReplaceDialog.open(this, true));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "chromite.findNext", //$NON-NLS-1$
                () -> FindReplaceDialog.findAgain(this, true));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "chromite.findPrevious", //$NON-NLS-1$
                () -> FindReplaceDialog.findAgain(this, false));
            // Editing commands (Edit > Advanced shows the same keys)
            bindKey(UiKeys.command(CHORD_KEY), "chromite.chord", //$NON-NLS-1$
                () -> chordPending = true);
            bindKey(UiKeys.command(KeyEvent.VK_F, InputEvent.SHIFT_DOWN_MASK), "chromite.format", //$NON-NLS-1$
                () -> runCommand(Command.FORMAT));
            bindKey(UiKeys.command(KeyEvent.VK_U, InputEvent.SHIFT_DOWN_MASK), "chromite.upperCase", //$NON-NLS-1$
                () -> runCommand(Command.UPPER_CASE));
            bindKey(UiKeys.command(KeyEvent.VK_L, InputEvent.SHIFT_DOWN_MASK), "chromite.lowerCase", //$NON-NLS-1$
                () -> runCommand(Command.LOWER_CASE));
            // The keys of the SWT editor
            bindKey(UiKeys.command(KeyEvent.VK_SLASH, InputEvent.SHIFT_DOWN_MASK), "chromite.blockComment", //$NON-NLS-1$
                () -> runCommand(Command.TOGGLE_BLOCK_COMMENT));
            bindKey(UiKeys.command(KeyEvent.VK_W, InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "chromite.wordWrap", () -> runCommand(Command.WORD_WRAP)); //$NON-NLS-1$
            // Tab: next template variable, a template name before the caret, else the text area's own Tab (indent)
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "chromite.tab", this::tab); //$NON-NLS-1$
            // Ctrl+Enter (Cmd+Enter on macOS, like the text area's own key) is Query > Execute Current Statement (main window);
            // the text area's own word completion would take it
            getInputMap(JComponent.WHEN_FOCUSED).put(UiKeys.command(KeyEvent.VK_ENTER), "none"); //$NON-NLS-1$
            // Key listeners run before the key bindings: the second key of a chord never reaches them
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    handleChordKey(e);
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    chordPending = false;
                }
            });
        }

        /**
         * Commands of the query editor (Edit &gt; Advanced and the editor keys).
         */
        enum Command {
            FORMAT, UPPER_CASE, LOWER_CASE, COMMENT, UNCOMMENT, TOGGLE_BLOCK_COMMENT, INDENT, OUTDENT, INSERT_TEMPLATE,
            TRIM, TRIM_LEADING, TRIM_TRAILING, MORPH_LIST,
            // Commands that do not change the text: also for read-only text areas
            WORD_WRAP(false), SEARCH_WEB(false),
            COLLAPSE_FOLD(false), EXPAND_FOLD(false), COLLAPSE_ALL_FOLDS(false), EXPAND_ALL_FOLDS(false), COLLAPSE_COMMENT_FOLDS(false);

            private final boolean edits;

            Command() {
                this(true);
            }

            Command(boolean edits) {
                this.edits = edits;
            }

            /**
             * Whether the command changes the text (not available in read-only text areas).
             */
            boolean isEdit() {
                return edits;
            }
        }

        /**
         * Runs a command on this text area; read-only text areas (DDL tabs) ignore the commands that change the text.
         */
        void runCommand(@NotNull Command command) {
            if (command.isEdit() && !isEditable()) {
                return;
            }
            switch (command) {
                case FORMAT -> SqlEditCommands.format(this, container);
                case UPPER_CASE -> SqlEditCommands.changeCase(this, true);
                case LOWER_CASE -> SqlEditCommands.changeCase(this, false);
                case COMMENT -> SqlEditCommands.comment(this, container);
                case UNCOMMENT -> SqlEditCommands.uncomment(this, container);
                case TOGGLE_BLOCK_COMMENT -> SqlTextTools.toggleBlockComment(this, container);
                case INDENT -> SqlEditCommands.indent(this);
                case OUTDENT -> SqlEditCommands.outdent(this);
                case INSERT_TEMPLATE -> showTemplateMenu();
                case TRIM -> SqlTextTools.trim(this, SqlTextTools.Trim.BOTH);
                case TRIM_LEADING -> SqlTextTools.trim(this, SqlTextTools.Trim.LEADING);
                case TRIM_TRAILING -> SqlTextTools.trim(this, SqlTextTools.Trim.TRAILING);
                case MORPH_LIST -> SqlTextTools.morphDelimitedList(this);
                case WORD_WRAP -> setLineWrap(!getLineWrap());
                case SEARCH_WEB -> SqlTextTools.searchWeb(this);
                case COLLAPSE_FOLD -> runTextAction(RSyntaxTextAreaEditorKit.rstaCollapseFoldAction);
                case EXPAND_FOLD -> runTextAction(RSyntaxTextAreaEditorKit.rstaExpandFoldAction);
                case COLLAPSE_ALL_FOLDS -> runTextAction(RSyntaxTextAreaEditorKit.rstaCollapseAllFoldsAction);
                case EXPAND_ALL_FOLDS -> runTextAction(RSyntaxTextAreaEditorKit.rstaExpandAllFoldsAction);
                case COLLAPSE_COMMENT_FOLDS -> runTextAction(RSyntaxTextAreaEditorKit.rstaCollapseAllCommentFoldsAction);
            }
        }

        /**
         * Edit &gt; Advanced &gt; Copy as Source Code: the selection (or the statement at the caret) for a programming language.
         */
        void copyAsSourceCode(@NotNull SqlTextTools.SourceFormat format) {
            SqlTextTools.copyAsSourceCode(this, format, container);
        }

        private void runTextAction(@NotNull String actionKey) {
            Action action = getActionMap().get(actionKey);
            if (action != null) {
                action.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, actionKey));
            }
        }

        /**
         * Text of the chord whose second key is the command key and {@code keyCode} ("Ctrl+K, Ctrl+D", "⌘K, ⌘D"), for menus and
         * tool tips.
         */
        @NotNull
        static String getChordText(int keyCode) {
            return UiKeys.getChordText(UiKeys.command(CHORD_KEY), UiKeys.command(keyCode));
        }

        private void handleChordKey(@NotNull KeyEvent e) {
            if (!chordPending) {
                return;
            }
            int code = e.getKeyCode();
            if (code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_ALT || code == KeyEvent.VK_META) {
                return;
            }
            chordPending = false;
            e.consume();
            if (!UiKeys.isCommandDown(e)) {
                return;
            }
            switch (code) {
                case KeyEvent.VK_D -> runCommand(Command.FORMAT);
                case KeyEvent.VK_C -> runCommand(Command.COMMENT);
                case KeyEvent.VK_U -> runCommand(Command.UNCOMMENT);
                case KeyEvent.VK_X -> runCommand(Command.INSERT_TEMPLATE);
                default -> UIManager.getLookAndFeel().provideErrorFeedback(this);
            }
        }

        private void tab() {
            Action defaultTab = getActionMap().get(DefaultEditorKit.insertTabAction);
            if (isEditable() && getSelectionStart() == getSelectionEnd()) {
                SqlTemplates.Session session = templateSession;
                if (session != null && session.contains(getCaretPosition())) {
                    session.selectNext(this);
                    return;
                }
                templateSession = null;
                SqlTemplates.Template template = SqlTemplates.findAtCaret(this);
                if (template != null) {
                    templateSession = SqlTemplates.expand(this, template);
                    return;
                }
            } else if (templateSession != null && getSelectedText() != null && templateSession.contains(getSelectionStart())) {
                // A selected template variable: go on to the next one
                templateSession.selectNext(this);
                return;
            }
            if (defaultTab != null) {
                defaultTab.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, DefaultEditorKit.insertTabAction));
            }
        }

        /**
         * Ctrl+K Ctrl+X: the templates in a menu at the caret; the chosen one is inserted at the caret.
         */
        private void showTemplateMenu() {
            JPopupMenu menu = new JPopupMenu();
            for (SqlTemplates.Template template : SqlTemplates.getTemplates()) {
                if (!template.enabled()) {
                    continue;
                }
                JMenuItem item = new JMenuItem(template.name() + "  -  " + template.description()); //$NON-NLS-1$
                item.addActionListener(e -> {
                    replaceSelection(template.name());
                    templateSession = SqlTemplates.expand(this, template);
                    requestFocusInWindow();
                });
                menu.add(item);
            }
            try {
                Rectangle caret = modelToView2D(getCaretPosition()).getBounds();
                menu.show(this, caret.x, caret.y + caret.height);
            } catch (BadLocationException e) {
                menu.show(this, 0, 0);
            }
        }

        private void bindKey(@NotNull KeyStroke keyStroke, @NotNull String actionKey, @NotNull Runnable task) {
            getInputMap(JComponent.WHEN_FOCUSED).put(keyStroke, actionKey);
            getActionMap().put(actionKey, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    task.run();
                }
            });
        }

        /**
         * Highlights the words of the connection's SQL dialect (standard SQL without a connection) and repaints the text;
         * the editing commands (format, comment) use the same dialect.
         */
        void setDataSourceContainer(@Nullable DBPDataSourceContainer container) {
            this.container = container;
            SqlTokenClassifier.Words newWords = SqlTokenClassifier.Words.of(container);
            if (newWords != words) {
                words = newWords;
                // Creates a new token maker (same factory) and re-lexes the whole document
                ((RSyntaxDocument) getDocument()).setSyntaxStyle(getSyntaxEditingStyle());
            }
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (getParent() != null) {
                // Look and feel switch: the tree update resets UI defaults first, then the theme colors are applied again
                SwingUtilities.invokeLater(this::applyTheme);
            }
        }

        /**
         * Applies the saved editor colors of the current theme and the saved editor font.
         */
        void applyTheme() {
            ChromiteTheme theme = ChromiteTheme.getCurrent();
            Font font = EditorFont.get();
            applyTheme(EditorColors.createTheme(theme, EditorColors.getSaved(theme), font), font);
        }

        /**
         * Applies colors and font that are not saved (Options preview).
         */
        void applyTheme(@NotNull Theme theme, @NotNull Font font) {
            // The theme files name no font: the theme's base font is the editor font (a composite font, so Hangul is not
            // shown as boxes), and its bold and italic styles are derived from it
            theme.apply(this);
            setFont(font);
            Gutter gutter = RSyntaxUtilities.getGutter(this);
            if (gutter != null) {
                gutter.setLineNumberFont(font);
            }
        }

        /**
         * Applies the saved colors and font to every SQL text area of every window (editors, DDL tabs).
         */
        static void applyThemeToAll() {
            for (Window window : Window.getWindows()) {
                applyThemeToAll(window);
            }
        }

        private static void applyThemeToAll(@NotNull Container container) {
            for (Component child : container.getComponents()) {
                if (child instanceof SqlTextArea textArea) {
                    textArea.applyTheme();
                } else if (child instanceof Container childContainer) {
                    applyThemeToAll(childContainer);
                }
            }
        }
    }
}
