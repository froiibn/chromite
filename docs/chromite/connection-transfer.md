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
| File 메뉴 | Import Connections… / Export Connections… | 내보내기: 모든 연결 |
| Registered Servers, 서버의 메뉴 | Export Connections… | 그 서버만 |
| Registered Servers, 그룹의 메뉴 | Export Connections… | 그 그룹과 그 안의 모든 그룹·서버 |
| Registered Servers, 빈 곳의 메뉴 | Import Connections… / Export Connections… | 내보내기: 모든 연결 |

내보내기 대화상자에서 그룹·서버를 체크로 고른다(그룹을 체크하면 그 아래 전부). "Include passwords"는 기본으로 켜져 있고, 켜면
암호를 두 번 넣는다(12자 이상). 그다음 저장할 파일을 고른다.

가져오기는 파일 고르기 → 암호(암호화한 파일일 때) → 미리 보기(이름, 호스트, 드라이버, 그룹, 비밀번호 유무, 기존 연결과의 충돌,
충돌마다 할 일) → 가져오기 순서다. 끝나면 가져온 수, 바꾼 수, 사본 수, 건너뛴 수와 주의할 것을 알린다.

## 구조

| 위치 | 클래스 | 하는 일 |
|---|---|---|
| `plugins/com.seizia.chromite.registry` | `ConnectionTransferFile` | 파일 컨테이너: 평문 머리말 한 줄 + 페이로드, AES-256-GCM, PBKDF2 |
| | `ConnectionTransfer` | 내보내기(레지스트리 직렬화기 → 필요한 것만 남기고 자격 증명 채우기), 읽기(미리 보기·충돌), 가져오기 |
| | `ConnectionTransferException` | 읽을 수 없는 이유(파일 아님, 지원하지 않는 형식, 암호 틀림·파일 바뀜, 내용 오류) |
| `plugins/com.seizia.chromite.ui.swing` | `ConnectionTransferCommands` | 메뉴 명령, 파일 고르기, 암호 묻기, 진행 대화상자, 결과 알림 |
| | `ConnectionExportDialog` | 내보낼 그룹·서버, 비밀번호 포함, 암호 두 번 |
| | `ConnectionImportDialog` | 미리 보기와 충돌별 동작(모든 충돌에 한 번에 정하기 포함) |
| | `internal/ConnectionTransferMessages*` | 화면 문구(영어, 한국어) |
| | `MainFrame`, `RegisteredServersPanel` | 메뉴 항목 몇 줄 |
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

## 변경 이력

- 2026-11-07 OS 보안 저장소 이전과의 관계 갱신: 내보내기 암호 안내 문장, 가져오기 저장 실패 알림, 이전 뒤 확인 결과([credential-storage.md](credential-storage.md))
- 2026-11-07 최초 작성: 연결 내보내기·가져오기(파일 형식 버전 1)
