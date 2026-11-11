# Swing 결과 값 편집기 나머지 (참조 값, SET, 배열·구조체·hstore, 16진수, 외부 편집기, UUID)

## 목적

#154([swing-grid-editing.md](swing-grid-editing.md))가 남긴 SWT 값 편집기를 Swing 결과 그리드로 옮긴다. SWT 대조 B-R14의 나머지, D-P28, D-M18이다
([swt-parity/B-editors.md](swt-parity/B-editors.md), [swt-parity/D-db-specific.md](swt-parity/D-db-specific.md)).

| 기능 | SWT | Swing |
|---|---|---|
| 참조 값 고르기(외래 키, 검색) | `ReferenceValueEditor` | `ResultSetReferencePicker` |
| MySQL/MariaDB `SET` 여러 값 고르기 | `MySQLEnumValueManager` | `ResultSetValueDialogs` (SET) |
| 배열·구조체 편집 | `ComplexObjectEditor`, `ArrayValueManager`, `StructValueManager` | `ResultSetValueDialogs` (배열, 구조체) |
| PostgreSQL hstore | (SWT는 값 관리자를 주석 처리, 글자) | `ResultSetValueDialogs` (hstore 키·값 표) |
| PostgreSQL money, bit string | `StringValueManager`(주석 처리), `BitStringValueManager` | 글자 편집, 비트 문자열은 0·1만 |
| `OBJECT` 열 글자 편집(PostgreSQL uuid, inet 등) | `ObjectValueManager`(= `StringValueManager`) | `ResultSetEditModel` 편집 대상에 `OBJECT` |
| 이진 값 16진수 편집 | `ui.editors.hex` `HexEditControl` | `ResultSetHexEditor` |
| 외부 편집기 | `ContentValueManager` "Open in external editor"(열기만) | `ResultSetExternalEditor`(저장하면 셀에 넣음) |
| UUID 만들기 | `GenerateUUIDHandler` (Ctrl+Shift+Alt+U) | 그리드 "UUID 만들기" (같은 단축키) |

모든 편집기는 셀 값만 바꾼다. DB는 #154처럼 Save 또는 저장하고 커밋으로 바뀐다(수동 커밋 연결의 Save는 열린 트랜잭션에 둠, 저장하고 커밋은 그 전 변경을 먼저 묻고
기본 단추는 Cancel). 실행 취소, 변경 미리 보기, 저장 뒤 다시 읽기도 그대로다.

## 구조

모두 `com.seizia.chromite.ui.swing` 번들이다. 새 번들·새 의존은 없다(PostgreSQL·MySQL 모델 번들 의존은 원래 있었다).

| 클래스 | 새/변경 | 역할 |
|---|---|---|
| `ResultSetValueDialogs` | 새 | 칸 형식 판단(`getKind`), SET·배열·구조체·hstore 대화상자, hstore 글자 읽기·만들기 |
| `ResultSetReferencePicker` | 새 | 참조 값 고르기 대화상자(사전 접근기, 검색, 더 읽기) |
| `ResultSetHexEditor` | 새 | 16진수 편집 대화상자 |
| `ResultSetExternalEditor` | 새 | 임시 파일로 기본 프로그램 열기, 저장 감지, 셀에 넣기 |
| `internal/ValueEditMessages` (+ `.properties`, `_ko.properties`) | 새 | 이 기능의 화면 문구(영어·한국어) |
| `ResultSetGrid` | 변경 | "값 편집"(Shift+Enter), "UUID 만들기", 글자 편집기 끝의 대화상자 단추, 비트 문자열 입력 거르기, `showStatus` |
| `ResultSetViewerPanel` | 변경 | "16진수로 편집", "외부 편집기로 열기" 메뉴, 결과를 닫을 때 외부 편집 멈춤, 배열·구조체 값은 "값으로 필터"에 넣지 않음 |
| `ResultSetEditModel` | 변경 | `OBJECT`·`ARRAY`·`STRUCT`(PostgreSQL) 열 편집, SET 열 표시(`isMultiValueColumn`) |
| `ResultSetGridReceiver` | 변경 | 배열·구조체 값(`isComplexValue`)을 놓아 주지 않고 원래 값으로 남김 |
| `ResultSetEditPersister` | 변경 | 저장 뒤 다시 읽은 행의 배열·구조체 값도 남김 |
| `ResultSetEnumValues` | 변경 | MySQL `SET` 허용 값도 읽음, `isMultiple` |
| `ResultSetValuePanel` | 변경 | NULL 배열·구조체(값 없는 값 객체)를 NULL로 보임 |

## 설계 결정과 이유

### 어떤 칸이 대화상자를 여나

SWT는 값 관리자 확장점이 형식마다 편집기를 골랐다. Swing에는 그 확장점이 없어 `ResultSetValueDialogs.getKind`가 모델 정보만으로 정한다(DB를 읽지 않음).

1. `SET` 열(`MySQLTableColumn.isTypeSet`)
2. 외래 키 열: 바인딩이 이미 아는 관계(`DBDAttributeBinding.getReferrers`, 실제·가상 외래 키, #164의 `ResultSetReferences.getCellAssociations`)
3. 자료형 `ARRAY`, `STRUCT`
4. 값 처리기가 `PostgreHStoreValueHandler`

여는 길은 세 가지다: 오른쪽 클릭 "값 편집", Shift+Enter(SWT "Open value editor"와 같은 키), 셀 글자 편집기 끝의 단추(외래 키는 돋보기, 나머지는 "…").
대화상자가 없는 칸에서 "값 편집"은 상태 줄에 "글자로 편집하세요"를 쓴다. LOB 칸은 #154처럼 값 패널을 연다.
대화상자는 셀만 바꾸므로 OK가 기본 단추이고 Esc는 취소다(날짜 선택기와 같음).

### 참조 값 고르기

- 값 목록은 모델의 사전 접근기(`DBSDictionary.getDictionaryAccessor`)가 읽는다. 어느 외래 키인지는 SWT처럼 `DBStructUtils.getEnumerableConstraint`,
  참조되는 열은 `DBUtils.getReferenceAttribute`. 설명 열·정렬·검색 SQL도 접근기가 만든다. 그래서 실제·가상 외래 키 모두이고 DB마다 다른 코드는 없다.
- 여러 열 외래 키는 같은 행의 다른 키 열 값을 조건으로 준다(SWT `restColumns`).
- 한 번에 읽는 수는 연결 설정 `dictionary.max.rows`(기본 200). 검색어가 없으면 처음부터(`getValues`), 있으면 값·설명에서 대소문자 없이(`getSimilarValues`).
  "더 읽기"가 다음 묶음을 붙인다. SWT의 지금 값 주변 읽기(`getValuesNear`)는 접근기마다 지원이 달라 쓰지 않았고, 읽은 목록에 지금 값이 있으면 고른다.
- 읽기는 백그라운드 Job, 입력이 바뀌면 0.3초 뒤 다시 읽고 늦게 끝난 이전 결과는 버린다(세대 번호).
- 고른 키는 열의 값 처리기가 셀 값으로 바꾼다(`parseObject`).

### SET

허용 값을 확인란으로 고른다. 결과 글자는 고른 값을 선언 순서로 쉼표로 이은 것(MySQL이 쓰는 형식). NULL 열이면 "값 전체가 NULL" 확인란이 있다.
글자 편집(F2)도 그대로다. ENUM 콤보는 SET 열에는 쓰지 않는다(`ChoiceCellEditor.hasChoices`).

### 배열·구조체

- **값을 남긴다.** #154까지 수신기는 배열·구조체 값 객체를 표시 문자열로 바꾸고 놓아 주어 이 열은 읽기 전용이었다. 그런데 `JDBCCollection`·`JDBCComposite`는
  읽을 때 요소를 단순 값으로 바꿔 갖고(드라이버의 `Array`·`Struct`를 붙잡지 않음) `release`는 그 배열을 비울 뿐이다. 그래서 놓아 주지 않고 편집 모델에 남긴다
  (`ResultSetGridReceiver.isComplexValue`). 저장 뒤 다시 읽은 행도 같다. 값 패널의 칸 값(`cellValues`)과 XLSX용 값은 예전대로 넣지 않는다.
- **글자 편집:** 값 처리기의 편집 형식(PostgreSQL `{a,"b c"}`, `(1,2,"x")`)으로 고치고 처리기가 값으로 바꾼다(SWT `StringValueManager`의 인라인 편집과 같음).
- **대화상자:** 항목마다 한 줄. 글자는 항목 형식의 값 처리기가 편집 형식으로 만들고 값으로 바꾼다(SWT `ComplexObjectEditor`와 같은 호출). 새 값은 원래 값 객체를
  복제해(`DBDValueCloneable`) 항목만 바꾼다: 요소 형식과 처리기가 그대로라 PostgreSQL 처리기가 배열 문자열·복합 형식 문자열로 바인딩한다.
  배열은 추가·제거·위로·아래로, 구조체는 속성 이름과 형식을 보인다. 안에 든 배열·구조체는 여기서 고치지 않는다(셀 글자로). 빈 글자는 빈 문자열, NULL은 "항목을 NULL로".
- **구조체는 PostgreSQL 복합 형식만 편집한다.** 일반 JDBC 구조체 처리기(`JDBCStructValueHandler`)는 읽어 온 `Struct`를 그대로 바인딩해(`JDBCComposite.getStructValue`가
  원래 구조체를 돌려줌) 바꾼 속성이 저장되지 않는다. 그런 열을 편집할 수 있게 하면 사용자가 바꾼 값이 조용히 버려지므로 읽기 전용으로 둔다.
  배열은 일반 처리기도 지금 요소로 `createArrayOf`를 만들어 바인딩하므로 편집 대상이다(드라이버가 거절하면 저장 오류가 Messages에 나옴).

### hstore, money, bit string, OBJECT 열

- PostgreSQL의 `uuid`, `inet` 같은 형식은 열 자료형이 `OBJECT`(JDBC `OTHER`)이고 값 처리기가 글자로 값을 만든다(`PostgreStringValueHandler`, 바인딩은 `Types.OTHER` 문자열).
  SWT는 `OBJECT` 열을 글자로 편집했다(`ObjectValueManager` = `StringValueManager`). #154는 문자열·숫자·불리언·날짜만 칸에서 편집해 이 열들이 읽기 전용이었다.
  이제 `OBJECT`도 편집한다. 값 객체(`DBDValue`)로 오는 열(공간 값 등)은 그대로 읽기 전용이다.
- 실제 결과(PostgreSQL 17, 이 확인 DB): `uuid`는 `OBJECT`, `hstore`는 테이블 열에 묶이면 `STRING`, `money`·`bit`·`varbit`은 `NUMERIC`이었다. 그래서 hstore·money·bit string은
  #154에서도 글자로 편집되었고(SWT처럼 처리기가 문자열로 바인딩), 이번에 더한 것은 hstore 대화상자와 비트 문자열 입력 거르기다.
- **hstore:** 키·값 표로 고친다. PostgreSQL 입력 형식(`"키"=>"값", "키"=>NULL`, 큰따옴표·역슬래시는 역슬래시로)으로 만든다. 같은 키나 빈 키는 거절한다(빈 키는 hstore에
  쓸 수 있지만 대개 실수라서).
- **money:** 글자로 고친다(`$15.75`처럼 DB의 `lc_monetary` 형식 또는 숫자). 서버가 읽는다. SWT도 money 전용 관리자를 주석 처리해 두었다.
- **bit string:** 칸 편집기가 0과 1만 받는다(SWT `BitStringInlineEditor`). 길이(`bit(8)`)는 입력 때 막지 않고 저장할 때 DB가 검사한다
  (SWT는 형식의 정밀도·최대 길이로 입력 길이를 막았다).

### 16진수 편집

- SWT 16진수 편집기는 바이트 칸마다 커서가 움직이는 전용 컨트롤이었다. Swing에는 그런 컨트롤이 없어 16진수 글자를 고치는 글 상자로 했다: 한 줄 16바이트,
  왼쪽 오프셋, 오른쪽 글자(ASCII) 미리 보기. 공백·줄 바꿈은 무시하고, 16진수가 아닌 글자나 홀수 개 숫자는 이유를 쓰고 OK를 끈다. "정리"가 16바이트 줄로 다시 맞춘다.
- 결과 오른쪽 클릭 "16진수로 편집"(파일 불러오기·저장 옆). 이진 값(바이트, 이진 LOB)이나 편집할 수 있는 이진 LOB 칸(NULL이면 새 바이트를 입력)에서 켜진다.
- 값 패널이 읽는 크기(1,000,000바이트)까지 연다. 더 큰 값은 파일 불러오기나 외부 편집기를 안내한다.
- OK는 바이트를 열의 값 처리기에 준다(파일 불러오기와 같은 길). 읽기 전용 칸은 보기만 한다. Enter는 줄 바꿈이라 OK는 Ctrl+Enter다.

### 외부 편집기

- 결과 오른쪽 클릭 "외부 편집기로 열기". 값을 플랫폼 임시 폴더(`DBPPlatform.getTempFolder`, 이름 `value-editor`)의 파일로 쓰고 `Desktop.open`으로 그 형식의 기본 프로그램을 연다.
  확장자는 파일 불러오기·저장과 같은 규칙(JSON, XML, 글자 txt, 이진 bin)에, 이진 값은 첫 바이트로 PNG·JPEG·GIF·PDF·ZIP·BMP를 알아본다.
- 편집할 수 있는 칸이면 1초마다 파일의 수정 시각·크기를 보고, 바뀐 뒤 한 번 더 같으면(쓰기가 끝남) 파일을 읽어 셀에 넣는다(글자는 UTF-8, BOM 제거). 상태 줄에
  "저장된 파일 …을 셀에 넣었습니다"를 쓴다. SWT의 "Open in external editor"는 열기만 했다.
- 결과 뷰어가 표를 새로 만들거나(다시 실행, 정렬·필터·다음 페이지. 저장 뒤 행 다시 읽기는 같은 표라 계속됨), 행이 없어지거나, 결과 창을 닫으면
  지켜보기를 멈추고 파일을 지운다. 읽기 전용 칸은 열기만 하고 그렇다고 알린다.
- 글자 칸(문자열·`OBJECT`)도 연다(값 처리기의 편집 형식). 편집기가 끝에 줄 바꿈을 더하면 그것도 값에 들어간다.

### UUID 만들기

SWT `GenerateUUIDHandler`처럼 고른 셀마다 새 무작위 UUID를 넣는다. 단축키도 같다(Ctrl+Shift+Alt+U). SWT는 열 형식을 보지 않았지만, 숫자·날짜 칸에 UUID 글자를
넣는 실수를 막으려고 글자 열(`STRING`: SQL Server `uniqueidentifier`, MariaDB `UUID`, 문자 열), `OBJECT` 열(PostgreSQL `uuid`), UUID 값 처리기 열만 바꾸고
나머지는 건너뛴 수를 상태 줄에 쓴다. 한 번에 한 실행 취소 단계다.

## upstream 대비 변경점

- upstream(SWT) 코드와 모델은 바꾸지 않았다. Swing 번들만 바뀌었다.
- SWT와 다른 동작: 16진수 편집기는 글 상자, 외부 편집기는 저장하면 셀에 넣음, UUID 만들기는 글자·UUID 열만, 참조 값은 지금 값 주변 읽기 대신 처음부터·검색,
  구조체 편집은 PostgreSQL만, bit string 길이를 입력 때 막지 않음, hstore 키·값 표.

## 확인 (2026-11-08)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → `BUILD SUCCESS`, `python tools/rename/gate.py` → `ALL GATES PASSED`.
- 실행: 빌드한 Swing 제품을 `D:\Tools\_scratch\chromite-value-editors-rest`에 복사해 `CHROMITE_DATA`와 작업 공간을 그 폴더에 두고 열었다. 화면 조작은 실행 중인 앱 안의
  시험용 Java 에이전트(제품에 들어가지 않음)가 Swing API로 했다(OS 입력·앞 창 없음). 외부 편집기 확인에서는 에이전트가 `Desktop.open`을 가로채 파일 경로만 기록하고
  (실제 프로그램을 띄우지 않음) 파일을 직접 고쳐 "다른 프로그램의 저장"을 대신했다. DB 값은 앱 밖의 다른 세션(`docker exec`의 psql·sqlcmd·mariadb)으로 확인했다.
  시험 DB는 공용 Docker에 만든 `chr_value_editors`(PostgreSQL·SQL Server·MariaDB)이고 끝난 뒤 지웠다.
- **PostgreSQL**(자동 커밋, 한국어): `uuid`·`hstore`·`money`·`bit(8)`·`varbit`·`ve.pt` 복합 형식·`int[]`·`text[]`가 편집 가능. UUID 만들기(빈 셀 2개),
  배열 대화상자(항목 고치기, 추가 `new "q"`, 항목 NULL), 잘못된 항목(`abc`)은 "2행: …"으로 거절 → 고쳐 확인, 위로, hstore(같은 키 거절, `quote"key`=>`back\slash`),
  구조체(`x=10`, `label=p, "two"`), money `$15.75`, bit `1x` 거절·`11110000`, varbit `1101`, NULL 배열에 "값 전체가 NULL"을 끄고 항목 추가, 배열을 NULL로,
  참조 값 고르기(40개, "Music" 검색 → 4개 → 12 선택), 16진수(`2G` 거절, 정리, 20바이트, NULL bytea에 `CAFEBABE`), 외부 편집기(글자 셀 → 저장 → 셀,
  bytea → PNG 머리 바이트 저장 → 셀) → 저장 → psql에서 모두 기대 값(`tags {reddish,NULL,"x,y","new \"q\""}`, `pos (10,2,"p, ""two""")`, `data 89504e47…` 등).
  저장 뒤 다시 읽은 배열을 다시 대화상자로 열어 항목이 그대로임, `dictionary.max.rows=15`로 더 읽기 15 → 30 → 40(끝에서 꺼짐),
  결과를 다시 읽으면 외부 편집 파일이 지워짐, 읽기 전용 결과(조인)에서 16진수는 보기만·외부 편집기는 열기만 알림.
- **MariaDB**(자동 커밋, 한국어): `SET` 확인란(글자 편집기 단추, 값 편집…), `UUID` 형식 열에 UUID 만들기, 참조 값 고르기("Tools" 검색 8개), varbinary 16진수,
  외부 편집기(한글 글자) → 저장 → mariadb에서 확인.
- **SQL Server**(자동 커밋, 한국어): `uniqueidentifier`에 UUID 만들기, 참조 값 고르기("Movies"), money `99.25`, varbinary 16진수 `DEADBEEF`, 외부 편집기 → 저장 → sqlcmd에서 확인.
- **수동 커밋 연결**(`bootstrap.autocommit = false`, 영어 `-nl en`):
  - PostgreSQL: 배열·hstore 대화상자 → Save → 다른 세션에서 이전 값 → 참조 값 고르기로 하나 더 → 저장하고 커밋 → "열린 트랜잭션에 먼저 한 변경 2개…"(기본 단추 Cancel)
    → 확인 → 다른 세션에서 세 값 모두.
  - MariaDB(다크 테마): SET 대화상자·16진수 → Save → 다른 세션에서 이전 값 → 쿼리 > 커밋 → 새 값.
- **언어·테마:** 한국어(OS 언어)와 영어, 라이트·다크에서 오른쪽 클릭 메뉴, 배열·구조체·hstore·SET·참조 값·16진수 대화상자, 셀 편집기의 돋보기 단추를 화면으로 확인했다.
- 확인하지 못한 것: macOS, Oracle·Altibase 등 다른 DB의 배열(VARRAY)·객체 형식, 실제 외부 프로그램(메모장 등)으로 연 뒤 저장(에이전트가 파일을 고쳐 대신함),
  1,000,000바이트보다 큰 값의 16진수 거절(코드 경로만).
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 그리드 편집·저장, 값 패널(배열·구조체 표시), "값으로 필터", 저장 뒤 다시 읽기,
  `OBJECT` 열을 가진 결과(이제 편집 가능, 다른 DB의 `OBJECT` 열은 저장 때 드라이버가 거절할 수 있음).

## 남긴 것

- 커서(REFCURSOR) 값 보기(SWT `CursorViewDialog`): 이번 범위(#154가 남긴 목록)에 없다.
- 일반 JDBC 구조체(Oracle 객체 형식 등) 편집: 모델 처리기가 바꾼 속성을 바인딩하지 않는다(위 "구조체는 PostgreSQL만").
- 안에 든 배열·구조체(다차원 배열, 구조체 안의 배열)의 대화상자 편집. 셀 글자로는 고칠 수 있다.
- SWT 16진수 편집기의 찾기·바꾸기, 삽입·덮어쓰기 모드.

## 변경 이력

- 2026-11-08 최초 작성(`chromite/value-editors-rest`): 참조 값 고르기, SET, 배열·구조체·hstore 대화상자, `OBJECT`·배열·구조체 열 편집, 비트 문자열 입력,
  16진수 편집, 외부 편집기, UUID 만들기.
