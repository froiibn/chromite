# Swing 서버 대시보드

## 목적

DBeaver(SWT)의 데이터베이스 대시보드(`com.seizia.chromite.ui.dashboard`)를 Swing 프런트엔드로 옮긴다. 배치는 SSMS Activity Monitor의
개요(Overview) 창을 따른다: 연결 하나의 서버 활동(세션, 초당 트랜잭션·요청, CPU, I/O, 잠금·대기, 캐시 적중률)을 실시간 선 차트로 본다.
[swing-admin-tools.md](swing-admin-tools.md)에서 "갱신 로직이 SWT 플러그인에 있고 차트 라이브러리가 필요하다"는 이유로 미뤘던 항목이다.

## 여는 곳

| 명령 | 위치 | 동작 |
|---|---|---|
| Server Dashboard | Tools 메뉴(Locks 아래) | 선택한 노드의 연결(없으면 활성 쿼리 창의 연결)의 Activity Monitor를 개요 탭으로 연다. |
| Server Dashboard | Object Explorer 오른쪽 클릭 메뉴(Locks 아래) | 선택한 노드의 연결의 Activity Monitor를 개요 탭으로 연다. |
| 개요(Overview) 탭 | Activity Monitor 문서의 첫 탭 | Activity Monitor를 Processes·Locks로 열었어도 개요 탭을 누르면 읽기 시작한다. |

Activity Monitor가 이미 열려 있으면 그 문서의 개요 탭을 보인다(연결마다 문서 하나). 화면 문구에는 다른 제품 이름을 쓰지 않는다.

## 구조

```
com.seizia.chromite.ui.swing (Swing, SWT 없음)
  MainFrame ── Tools > Server Dashboard
  ObjectExplorerPanel ── 오른쪽 클릭 메뉴 Server Dashboard
  ActivityMonitorPanel ── 탭: 개요(DashboardPanel), Processes, Locks; 서버 연결 AdminConnection을 셋이 함께 씀
    DashboardPanel ── 도구 막대(일시 중지·다시 시작, 새로 고침 간격, 차트 선택, 한 줄의 차트 수), 카드 격자, 상태 줄
      DashboardChart ── Java2D 시계열 차트(범례, 값 축, 시간 축, 선, 마우스 위치의 값)
  internal/DashboardMessages(+_ko) ── 화면 문구
        │
        ▼
com.seizia.chromite.model.admin (모델 번들, UI 없음)
  dashboard/ServerDashboards ── 연결의 드라이버에 맞는 차트 정의 목록(DashboardRegistry)
  dashboard/DashboardSampler ── 질의 실행, 맵 질의, 수식, 열·행 결과, 차분(초당 값)
  dashboard/DashboardSample ─── 한 번 읽은 결과(점들 또는 오류)
  dashboard/DashboardHistory ── 계열별 최근 값(최대 개수·최대 나이)
  dashboard/DashboardLabels(+.properties, _ko) ── DBeaver 차트 제목·계열 이름의 번역표
  plugin.xml ── Chromite 차트 정의(SQL Server 7개, PostgreSQL 3개, MySQL·MariaDB 3개)
        │
        ▼
DBeaver 모델 (그대로 재사용)
  com.seizia.chromite.model.dashboard: DashboardRegistry, DashboardItemConfiguration(질의, calc, value, fetch, mapQuery, mapFormula)
  ext.postgresql·ext.mysql plugin.xml의 차트 정의
```

### 갱신 로직을 모델 번들로 옮긴 이유

SWT에서 값을 읽고 모양을 바꾸는 코드는 UI 플러그인에 있다.

| SWT 클래스 (com.seizia.chromite.ui.dashboard) | 하는 일 | Swing에서 |
|---|---|---|
| `view/DashboardUpdateJob` | 1초마다 도는 작업, 보이는 대시보드 보기가 없으면 멈춤 | `DashboardPanel`의 Swing 타이머(탭이 보일 때만, 고른 간격) |
| `view/DashboardUpdater#updateDashboards`, `getDashboardsToUpdate` | 열린 Eclipse 보기에서 갱신할 차트를 모으고 차트마다 `updatePeriod` 확인 | `DashboardPanel#sample`(보이는 차트 전부, 실패한 차트는 30초 건너뜀) |
| `view/DashboardUpdater#readMapQueryData`, `fetchDashboardMapData` | 맵 질의 실행(연결마다 한 번), `mapKeys`·`mapFormula`(JEXL) | `DashboardSampler#readMapQuery`, `readMapItem` |
| `view/DashboardUpdater#updateDashboard`, `fetchDashboardData`, `transposeDataset` | 차트 질의 실행, `STAT_TIMESTAMP` 열, `fetch="rows"` 바꾸기 | `DashboardSampler#readQueries`, `readResults` |
| `histogram/DashboardRendererTimeseries#updateDashboardData` | `calc="delta"`의 초당 값, `fetch="stats"`의 계열 비우기, 최대 개수·나이 | `DashboardSampler#calculate`, `DashboardHistory` |

이 클래스들은 Eclipse 워크벤치 보기(`IWorkbenchPage`), SWT 컴포지트, JFreeChart 데이터 집합에 묶여 있어 Swing 번들이 쓸 수 없다.
화면과 상관없는 부분만 모델 번들 `com.seizia.chromite.model.admin`의 `dashboard` 패키지에 다시 만들었다. 새 번들 대신 이 번들을 쓴 이유는
같은 Activity Monitor의 서버 관리 모델이 이미 여기 있고(세션·잠금), Swing 제품에만 들어가는 번들이라 SWT 제품에 영향이 없기 때문이다.
DBeaver 파일은 고치지 않았다.

SWT와 다르게 한 것:

- 차분 값은 두 읽기 사이의 **밀리초** 시간으로 나눈다. SWT는 경과 시간을 초 단위로 버려(`/ 1000`, 정수) 1.9초 간격을 1초로 나누는 일이 있었다.
- 카운터가 줄어든 구간(서버 재시작, 통계 초기화)의 음수 차분은 점으로 넣지 않는다.
- 맵 질의 값이 글자(`SHOW GLOBAL STATUS`)이면 수식에 숫자로 넘긴다. JEXL `has()`가 `map`을 알도록 했다(SWT는 `object`, `dashboard`만 답함).
- 수식이 0으로 나누면(예: MariaDB 키 효율, `Key_read_requests`가 0) 오류가 아니라 그 시각의 값이 없는 것으로 본다(차트는 "값을 기다리는 중").
  SWT는 이때 예외로 차트 갱신을 끈다. 다른 수식 오류는 JEXL 메시지(내부 클래스 이름 포함)를 로그에만 남기고 차트에는 수식만 보인다.
- 차트마다 다른 `updatePeriod` 대신 화면의 새로 고침 간격 하나로 모든 차트를 한 번에 읽는다(같은 연결에서 차례로).
- 실패한 차트는 SWT처럼 끄지 않고 오류를 차트에 보인 뒤 30초 뒤 다시 읽는다. 다시 시작을 누르면 바로 다시 읽는다.

### 서버 연결

Activity Monitor의 세 탭이 `AdminConnection`(격리된 실행 컨텍스트 하나, 탭을 닫을 때 닫음)을 함께 쓴다. SWT도 대시보드는 기본으로
따로 연결을 연다(`dashboard.openSeparateConnection`). 질의 기록(Query Manager)에 남기지 않는다(`session.enableLogging(false)`, SWT와 같음).
첫 읽기(개요 탭을 처음 볼 때, 다시 시작)에서만 연결하고, 그 밖의 타이머 차례에는 연결이 끊겨 있으면 읽지 않는다(간격마다 비밀번호 창이
뜨지 않도록, 세션·잠금 탭과 같은 규칙).

## 차트 정의

DBeaver 대시보드 등록부(`DashboardRegistry#getDashboardItems`)에서 연결의 드라이버에 맞는 시계열(`dataType="timeseries"`) 정의를 모두 쓴다.
웹 페이지를 보이는 `provided` 형식은 그리지 않는다. 처음 보이는 차트는 정의의 `showByDefault`이고 나머지는 "차트" 단추에서 켠다.

| DB | 정의한 곳 | 차트 (기본 표시 *) |
|---|---|---|
| PostgreSQL | DBeaver `ext.postgresql` | 서버 세션*(활성·유휴·전체), 초당 트랜잭션*(전체·커밋·롤백), 블록 I/O*(읽기·적중, 초당 블록 수 — 2026-11-07 `value="bytes"`를 `integer`로 고침) |
| PostgreSQL | Chromite | 대기 유형별 활성 세션*(CPU·잠금·IO·기타), 잠금(허용됨·대기 중), 캐시 적중률*(%) |
| MySQL·MariaDB | DBeaver `ext.mysql` | 네트워크 트래픽*, InnoDB 데이터*, 쿼리*(SELECT·INSERT·UPDATE·DELETE 초당), 서버 세션*(명령별), InnoDB 메모리, 키 효율 |
| MySQL·MariaDB | Chromite | 스레드*(연결됨·실행 중), 행 잠금 대기*, 버퍼 풀 적중률*(%) |
| SQL Server | Chromite (DBeaver에는 없음) | 세션*, CPU 사용률*(%), 초당 요청*(일괄 처리·트랜잭션·컴파일), 대기 중인 작업*(대기·차단됨), 데이터베이스 I/O*, 잠금 대기*(잠금 대기·교착 상태, 초당), 버퍼 캐시 적중률*(%) |
| 그 밖 | — | 정의가 없으면 "이 데이터베이스 유형에는 정의된 차트가 없습니다." |

Chromite 정의는 DBeaver 확장 점(`com.seizia.chromite.dashboard`)에 `com.seizia.chromite.model.admin/plugin.xml`로 더했다. 제목·설명은
`%키`로 이 번들의 `OSGI-INF/l10n/bundle*.properties`(영어·한국어)에 있다. DBeaver upstream 파일을 고치지 않으려는 것이다.

SQL Server 질의의 근거(로컬 SQL Server 2022 Linux 컨테이너에서 확인):

- CPU: `sys.dm_os_schedulers.total_cpu_usage_ms`의 합 ÷ (10 × `cpu_count`)를 차분하면 전체 프로세서 대비 백분율이다. 링 버퍼
  (`RING_BUFFER_SCHEDULER_MONITOR`)는 Linux에서 0만 돌려주고, `Resource Pool Stats`의 `CPU usage %`는 읽을 때마다 크게 튀어 쓰지 않았다.
- 초당 요청·잠금 대기: `sys.dm_os_performance_counters`의 누적 카운터(`Batch Requests/sec` 등, 형식 272696576)를 차분한다.
  `object_name`은 명명된 인스턴스에서 `MSSQL$이름:`으로 시작하므로 `LIKE N'%:SQL Statistics%'`로 찾는다.
- 버퍼 캐시 적중률: `Buffer cache hit ratio` ÷ `Buffer cache hit ratio base`(형식 537003264, 비율 카운터).
- I/O: `sys.dm_io_virtual_file_stats(NULL, NULL)`의 읽기·쓰기 바이트 합을 차분.
- 모두 `VIEW SERVER STATE` 권한이 필요하다. 권한이 없으면 그 차트에 오류가 보인다.

PostgreSQL·MySQL의 캐시 적중률은 통계를 다시 설정한 뒤(서버 시작 뒤)의 누적 비율이라 짧은 변화는 잘 보이지 않는다. 순간 비율은
DBeaver의 블록 I/O(읽기·적중 초당) 차트로 본다.

## 화면

- **도구 막대**: 일시 중지/다시 시작, 새로 고침 간격(1·2·5·10·30초, 1분, 기본 2초), 차트(표시할 차트 체크, 값 지우기, 레이아웃 다시 설정),
  한 줄의 차트 수(1~4, 기본 2).
- **카드 격자**: 차트마다 둥근 카드(제목, 설명 도구 설명, 옵션 단추). 옵션: 넓게(두 칸 차지), 왼쪽으로 이동, 오른쪽으로 이동, 값 지우기, 숨기기.
  줄 높이는 보이는 높이를 채우도록 늘어나고(230~420 px), 넘치면 세로로 스크롤한다.
- **차트**: 범례(계열 색, 이름, 현재 값), 0부터 시작하는 값 축(백분율은 0~100%, 눈금 4칸), 시간 축(HH:mm:ss), 2 px 선, 계열이 하나면 선 아래를 옅게 칠함.
  마우스를 올리면 세로선과 가장 가까운 점, 그 시각의 계열별 값을 보인다. 값은 형식대로 쓴다(바이트: B/KB/MB/GB, 백분율, 정수; 초당 값은 `/s`).
- **시간 창**: 새로 고침 간격 × 150(최소 1분, 정의의 `maxAge` 30분 이하). 기본 2초면 최근 5분이다. 점 사이가 간격 × 3 + 1초보다 멀면
  (일시 중지, 다른 탭을 본 동안) 선을 잇지 않는다.
- **상태 줄**: 마지막으로 읽은 시각, 일시 중지됨, 연결 안 됨, 읽지 못한 차트 수.
- **설정**(이 번들의 설정 저장소): `dashboard.refreshSeconds`, `dashboard.perRow`, `dashboard.layout.<공급자>.<드라이버>`(보이는 차트 ID와 순서,
  넓은 카드는 `:wide`, 모두 숨기면 `-`).

### 차트 라이브러리: JFreeChart 대신 Java2D 차트를 만든 이유

| 기준 | JFreeChart 1.5.3 (`org.jkiss.bundle.jfreechart` 1.5.8) | 직접 만든 Java2D 차트 (`DashboardChart`) |
|---|---|---|
| SWT 없이 쓸 수 있나 | 예. 번들은 `Require-Bundle`이 없고 jar 안에 SWT 클래스가 없다(SWT 연결부는 `ui.charts`의 `org.jfree.swt`). | 예 |
| Swing 제품에 이미 있나 | 아니요. `com.seizia.chromite.ui.feature`(SWT)에만 들어 있어 Swing 피처에 더해야 한다. | 새 의존성 없음 |
| 라이선스 | LGPL 2.1. 배포하려면 `NOTICE`에 출처·소스 위치를 적고 라이선스 문서를 함께 내야 한다. DBeaver가 묶은 번들 jar에는 라이선스 파일이 없다. | Apache 2.0 (Chromite 코드) |
| 크기 | 1.6 MB | 약 450줄 |
| 테마 | 차트·축·범례 색과 글꼴을 테마를 바꿀 때마다 다시 넣어야 한다(SWT 렌더러도 만들 때 한 번만 넣는다). 기본 모양이 Islands와 다르다. | 그릴 때마다 Look and Feel 색을 읽어 라이트·다크를 따라간다. |
| 필요한 기능 | 차고 넘침 | 선 몇 개, 시간 축, 0부터의 값 축, 범례, 마우스 위치 값으로 충분 |

새 의존성을 더하지 않는다는 원칙(CLAUDE.md 1번)과 Islands 모양을 맞추기 쉬운 점 때문에 직접 만들었다. 더 많은 차트 종류(막대, 원형,
히스토그램)가 필요해지면 그때 JFreeChart를 다시 검토한다. JFreeChart는 LGPL 2.1이다. 제품에 넣지 않으므로 `LICENSE.md`의 제3자 목록에서
"JFreeChart - Apache 2.0" 줄을 지웠다(2026-11-08, 아래 "확인하지 못한 것·알릴 것"). 다시 넣으면 LGPL 2.1로 고지한다.

계열 색은 라이트·다크 각각 고른 8색이고 순서가 정해져 있다(처음 나온 계열부터, 계열이 사라져도 남은 계열 색은 바뀌지 않음).
색각 이상 구분 검사 결과(인접 계열 OKLab ΔE): 라이트(바탕 #ffffff) CVD 9.1 / 일반 19.6, 다크(바탕 #191a1c) CVD 8.4 / 일반 19.3 — 모두 통과.
라이트에서 노랑·분홍·청록은 바탕 대비 3:1에 못 미쳐, 범례에 이름과 현재 값을 글자로 함께 보이고 마우스 위치 값도 글자로 보인다.

## 화면 문구

- `DashboardMessages`(+`_ko`): 메뉴, 도구 막대, 카드 메뉴, 상태 줄, 빈 화면 문구. 새로 고침 간격은 Activity Monitor와 같은
  `ConnectionMessages.auto_refresh_*`를 쓴다.
- 차트 제목·설명·계열 이름: Chromite 정의는 `%키`(모델 번들 `bundle*.properties`). DBeaver 정의는 제목·계열 이름이 `plugin.xml`에 영어로
  적혀 있어 모델 번들의 `DashboardLabels_ko.properties`(키 `title.<차트 ID>`, `description.<차트 ID>`, `series.<계열 이름>`)로 옮긴다.
  표에 없으면 정의의 문구 그대로다. 서버가 돌려주는 계열 이름(MySQL 명령 `Sleep`, `Query` 등)은 옮기지 않는다.
- 단위(B, KB, MB, GB, %, /s)는 옮기지 않는다.

## upstream 대비 변경점

- DBeaver 파일은 고치지 않았다. Chromite 번들에 차트 정의(`plugin.xml`)와 모델 클래스를 더했다.
- SWT 제품의 대시보드에도 Chromite 정의(SQL Server 등)가 보인다. 같은 확장 점을 쓰고 `com.seizia.chromite.model.admin`은 Swing 피처에만
  들어 있으므로 실제로는 Swing 제품에서만 보인다.

## 미룬 것

- 차트 정의 편집(사용자 정의 차트 만들기·고치기, SWT `DashboardItemConfigurationDialog`)과 대시보드 여러 개 저장(SWT `.dashboard` 파일).
  SWT 제품에서 만든 사용자 정의 차트(`dashboards.xml`)는 등록부가 읽으므로 "차트" 목록에 보인다.
- 차트 종류 바꾸기(SWT 보기 설정의 범례·격자·축 끄기), 막대·원형 차트. 지금은 선 차트만.
- 차트를 큰 창으로 보기(SWT `DashboardItemViewDialog`).
- Oracle 등 정의가 없는 DB의 차트. Oracle은 시험 DB가 있지만 DBeaver·Chromite 모두 정의가 없다.

## 확인 (2026-11-07)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swt,product-chromite-swing` → BUILD SUCCESS.
Swing 제품을 별도 작업 공간으로 띄우고(한국어 OS 기본, `-nl en`), 로컬 Docker DB의 `chr_dashboard`에 연결해 확인했다.
부하는 `docker exec`로 만들었다: PostgreSQL `pgbench -c 6 -T 120`과 한 행을 잡고 `pg_sleep(45)` 하는 트랜잭션 + 같은 행을 갱신하는 세션 2개,
SQL Server 갱신·조회 루프 4개(150초)와 `WAITFOR DELAY` 차단 + 대기 세션 2개, MariaDB 저장 프로시저 루프 4개와 `SLEEP(45)` 차단 + 대기 세션.

| 항목 | PostgreSQL 17 | SQL Server 2022 | MariaDB 11.8 |
|---|---|---|---|
| 여는 곳 | Object Explorer 메뉴 | Tools 메뉴 | Object Explorer 메뉴 |
| 기본 차트 수 | 5 | 7 | 7 |
| 부하에 따라 값이 움직임 | 초당 트랜잭션(최고 약 220/s), 대기 유형별(잠금 대기 4~7), 세션 | 초당 요청(트랜잭션 약 200/s), 대기 중인 작업·차단됨, I/O 쓰기, 잠금 대기 | 스레드, 행 잠금 대기(5), 쿼리, 트래픽 |
| 마우스 위치 값(세로선, 점, 시각별 값) | 됨 | 됨 | 됨 |
| 라이트·다크 | 됨 | 됨 | 됨 |

그 밖에 확인한 것: 일시 중지(상태 "일시 중지됨", 다시 시작하면 그 사이 선을 잇지 않음), 차트 메뉴(체크로 차트 더하기: 키 효율, InnoDB 메모리),
한 줄에 3개·4개, 카드 옵션 "넓게"(두 칸), 개요 탭을 보는 동안 프로세스 탭 전환, 영어(`-nl en`) 문구.
화면은 작업 공간 밖 `D:\Tools\_scratch\dashboard\shots\`에 있다.

확인 중 고친 것: 좁은 카드에서 시간 축 글자가 겹치던 것(가장 새 시각부터 그리고 겹치는 글자는 뺌), MariaDB 키 효율이 0으로 나누기 오류와
내부 클래스 이름을 보이던 것, 차트 메뉴의 "지우기"를 "모든 값 지우기"로, 좁은 카드에서 잘린 제목을 도구 설명에 모두 보임.

확인하지 못한 것·알릴 것:

- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: Activity Monitor의 탭 번호(Processes·Locks로 여는 명령), Object Explorer 오른쪽 클릭 메뉴, Tools 메뉴.
- Oracle은 차트 정의가 없어 빈 화면 문구만 해당된다(확인하지 않음).
- DBeaver PostgreSQL "Block IO" 정의는 블록 수를 `value="bytes"`로 적어 단위가 바이트로 보였다(실제는 블록/초). 2026-11-07 `value="integer"`로 고침([model-fixes.md](model-fixes.md) "PostgreSQL Block IO 대시보드 단위").
- `LICENSE.md`의 제3자 목록이 JFreeChart를 "Apache 2.0"으로 적었으나 JFreeChart는 LGPL 2.1이다. 이 작업은 JFreeChart를 쓰지 않아 고치지 않았다.
  2026-11-08: 제품에 JFreeChart가 없어(빌드 설정·`MANIFEST.MF`에 없음) 그 줄을 지웠다([model-fixes.md](model-fixes.md) "작은 모델·안전·라이선스 수정").

## 변경 이력

- 2026-11-07 최초 작성: Activity Monitor 개요 탭(서버 대시보드), Tools·Object Explorer의 Server Dashboard 명령, 모델 번들의 갱신 로직
  (`DashboardSampler`, `DashboardHistory`)과 SQL Server·PostgreSQL·MySQL 차트 정의, Java2D 차트.
- 2026-11-07 Activity Monitor 탭 스타일: 밀도 B 브랜치와 합칠 때 옛 고정값(`tabHeight: 32`) 줄이 남아 밀도 B 값(`UiSizes.TOOL_TABS_STYLE`)을 덮던 것을 지움.
- 2026-11-07 PostgreSQL "Block IO" 정의의 단위를 바이트에서 정수(블록/초)로(`ext.postgresql` `plugin.xml`, #50).
- 2026-11-08 `LICENSE.md`의 "JFreeChart - Apache 2.0" 줄 삭제(제품에 없음, 실제 라이선스는 LGPL 2.1) 기록(#49).
