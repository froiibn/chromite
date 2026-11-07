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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.RSyntaxUtilities;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.Gutter;
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
import com.seizia.chromite.model.exec.DBCStatementType;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.exec.DBCTransactionManager;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.exec.output.DBCOutputSeverity;
import com.seizia.chromite.model.exec.output.DBCOutputWriter;
import com.seizia.chromite.model.exec.output.DBCServerOutputReader;
import com.seizia.chromite.model.exec.plan.DBCQueryPlanner;
import com.seizia.chromite.model.impl.struct.ContextDefaultObjectsReader;
import com.seizia.chromite.model.qm.QMTransactionState;
import com.seizia.chromite.model.qm.QMUtils;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLQueryListener;
import com.seizia.chromite.model.sql.SQLQueryResult;
import com.seizia.chromite.model.sql.SQLScriptContext;
import com.seizia.chromite.model.sql.SQLScriptElement;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *     ({@link SQLQueryExecuteHandler#handleExecutionError})에는 묻지 않고 멈춤으로 답한다. 오류가 나면 SSMS처럼 Messages 탭을 보여 준다.
 *     Options &gt; Query Execution에서 "건너뛰고 계속"을 고르면 실패한 문장을 건너뛰고 다음 문장을 실행한다({@link SqlExecutionOptionsPage}).
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
 *     <li>상태 줄({@link QueryStatusLine}): 편집기마다 하나이고 메인 창이 결과 아래에 보인다. 실행 상태, 연결의 서버·사용자, 기본 DB/스키마 콤보의 값, 시간, 행 수를 넣는다.</li>
 *     <li>결과 편집: 결과 표에 저장하지 않은 변경이 있으면 다시 실행하기 전에 버릴지 묻고(실행하면 결과 표를 새로 만든다), 저장 중에는 실행하지 않는다.
 *     닫을 때·끝낼 때 묻기는 메인 창이 {@link #hasPendingResultChanges}, {@link #saveResultChanges}로 한다.</li>
 *     <li>실행 계획({@link ExecutionPlanView}, 메인 창의 Execution plan 탭): Ctrl+L은 선택(없으면 전체)의 문장마다 연결의 계획기로 예상 계획을 구한다
 *     ({@link SqlPlanExplainer}). Ctrl+M(쿼리 창마다 켜고 끔)을 켜고 실행하면 실제 계획을 함께 받는다. 연결이 실행 중 계획 결과 집합을 주면
 *     ({@link SQLActualPlanProvider}, SQL Server) 실행 전에 켜고 끝나면 끄며 그 결과 집합을 계획으로 바꾸고({@link ActualPlanReceiver}),
 *     아니면 실행이 끝난 뒤 데이터를 바꾸지 않는 SELECT만 계획기의 ANALYZE 설정으로 다시 계획한다.</li>
 *     <li>쿼리 기록: 엔진의 문장 끝 이벤트마다 {@link QueryHistory}에 문장, 연결, 기본 DB/스키마, 시간, 행 수, 결과를 남긴다.</li>
 *     <li>매개변수: {@code :name}, {@code ${var}}에 값이 없으면 엔진이 {@link QueryParameterBinder.ScriptProvider}로 묻고 값을 SQL 글자로 넣는다.
 *     Query Library 쿼리(확장)와 기록에서 다시 연 바인딩 실행({@link #setRecordedParameters})은 실행 전에 묻고 값을 바인딩한다
 *     ({@link SqlParameterBinding}). 대화상자는 모두 {@link QueryParameterDialog}다.</li>
 *     <li>편집 명령: SQL 서식(Ctrl+K Ctrl+D, Ctrl+Shift+F), 대문자·소문자(Ctrl+Shift+U/L), 주석 넣기·빼기(Ctrl+K Ctrl+C / Ctrl+K Ctrl+U),
 *     들여쓰기({@link SqlEditCommands}), 템플릿(이름 + Tab, Ctrl+K Ctrl+X 목록, {@link SqlTemplates})은 편집 영역({@link SqlTextArea})이 직접 키를 받는다.
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
 * - 2026-11-08 촘촘한 모드: 도구 막대 선택 상자의 여백을 치수 키로 정하고, 높이를 UI가 바뀔 때 다시 정함
 *   (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 * - 2026-11-08 매개변수 대화상자를 하나로({@link QueryParameterDialog}, 엔진 제공자는 {@link QueryParameterBinder.ScriptProvider}),
 *   값을 바인딩한 결과도 바인딩의 데이터 컨테이너로 다시 읽음(필터·서버 정렬·다음 페이지·Export Data), 기록에 {@code :name} 글자와 값을 남기고
 *   기록에서 연 창은 그 값으로 다시 바인딩({@link #setRecordedParameters}) (docs/chromite/swing-query-library.md "매개변수 대화상자와 바인딩 하나로")
 * - 2026-11-08 문장마다 실행 전에 서버 출력 켜짐을 설정에 맞춤({@code syncServerOutput}). Oracle DBMS_OUTPUT을 끄거나 켜도 열린 세션은
 *   다시 연결할 때까지 그대로였던 것 (docs/chromite/model-fixes.md "작은 모델·안전·라이선스 수정")
 */
final class SqlEditorPanel extends JPanel {

    private static final Log log = Log.getLog(SqlEditorPanel.class);

    private static final DateTimeFormatter COMPLETION_TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * The main window side of an editor.
     */
    interface Host {
        /**
         * The results of the editor changed; show them (if it is the active editor) on the Results or Messages tab.
         */
        void showResults(@NotNull SqlEditorPanel editor, boolean messages);

        /**
         * Execution or a plan started or finished, or a stop was requested: what can run now changed (Query menu and
         * SQL Editor tool bar state, {@link #canExecute()}).
         */
        void executionStateChanged(@NotNull SqlEditorPanel editor);

        /**
         * The tab title or tool tip changed (file name, connection, modified marker).
         */
        void titleChanged(@NotNull SqlEditorPanel editor);

        /**
         * Brings the Execution plan tab of the editor to the front.
         */
        void showPlan(@NotNull SqlEditorPanel editor);
    }

    private static final String NOT_AVAILABLE = "< N/A >"; //$NON-NLS-1$
    /**
     * Widths of the connection and database/schema pickers, also of the empty pickers shown without a query window so
     * that the tool bar does not move.
     */
    static final int CONNECTION_PICKER_WIDTH = 260;
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
    // EDT only: a newer read of the auto-commit state replaces older ones still running
    private int transactionSerial;
    @Nullable
    private SchemaState schemaState;
    private final SqlTextArea textArea;
    private final SqlCompletionPopup completion;
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
            // A grid was read again after the run (filter, sort, more rows): the rows it shows now, as the grid's own status
            if (execution == null) {
                long rows = resultsView.getRowCount();
                statusLine.setRows(NLS.bind(rows == 1 ? EditorMessages.editor_sql_rows_one : EditorMessages.editor_sql_rows_many, rows));
            }
        }
    });
    // EDT only: Query > Include Actual Execution Plan for this window
    private boolean includeActualPlan;
    private final ExecutionPlanView planView = new ExecutionPlanView();
    // EDT only
    private boolean explaining;
    private boolean planTabSelected;
    private final QueryStatusLine statusLine = new QueryStatusLine();
    private final Timer elapsedTimer;
    private final Timer transactionTimer;
    /**
     * The open transaction the status line shows (EDT only), null when there is none.
     */
    @Nullable
    private QMTransactionState openTransaction;
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
    // The window's session: its own context or the connection's main context
    private final QueryWindowContext windowContext = new QueryWindowContext(this::getFileName);

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

        textArea = new SqlTextArea();
        // The connection and session at the time of the request (the connection picker may change them)
        completion = new SqlCompletionPopup(textArea, this::getDataSourceContainer, this::getExecutionContext);
        RTextScrollPane scrollPane = new RTextScrollPane(textArea, true);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        // The gutter takes its colors from the theme too, so the theme is applied once the scroll pane exists
        textArea.applyTheme();

        createPickers();
        add(scrollPane, BorderLayout.CENTER);

        elapsedTimer = new Timer(250, e -> {
            if (execution != null) {
                statusLine.setElapsed(formatElapsed(System.currentTimeMillis() - execution.startTime, false));
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
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                setDirty(true);
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
        dirty = false;
        host.titleChanged(this);
    }

    /**
     * The text was written to a file (Save, Save As).
     */
    void saved(@NotNull Path file, @NotNull SqlFileContent content) {
        this.file = file;
        this.fileName = file.getFileName().toString();
        this.fileFormat = content;
        dirty = false;
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
     * The object whose definition the window edits (Modify), for Query &gt; Debug…; null for other windows.
     */
    @Nullable
    DBSObject getSourceObject() {
        return sourceObject;
    }

    void setSourceObject(@Nullable DBSObject sourceObject) {
        this.sourceObject = sourceObject;
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
        statusLine.setConnection(dataSourceContainer);
        refreshSchemas();
    }

    @NotNull
    QueryResultsView getResultsView() {
        return resultsView;
    }

    /**
     * Content of the Execution plan tab.
     */
    @NotNull
    ExecutionPlanView getPlanView() {
        return planView;
    }

    /**
     * Whether the Execution plan tab was the last one the user looked at for this window (restored on activation).
     */
    boolean isPlanTabSelected() {
        return planTabSelected;
    }

    void setPlanTabSelected(boolean planTabSelected) {
        this.planTabSelected = planTabSelected;
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

    boolean isExplaining() {
        return explaining;
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
     * The query status line; the main window shows the active editor's line below the results (SSMS).
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
     * The tab is closing: stop a running query and close the window's own session (the main window asked about its
     * transaction first, {@link #confirmEndSession}).
     */
    void dispose() {
        cancel();
        completion.cancel();
        elapsedTimer.stop();
        transactionTimer.stop();
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
        connectionCombo.setToolTipText(EditorMessages.editor_sql_connection_tip);
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

        schemaCombo.setToolTipText(EditorMessages.editor_sql_schema_tip);
        schemaCombo.setRenderer(new SchemaRenderer());
        sizePicker(schemaCombo, SCHEMA_PICKER_WIDTH);
        schemaCombo.addActionListener(e -> {
            if (!updatingCombos && schemaCombo.getSelectedItem() instanceof DBSObject selected) {
                changeDefaultObject(selected);
            }
        });
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
        if (selectionEnd - selectionStart >= 1) {
            offset = selectionStart;
            length = selectionEnd - selectionStart;
        }
        if (text.substring(offset, offset + length).isBlank()) {
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
        int pendingChanges = resultsView.getChangeCount();
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
            binding = extension.prepareExecution(this, container, text.substring(offset, offset + length));
            if (binding == null) {
                return;
            }
        } else if (recordedParameters != null) {
            boolean ask = !runRecordedWithoutAsking;
            runRecordedWithoutAsking = false;
            binding = recordedParameters.prepareExecution(this, getFileName(), container, text.substring(offset, offset + length), ask);
            if (binding == null) {
                return;
            }
        }

        long connectionMaxRows = container.getPreferenceStore().getInt(ModelPreferences.RESULT_SET_MAX_ROWS);
        Execution run = new Execution(container, text, offset, length,
            maxRows > 0 ? Math.max(maxRows, connectionMaxRows) : connectionMaxRows, maxRows > 0);
        run.includeActualPlan = isIncludeActualPlan();
        run.binding = binding;
        execution = run;
        resultsView.clear();
        if (run.includeActualPlan) {
            planView.clear(EditorMessages.editor_sql_waiting_actual_plan);
        }
        statusLine.setState(QueryStatusLine.State.EXECUTING, container.isConnected() ? EditorMessages.editor_sql_executing : EditorMessages.editor_sql_connecting);
        statusLine.setElapsed(formatElapsed(0, false));
        statusLine.setRows(null);
        elapsedTimer.start();
        updateActions();
        host.showResults(this, false);

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
        statusLine.setState(QueryStatusLine.State.EXECUTING, EditorMessages.editor_sql_canceling);
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
        long elapsed = System.currentTimeMillis() - run.startTime;

        if (status.getSeverity() == IStatus.ERROR && !run.hasErrors) {
            // Errors outside of a query (e.g. opening the session); query errors were already written by the listener
            run.hasErrors = true;
            resultsView.appendError(ErrorText.describe(status));
        }
        boolean canceled = run.cancelRequested || status.getSeverity() == IStatus.CANCEL;
        if (canceled) {
            resultsView.appendMessage(EditorMessages.editor_sql_canceled_by_user);
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
        long rows = run.rowsFetched.get();
        statusLine.setRows(NLS.bind(rows == 1 ? EditorMessages.editor_sql_rows_one : EditorMessages.editor_sql_rows_many, rows));
        refreshOpenTransaction();
        updateActions();
        // As in SSMS: errors (or no result grids at all) bring the Messages tab to the front
        host.showResults(this, run.hasErrors || resultsView.getGridCount() == 0);
        finishActualPlan(run, canceled);
        // The run may have connected, or changed the default database (USE)
        refreshSchemas();
        if (executionFinishedListener != null) {
            executionFinishedListener.run();
        }
    }

    /**
     * Include Actual Execution Plan: the plans already arrived as result sets, or the read-only SELECT statements of the
     * run are planned now with run-time values (the planner executes them again and rolls back).
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
        if (run.actualPlanProvider != null) {
            if (!planView.hasContent()) {
                planView.clear(canceled ? EditorMessages.editor_sql_plan_canceled : EditorMessages.editor_sql_plan_none_returned);
            }
            return;
        }
        if (run.planner == null || run.context == null) {
            planView.clear(EditorMessages.editor_sql_no_plans);
            return;
        }
        if (canceled || run.actualPlanQueries.isEmpty()) {
            planView.clear(canceled ? EditorMessages.editor_sql_plan_canceled
                : EditorMessages.editor_sql_actual_plan_select_only);
            return;
        }
        explaining = true;
        updateActions();
        planView.clear(EditorMessages.editor_sql_making_actual_plan);
        SqlPlanExplainer.explain(run.context, run.planner, List.copyOf(run.actualPlanQueries), true, planView::addEntry, () -> {
            explaining = false;
            updateActions();
        });
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
        planTabSelected = true;
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
                    DBCExecutionContext context = dataSource == null || !container.isConnected() ? null : windowContext.open(monitor, dataSource);
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
                            SqlPlanExplainer.explain(context, planner, queries, false, planView::addEntry, () -> {
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
            DBCExecutionContext context = windowContext.open(monitor, dataSource);
            List<SQLScriptElement> elements = run.binding.prepare(monitor, context,
                SQLScriptBatches.extractQueries(dataSource, run.text, run.offset, run.length));
            if (elements.isEmpty()) {
                stopPreparation(run, Status.OK_STATUS);
                return;
            }
            // Asked here, not in execute(): the statements are known only after splitting with the connection's dialect
            if (!SqlExecutionSafety.confirmDangerousStatements(this, dataSource, run.text, elements)) {
                stopPreparation(run, Status.CANCEL_STATUS);
                return;
            }
            if (run.includeActualPlan) {
                prepareActualPlan(run, monitor, dataSource, context);
            }
            // Parameters without a value are asked for in a dialog (SWT: SQLEditorParametersProvider)
            SQLScriptContext scriptContext = new SQLScriptContext(null, () -> context, null, new MessagesWriter(run),
                new QueryParameterBinder.ScriptProvider(this));
            SQLQueryExecuteJob job = new SQLQueryExecuteJob(
                "Execute query", context, null, elements, scriptContext,
                new QueryListener(run, context), new QueryHandler(run, context, scriptContext));
            if (run.customMaxRows) {
                job.setResultSetLimit(0, run.maxRows);
            }
            // Stop or skip on errors; never the engine default STOP_ROLLBACK (see class doc)
            job.setErrorHandling(SqlExecutionOptionsPage.getEngineErrorHandling(container.getPreferenceStore()));
            job.addJobChangeListener(new JobChangeAdapter() {
                @Override
                public void done(IJobChangeEvent event) {
                    // Also after errors and cancels: the connection must not keep returning plans
                    disableActualPlan(run, context);
                    // Canceling the parameter dialog (or the execution confirmation) stops the script without an error
                    // and with an OK job status
                    IStatus result = job.isCanceledByUser() ? Status.CANCEL_STATUS : event.getResult();
                    SwingUtilities.invokeLater(() -> finishExecution(run, result != null ? result : Status.OK_STATUS));
                }
            });
            synchronized (jobLock) {
                if (run.cancelRequested) {
                    disableActualPlan(run, context);
                    stopPreparation(run, Status.CANCEL_STATUS);
                    return;
                }
                run.job = job;
                job.schedule();
            }
            post(run, () -> statusLine.setState(QueryStatusLine.State.EXECUTING, EditorMessages.editor_sql_executing));
        } catch (Throwable e) {
            log.debug("Query preparation failed", e);
            stopPreparation(run, errorStatus(ErrorText.describe(e)));
        }
    }

    /**
     * Include Actual Execution Plan, before the run (job thread): turns on the plan result sets of the connection, or
     * keeps its planner for planning the SELECT statements afterwards.
     */
    private void prepareActualPlan(
        @NotNull Execution run,
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull DBCExecutionContext context
    ) {
        run.context = context;
        SQLActualPlanProvider provider = DBUtils.getAdapter(SQLActualPlanProvider.class, dataSource);
        if (provider != null) {
            try {
                executeStatements(monitor, context, provider.getEnableStatements());
                run.actualPlanProvider = provider;
            } catch (Throwable e) {
                log.debug("Can't turn the actual plan on", e);
                post(run, () -> resultsView.appendError(NLS.bind(EditorMessages.editor_sql_cant_include_actual_plan, ErrorText.describe(e))));
            }
        } else {
            run.planner = SqlPlanExplainer.getPlanner(dataSource);
        }
        run.planChecked = true;
    }

    private void disableActualPlan(@NotNull Execution run, @NotNull DBCExecutionContext context) {
        SQLActualPlanProvider provider = run.actualPlanProvider;
        if (provider == null) {
            return;
        }
        try {
            executeStatements(new VoidProgressMonitor(), context, provider.getDisableStatements());
        } catch (Throwable e) {
            log.warn("Can't turn the actual plan off", e);
        }
    }

    /**
     * Runs statements one by one on the execution context's connection (session settings such as SET STATISTICS XML).
     */
    private static void executeStatements(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull List<String> statements
    ) throws DBException {
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Execution plan settings")) {
            for (String sql : statements) {
                try (DBCStatement statement = session.prepareStatement(DBCStatementType.SCRIPT, sql, false, false, false)) {
                    statement.executeStatement();
                }
            }
        }
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
     * Messages of one finished query: warnings and PRINT output, server output (DBMS_OUTPUT), rows affected, time, or
     * the error with its position in the editor.
     */
    private void reportQueryResult(@NotNull Execution run, @NotNull DBCSession session, @NotNull SQLQueryResult result) {
        List<String> messages = new ArrayList<>();
        for (Throwable warning : CommonUtils.safeCollection(result.getWarnings())) {
            // SQL Server PRINT and RAISERROR (low severity), PostgreSQL NOTICE etc. arrive as SQLWarning
            messages.add(CommonUtils.notEmpty(warning.getMessage()));
        }
        readServerOutput(session, messages);
        Throwable error = result.getError();
        String errorText = null;
        if (error == null) {
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
            if (!anyCount && !run.cancelRequested) {
                // A canceled statement ends without an error and without counts; it did not complete
                messages.add(EditorMessages.editor_sql_commands_completed);
            }
            messages.add(NLS.bind(EditorMessages.editor_sql_execution_time, result.getQueryTime()));
        } else {
            run.hasErrors = true;
            errorText = describeError(run, session, result.getStatement(), error);
            run.reportedErrors.add(error);
        }
        String finalErrorText = errorText;
        post(run, () -> {
            messages.forEach(resultsView::appendMessage);
            if (finalErrorText != null) {
                resultsView.appendError(finalErrorText);
            }
        });
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
     * Turns the server output on or off on this session when the setting changed after the session opened (Oracle
     * {@code DBMS_OUTPUT}), before the statement runs: a statement run after turning it on is read, and a session
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
     * SSMS-like error header ({@code Msg 208, State 42S02, Line 3, Column 15}) followed by the error messages.
     * The position comes from the dialect's error assistant when it can locate the error, else it is the query start.
     */
    @NotNull
    private static String describeError(@NotNull Execution run, @NotNull DBCSession session, @NotNull SQLQuery query, @NotNull Throwable error) {
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
                    line = lineOf(run.text, query.getOffset()) + position.line;
                    column = position.position >= 0 ? position.position + 1 : -1;
                }
            }
        } catch (Throwable e) {
            log.debug("Can't get error position", e);
        }
        if (errorOffset >= 0 && errorOffset <= run.text.length()) {
            line = lineOf(run.text, errorOffset);
            column = errorOffset - run.text.lastIndexOf('\n', errorOffset - 1);
        } else if (line < 0) {
            line = lineOf(run.text, query.getOffset());
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
        header.append(NLS.bind(EditorMessages.editor_sql_error_line, line));
        if (column > 0) {
            header.append(NLS.bind(EditorMessages.editor_sql_error_column, column));
        }
        // The header already shows the error code and state
        return header + "\n" + ErrorText.describeMessages(error);
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
        volatile boolean cancelRequested;
        @Nullable
        Job job;
        // Include Actual Execution Plan: set in the EDT before the run, the rest in the preparation job
        boolean includeActualPlan;
        volatile boolean planChecked;
        @Nullable
        volatile SQLActualPlanProvider actualPlanProvider;
        @Nullable
        volatile DBCQueryPlanner planner;
        @Nullable
        volatile DBCExecutionContext context;
        // Succeeded read-only SELECT statements, planned after the run when there is no actualPlanProvider
        final List<SQLQuery> actualPlanQueries = new CopyOnWriteArrayList<>();
        // Receivers of each query's result sets in fetch order (job thread only): plan result sets are not counted as rows
        final Map<SQLQuery, List<ActualPlanReceiver>> planReceivers = new IdentityHashMap<>();
        // Set in the EDT before the jobs start
        SqlParameterBinding binding = SqlParameterBinding.NONE;

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
        private final Set<SQLQuery> gridQueries = Collections.newSetFromMap(new IdentityHashMap<>());

        QueryHandler(@NotNull Execution run, @NotNull DBCExecutionContext context, @NotNull SQLScriptContext scriptContext) {
            this.run = run;
            this.context = context;
            this.scriptContext = scriptContext;
        }

        @Nullable
        @Override
        public DBDDataReceiver getDataReceiver(@NotNull SQLQuery query, int resultSetNumber) {
            SQLActualPlanProvider planProvider = run.actualPlanProvider;
            if (planProvider != null) {
                // Plan result sets go to the Execution plan tab, the others to the grids
                ActualPlanReceiver receiver = new ActualPlanReceiver(planProvider, query.getText(), () -> createGridReceiver(query),
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
            return new ResultSetGridReceiver(dataContainer, fetched -> {
                run.rowsFetched.addAndGet(fetched.data().rows().size());
                // Only a query's first result set can be read again on its own (Export Data); receivers are called in order
                boolean exportable = gridQueries.add(query);
                post(run, () -> {
                    resultsView.addGrid(exportable ? fetched.withExportQuery(dataContainer) : fetched);
                    statusLine.setRows(NLS.bind(EditorMessages.editor_sql_rows_many, run.rowsFetched.get()));
                    host.showResults(SqlEditorPanel.this, false);
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
                post(run, () -> resultsView.appendError(ErrorText.describe(error)));
            }
            return DBPPlatformUI.UserResponse.STOP;
        }

        @Override
        public boolean hasBoundParameters(@NotNull SQLQuery query) {
            return run.binding.isBound(query);
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

        QueryListener(@NotNull Execution run, @NotNull DBCExecutionContext context) {
            this.run = run;
            this.context = context;
        }

        @Override
        public void onStartQuery(@NotNull DBCSession session, @NotNull SQLQuery query) {
            syncServerOutput(session);
        }

        @Override
        public void onEndQuery(@NotNull DBCSession session, @NotNull SQLQueryResult result, @NotNull DBCStatistics statistics) {
            reportQueryResult(run, session, result);
            recordHistory(run, context, result);
            if (run.includeActualPlan && run.planner != null && result.getError() == null && !run.cancelRequested
                && SqlPlanExplainer.isReadOnlySelect(result.getStatement())) {
                run.actualPlanQueries.add(result.getStatement());
            }
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
     * Output of script commands ({@code @echo} etc.) goes to the Messages tab.
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
                post(run, () -> resultsView.appendMessage(text.stripTrailing()));
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
                    context = windowContext.open(monitor, dataSource);
                } catch (DBException e) {
                    log.debug("Can't open the session of the query window on '" + container.getName() + "'", e);
                }
                if (context != null && context.getContextDefaults() != null) {
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
            statusLine.setAutoCommit(null);
            return;
        }
        new AbstractJob("Read commit mode of " + container.getName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Boolean autoCommit = null;
                DBCTransactionManager txnManager = null;
                try {
                    txnManager = DBUtils.getTransactionManager(windowContext.open(monitor, dataSource));
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
                        statusLine.setAutoCommit(finalAutoCommit);
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Shows the open transaction of the execution context this window runs in (its own session, or the connection's
     * main context shared by the windows that do not have one): hidden in auto-commit mode and when nothing ran since
     * the last commit or rollback. Reads only the query manager's in-memory state, so it runs in the EDT. When the
     * transaction opens, ends, changes or starts or stops running a statement, the main window updates Query &gt;
     * Commit / Roll Back. Also the session line of the commit mode's tool tip (sessions open on the connection).
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
        statusLine.setOpenTransaction(shown);
        if (previous == null ? shown != null
            : shown == null || previous.getTransactionStartTime() != shown.getTransactionStartTime() || previous.isExecuting() != shown.isExecuting()) {
            host.executionStateChanged(this);
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
        if (model.getSize() == 0) {
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
        statusLine.setDatabase(selected instanceof DBSObject object ? object.getName() : null);
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
        // EDT only; read by the token maker while painting
        private SqlTokenClassifier.Words words = SqlTokenClassifier.Words.STANDARD;
        // EDT only: connection for the dialect of the editing commands
        @Nullable
        private DBPDataSourceContainer container;
        // Ctrl+K was pressed; the next key completes the chord (Ctrl+K Ctrl+D etc.)
        private boolean chordPending;
        @Nullable
        private SqlTemplates.Session templateSession;

        SqlTextArea() {
            // Keywords, functions and types of the connection's dialect, SSMS operator words (SqlTokenClassifier)
            ((RSyntaxDocument) getDocument()).setTokenMakerFactory(new SqlTokenClassifier.Factory(() -> words));
            setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
            setCodeFoldingEnabled(false);
            setTabSize(4);
            // SSMS find keys (Edit menu); bound here so they win over the text area's own keys (Ctrl+H deletes backwards)
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "chromite.find", //$NON-NLS-1$
                () -> FindReplaceDialog.open(this, false));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "chromite.replace", //$NON-NLS-1$
                () -> FindReplaceDialog.open(this, true));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "chromite.findNext", //$NON-NLS-1$
                () -> FindReplaceDialog.findAgain(this, true));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "chromite.findPrevious", //$NON-NLS-1$
                () -> FindReplaceDialog.findAgain(this, false));
            // Editing commands (Edit > Advanced shows the same keys)
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK), "chromite.chord", //$NON-NLS-1$
                () -> chordPending = true);
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "chromite.format", //$NON-NLS-1$
                () -> runCommand(Command.FORMAT));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "chromite.upperCase", //$NON-NLS-1$
                () -> runCommand(Command.UPPER_CASE));
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "chromite.lowerCase", //$NON-NLS-1$
                () -> runCommand(Command.LOWER_CASE));
            // Tab: next template variable, a template name before the caret, else the text area's own Tab (indent)
            bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "chromite.tab", this::tab); //$NON-NLS-1$
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
         * Editing commands of the query editor (Edit &gt; Advanced and the editor keys).
         */
        enum Command {
            FORMAT, UPPER_CASE, LOWER_CASE, COMMENT, UNCOMMENT, INDENT, OUTDENT, INSERT_TEMPLATE
        }

        /**
         * Runs an editing command on this text area; read-only text areas (DDL tabs) ignore them.
         */
        void runCommand(@NotNull Command command) {
            if (!isEditable()) {
                return;
            }
            switch (command) {
                case FORMAT -> SqlEditCommands.format(this, container);
                case UPPER_CASE -> SqlEditCommands.changeCase(this, true);
                case LOWER_CASE -> SqlEditCommands.changeCase(this, false);
                case COMMENT -> SqlEditCommands.comment(this, container);
                case UNCOMMENT -> SqlEditCommands.uncomment(this, container);
                case INDENT -> SqlEditCommands.indent(this);
                case OUTDENT -> SqlEditCommands.outdent(this);
                case INSERT_TEMPLATE -> showTemplateMenu();
            }
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
            if (!e.isControlDown()) {
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
