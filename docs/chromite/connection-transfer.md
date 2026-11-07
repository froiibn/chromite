# 연결 내보내기·가져오기 (비밀번호는 암호로 암호화한 파일)

## 목적

저장된 연결 비밀번호를 OS 보안 저장소(Windows DPAPI, macOS 키체인)로 옮길 계획이다(AI 키는 #103에서 옮겼다,
[ai-agent.md](ai-agent.md) "API 키 저장"). 그렇게 되면 수십 대의 서버를 관리하는 DBA가 작업 공간을 복사해 새 PC로 옮길 때
비밀번호가 따라가지 않는다. OS가 보호하는 키는 그 PC(그 사용자)를 떠나지 않기 때문이다.

사용자 결정(2026-11-07, "A로 한다"):

1. **먼저(이 작업)** 사용자가 정한 암호로 암호화한 파일에 비밀번호까지 담아 연결을 내보내고 가져오는 기능을 만든다.
2. **이 기능이 병합된 뒤에만** 저장 위치를 OS 보안 저장소로 옮기는 작업을 따로 한다.

이 작업은 비밀번호를 지금 어디에 어떻게 저장하는지를 바꾸지 않는다.

## 사용 방법

| 시작하는 곳 | 메뉴 | 처음 고른 것 |
|---|---|---|
| File 메뉴 | Import Connections > From a Chromite File… / From Another Tool…, Export Connections… | 내보내기: 모든 연결 |
| Registered Servers, 서버의 메뉴 | Export Connections… | 그 서버만 |
| Registered Servers, 그룹의 메뉴 | Export Connections… | 그 그룹과 그 안의 모든 그룹·서버 |
| Registered Servers, 빈 곳의 메뉴 | Import Connections… / Export Connections… | 내보내기: 모든 연결 |

내보내기 대화상자에서 그룹·서버를 체크로 고른다(그룹을 체크하면 그 아래 전부). "Include passwords"는 기본으로 켜져 있고, 켜면
암호를 두 번 넣는다(12자 이상). 그다음 저장할 파일을 고른다.

가져오기는 파일 고르기 → 암호(암호화한 파일일 때) → 미리 보기(이름, 호스트, 드라이버, 그룹, 비밀번호 유무, 기존 연결과의 충돌,
충돌마다 할 일) → 가져오기 순서다. 끝나면 가져온 수, 바꾼 수, 사본 수, 건너뛴 수와 주의할 것을 알린다.

다른 도구의 연결은 File > Import Connections > From Another Tool…에서 가져온다(아래 "다른 도구에서 가져오기"). 원본 도구와 파일을
고른 뒤의 미리 보기·충돌·가져오기는 위와 같다.

## 구조

| 위치 | 클래스 | 하는 일 |
|---|---|---|
| `plugins/com.seizia.chromite.registry` | `ConnectionTransferFile` | 파일 컨테이너: 평문 머리말 한 줄 + 페이로드, AES-256-GCM, PBKDF2 |
| | `ConnectionTransfer` | 내보내기(레지스트리 직렬화기 → 필요한 것만 남기고 자격 증명 채우기), 읽기(미리 보기·충돌), 가져오기 |
| | `ConnectionTransferException` | 읽을 수 없는 이유(파일 아님, 지원하지 않는 형식, 암호 틀림·파일 바뀜, 내용 오류) |
| | `ForeignConnection`, `ForeignConnectionReader` | 다른 도구의 연결 하나(드라이버 단서, 주소, 사용자, 평문 비밀번호, 공급자 속성, 네트워크 처리기)와 형식별 읽기 인터페이스 |
| | `CsvConnectionReader` | CSV 읽기 |
| | `ForeignConnectionImport` | 드라이버 고르기, 연결 설정 만들기, `ConnectionTransfer`의 가져오기 파일로 바꾸기 |
| `plugins/com.seizia.chromite.ext.oracle` | `importer/SqlDeveloperConnectionReader`, `importer/ToadConnectionReader`, `importer/OracleImportUtils` | Oracle SQL Developer(`connections.json`/`connections.xml`)와 Toad(연결 내보내기 XML) 읽기 |
| `plugins/com.seizia.chromite.ui.swing` | `ConnectionTransferCommands` | 메뉴 명령, 파일 고르기, 암호 묻기, 진행 대화상자, 결과 알림 |
| | `ConnectionExportDialog` | 내보낼 그룹·서버, 비밀번호 포함, 암호 두 번 |
| | `ConnectionImportDialog` | 미리 보기와 충돌별 동작(모든 충돌에 한 번에 정하기 포함) |
| | `internal/ConnectionTransferMessages*` | 화면 문구(영어, 한국어) |
| | `MainFrame`, `RegisteredServersPanel` | 메뉴 항목 몇 줄(File의 Import Connections는 하위 메뉴) |
| | `RegisteredServerCommands.updateConfig` | 패키지 안으로 열어, 가져온 그룹을 탐색기에 알린다 |

모델 클래스를 registry 번들의 `com.seizia.chromite.registry` 패키지에 둔 이유: 직렬화기 생성자(`protected`), 연결의
`resolveSecretsIfNeeded`(`protected`), `DataSourceParser.saveCredentialsToMap`(패키지 전용)을 같은 패키지에서만 쓸 수 있다.
이미 내보내는 패키지라 `MANIFEST.MF`를 고치지 않았다.

## 파일 형식 (버전 1)

확장자: `.chromite-connections` ("dbeaver"가 들어가지 않는다).

```
{"format":"chromite-connections","version":1,"encryption":"AES-256-GCM","kdf":"PBKDF2WithHmacSHA256","iterations":600000,"salt":"<base64 16바이트>","iv":"<base64 12바이트>","tagBits":128}\n
<암호문 + 128비트 인증 태그>
```

- 첫 줄(첫 `\n`까지, 4096바이트 안)은 평문 JSON 머리말이다. 나중 버전이 옛 파일을 읽을 수 있게 복호화에 필요한 값을 모두 여기에 둔다.
- 머리말 바이트 전체(줄바꿈 제외)를 GCM의 AAD로 넣는다. 머리말을 바꿔도(반복 횟수, salt 등) 인증이 실패한다.
- 비밀번호를 넣지 않은 내보내기는 `{"format":"chromite-connections","version":1,"encryption":"none"}` 다음에 페이로드를 평문으로 쓴다.
- 읽을 때 `format`이 다르면 "Chromite 연결 파일이 아님", `version`이 1보다 크거나 암호 방식·KDF·매개변수가 다르면 "지원하지 않는 형식"이다.
  반복 횟수는 100,000..10,000,000만 받는다(약하게 만든 파일과, 일부러 아주 느리게 만든 파일을 막음).

페이로드(UTF-8 JSON):

```
{"created":"<ISO-8601>","secrets":true|false,"configuration":{ ...data-sources.json과 같은 형식... }}
```

`configuration`은 레지스트리의 연결 설정 파일(`data-sources.json`)과 같은 형식이다. 자격 증명은 각 연결의
`configuration.credentials`, 네트워크 처리기의 `handlers.<id>.credentials`, 네트워크 프로필 처리기의 `credentials`에
`user`, `password`, 보안 속성으로 들어간다(직렬화기가 "secure" 설정 관리자에 쓰는 형식 그대로).

## 보안 결정

| 결정 | 이유 |
|---|---|
| JDK만 쓴다: AES-256-GCM, PBKDF2-HMAC-SHA256 | 외부 라이브러리 없이 모든 플랫폼에서 같다. GCM은 암호화와 무결성(변조 감지)을 함께 준다. |
| 반복 600,000번 | OWASP Password Storage Cheat Sheet(2023)의 PBKDF2-HMAC-SHA256 권장값. 파일을 열 때 한 번만 계산하므로(1초 안팎) 기다릴 만하고, 파일을 가져간 사람의 추측은 그만큼 느려진다. |
| salt 16바이트, IV 12바이트, 태그 128비트, 파일마다 `SecureRandom` | NIST SP 800-38D의 GCM 권장 IV 길이. 같은 암호로 여러 파일을 만들어도 키·IV가 겹치지 않는다. |
| 페이로드 전체를 암호화 | 연결 이름과 호스트도 민감할 수 있다. 암호화한 파일에는 머리말 말고 읽을 수 있는 글자가 없다. |
| 암호 12자 이상, 두 번 입력 | 오프라인 추측에 버틸 길이. 잃어버리면 되찾을 수 없으므로 잘못 친 암호로 만들지 않게 두 번 받는다. |
| 고정 키를 쓰지 않는다 | SWT 프로젝트 내보내기는 `credentials-config.json`을 그대로 넣었고, 그 파일은 모든 설치에 같은 고정 키(`BaseProjectImpl.java:101` `LOCAL_KEY_CACHE`)로 암호화되어 있어 누구나 풀 수 있었다. |
| 암호 틀림과 파일 바뀜을 한 문구로 | GCM은 둘을 구별할 수 없다(둘 다 인증 태그 불일치). 둘 다 "아무것도 가져오지 않았다"고 알리고 암호를 다시 묻는다. |
| 메모리 | 암호는 `JPasswordField.getPassword()`의 `char[]`로만 다루고 쓰고 나면 지운다. 키 바이트, 직렬화 버퍼, 페이로드·평문 바이트 배열도 지운다. 로그에 내용·암호를 쓰지 않는다. JSON을 다루는 동안 생기는 `String`(Gson)과 연결 객체 안의 비밀번호 문자열은 지울 수 없다(JVM 한계, 연결 객체는 원래 비밀번호를 메모리에 둔다). |
| 비밀번호를 넣지 않으면 암호화도 하지 않음 | 브리프가 "켜면 암호를 두 번" 넣으라고 했다. 대화상자가 "파일을 가진 사람은 연결 이름, 호스트, 사용자 이름을 읽을 수 있다"고 알린다. |

## 내보내는 것과 내보내지 않는 것

자격 증명은 `credentials-config.json`을 읽지 않고 연결 객체에서 채운다.

| 내보냄 | 내용 |
|---|---|
| 연결 설정 | 직렬화기가 쓰는 모든 항목(호스트, 포트, DB, URL, 드라이버 ID, 연결 종류, 색, 속성, 부트스트랩, 이벤트, 필터, 태그, 연결별 설정, 가상 모델 등) |
| 그룹(연결 폴더) | 고른 그룹과 고른 연결의 그룹, 그 상위 그룹. 체크한 빈 그룹도 남는다 |
| 연결 종류 | 연결이 쓰는 것 중 내장(dev/test/prod)이 아닌 것 |
| 네트워크 프로필 | 연결이 참조하는 프로젝트 프로필(외부 소스 프로필은 참조만) |
| 자격 증명(비밀번호 포함일 때) | 사용자, 저장된 비밀번호(`save-password`가 켜진 것만), 인증 모델 속성, SSH·프록시·SSL 처리기의 사용자·저장된 비밀번호·보안 속성, 프로필 처리기의 같은 값 |
| 자격 증명(비밀번호 뺄 때) | 사용자 이름만. 비밀번호, 보안 속성, 인증 속성, `save-password` 표시, 잠금 비밀번호 해시(`lockPassword`)는 넣지 않는다. 가져온 쪽은 연결할 때 묻는다 |

| 내보내지 않음 | 이유 |
|---|---|
| 드라이버 정의와 jar | 드라이버는 ID로만 참조한다. 사용자 드라이버의 jar 경로는 다른 PC에 없을 수 있다. 없는 드라이버는 미리 보기에 "설치 안 됨"으로 보이고, 연결은 저장 파일을 읽을 때와 같이 "없는 드라이버"로 들어온다 |
| 인증 프로필, 저장된 필터 | 프로젝트 전체의 것이고 고른 연결에 속하지 않는다 |
| 키 파일(SSH 개인 키, SSL 인증서 파일) | 경로만 설정에 남는다. 다른 PC에서는 그 경로에 파일을 두거나 연결을 고쳐야 한다 |
| 스크립트, 설정, 프로젝트 자원 | 이 기능의 범위가 아니다(SWT 프로젝트 내보내기와의 차이 아래) |

## 가져오기와 충돌

가져오기는 파일 내용을 따로 만든 임시 레지스트리(같은 프로젝트, 메모리 설정 관리자)에 레지스트리 직렬화기로 읽고,
가져올 연결만 실제 레지스트리로 복사해 보통 경로(`DataSourceRegistry.addDataSource`)로 더한다. 그래서 자격 증명은 그때 쓰는 저장소
(2026-11-07부터 OS 보안 저장소, [credential-storage.md](credential-storage.md))에 저장된다. 실제 레지스트리에 바로 읽어 들이지 않는 이유: 그 경로
(`loadDataSources`)는 파일에 없는 네트워크 프로필을 지우고, 인증 프로필 목록을 파일의 것으로 바꾸고, 같은 ID의 연결을 그 자리에서 바꾼다.

충돌은 같은 ID, 또는 같은 그룹에 같은 이름인 기존 연결이다. 충돌마다 고른다(기본: 건너뛰기, "모든 충돌에"로 한 번에):

| 동작 | 결과 |
|---|---|
| 건너뛰기 | 기존 연결을 그대로 둔다 |
| 바꾸기 | 기존 연결을 지우고 파일의 연결을 **기존 ID로** 더한다(그 연결에 묶인 스크립트가 계속 찾게). 기존 연결이 연결 중이면 고를 수 없고, 가져오는 순간 다시 확인해 연결 중이면 건너뛰고 결과에 적는다 |
| 사본으로 가져오기 | 새 ID, 이름은 "이름 (2)"처럼 그 그룹에 없는 이름 |

- 충돌이 없는 연결은 파일의 ID 그대로 가져온다.
- 네트워크 프로필은 같은 이름이 이미 있으면 기존 것을 두고 결과에 적는다.
- 새 연결 종류는 전역 연결 종류에 더하고 저장한다.
- **트랜잭션:** 가져오기는 세션을 건드리지 않는다. 연결 중인 연결은 바꾸지 않으므로 커밋·롤백 질문(공통 규칙의 트랜잭션 안전 규칙)이 생길 일이 없다.
- 잘못된 암호, 바뀐 파일, 형식이 다른 파일은 미리 보기 전에 멈춘다. 레지스트리는 바뀌지 않는다.

## OS 보안 저장소 이전과의 관계

- 내보내기는 연결 객체에서 자격 증명을 읽는다(`SecureCredentials`, 처리기의 `getSecureProperties`). 프로젝트가 비밀 저장소를 쓰면
  (`isUseSecretStorage`) 먼저 `resolveSecretsIfNeeded`/`DBWNetworkProfile.resolveSecrets`로 비밀 컨트롤러에서 읽는다.
  직렬화기가 비밀 저장소 프로젝트에서 자격 증명을 쓰지 않아도 이 단계가 채운다.
- 가져오기는 `addDataSource`(→ `persistSecretIfNeeded`와 설정 저장)로 저장한다.
- 그래서 다음 작업에서 저장 위치를 바꿀 때 이 기능의 코드를 바꿀 필요가 없도록 했다. 다음 작업은 이 기능으로
  "내보내기 → 새 PC에서 가져오기 → 비밀번호 없이 연결"이 계속 되는지 확인해야 한다(아래 확인 절차).
- **2026-11-07 이전 완료**([credential-storage.md](credential-storage.md)): 이 기능의 코드는 바꾸지 않았다(데스크톱 프로젝트는
  `isUseSecretStorage`가 여전히 false이고, 직렬화기가 쓰는 저장소만 바뀌었다). 확인: 이전한 작업 공간에서 비밀번호 포함 내보내기 →
  자기 keyring을 쓰는 빈 작업 공간에서 가져오기 → 5개 모두 비밀번호를 묻지 않고 연결, 다시 시작한 뒤에도 같음(credential-storage.md "확인" 6).
- 작업 공간을 복사해도 비밀번호가 다른 PC로 따라가지 않으므로, 내보내기 대화상자의 암호 안내(`transfer_export_passphrase_hint`)에
  "저장된 비밀번호는 이 PC의 보안 저장소에 있어 작업 공간을 복사해도 따라가지 않고 이 파일에는 담긴다"는 문장을 더했다. 따로 묻는 대화상자는 두지 않았다.
- 가져온 연결을 저장하다 보안 저장소가 실패하면 결과 알림 뒤에 그 오류를 보인다(`transfer_import_save_failed`, `ConnectionTransferCommands`).

## 다른 도구에서 가져오기

목적: 다른 도구를 쓰던 사용자가 연결을 손으로 다시 만들지 않게 한다(감사 A-X4, O23). SWT에는 도구마다 `ConfigImportWizard*`가 있었지만
읽는 코드가 모두 UI 번들(`ui.config.migration`, `ext.oracle.ui`)에 있어 SWT를 지울 때 함께 없어졌다(`263b8ddb45`). 남은 모델 코드에는
다른 도구의 파일을 읽는 코드가 없었다(검색: `ImportConnectionInfo`, `connections.xml`, `ConfigImport`, 도구 이름). 그래서 읽는 규칙을 모델 번들로 옮겼다.

### 지원하는 원본

| 원본 | 파일 | 읽는 것 | 비밀번호 |
|---|---|---|---|
| Oracle SQL Developer | `connections.json`(연결 내보내기도 같은 형식), `connections.xml`(SQL Developer 설정의 `system*/o.jdeveloper.db.connection*`) | 이름, 호스트, 포트, SID 또는 서비스 이름, 사용자, 역할(SYSDBA·SYSOPER), OS 인증, 연결 방식(기본, TNS 별칭, 사용자 URL). Oracle URL이 아닌 사용자 URL은 URL로 드라이버를 고른다 | 읽지 않음 |
| Toad | 연결 내보내기 XML(`ConnectionHierarchy/DbPlatform[name=Oracle]/Connections/Connection`) | Oracle 연결: 별칭, 호스트, 포트, SID, 서비스 이름 여부, 사용자, 역할, SSH 터널(호스트, 포트, 사용자, 키 파일 경로) | SSH 비밀번호만, 파일에 평문 `SSHPassword`로 있을 때 |
| CSV | 첫 줄이 열 이름: `name`(필수), `driver`(또는 `type`), `host`, `port`, `database`, `user`, 그리고 있으면 `password`, `url`, `folder`(또는 `group`), `driver_class` | 쉼표, 없으면 세미콜론·탭 구분. UTF-8(BOM 무시), UTF-8이 아니면 이 PC의 기본 인코딩. 따옴표 안의 따옴표는 두 번(RFC 4180), 역슬래시는 글자 그대로 | `password` 열(사용자가 만든 평문 파일) |

다른 도구(DbVisualizer, Navicat, SQuirreL, DataGrip, pgAdmin, SQL Workbench/J)는 아직 없다. 남은 모델 코드에 그 읽기 코드가 없고,
이번 범위(브리프의 최소 목록)를 넘는다. SWT 코드(`263b8ddb45`의 `ui.config.migration`)를 같은 방식으로 옮기면 된다.

### 비밀번호 (원본 도구의 암호화를 풀지 않는다)

| 원본 | SWT가 한 일 | Chromite |
|---|---|---|
| SQL Developer | 비밀번호를 읽지 않았다(`info.password`·`password` 주소는 SQL Developer가 자기 키로 암호화한 값) | 같음. 읽지 않는다. 연결할 때 묻는다 |
| Toad | DB 비밀번호를 읽지 않았다. SSH 비밀번호는 `SSHPassword` 요소의 값을 그대로 넣었다 | 같음. SSH 비밀번호가 있을 때만 SSH "비밀번호 저장"을 켠다(SWT는 늘 켰다) |
| CSV(SWT "사용자 정의") | `password` 열을 그대로 넣었다 | 같음 |
| Navicat(참고) | `NavicatEncrypt`로 Navicat의 고정 키 암호화를 풀었다 | 옮기지 않았다(브리프: 원본 도구의 암호화를 풀지 않음) |

가져온 비밀번호는 Chromite 파일과 같은 길(`DataSourceRegistry.addDataSource` → 지금 쓰는 저장소, 2026-11-07부터 OS 보안 저장소)로 저장된다.

### 드라이버 고르기

`ForeignConnectionImport.findDriver`가 단서를 이 순서로 본다. 처음 찾은 것을 쓴다.

1. 원본이 정한 드라이버: SQL Developer·Toad의 Oracle 연결은 `oracle:oracle_thin`.
2. 종류 이름(CSV `driver`): 별칭 표 → 드라이버 ID·`공급자:ID`·드라이버 이름(`DataSourceProviderRegistry.findDriver`) → 같은 ID의 공급자의 첫 사용 가능 드라이버.
   별칭 표는 흔한 다른 이름(postgres, pg, pgsql, mssql, sql server, mariadb 등)과 공급자의 첫 드라이버가 기본이 아닌 것(MySQL은 첫 드라이버가 5.x용이라 `mysql8`)만 담는다.
   이름은 소문자로 바꾸고 공백·`-`·`_`·`.`을 빼고 비교한다.
3. 드라이버 클래스(CSV `driver_class`): 그 클래스의 사용 가능 드라이버. 여럿이면 URL 하위 프로토콜과 같은 ID의 공급자 것.
4. JDBC URL의 하위 프로토콜(`jdbc:postgresql:` → `postgresql`): 2와 같은 규칙.

꺼진 드라이버와 "없는 드라이버"(임시)는 고르지 않는다. 어느 단서로도 못 찾으면(**알 수 없는 종류**) Generic 공급자에 사용자 드라이버
`imported_<종류>`(이름은 원본 종류, 클래스는 `driver_class`가 있으면 그것)를 만든다:

- 미리 보기에서는 만들지 않고 대기시켜 두며(`ImportFile`의 대기 드라이버), 그 드라이버를 쓰는 연결을 실제로 가져올 때만 등록하고 `drivers.xml`에 저장한다.
  건너뛴 연결 때문에 드라이버가 생기지 않는다. 같은 종류를 다시 가져오면 만든 드라이버를 다시 쓴다.
- 미리 보기의 드라이버 열은 "<종류>(알 수 없는 종류)", 표 아래에 Generic JDBC 드라이버로 가져오며 드라이버 관리자에서 클래스와 라이브러리를 넣어야 한다는 안내,
  결과 알림에도 그 연결 목록이 나온다. 같은 종류를 다시 가져와 만든 드라이버를 다시 쓸 때도 같게 표시한다(라이브러리가 아직 없을 수 있다).
- 지원 범위: Chromite는 JDBC로 100개가 넘는 DB를 지원한다. 위 규칙은 이 PC에 설치된 모든 드라이버 정의(번들 드라이버만이 아니다)에서 찾는다.

### 연결 설정 만들기

- 호스트가 있으면 호스트 방식(MANUAL). 호스트 없이 URL만 있으면 드라이버의 URL 견본으로 호스트·포트·DB를 뽑아 보고(SWT `populateBySampleParams`와 같음),
  뽑히면 호스트 방식, 아니면 URL 방식 연결이다.
- Oracle: SID/서비스(`PROP_SID_SERVICE`), 연결 방식(`PROP_CONNECTION_TYPE`: BASIC, TNS, CUSTOM), TNS 별칭은 DB 이름, TNS 경로는 `TNS_ADMIN`이 없을 때만 기본 Oracle 홈.
  역할은 SYSDBA·SYSOPER만 `oracle.logon-as` 공급자 속성(인증 모델과 연결 대화상자가 읽는 값). OS 인증은 `OracleConstants.OS_AUTH_PROP`(`@chromite-os-authentication@`) 공급자 속성.
- 구현: 연결을 같은 프로젝트의 메모리 레지스트리에 `DataSourceDescriptor`로 만들고 레지스트리 직렬화기로 `data-sources.json` 형식을 만든다(`connections`와
  `folders`만 남김). 그다음은 Chromite 파일과 같은 `ConnectionTransfer.readConfiguration` → 미리 보기 → `importConnections`다. 새 형식 코드가 없어 SSH 처리기·자격 증명·그룹이
  저장 파일과 같은 규칙으로 들어간다.
- 충돌: 다른 도구의 연결은 늘 새 ID라 "같은 그룹의 같은 이름"만 충돌이다. 건너뛰기·바꾸기(기존 ID 유지)·사본은 위 "가져오기와 충돌"과 같다.

### SWT 대비 (근본 원인을 고친 것)

| SWT | Chromite | 이유 |
|---|---|---|
| SQL Developer OS 인증이면 사용자 이름에 속성 키(`@chromite-os-authentication@`)를 넣음 | 공급자 속성으로 넣음 | Oracle 데이터 소스는 공급자 속성을 읽는다(`OracleDataSource.java` OS 인증 분기). 사용자 이름이 깨졌다 |
| Toad 역할 "Default"를 옛 키(`PROP_INTERNAL_LOGON`)에 "Normal"로 넣음 | 넣지 않음 | 옛 키가 있으면 `OracleDataSourceProvider.getConnectionUserName`이 "user AS Normal"을 만든다 |
| SQL Developer 연결 방식 값을 그대로 넣음(예: ADVANCED) | BASIC·TNS·CUSTOM으로 바꿈 | Chromite가 아는 값만 연결 대화상자와 공급자가 읽는다 |
| SQL Developer의 Oracle이 아닌 URL도 Oracle 드라이버로 | URL로 드라이버를 고름 | SQL Developer는 다른 DB 연결도 담는다 |
| 알 수 없는 드라이버 클래스면 그 클래스로 Generic 드라이버, 클래스가 없으면 가져오기 실패 | 클래스가 없어도 `imported_<종류>` 드라이버로 가져오고 미리 보기에서 알림 | 브리프: 알 수 없는 종류는 Generic JDBC 연결로 만들고 표시 |
| 설치 폴더에서 파일을 찾음(SQL Developer) | 사용자가 파일을 고름. 이 PC의 설정 폴더가 있으면 그 연결 폴더에서 시작 | 다른 PC에서 가져온 파일도 받는다 |
| 미리 보기는 체크 표, 충돌 처리 없음(이름 뒤 번호) | Chromite 파일과 같은 미리 보기·충돌 화면 | 브리프 |

## upstream·SWT 대비

SWT `ProjectExportWizard`/`ProjectImportWizard`(`263b8ddb45`의 `plugins/com.seizia.chromite.core/src/com/seizia/chromite/tools/project/`)는
프로젝트 폴더 전체를 ZIP(`.dbp`)으로 묶었다(스크립트, `.dbeaver/data-sources*.json`, `credentials-config.json`, 선택하면 드라이버 jar).
자격 증명은 위 고정 키로만 보호되었다. 가져오기는 프로젝트를 새로 만드는 방식이라 기존 프로젝트에 연결을 합치지 못했다.

옮긴 것: 드라이버 참조와 없는 드라이버 처리, 연결·폴더 설정(같은 직렬화기). 옮기지 않은 것: 고정 키 보호, 드라이버 jar 묶기,
프로젝트 자원(스크립트 등) 묶기. 그래서 [swt-parity/A-platform.md](swt-parity/A-platform.md) A-C14는 "일부"다.

## 설계 결정 (판단)

| 결정 | 이유 |
|---|---|
| File 메뉴와 Registered Servers 메뉴 둘 다 | File 메뉴는 "새 PC로 옮기기"를 찾는 자리, Registered Servers는 그룹·서버를 골라 시작하는 자리다 |
| 연결 설정은 레지스트리 직렬화기를 그대로 씀 | 새 형식을 만들지 않아 설정 항목이 빠지지 않고, 저장 파일과 같은 규칙으로 읽힌다 |
| 바꾸기는 기존 ID 유지 | 기존 연결에 묶인 스크립트·편집기가 계속 찾는다 |
| 연결 중인 연결은 바꾸지 않음(묻지 않고 건너뛰고 알림) | 세션을 건드리지 않는 가장 단순하고 안전한 방법. 미리 보기에서 "바꾸기"를 고를 수 없게 하고, 가져오는 순간 다시 확인한다 |
| 비밀번호를 뺀 파일은 사용자 이름은 남김 | 비밀이 아니고, 가져온 쪽이 비밀번호만 넣으면 되게 한다 |

## 확인 결과 (2026-11-07)

이 브랜치의 Swing 제품(번들 JRE는 테스트 빌드의 `jre\`)을 `D:\Tools\_scratch\connection-transfer\app`에 두고, 테스트 작업 공간
(`D:\Chromite-Test\workspace`, "테스트 DB" 그룹에 연결 5개)의 사본(A), 빈 작업 공간 둘(B, C. 드라이버 설정 `.metadata\.config\drivers.xml`만
복사해 "드라이버가 준비된 새 PC"로 둠)을 각각 `-data`로 띄웠다. 다른 에이전트의 앱이 같은 PC에서 돌고 있어 OS 입력은 쓰지 않았다.
우리 프로세스에 붙인 테스트 에이전트(attach API)가 EDT에서 Swing API(`doClick`, `setText`, `JFileChooser.approveSelection`)로 조작했고,
화면 사진은 창 자체를 그린 것이다. 테스트 암호는 파일에서 읽어 넣었고 로그·문서에 남기지 않았다(확인 뒤 파일을 지움).
증거: `D:\Tools\_scratch\connection-transfer\evidence\`(`shots\`, `run-logs\`, 바이트 검색 결과).

| # | 확인 | 결과 |
|---|---|---|
| 1 | A에서 File > Export Connections…, 5개 모두, 비밀번호 포함 | 12자 미만 암호와 서로 다른 암호를 막음. `all-with-passwords.chromite-connections`(2,319바이트) |
| 1b | 파일 바이트 검색 | 테스트 비밀번호 6개·암호(UTF-8/UTF-16) 0건, `127.0.0.1`·포트 5개·연결 이름 5개·그룹 이름·`postgres`·`FREEPDB1`·`mydb`·`"password"`·`"user"` 0건. 보이는 것은 머리말뿐 |
| 2 | 빈 작업 공간 B에서 가져오기 | 미리 보기 5개(비밀번호 있음, 충돌 없음) → 새로 5개. `credentials-config.json`이 보통 경로로 저장됨 |
| 3 | B에서 5개 연결(PostgreSQL, SQL Server, MariaDB, Oracle, Altibase) | 비밀번호를 묻지 않고 모두 연결됨 |
| 4 | 틀린 암호 | "암호가 틀렸거나 … 아무것도 가져오지 않았습니다", 다시 물음. 연결 0개 그대로 |
| 5 | 본문 1바이트 바꾼 파일, 머리말의 반복 횟수를 바꾼 파일 | 둘 다 같은 문구로 거부, 연결 0개 그대로 |
| 6 | Registered Servers 그룹 메뉴에서 비밀번호 없이 내보내기 → 빈 작업 공간 C에서 가져오기 | 파일에 비밀번호 0건(사용자 이름만). 가져온 5개 모두 `save-password` 꺼짐, 연결하면 5개 모두 인증 대화상자(사용자 이름 채워짐, 비밀번호 빈칸) |
| 7 | A(원래 연결 5개)에 다시 가져오기, PostgreSQL은 연결해 두고 SQL Server는 이름을 바꿔 둠 | 충돌 5개 모두 "같은 연결", 기본 건너뛰기. "모든 충돌에: 바꾸기"는 연결 중인 PostgreSQL만 건너뛰기로 둠. MariaDB 건너뛰기, Oracle 사본, 나머지 바꾸기(PostgreSQL은 모델로 억지로 바꾸기) → "0개 새로, 2개 바꿈, 1개 사본, 1개 건너뜀 / 연결 중이라 바꾸지 않음: PostgreSQL 테스트". SQL Server는 같은 ID로 원래 이름이 됨, `Oracle 테스트 (2)`는 새 ID, PostgreSQL은 연결된 채로 남음. 사본·바뀐 연결도 비밀번호 없이 연결됨 |
| 8 | 같은 그룹에 같은 이름(다른 ID)과 없는 드라이버(손으로 만든 평문 파일) | "그룹에 같은 이름", 드라이버 "no-such-driver(설치 안 됨)"와 안내. 사본 → `MariaDB 테스트 (2)`, 바꾸기 → 기존 ID 유지. 결과에 "드라이버 설치 안 됨" |
| 9 | 연결 파일이 아닌 파일, 버전 2 파일 | 각각 "Chromite 연결 파일이 아니거나 …", "더 새로운 Chromite가 만들었거나 …" |
| 10 | 앱 로그와 테스트 로그에서 비밀번호·암호 검색 | 0건. 앱 로그에 오류 없음 |

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `python tools/rename/gate.py` ALL GATES PASSED.
회귀 테스트는 요청이 없어 만들지 않았다.

## 확인 결과 (2026-11-08, 다른 도구에서 가져오기)

이 브랜치의 빌드 결과물(`product/swing/target/products/.../chromite-swing`, JBR 25로 실행)을 `CHROMITE_DATA`와 빈 작업 공간을
`D:\Tools\_scratch\import-other-tools`에 두고 하나만 띄웠다. OS 입력은 쓰지 않았다: 우리 프로세스에 붙인 테스트 에이전트(attach API)가 EDT에서
Swing API(`doClick`, `setSelected`, `JFileChooser.approveSelection`, 표 모델)로 조작했고, 화면 사진은 창 자체를 그린 것이다.
견본 파일은 공개된 형식으로 손으로 만들었고 실제 자격 증명이 없다(가짜 비밀번호 `csv-fake-pw`, `fake-ssh-test-pw`, 가짜 암호화 값).

| # | 확인 | 결과 |
|---|---|---|
| 1 | File > 연결 가져오기 하위 메뉴 | "Chromite 파일에서…", "다른 도구에서…". 원본 고르기: Oracle SQL Developer, Toad, CSV 파일과 비밀번호·CSV 열 안내 |
| 2 | CSV 9줄(BOM, CRLF, 따옴표 안 쉼표·따옴표, 역슬래시 경로) | 미리 보기 9개. `postgres`→PostgreSQL(`postgres-jdbc`), `mssql`→SQL Server(`microsoft`), `MySQL`→`mysql8`, `mariadb`→`mariaDB`, URL만→PostgreSQL에 호스트 `db.example.test`·5433·`sales`(URL 견본으로 뽑음), 드라이버 ID `sqlite_jdbc`→SQLite(DB `D:\data\x.db`), 클래스만→SQL Server, `"user""q"`→`user"q`. 그룹 `Imported/Team A`. 비밀번호 열이 있는 줄만 "있음" |
| 3 | 알 수 없는 종류 `FooDB` | 미리 보기 드라이버 열 "FooDB(알 수 없는 종류…)"와 표 아래 안내, 결과 알림 "알 수 없는 종류라 Generic JDBC 드라이버로 가져옴: CSV unknown". `generic:imported_foodb`(이름 FooDB, 클래스 `com.example.foodb.Driver`)가 가져올 때 만들어져 `drivers.xml`에 저장, 연결은 URL 방식 |
| 4 | 가져온 CSV 연결 | 9개 새로. 비밀번호 있는 줄만 `save-password` 켜짐, 비밀번호는 `credentials-storage.properties`(OS 보안 저장소)로 저장되고 작업 공간·데이터 폴더 파일에서 평문 검색 0건 |
| 5 | 같은 CSV 다시 | 9개 모두 "그룹에 같은 이름", 기본 건너뛰기. PostgreSQL 바꾸기(기존 ID 유지), MySQL·unknown 사본 → "0개 새로, 1개 바꿈, 2개 사본, 6개 건너뜀", `CSV MySQL (2)`·`CSV unknown (2)`. 드라이버는 다시 만들지 않고 `imported_foodb`를 다시 씀 |
| 6 | SQL Developer `connections.json` 5개 | 서비스 이름(`SERVICE`, `oracle.logon-as=SYSDBA`), SID(`SID`, OS 인증 공급자 속성, 역할 "default"는 넣지 않음), TNS 별칭(DB `SALES_PROD`, 연결 방식 TNS), ADVANCED 사용자 URL(URL 방식, 연결 방식 CUSTOM), PostgreSQL URL(→PostgreSQL 드라이버, 호스트 방식). 파일의 암호화된 `password`는 읽지 않음(비밀번호 "없음") |
| 7 | SQL Developer `connections.xml` | 주소 있는 1개만(주소 없는 `Reference`는 건너뜀). SYSOPER, 서비스 이름 |
| 8 | Toad 내보내기 | Oracle 2개만(SQL Server 플랫폼은 건너뜀). 별칭 없는 연결은 SID 이름 `ORCL`, 호스트가 없어 SSH 호스트로, SYSDBA, SSH 터널(사용자, 22, PASSWORD, 비밀번호 저장 켜짐, 비밀번호 있음). "Default" 역할은 넣지 않음 |
| 9 | CSV 파일을 Toad로 고름 | "이 파일에서 연결을 읽을 수 없습니다 / The Toad connection file cannot be parsed", 아무것도 가져오지 않음 |
| 10 | 앱 로그 | 9의 예상한 파싱 오류 말고 오류 없음. 보안 저장소는 `CHROMITE_DATA` 아래에만 생김(`%APPDATA%\ChromiteData` 변경 없음) |
| 11 | 고친 빌드로 앱을 다시 띄움 | 19개 연결, 그룹, `CSV PostgreSQL`의 저장된 비밀번호, `ORCL`의 SSH 처리기(비밀번호 있음), `imported_foodb` 드라이버가 그대로 읽힘 |
| 12 | 같은 CSV 다시(드라이버가 이미 있음) | "CSV unknown"이 다시 "FooDB(알 수 없는 종류)"로 표시, 사본 → 결과에 "알 수 없는 종류…: CSV unknown (3)". 드라이버는 하나 그대로. 앱 로그 오류 0건 |

증거: `D:\Tools\_scratch\import-other-tools\evidence\shots\`(01~11), `run\*.log`, 견본 파일 `samples\`. 첫 실행에서 12의 표시가 빠진 것을 보고 고쳤다(이전 가져오기가 만든 드라이버가 이름으로 찾혀 알 수 없는 종류 표시가 빠졌다 → `imported_` 드라이버로 찾힌 연결도 표시).
빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `python tools/rename/gate.py` ALL GATES PASSED.
회귀 테스트는 요청이 없어 만들지 않았다. 영향받을 수 있는 기능: Chromite 파일 가져오기(읽은 뒤 단계를 `readConfiguration`으로 나눔), File 메뉴의 가져오기 항목.

## 변경 이력

- 2026-11-08 다른 도구에서 가져오기(Oracle SQL Developer, Toad, CSV): 모델 읽기 클래스, 드라이버 고르기와 알 수 없는 종류의 Generic 드라이버,
  같은 미리 보기·충돌 화면, File > Import Connections 하위 메뉴(감사 A-X4 일부, O23 있음)

- 2026-11-07 OS 보안 저장소 이전과의 관계 갱신: 내보내기 암호 안내 문장, 가져오기 저장 실패 알림, 이전 뒤 확인 결과([credential-storage.md](credential-storage.md))
- 2026-11-07 최초 작성: 연결 내보내기·가져오기(파일 형식 버전 1)
