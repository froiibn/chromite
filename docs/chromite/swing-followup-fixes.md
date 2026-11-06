# Swing 후속 수정 — 편집기·결과·도구 (2026-11-07)

2026-11-06 통합 중 모은 후속 과제(followups-2026-11-06의 #33, #40, #43, #44) 가운데 편집기·결과·도구 쪽 여섯 가지를
고친 기록이다. 항목마다 증상, 근본 원인(파일:줄, 고치기 전 기준), 같은 원인의 다른 경우, 고친 방법, SWT(DBeaver) 대비 변경점을 적는다.

## 자동완성 프로시저

- 증상: Swing SQL 편집기 자동완성이 저장 프로시저·함수를 제안하지 않음(#33).
- 원인: `SqlCompletionAnalysis.SwingCompletionContext.isSearchProcedures()`가 늘 `false`였다(`SqlCompletionAnalysis.java:426-428`).
  모델의 새 완성 엔진(`SQLQueryCompletionContext`)은 이 값이 켜져 있을 때만 테이블 문맥과 값 문맥(SELECT 목록, WHERE)에서 `DBSProcedure`·`DBSPackage`를
  찾고(`SQLQueryCompletionContext.java:466`, `:657`, `:1374`), 프로시저 목록도 이 값이 켜져 있을 때만 읽는다(`collectProcedures` `:1533`, `collectPackages` `:1511`).
- SWT: 같은 값을 설정 `SQLEditor.ContentAssistant.show.column.procedures`(`SQLPreferenceConstants.SHOW_COLUMN_PROCEDURES`)에서 읽고
  (`SQLEditorCompletionContext.java:100`), 기본값은 끔(`SQLEditorPreferencesInitializer.java:108`)이며 설정 화면의 확인란
  "Show stored procedures in column list"(`PrefPageSQLCompletion.java:228`)로 켠다. SWT도 기본으로는 제안하지 않지만 켤 수 있고, Swing에는 켤 곳이 없었다.
- 고친 것: Tools > Options > 일반 > Query Editor에 확인란 **Propose stored procedures and functions**(저장 프로시저와 함수도 제안)를 두고
  Swing 번들 설정 `sqlEditor.completion.showProcedures`(기본 끔)에 저장한다(`SqlCompletionPopup.isShowProcedures`·`setShowProcedures`).
  `SwingCompletionContext`가 완성 요청마다 이 값을 읽어 엔진에 넘긴다.
- 결정: 기본값은 SWT와 같이 끔. 켜면 SELECT 목록·WHERE의 제안에 프로시저·함수가 섞여 길어지므로 SWT 기본을 따랐다.
  연결별 값은 두지 않았다(SWT는 연결 설정으로 덮어쓸 수 있으나 Swing 자동완성의 다른 설정도 모두 전역이다).
- 새 엔진에는 `EXEC`/`CALL` 뒤의 프로시저 제안 문맥이 없다. 예전 엔진(`SQLCompletionAnalyzer.java:353-358`)에는 있지만 SWT 기본 설정도
  새 엔진만 쓰므로(`SqlCompletionAnalysis` 클래스 설명) Swing도 그대로 두었다.

## 확인 (2026-11-07)

Swing 제품(`-vm D:\Tools\jbrsdk-25\bin\server\jvm.dll`, 비공개 작업 공간)을 실행하고 같은 프로세스에 붙인 EDT 시험 에이전트로 조작했다(OS 입력 없음).
데이터베이스는 `chr_fixes_editor`(PostgreSQL 17, SQL Server 2022, MariaDB 11.8). 화면은 한국어(OS 기본)와 `-nl en`, 라이트·다크
(`D:\Tools\_scratch\fixes-editor\run\shots\`).
- 자동완성: 팝업은 편집기가 초점을 가져야 뜨는데(`SqlCompletionPopup.analysisDone`) 시험 에이전트는 창 초점을 줄 수 없어, 팝업이 쓰는 분석
  (`SqlCompletionAnalysis.compute`)을 앱 프로세스 안에서 같은 요청으로 불렀다. 설정을 끄면 SQL Server `SELECT ufn_`·PostgreSQL `SELECT fx_` 제안 0개,
  켜면 `ufn_total_amount(:@a)`(PROCEDURE)가 나옴. Options 확인란·저장·다시 열기 확인.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능은 각 항목의 "고친 것"에 적은 곳이다.

## 남긴 것
- SQL Server에서 `EXEC dbo.` 뒤 자동완성이 모델 안에서 `NullPointerException`("identifier" is null)으로 실패한다. 프로시저 설정과 상관없다(고치기 전에도 같음).
- 새 완성 엔진의 `EXEC`/`CALL` 뒤 프로시저 제안(SWT도 기본 설정에서는 없음).

## 변경 이력
- 2026-11-07 최초 작성: 자동완성 프로시저·함수 제안 설정(#33).
