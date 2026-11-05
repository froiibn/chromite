# Swing 프런트엔드

## 목적

DBeaver의 SWT/Eclipse RCP UI를 Swing으로 새로 만든다.
화면 배치는 SSMS를 따르고, 디자인은 DataGrip(다크)과 SSMS(라이트) 스타일로 한다.

## 왜 Swing인가

- DataGrip(IntelliJ 플랫폼)이 Swing 기반이다. DataGrip 수준의 디자인을 내려면 Swing이 유리하다.
- SWT는 각 OS의 네이티브 위젯을 쓰므로 트리, 탭, 스크롤바 등의 모양을 바꾸는 데 한계가 있다.
- SWT 안에 Swing을 섞는 방식(SWT_AWT 브리지)은 포커스, 키 입력, HiDPI 문제가 잦아 쓰지 않는다.

## 현재 코드 규모 (2026-11-06 측정)

| 구분 | 규모 |
|---|---|
| 전체 Java 파일 | 6,949개, 플러그인 163개 |
| UI 계층 (SWT·JFace·Eclipse UI·`org.jkiss.dbeaver.ui` 의존) | 약 2,050개 파일, 약 38.6만 줄 → 새로 작성 |
| 모델 계층 (UI 무관) | 약 5,200개 파일 → 재사용 |

UI는 SWT 위젯뿐 아니라 Eclipse RCP 워크벤치(뷰, 편집기, 퍼스펙티브, 명령, 단축키, 설정 화면, `plugin.xml` UI 확장)에 묶여 있어
점진적으로 바꿀 수 없다. Swing UI를 새로 만들고 모델 계층 위에 올린다.

## 구조

```
┌──────────────────────────────────────────┐
│ Chromite Swing UI (새 플러그인)            │
│  FlatLaf 테마 · 도킹 · SQL 편집기 · 그리드  │
│  SwingPlatformUI (DBPPlatformUI 구현)      │
├──────────────────────────────────────────┤
│ 기존 모델 계층 (재사용)                     │
│  model · model.sql · registry · data.transfer · ext.* │
├──────────────────────────────────────────┤
│ OSGi (Equinox) 런타임                      │
└──────────────────────────────────────────┘
```

- **출발점:** [org.jkiss.dbeaver.headless](../../plugins/org.jkiss.dbeaver.headless) 플러그인이 SWT 없이 모델 계층을 띄운다.
  `DBeaverHeadlessApplication`(`DesktopApplicationImpl` 상속), `DBeaverTestPlatform`(`BasePlatformImpl` 상속),
  `DBeaverTestPlatformUI`(`ConsoleUserInterface` 상속)로 구성되며, 의존하는 번들은
  `equinox.app`, `core.resources`, `model`, `model.rcp`, `registry`뿐이다.
- **새 플러그인:** headless 앱과 같은 방식으로 `DesktopApplicationImpl`을 상속하고, 시작할 때 Swing 창을 연다.
- **모델 → UI 호출:** 모델 계층은 오류 표시, 확인 질문, 비밀번호 입력 등을
  [DBPPlatformUI](../../plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/runtime/ui/DBPPlatformUI.java)(메서드 약 28개)로 요청한다.
  이것을 Swing으로 구현하면 모델 계층을 고치지 않고 쓸 수 있다.
- **스레드:** 화면 작업은 Swing EDT에서 하고, DB 작업은 모델 계층의 Job으로 백그라운드에서 실행한다.
- **제품 구성:** SWT UI 플러그인을 뺀 Swing 전용 `.product`를 새로 만든다.
- **Java 버전:** 새 Swing 코드는 Java 25를 기준으로 한다(권장, [runtime.md](runtime.md) 참고).

## 외부 라이브러리 (후보)

| 라이브러리 | 용도 | 라이선스 |
|---|---|---|
| FlatLaf | IntelliJ/Darcula 계열 테마, JBR 사용자 정의 제목 표시줄 지원 | Apache 2.0 |
| RSyntaxTextArea | SQL 편집기 (구문 강조, 자동완성 팝업) | BSD |
| ModernDocking | SSMS식 도킹 창 배치 | MIT |

도입 전에 각 라이브러리의 최신 라이선스를 다시 확인한다.

## 기존 코드 재사용 분석

| 기능 | 핵심 클래스 | 위치 | 판단 |
|---|---|---|---|
| 연결 정보, 드라이버 목록 | `DataSourceDescriptor`, `DriverDescriptor` | `registry` | 그대로 재사용 |
| 자동완성 | `SQLCompletionAnalyzer` | `model.sql` (SWT 의존 없음) | 분석 로직 재사용, 팝업 UI만 새로 작성 |
| SQL 문장 분리 | `SQLScriptParser` | `model.sql` | 그대로 재사용 |
| DDL 보기, Script as | `DBPScriptObject` | `model` | 그대로 재사용 |
| 결과 내보내기 | `DataExporterCSV` 등 | `data.transfer` (SWT 의존 없음) | 엔진 재사용, 설정 화면만 새로 작성 |
| 쿼리 실행 | `SQLQueryJob` (1,231줄, UI import 21개) | `ui.editors.sql` | 실행 로직을 분리해 Swing용으로 재구성 |
| 결과 데이터 모델 | `ResultSetModel` (1,261줄, UI import 1개) | `ui.editors.data` | 거의 그대로 이전 |
| 결과 편집 저장 | `ResultSetPersister` (816줄, UI import 4개) | `ui.editors.data` | 정리 후 이전 |

## MVP 범위

1. **메인 창 (SSMS 배치):** 왼쪽 Object Explorer, 가운데 쿼리 탭, 아래 Results / Messages 탭, 오른쪽 Properties
2. **연결:** 범용 연결 화면 하나로 전체 드라이버를 지원한다. 드라이버 정의의 속성 목록으로 화면을 자동으로 구성한다
   (호스트, 포트, DB, 계정 + 드라이버 속성 표). 저장 형식은 기존 모델 계층을 그대로 써서 SWT 버전과 연결 목록을 공유한다.
3. **Object Explorer:** 서버 → DB → 스키마 → 테이블·뷰·프로시저 트리
4. **쿼리 편집기:** 구문 강조, 자동완성, 실행 (아래 "쿼리 실행 사양")
5. **결과 그리드:** 조회 결과 표시, 복사, 편집·저장 (SSMS의 Edit Top 200 Rows에 해당)
6. **Script as / DDL 보기:** 객체 우클릭 → CREATE·SELECT 스크립트 생성, DDL 보기
7. **결과 내보내기:** CSV, Excel
8. **테마:** DataGrip풍 다크, SSMS풍 라이트

MVP 이후: 테이블 디자이너, ER 다이어그램, 데이터 비교, AI 기능 등.

## 쿼리 실행 사양

| 단축키 | 동작 |
|---|---|
| F5 | 선택 영역이 있으면(1자 이상) 선택한 내용만 실행하고, 없으면 편집기 전체를 실행한다 |

- SWT 버전의 "스크립트 실행" 명령([SQLEditor.java:2965](../../plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/SQLEditor.java:2965))도
  같은 방식이지만 선택 길이가 2자 이상일 때만 선택으로 본다. Swing 버전은 1자부터 선택으로 본다.
- **SQL Server:** SSMS처럼 `GO`를 기준으로 배치를 나눠 배치 단위로 서버에 보낸다.
  같은 배치 안에서 `DECLARE`한 변수를 이어서 쓸 수 있어야 한다.
  (기존 DBeaver 방식은 구분자로 문장을 나눠 하나씩 실행한다.)
- **결과 표시:** 결과 창 하나에 결과 표를 위아래로 쌓고, 메시지(영향받은 행 수, 오류, PRINT 출력)는 Messages 탭에 모은다.
- F5 사양은 Swing 버전에만 적용한다. SWT 버전의 단축키는 바꾸지 않는다.

## 위험과 주의 사항

- **연결 설정 화면:** 각 DB의 전용 연결 화면은 SWT로 된 `ext.*.ui` 플러그인에 있어 재사용할 수 없다. MVP는 범용 화면으로 대신한다.
  드라이버별 특수 설정(예: Oracle SID/서비스명, SQL Server Windows 인증)이 범용 화면에서 표현되는지 확인해야 한다.
- **결과 편집 저장:** 키 판별, 트랜잭션, 오류 처리가 필요해 MVP에서 가장 신경 쓸 부분이다.
- **기능 공백:** SWT 버전에 있던 ER 다이어그램, 데이터 비교, 설정 화면, 드라이버 관리 등은 다시 만들기 전까지 Swing 버전에 없다.
- **디자인 자산:** JetBrains와 Microsoft의 아이콘, 로고 등 고유 디자인 자산은 복사하지 않는다.
  배치와 사용 흐름, 색감만 참고하고, 아이콘은 직접 그리거나 공개 라이선스 아이콘 세트를 쓴다.
- **아이콘 규모:** 기존 플러그인 아이콘은 약 1,060개다. 자주 보이는 것부터 단계적으로 만든다.

## 변경 이력

- 2026-11-06 최초 작성. Swing 전환 결정, 구조, 재사용 분석, MVP 범위, F5 실행 사양을 정리.
