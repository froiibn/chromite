# 저장된 비밀번호의 OS 보안 저장소 이전

## 목적

연결 비밀번호와 작업 공간 비밀을 이 PC 운영체제의 보안 저장소(Windows DPAPI, macOS 키체인)에 둔다.

이전까지:

- 연결 자격 증명(사용자, 비밀번호, 인증 속성, SSH·프록시·SSL 처리기 비밀, 인증 프로필과 프로젝트 네트워크 프로필의 비밀)은 프로젝트의
  `.chromite/credentials-config*.json`에 있었다. 이 파일은 소스에 들어 있는 고정 키(`BaseProjectImpl.LOCAL_KEY_CACHE`)로 AES 암호화되어
  파일만 있으면 누구나 풀 수 있었다. 같은 날의 백업 `.credentials-config.json.bak`(`ContentUtils.makeFileBackup`)도 같은 상태였다.
- 전역 네트워크 프로필, 프록시 사용자·비밀번호, Maven 저장소 인증은 작업 공간 세션의 비밀 컨트롤러 `LocalSecretController`가
  작업 공간 설정 파일(`.metadata/.plugins/org.eclipse.core.runtime/.settings/com.seizia.chromite.ui.swing.prefs`의 `//secrets/…`)에 평문으로 썼다.

사용자 결정(2026-11-07, A안 2단계): 먼저 연결 내보내기·가져오기(#108, [connection-transfer.md](connection-transfer.md))로 PC 사이에서 비밀번호를
옮길 길을 만들고, 그 다음 이 작업을 한다. AI 키는 #103에서 따로 옮겼다([ai-agent.md](ai-agent.md) "API 키 저장").

## 저장소 구조

| 항목 | 내용 |
|---|---|
| 저장소 | Eclipse secure storage + OS 제공자(`DBSecurityUtils.findNativeOSProvider`). 제공자를 필수 모듈(`IProviderHints.REQUIRED_MODULE_ID`)로 지정해, 값은 제공자가 지키는 마스터 비밀번호로 암호화된다 |
| Windows | `org.eclipse.equinox.security.win32`(`WindowsPasswordProvider64bit`): 마스터 비밀번호를 DPAPI(`CryptProtectData`, 현재 Windows 사용자)로 암호화해 같은 파일에 둔다 |
| macOS | `model.security`의 키체인 제공자(`MacOSSecureStorageKeychain`): 마스터 비밀번호를 키체인에 둔다. **Mac이 없어 실행해 보지 못했다.** 첫 접근 때 키체인 허용 질문이 뜰 수 있다 |
| 파일 | `<데이터 폴더>/secure/secure_storage`(Windows 기본 `%APPDATA%\ChromiteData\secure\secure_storage`, macOS `~/Library/ChromiteData/secure/secure_storage`). `-eclipse.keyring <파일>`이나 환경 변수 `ECLIPSE_KEYRING`이 있으면 그 파일. 옆의 `secure_storage.lock`은 다른 Chromite 창과의 잠금용(내용 없음) |
| 노드 | `/chromite/projects/<범위>/credentials-config<부 ID>.json` = 예전 파일과 같은 JSON(데이터 소스 ID → `#connection`/`network/<처리기>`/`profile:<ID>` → 속성) |
| | `/chromite/workspaces/<범위>/<비밀 ID>` = 작업 공간 비밀(예: `global/network-profile/<이름>`, `maven/<저장소>/auth-password`, `ui.proxy.user`) |
| 범위 | 폴더 실제 경로(Windows는 소문자)의 SHA-256 앞 32자. 프로젝트는 `<프로젝트>/.chromite`, 작업 공간은 `<작업 공간>/.metadata`. 그 폴더의 `credentials-storage.properties`에 범위 키와 이전 기록을 적는다(비밀 없음) |
| AI 키 | 그대로(#103): Eclipse 기본 파일(`~/.eclipse/org.eclipse.equinox.security/secure_storage`) 노드 `chromite` 바로 아래 |

코드:

| 번들 | 클래스 | 역할 |
|---|---|---|
| `model.security` | `ChromiteSecureStorage` (새) | 파일 위치, 제공자, 읽기·쓰기(쓴 뒤 flush하고 파일을 다시 읽어 비교), 노드 지우기, 범위와 상태 파일, 다른 프로세스 잠금 |
| `model.security` | `WorkspaceSecretController` (새) | 데스크톱 작업 공간 세션의 비밀 컨트롤러("전역" 컨트롤러). 예전 평문 값 이전 |
| `registry` | `ProjectCredentialsStorage` (새) | 프로젝트 자격 증명 읽기·저장, 예전 파일 이전, "읽지 못함" 표시 |
| `registry` | `DataSourceSerializerModern` | 데스크톱 프로젝트 파일 관리자(`DataSourceConfigurationManagerNIO`)일 때 자격 증명을 파일 대신 `ProjectCredentialsStorage`로 읽고 씀. 저장 실패를 예외로 올림 |
| `model.rcp` | `DesktopWorkspaceImpl` | 작업 공간 세션이 `WorkspaceSecretController`를 돌려줌. 내용과 함께 지운 프로젝트의 범위를 저장소에서 지움 |
| `model.rcp` | `CredentialStorageMigration` (새), `DesktopApplicationImpl` | 시작할 때(`beforeWorkspaceInitialization`, `LegacyNameMigration` 다음) 모든 프로젝트와 작업 공간 비밀 이전 |
| `model` | `LocalSecretController` | 평문 비밀 ID 목록(`getSecretIds`). 이제 예전 값을 읽고 지우는 데만 쓴다 |
| `model` | `BaseProjectImpl` | 예전 파일을 읽는 고정 키 암호기(`getLegacyCredentialsEncryptor`). `getValueEncryptor`는 그대로 |
| `ui.swing` | `ConnectionDialog`, `ConnectionTransferCommands` | 새 연결·가져온 연결의 저장 실패를 알림 |

`registry`와 `model.rcp`가 `com.seizia.chromite.model.security`를 `Require-Bundle`에 더했다(순환 없음: `model.security`는 `model`만 쓴다).

## 설계 결정

### 기존 "비밀 저장소" 경로(`isUseSecretStorage`)를 켜지 않은 이유

브리프는 `DesktopProjectImpl.isUseSecretStorage()`를 켜고 OS 컨트롤러를 붙이는 쪽을 먼저 보라고 했다. 확인한 결과:

- 데스크톱에서 `DBSSecretController.getProjectSecretController(project)`는 작업 공간 세션 컨트롤러이고, 그것은 `LocalSecretController`(평문
  설정 파일, "Doesn't encrypt values")였다. 켜기만 하면 고정 키 파일에서 평문 파일로 옮겨 가는 셈이었다.
- 그 경로는 CloudBeaver용이다. 켜면 자격 증명이 연결 객체에 지연 로드되고(`resolveSecretsIfNeeded`), `isCredentialsSaved`와 비밀번호 저장
  표시의 뜻이 바뀌고(`DataSourceSerializerModern` "savePasswordApplicable"), 데이터 소스마다 따로 저장되며 지우기는 컨트롤러의
  `deleteObjectSecrets`(기본 구현은 아무것도 안 함)에 맡긴다. Swing 연결 대화상자, 인증 질문(#106), 내보내기(#108)는 연결 객체의
  비밀번호를 바로 읽으므로 동작이 달라질 곳이 많다.

그래서 직렬화기·연결 객체의 동작은 그대로 두고 **예전 파일이 하던 저장소 자리만** OS 보안 저장소로 바꿨다. 같은 JSON을 값 하나로 둔다.
작업 공간 비밀은 같은 `DBSSecretController` 인터페이스 뒤에 OS 저장소 컨트롤러를 붙였다(기존 경로를 켠 것과 같은 방식).

### Chromite 전용 저장 파일

Equinox는 저장 파일을 처음 열 때 메모리로 읽고, flush 때 메모리 내용으로 파일 전체를 다시 쓴다. 다른 프로세스가 그 사이 파일을 바꿨으면
UI 콜백(`equinox.security.ui`)에 묻는데 Chromite에는 없어 로그만 남기고 덮어쓴다(`SecurePreferencesRoot.flush`). Eclipse 기본 파일을 쓰면
같은 사용자의 Eclipse IDE나 다른 Eclipse 기반 앱이 자기 사본으로 덮어써 연결 비밀번호가 조용히 사라질 수 있다. 그래서 데이터 폴더의
파일을 쓴다(`BaseApplicationImpl.SECURE_DATA_FOLDER = "secure"`, 지금까지 쓰이지 않던 상수). 시험·개발에서는 `-eclipse.keyring`이 그대로 통한다.

Chromite 창 여러 개(다른 작업 공간)는 같은 파일을 쓴다. 읽기·쓰기마다 `secure_storage.lock`을 잠그고, 파일이 이 JVM이 마지막으로 읽은
뒤 바뀌었으면 캐시를 버리고 다시 읽는다(내부 API `SecurePreferencesMapper.close`. 공개 API에는 다시 읽는 방법이 없다. 이 파일은
`ChromiteSecureStorage`만 열므로 버려진 캐시를 쥔 코드가 없다).

### 범위(scope)

OS 저장소는 OS 사용자 단위, 프로젝트 ID와 작업 공간 ID는 작업 공간을 복사하면 같다. ID로 나누면 복사한 두 작업 공간이 서로의 비밀번호를
덮어쓰거나 지운다. 그래서 폴더 경로로 나눈다. 폴더를 옮기거나 복사하면 경로가 달라지므로, 폴더의 `credentials-storage.properties`에 마지막
범위를 적어 두고 다르면 이전 범위의 값을 새 범위로 **복사**한다(옮긴 것인지 복사한 것인지 알 수 없어 원래 것은 남긴다). 다른 PC로 복사하면
그 PC의 저장소에는 이전 범위가 없어 비밀번호가 없다(아래 "작업 공간 복사").

### 저장 실패

- OS 제공자가 없거나 헤드리스 앱이면 `DBSecurityException`. 평문이나 고정 키로 대신 저장하지 않는다.
- 값을 쓴 뒤 `isEncrypted`로 암호화를 확인하고(아니면 지우고 예외), flush한 뒤 캐시를 버리고 파일을 다시 읽어 같은지 비교한다.
- 연결 저장: 직렬화기가 예외를 레지스트리의 마지막 오류로 올린다(예전에는 `saveSecureCredentialsFile`이 로그만 남겼다). 연결 편집은
  `persistConfiguration`이 "Save error" 대화상자를 보이고, 새 연결(`ConnectionDialog`)과 가져오기(`ConnectionTransferCommands`)는 저장 뒤 마지막
  오류를 확인해 알린다. 연결 설정 파일(`data-sources.json`)은 저장되고 비밀번호만 저장되지 않는다.
- 읽기 실패(저장소는 있지만 복호화 실패: 다른 PC·계정에서 가져온 파일, 손상): 그 프로젝트 파일을 "읽지 못함"으로 표시하고 이번 세션의
  저장을 거부한다. 비밀번호가 없는 메모리 상태로 저장하면 저장소의 비밀번호를 지우기 때문이다. 메시지가 저장 파일 경로와 해결 방법(다시
  시작, 다른 PC·계정의 파일이면 Chromite를 닫고 지움)을 알린다. 예전 파일이 남아 있으면 그것으로 읽기만 한다. 앱은 정상 시작한다(#103에서
  `Win32Exception`을 `DBSecurityException`으로 바꾼 것을 이어 씀).
- 작업 공간 비밀: 전역 네트워크 프로필 저장(`GlobalNetworkProfileManager.saveSettings`), 프록시(`GlobalProxyAuthenticator`), Maven(`MavenRegistry`)은
  예외를 로그로만 남긴다(upstream 그대로). Swing에는 이들을 편집하는 화면이 없어 사용자가 저장을 시작하는 곳이 없다(프록시 질문의 "저장"만
  예외: 실패는 로그에 남고 비밀번호는 저장되지 않는다).

## 이전

순서(프로젝트 파일 하나마다, `ProjectCredentialsStorage.load`):

1. 범위를 정한다(폴더가 옮겨졌으면 이전 범위의 값을 복사).
2. 저장소 값을 읽는다. 실패하면 "읽지 못함"(위)으로 끝.
3. 예전 파일이 없으면 끝. 있으면 고정 키로 풀어 저장소 값과 합친다. 같은 데이터 소스·노드는 **저장소 값이 이긴다**(이미 옮긴 뒤 예전 파일이
   다시 나타난 경우 그 뒤의 변경을 되돌리지 않게). 풀 수 없는 파일은 지우지 않고 그대로 둔다.
4. 합친 값을 쓰고 되읽어 확인한 **뒤에만** `credentials-config*.json`과 `.credentials-config*.json.bak`을 지우고, 상태 파일에
   `legacy.migrated.<파일>=<시각>`을 적는다. 쓰기가 실패하면 파일을 남기고 로그에 적는다.

작업 공간 비밀(`WorkspaceSecretController`): 평문 키(`secrets/…`)마다 저장소에 없으면 쓰고 되읽어 확인한 뒤 평문 키를 지우고 설정 파일을
저장한다. 저장소에 이미 있으면 그 값을 쓰고 평문만 지운다. 상태 파일에 `legacy.secrets.migrated`. 읽을 때도 같은 일을 한다.

언제: 시작할 때 `DesktopApplicationImpl.beforeWorkspaceInitialization`이 `LegacyNameMigration` 다음에 Eclipse 작업 공간의 모든 프로젝트와 작업 공간
비밀에 한 번 한다(열지 않은 프로젝트도). 연결 레지스트리가 읽을 때와 작업 공간 비밀을 읽을 때도 같은 일을 한다.

중간에 죽어도: 예전 파일과 평문 키는 새 저장소에 쓰고 되읽어 확인한 뒤에만 지우므로, 그 전에 죽으면 다음 시작이 같은 일을 다시 한다(합치기는
같은 결과). 상태 파일은 기록용이고 판단은 파일·키가 있는지로 한다. 그래서 상태 파일이 없어도 다시 하지 않고, 있어도 남은 파일은 옮긴다.

예전 파일은 백업 이름으로 남기지 않는다. 고정 키 사본은 파일을 가진 누구에게나 비밀번호를 준다. 지우는 것은 새 저장소의 사본을 되읽은 뒤이고,
PC를 옮길 때는 #108 내보내기가 있으므로 남길 이유가 없다.

`LegacyNameMigration`(이름 변경 전 `.dbeaver` → `.chromite`)과의 관계: 그쪽이 먼저 `credentials-config*.json`을 바이트 그대로 복사하고 표시 파일을
남긴다. 같은 시작에서 이 이전이 그 사본을 옮기고 지운다. 그쪽은 표시 파일이 있으면 다시 복사하지 않는다. 표시 파일이 없어(그쪽 복사에 실패가
있어) 다음 시작에 다시 복사해도 3번의 합치기가 저장소 값을 지킨다. `.dbeaver`의 원본은 지우지 않는다: 그 폴더는 이름 변경 전 빌드나 DBeaver가
쓰는 폴더이고 `LegacyNameMigration`의 계약도 "옛 데이터는 고치거나 지우지 않음"이다.

## 옮긴 것과 옮기지 않은 것

| 대상 | 결과 |
|---|---|
| 연결 사용자·비밀번호·인증 속성, SSH·프록시·SSL 처리기 비밀 | 옮김(프로젝트 범위) |
| 프로젝트 네트워크 프로필·인증 프로필의 비밀(같은 JSON) | 옮김 |
| 전역 네트워크 프로필, 프록시 사용자·비밀번호, Maven 저장소 인증 | 옮김(작업 공간 범위, `WorkspaceSecretController`) |
| AI 키 | #103에서 이미 OS 저장소(기본 파일). 남은 평문이 있으면 작업 공간 컨트롤러를 거쳐 AI 위치로 간다(`AIUtils` 주석) |
| 공유 자격 증명(`isSharedCredentials`), 팀 비밀 | 데스크톱에서는 쓰지 않는다(`isUseSecretStorage` false). 연결 객체의 값이 그대로 같은 JSON으로 저장된다 |
| `isUseSecretStorage` 경로(`DataSourceDescriptor`, `DataSourceRegistry`, `DataSourceParser`, `DesktopWorkspaceImpl.deleteProject`) | 바꾸지 않음(데스크톱에서 false) |
| `DataSourceConfigurationManagerBuffer`(내보내기·가져오기, RM), `DataSourceConfigurationManagerRM` | 바꾸지 않음. 버퍼는 "secure" 관리자라 자격 증명을 JSON 안에 둔다 |
| `getValueEncryptor()`/`getLocalSecretKey()`의 다른 사용처: 암호화 프로젝트 설정(`isEncryptedProject`), 네이티브 도구 작업의 `tool.user`/`tool.password`(`AbstractNativeToolSettings`, `tasks.json`) | **옮기지 않음**(아래 "남은 일"). 같은 고정 키다 |
| `DataSourceSyncUnit`(DataDam 프로젝트 동기화가 `credentials-config*.json`을 올리고 내림) | 바꾸지 않음. DataDam 번들(`model.datadam`)은 제품에 없다. 넣는다면 비밀번호가 동기화되지 않는다 |
| `DDKeyStore`·`DDSecretStorage`(DataDam) | 제품에 없음. 자기 노드(`datadam`)를 따로 쓴다 |
| 프록시의 예전 설정 키(`ui.proxy.user/password`, `SecuredPasswordEncrypter`) | 그대로(upstream 하위 호환 읽기, 저장하면 지운다) |
| 이전 전 작업 공간 위치의 사본(작업 공간 위치 이전이 복사하고 남긴 옛 작업 공간, `.dbeaver`) | 지우지 않음 |

## 작업 공간 복사

- 같은 PC·같은 OS 사용자: 복사한 작업 공간이 처음 열릴 때 범위가 다르므로 원래 범위의 값을 복사한다. 이후 두 사본은 따로 바뀐다.
- 다른 PC나 다른 사용자: 비밀번호가 따라가지 않는다(연결할 때 묻는다). 옮길 때는 File > Export Connections…의 비밀번호 포함 내보내기를 쓴다.
  내보내기 대화상자의 암호 안내에 이 사실을 한 문장 더했다. 따로 묻는 대화상자는 두지 않았다.
- 데이터 폴더(`ChromiteData`)째 복사하면 `secure/secure_storage`도 따라가지만 DPAPI가 다른 사용자·PC에서 풀지 못해 "읽지 못함"이 되고, 저장할 때
  해결 방법(그 파일 지우기)을 알린다.

## upstream 대비 변경점

- 데스크톱 연결 자격 증명이 `credentials-config*.json`(고정 키) 대신 OS 보안 저장소에 있다. 파일은 시작할 때 옮기고 지운다.
- 데스크톱 작업 공간 세션의 비밀 컨트롤러가 `LocalSecretController`(평문) 대신 `WorkspaceSecretController`(OS 보안 저장소)다.
- 자격 증명 저장 실패가 로그만이 아니라 사용자에게 보인다.
- 내용과 함께 지운 프로젝트의 비밀번호를 저장소에서도 지운다.

## 확인 (2026-11-07, Windows 11)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `python tools/rename/gate.py` ALL GATES PASSED.
회귀 테스트는 요청되지 않아 만들거나 실행하지 않았다.

빌드한 제품을 JBR 25로(`-vm …\jvm.dll`) 띄우고, `D:\Chromite-Test\workspace`(연결 5개: PostgreSQL, SQL Server, MariaDB, Oracle, Altibase, 모두 비밀번호
저장)의 사본을 `D:\Tools\_scratch\credentials-os-storage\ws-*`, 데이터 폴더를 `data-*`, 저장소를 `-eclipse.keyring …\keyring-*\secure_storage`로 썼다(사용자
홈의 Eclipse 저장소와 `%APPDATA%\ChromiteData\secure`는 생기지 않음을 확인). 조작은 자기 프로세스에 attach한 시험 에이전트가 모델 API로 했다(화면
입력 없음). 로그에는 비밀번호 대신 "있음/없음"과 SHA-256 앞 8자리만 남겼다.

| 시험 | 결과 |
|---|---|
| 1. 처음 시작 | 로그 "1 saved secret(s) of the workspace settings were moved", "Saved passwords of …\General\.chromite (credentials-config.json) were moved", 예전 파일과 `.bak` 지움. 5개 연결의 사용자·비밀번호 해시가 이전 전 파일(고정 키로 풀어 본 값)과 같고, 5개 모두 비밀번호를 묻지 않고 연결 |
| 2. 파일에 비밀 없음 | `credentials-config.json` 없음(고정 키로 풀 파일이 없음). 작업 공간 설정의 `secrets/` 줄 0. 작업 공간·데이터·keyring 폴더 전체에서 5개 비밀번호(UTF-8·UTF-16) 0건 |
| 3. 저장소에 암호문 | keyring 키 `/chromite/projects/<범위>/credentials-config.json`, `/chromite/workspaces/<범위>/maven/test-repo/auth-password`, 값은 `org.eclipse.equinox.security.WindowsPasswordProvider64bit` 모듈 표시 + 암호문 |
| 4. 바꾸고 다시 시작 | PostgreSQL 비밀번호를 바꿈(`persistConfiguration` true) → 프로세스 종료·재시작 → 새 값의 해시. 원래 값으로 되돌린 뒤 5개 연결 |
| 5. 이전 중 종료 | keyring 파일이 생기는 순간 종료(마스터 비밀번호만 쓰이고 연결 값 없음, 예전 파일 그대로) → 다시 시작하면 이전이 끝나고 5개 연결. "쓴 뒤 지우기 전"은 예전 파일을 다시 넣어 재현: 저장소 값(바꾼 PostgreSQL 비밀번호)이 이기고 파일은 지워짐 |
| 6. #108 | A에서 비밀번호 포함 내보내기(5개) → 자기 keyring의 빈 작업 공간 B에서 가져오기 → 해시 같음, 다시 시작 뒤에도 같음, 5개 연결(Altibase는 B에 드라이버 jar 경로를 넣은 뒤. 드라이버는 #108 설계대로 파일에 없다). B의 파일·keyring에 비밀번호 0건 |
| 7. 네트워크 프로필 | 프로젝트 프로필(SSH 처리기, 사용자·비밀번호)과 전역 프로필 → 다시 시작 뒤 같은 해시. `data-sources.json`·`network-profiles.json`에는 자격 증명 없음, 비밀은 각각 프로젝트·작업 공간 범위 |
| 작업 공간 복사 | A를 C로 복사하고 같은 keyring으로 실행 → "copied from its previous location"(프로젝트, 작업 공간) → 5개 연결, 프로필·Maven 비밀 같음 |
| 두 창 동시 | A와 C를 같이 띄우고 C가 PostgreSQL 비밀번호를, 그 뒤 A가 작업 공간 비밀을 저장 → 두 값 모두 파일에 남음(A의 flush가 C의 값을 지우지 않음), C를 다시 시작하면 바꾼 값 |
| 저장소 손상 | keyring의 DPAPI 마스터 비밀번호를 망가뜨림 → 앱 정상 시작, 연결 값 없음, 로그에 `Win32Exception` 원인. 비밀번호 저장 → "Save error" 대화상자(저장 파일 경로와 해결 방법), 파일·keyring에 새 비밀번호 0건 |

증거: `D:\Tools\_scratch\credentials-os-storage\evidence\`.

## 위험과 남은 일

- **macOS 미확인**: 키체인 제공자 경로는 실행해 보지 못했다. 데이터 폴더(`~/Library/ChromiteData/secure`)에 파일을 두는 것과 첫 접근 때의 허용 질문을 Mac에서 확인해야 한다.
- **Equinox 저장 파일 쓰기는 원자적이지 않다**: flush가 파일을 바로 덮어쓴다. 쓰는 도중 전원이 꺼지면 파일이 깨질 수 있다. 이전 중에는 예전 파일이 확인 뒤에만 지워지므로 잃지 않지만, 이전 뒤에는 그 파일이 유일한 사본이다(#108 내보내기가 백업 수단).
- **다른 PC·계정의 저장 파일**: 데이터 폴더째 옮기면 그 파일을 지워야 다시 저장할 수 있다(메시지가 안내).
- **네이티브 도구 작업 자격 증명**(`AbstractNativeToolSettings`의 `tool.password`)과 암호화 프로젝트 설정은 여전히 고정 키다. 작업 설정 파일 형식과 실행기를 함께 바꿔야 해서 이번 범위에서 뺐다.
- **헤드리스·CLI**: Chromite 제품에는 헤드리스 앱이 없다(`com.seizia.chromite.headless`는 시험 기능에만 있다). 헤드리스에서는 저장소를 쓰지 않고 비밀번호가 있는 저장은 실패한다. 그 시험 앱으로 도는 기존 테스트 중 비밀번호를 저장하는 것은 이제 실패할 수 있다(실행하지 않음).
- **예전 파일이 세션 중 다시 생기면**(외부 도구가 쓴 경우): 다음 저장이 합치지 않고 지운다. 시작·다시 읽기 때 나타난 파일은 합친다.
- **지운 연결의 비밀번호가 예전 파일과 함께 다시 나타나면** 합치기가 되살린다(비밀번호를 뺀 연결에 다시 들어감). 예전 빌드로 돌아갔다 오는 경우에만 생긴다.
- **AI 키는 Eclipse 기본 파일**에 남아 다른 Eclipse 기반 앱이 덮어쓸 수 있다(#103 결정, 바꾸지 않음).

## 변경 이력

- 2026-11-07 최초 작성: 연결 자격 증명과 작업 공간 비밀을 OS 보안 저장소로 옮김, 시작할 때 이전, 저장 실패 표시, 확인 결과
