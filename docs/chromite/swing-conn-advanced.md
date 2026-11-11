# Swing 고급 연결 설정 (conn-advanced)

## 목적

SWT 연결 편집기(`com.seizia.chromite.ui.editors.connection`, `ext.*.ui`의 연결 화면)에만 있던 고급 연결 설정을 Swing Connect 대화상자
([swing-frontend.md](swing-frontend.md) "Connect 대화상자 (SSMS식)")로 옮긴다. 우선순위는 다음과 같다.

1. 드라이버별 인증 방식과 인증 방식별 추가 칸
2. 네트워크 핸들러: SSH 터널, 프록시, SSL
3. DB별 주 양식 칸: Oracle(Basic/TNS, SID/Service), SQL Server(Encrypt, Trust server certificate)
4. 일반 설정: 연결 종류와 연결 색(쿼리 창 상태 줄·Registered Servers·Object Explorer), 연결 직후 SQL(bootstrap queries)
5. Test Connection 결과에 위 설정 반영

모델 계층(`DBPConnectionConfiguration`, `DBWHandlerConfiguration`, 인증 방식·핸들러 레지스트리)은 그대로 쓰고, SWT 화면의 load/save 규칙을 Swing 칸으로 옮겼다.
그래서 SWT 제품과 같은 연결 설정 파일(`.dbeaver/data-sources.json`, 비밀 정보는 `credentials-config.json`/보안 저장소)을 주고받을 수 있다.

## 구조

| 클래스 (`com.seizia.chromite.ui.swing`) | 역할 | 옮긴 SWT 클래스 |
|---|---|---|
| `ConnectionAuthExtras` (새로) | 인증 방식별 추가 칸(SQL Server NTLM integrated security, Oracle Role, PgPass 호스트 이름, BigQuery OAuth, Snowflake Role·Authenticator, Spanner 키 파일) | `SQLServerAuthConfigurator`, `OracleAuthDatabaseNativeConfigurator`, `PostgreAuthPgPassConfigurator`, `BigQueryAuthConfigurator`, `SnowflakeAuthSnowflakeConfigurator`, `SpannerAuthConfigurator` |
| `ConnectionDriverFields` (새로) | 드라이버별 주 양식 칸(SQL Server, Oracle, BigQuery, Snowflake, SAP HANA, Firebird Embedded, Mimer SQL) | `SQLServerConnectionPage`, `OracleConnectionPage`, `BQConnectionPage`, `SnowflakeConnectionPage`, `HANAConnectionPage`, `FireBirdEmbeddedConnectionPage`, `MimerConnectionSettingsPage` |
| `ConnectionNetworkPage` (새로) | Options의 핸들러 탭 하나("Use …" 확인란 + 설정 칸), 탭 목록 규칙 | `ConnectionPageNetworkHandler`, `ConnectionPageSettings.getDialogPages` |
| `NetworkHandlerPanel` (새로) | 핸들러 설정 칸의 공통 틀(load/save/오류)과 핸들러별 칸 고르기 | `IObjectPropertyConfigurator`, `UIPropertyConfiguratorRegistry` |
| `SshTunnelPanel` (새로) | SSH 터널 칸과 Test Tunnel | `SSHTunnelDefaultConfiguratorUI` |
| `SocksProxyPanel` (새로) | SOCKS 프록시 칸 | `SocksProxyConfiguratorUI` |
| `SslHandlerPanel` (새로) | PostgreSQL·MySQL/MariaDB·SQL Server·ClickHouse SSL 칸, Databend·TiDB Lake SSL 안내 | `SSLConfiguratorTrustStoreUI`, `PostgreSSLConfigurator`, `MySQLSSLConfigurator`, `SQLServerSSLConfigurator`, `ClickhouseSSLConfigurator`, `DatabendSSLConfigurator`, `TiDBLakeSSLConfigurator` |
| `ConnectionColors` (새로) | 연결 색(연결에 정한 색, 없으면 연결 종류 색) | `UIUtils.getConnectionColor`, `getConnectionTypeColor`, `getConnectionColorByRGB` |
| `BootstrapQueriesDialog` (새로) | 연결 직후 SQL 목록 편집 | `EditBootstrapQueriesDialog` |
| `ConnectionRestrictionsDialog` (새로, 2026-11-08) | 연결별 제한(연결 유형을 따름 / 이 연결만 정함) | `EditConnectionPermissionsDialog` |
| `ConnectionSettingsPanel` (변경) | 위 칸·탭을 주 양식과 Options에 넣고 읽기·저장 순서를 정함, 연결 설명, 연결별 제한 | `ConnectionPageGeneral`, `ConnectionPageInitialization` |
| `ConnectionDialog` (변경) | Test Connection 결과에 쓴 네트워크 핸들러 표시, 설명·제한만 바꾸면 다시 연결을 묻지 않음, 잠긴 연결의 잠금 비밀번호 확인 | `ConnectionWizard.testConnection`, `EditConnectionWizard.checkLockPassword` |
| `QueryStatusLine` (변경) | 쿼리 창 상태 줄 바탕 = 연결 색 | — |
| `ObjectExplorerPanel` (변경) | 연결 노드 이름의 알약 = 연결 색(`ConnectionPill`, 2026-11-06 노드 바탕에서 바꿈) | `DatabaseNavigatorLabelProvider`의 연결 색 |

`MANIFEST.MF`의 `Require-Bundle`에 UI 없는 모델 번들 `com.seizia.chromite.net.ssh`, `ext.mssql`, `ext.mysql`, `ext.oracle`, `ext.postgresql`을 더했다.
상수(속성 키, 핸들러 ID)와 모델 클래스(`SSHTunnelImpl`, `SSHUtils`, 인증 방식 클래스, `OCIUtils`)를 문자열로 베끼지 않고 그대로 쓰기 위해서다.
다섯 번들 모두 이미 Swing 피처(`com.seizia.chromite.runtime.feature`, `com.seizia.chromite.db.feature`)에 들어 있으므로 결과물의 번들 수는 바뀌지 않고, SWT·JFace 번들은 여전히 없다.
2026-11-08에 같은 이유로 `ext.bigquery`, `ext.clickhouse`, `ext.mimer`, `ext.snowflake`, `ext.spanner`를 더했다(모두 `com.seizia.chromite.db.feature`에 있음).
HANA·Firebird·Databend·TiDB Lake 번들은 공급자 클래스나 상수를 내보내지 않아 의존을 더하지 않고 `plugin.xml`의 ID 문자열을 상수로 둔다.

## 화면

```
Database type     [Oracle ▾]
Connection type   [Basic ▾]                 ← Oracle만 (Basic / TNS)
Server name       [localhost:1521   ▾]      ← TNS면 숨김
Database          [ORCLPDB1        ][Service name ▾]   ← Oracle만 SID/Service
Network alias     [ORCL            ▾]       ← TNS만
TNS names folder  [               ][Browse…]← TNS만
                                            (묶음 사이 20)
Authentication    [Username/password ▾]
Login / Password / Remember password
Role              [Normal ▾]                ← 인증 방식별 추가 칸 (Oracle)
Encrypt           [Optional ▾]              ← SQL Server만
                  [ ] Trust server certificate
Connection name   [...]
── Options >> ──
Connection Properties | SSH ✓ | SSL | Proxy | Driver Properties | Provider Properties | Additional Connection Parameters
```

핸들러 탭 순서는 레지스트리의 순서(`order`: SSH 1, SSL 2, Proxy 3)이고, 쓰는 핸들러의 탭에는 확인 아이콘이 붙는다.

## 1. 인증 방식

인증 방식 목록과 Login/Password 칸은 이미 `ConnectionAuthModels`가 했다("Connect 대화상자 (SSMS식)" → "인증 방식"). 이번에는 SWT 설정 화면이 범용 칸에 더하는 칸을 옮겼다.

| 인증 방식 | 칸 | 저장 위치 | 근거 |
|---|---|---|---|
| SQL Server NTLM (`sqlserver_ntlm`) | Use integrated security (기본 켬) | 드라이버 속성 `integratedSecurity`. 다른 방식이면 지움 | `SQLServerAuthConfigurator.saveSettings`/`resetSettings`, 모델 `SQLServerAuthModelNTLM`은 속성이 없으면 true |
| Oracle Username/password (`oracle_native`) | Role: Normal / SYSDBA / SYSOPER | 인증 속성 `oracle.logon-as`(소문자). 예전 공급자 속성 `internal-logon`과 같은 키의 공급자 속성은 지움 | `OracleAuthDatabaseNativeConfigurator`, 모델 `OracleAuthModelDatabaseNative`(인증 속성 → 공급자 속성 순으로 읽음) |
| PostgreSQL PgPass (`postgres_pgpass`) | Override host name for pgpass + 이름 | 공급자 속성 `overriddenUsername`(`PostgreConstants.PG_PASS_HOSTNAME`), 비밀번호 저장 켬 | `PostgreAuthPgPassConfigurator` |
| BigQuery (`google_bigquery`, 2026-11-08) | OAuth type(Service account / User account), Key file(Service account일 때만) | 드라이버 속성 `OAuthType`(0/1, 0이고 키가 없으면 넣지 않음), `OAuthPvtKeyPath`(User account이거나 비우면 지움) | `BigQueryAuthConfigurator` |
| Snowflake (`snowflake_snowflake`, 2026-11-08) | Role(입력), Authenticator(빈 값 / Snowflake / External browser (SSO), 다른 값 입력 가능) | 인증 속성 `role`, `authenticator`(비우면 지움). 예전 공급자 속성 `@…-role@`·`@…-authenticator@`에서도 읽고 저장할 때 지움 | `SnowflakeAuthSnowflakeConfigurator` |
| Spanner (`google_spanner`, 2026-11-08) | Key file(서비스 계정 키 JSON) | 공급자 속성 `PvtKeyPath`(비우면 지움) | `SpannerAuthConfigurator` |

- 칸은 그 인증 방식을 골랐을 때만 보인다. 인증 방식 클래스는 `instanceof`로 판단한다(SWT 등록도 클래스 계층으로 고른다).
- 주 양식에 칸이 생긴 공급자 속성(Oracle `oracle.logon-as`)은 Provider Properties 표에서 뺀다. 한 값을 두 곳에서 고치지 않게 하기 위해서다.
- 바뀌지 않은 연결을 열어 Save만 눌러도 "바뀜"(다시 연결 질문)이 되지 않도록, SWT가 쓰는 기본값(빈 인증 속성 맵, `null` 공급자 속성)은 없는 키에 새로 넣지 않는다.
- SWT와 다른 점(2026-11-08): Snowflake Authenticator 목록에 없는 저장값(예: `username_password_mfa`)을 SWT는 빈 칸으로 보였지만 여기서는 그 값을 그대로 보인다.
  Role을 비우면 SWT는 예전 값을 남겼지만 여기서는 지운다. Spanner 키 파일을 비우면 SWT는 빈 문자열을 남겨 공식 드라이버에 `credentials=""`가 넘어갔다(`SpannerDataSource`). 여기서는 속성을 지운다.

## 2. 네트워크 핸들러

### 탭 목록과 저장 (`ConnectionNetworkPage`)

- 목록: `NetworkHandlerRegistry.getDescriptors(driver)`(대체된 핸들러 제외, 드라이버의 `objectType`에 맞는 것, SSH는 작업 공간이 터널 기능을 허용할 때만).
  내장 DB 드라이버는 `enableNetworkParameters`가 없으면, `disableNetworkParameters` 드라이버는 탭이 없다. 드라이버 대체가 지원하지 않는 핸들러도 뺀다.
  ([ConnectionPageSettings.java:1046-1056](../../plugins/com.seizia.chromite.ui.editors.connection/src/com/seizia/chromite/ui/dialogs/connection/ConnectionPageSettings.java)와 같은 조건)
- Swing 칸이 있는 핸들러만 탭을 만든다: SSH(`SSHTunnelImpl`), Proxy(`SocksProxyImpl`), SSL(`postgre_ssl`, `mysql_ssl`, `mssql_ssl`, 2026-11-08부터 `clickhouse-ssl`, `databend-ssl`, `tidblake-ssl`).
  SWT도 설정 화면이 없는 핸들러는 탭을 만들지 않는다.
- Databend·TiDB Lake SSL 핸들러는 `plugin.xml`에 `desktop="false"`가 붙어 있다. 그래도 SWT는 탭을 보였다: `ConnectionPageSettings.getDialogPages`는 이 값을 보지 않고,
  `isDesktopHandler`를 부르는 곳이 SWT·Swing 모두 없다(`NetworkHandlerDescriptor.java:185`만 있음). 그래서 Swing도 탭을 보인다.
- SSL 칸은 핸들러 ID로 고른다. Databend와 TiDB Lake는 같은 구현 `SSLHandlerImpl`을 쓰는데, SWT의 `UIPropertyConfiguratorRegistry`는 구현 클래스 이름을 키로 한
  `HashMap`이라(`descriptors.put(descriptor.getObjectType(), …)`) 나중에 읽힌 화면 하나가 두 DB에 모두 나왔다(코드로 판단, SWT 실행 확인 안 함).
- 읽기: 연결의 핸들러 설정(없으면 새 설정)을 칸에 채우고, "Use SSH"/"Use SSL"/"Use Proxy" 확인란은 그 설정의 `enabled`다. 확인란을 끄면 칸이 꺼진다.
  확인란 이름은 핸들러의 짧은 이름(`codeName`)이다. 핸들러의 `label`은 모델 번들이 OS 언어로 바꿔(예: "SSH 터널") 영어 화면에 섞이기 때문이다.
- 저장: 켜져 있으면 연결의 핸들러 설정 사본에 칸 값을 쓰고 `updateHandler`, 꺼져 있으면 `removeHandler`
  ([ConnectionPageNetworkHandler.java](../../plugins/com.seizia.chromite.ui.editors.connection/src/com/seizia/chromite/ui/dialogs/connection/ConnectionPageNetworkHandler.java) `saveSettings`).
  연결 레지스트리도 꺼진 핸들러는 파일에 쓰지 않는다([DataSourceSerializerModern.java](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/DataSourceSerializerModern.java) "Save network handlers' configurations").
- 비밀번호: 핸들러 비밀번호(`setPassword`)와 비밀 속성(`setSecureProperty`)에만 넣는다. 레지스트리가 저장할 때 이 값들을 `SecureCredentials`로 묶어
  OS 보안 저장소에 쓴다(2026-11-07부터, [credential-storage.md](credential-storage.md). 그 전에는 고정 키로 암호화한 `credentials-config.json`)
  ([DataSourceParser.java](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/DataSourceParser.java) `saveNetworkHandlerConfiguration`). 설정 파일·환경 설정에는 평문으로 남지 않는다.
- 켜진 핸들러의 칸이 비어 있으면(`getErrorMessage`) 대화상자 아래 빠진 값 안내에 `SSH: Enter the SSH host.`처럼 보이고 Test·Connect가 꺼진다(SWT `isPageComplete`).
- 네트워크 프로필: 연결이 프로필을 쓰고 그 프로필에 이 핸들러가 켜져 있으면 칸을 끄고 프로필 이름을 보인다. 이때 연결의 핸들러 설정은 건드리지 않는다.
  프로필 고르기와 편집은 아래 "네트워크 프로필"(2026-11-08).

### SSH (`SshTunnelPanel`)

- 칸: SSH host + Port(22), User name(새 터널은 OS 사용자 이름), Authentication(Password / Public key / Agent, `SSHConstants.AuthType` 순서),
  Private key(파일 + Browse…, 공개 키일 때), Password 또는 Passphrase, Remember password(새 터널은 켬), Agent socket(Agent이고 Windows가 아닐 때).
- 고급: Implementation(SSHJ 기본, `SSHSessionControllerRegistry`), Keep-alive interval(ms), Connect timeout(ms, 기본 10000), Local host/port, Remote host/port,
  Bypass host key verification, Share this tunnel with other connections.
- 읽기·쓰기: 대상 호스트는 `SSHUtils.loadHostConfigurations`/`saveHostConfigurations`. 공개 키는 데스크톱 SWT처럼 키 파일 경로(`KeyFile`)로 저장한다.
  고급 값의 저장 규칙(0 이하는 지움, 기본 시간 초과는 저장하지 않음)은 `SSHTunnelDefaultConfiguratorUI.saveSettings`와 같다.
- 점프 서버: 아래 "SSH 점프 서버"(2026-11-08). 그 전에는 읽은 점프 서버를 그대로 다시 저장하고 개수만 안내했다.
- Test Tunnel: 폼 값으로 만든 핸들러 설정(변수 풀기, DB 없음)으로 모델의 `SSHTunnelImpl`을 진행 대화상자에서 열었다 닫고 SSH 클라이언트·서버 버전을 보인다
  (`testTunnelConnection`과 같음). 저장하지 않는 비밀번호도 시험에는 쓴다.

### SSH 터널의 로컬 주소 (모델 수정)

SSH 시험 직후 Windows 방화벽이 앱(`chromite-swing.exe`)의 네트워크 허용 여부를 묻는 창(`PickerHost.exe FirewallNotificationDialogServer`)을 띄웠다.
방화벽은 앱이 루프백이 아닌 주소에서 들으려 할 때 묻는다. 원인은 모델 코드 두 곳이다(SWT 제품도 같다).

- **빈 포트 찾기**: `SSHTunnelImpl.loadPortForwardConfiguration`이 SSH 서버에 연결하기 **전에** `SSHUtils.findFreePort` → `IOUtils.findFreePort`
  (`deps/dbeaver-common`의 `org.jkiss.utils.IOUtils`, 191-207행)를 부르는데, 이 함수는 `new ServerSocket(port)`로 시험한다. 이 생성자는 모든 인터페이스(0.0.0.0)에서 듣는다.
  그래서 SSH 서버가 없어 연결이 거부되어도 잠깐 0.0.0.0에서 듣고 방화벽 창이 뜬다.
- **빈 로컬 주소**: 로컬 주소(`localHost`) 설정이 비어 있으면 빈 문자열이 포워더로 간다. SSHJ(`SSHJSession.LocalPortListener`)는 `InetAddress.getByName("")` = 루프백으로 듣지만,
  JSch는 빈 주소를 0.0.0.0으로 바꾼다(`com.jcraft.jsch.PortWatcher.normalize`, jsch 2.28.7 바이트코드에서 확인: `""`·`"*"` → `"0.0.0.0"`).
  즉 구현을 JSch로 고른 터널은 다른 컴퓨터에서도 접속할 수 있는 포트를 열었다. 설정 화면의 설명("비우면 127.0.0.1")과도 달랐다.

고친 것(`com.seizia.chromite.net.ssh`, upstream 파일에 Chromite 수정 표시):

- `SSHTunnelImpl.loadPortForwardConfiguration`: 비어 있는 로컬 주소를 `DBConstants.HOST_LOCALHOST_IP`(127.0.0.1)로 바꾼다. 점프 서버 포워드가 이미 쓰는 값과 같다(`AbstractSessionController`).
- `SSHUtils.findFreePort(localHost)`: 포워더가 들을 주소에서만 `ServerSocket(port, 0, address)`로 시험한다. `IOUtils`(`deps/`)는 고치지 않았다(`deps/`는 수정하지 않는 사본).
- 확인: 같은 방식으로 연 소켓(`new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))`)을 `netstat -ano`로 보면 `TCP 127.0.0.1:54187 … LISTENING`이다.
  고친 빌드로 앱을 다시 열었을 때 그 프로세스의 LISTENING 줄은 없었다(터널을 열지 않은 상태). 0.0.0.0 쪽 재현은 같은 방화벽 창을 또 띄우므로 하지 않았다
  (`ServerSocket(int)`이 와일드카드 주소에서 듣는 것은 Java API 동작).
- 이미 떠 있는 방화벽 창은 사용자가 정할 일이라(허용·취소 모두 방화벽 규칙을 바꿈) 건드리지 않았다.

### SSH 점프 서버 (2026-11-08, `SshJumpServersDialog`)

점프 서버는 대상 SSH 호스트 앞에 차례로 거치는 SSH 호스트다. 모델은 [점프 서버…, 대상 호스트] 순서의 `SSHHostConfiguration` 배열로 저장한다
(`SSHUtils.saveHostConfigurations`, SWT `SSHTunnelDefaultConfiguratorUI`와 같은 값).

- SSH 탭의 **Jump servers** 줄: 없음 / "N개, 첫 서버: 사용자@호스트:포트"와 **Edit…** 단추.
- 대화상자: 목록(거치는 순서, 첫 줄이 먼저), Add… / Edit… / Remove / Move Up / Move Down, OK / Cancel(Cancel은 아무것도 바꾸지 않음).
- 서버 하나: 호스트, 포트, 사용자, 인증(Password / Public key / Agent), 키 파일, 비밀번호 또는 키 암호, Remember password. 대상 호스트 칸과 같은 규칙이고
  비밀번호는 핸들러 설정과 함께 레지스트리가 보안 저장소에 둔다. 서버 제품이 저장한 키 내용(`KeyData`)은 키 파일을 고르지 않으면 그대로 둔다.
- Test Tunnel은 폼의 점프 서버까지 넣은 설정으로 시험한다(`saveSettings`를 그대로 쓴다).

### SSH 호스트 키 확인 (2026-11-08, 후속 29 a)

"호스트 키 확인 건너뛰기"를 끈 연결이 처음 보는 호스트 키를 묻지 않고 받아들였다(후속 확인 작업에서 `127.0.0.1:52222`의 서버가 질문 없이 연결됨).

- **근본 원인 1(모델 `net.ssh.sshj` `KnownHostsVerifier.verify`):** 호스트 이름이 `localhost`나 `127.0.0.1`이면 known_hosts를 보지 않고 `true`를 돌려주었다.
  upstream이 넣은 예외로, 점프 서버 뒤의 서버가 앞 서버가 연 로컬 포워드(`127.0.0.1:임의 포트`)로 열리기 때문으로 보인다. 그래서
  ① 이 PC나 Docker가 `127.0.0.1`로 내놓은 SSH 서버는 키를 묻지도, 바뀐 키를 거절하지도 않았고 ② **점프 서버 뒤의 모든 서버**가 키 확인 없이 연결됐다.
  `findExistingAlgorithms`는 `localhost`를 실제 호스트로 바꾸려 했으나, 실제 호스트 설정 자리에도 포워드 주소가 들어 있어 바뀌는 것이 없었다.
- **근본 원인 2(모델 `net.ssh` `AbstractSessionController.JumpSession.connect`):** 점프 서버 뒤의 서버를 포워드 주소만 가진 `SSHHostConfiguration`으로 열어
  서버의 이름이 사라졌다. JSch는 호스트 키 별칭을 `destination.hostname()`(=`127.0.0.1`)으로 두어, 점프 서버 뒤의 서버가 모두 `127.0.0.1` 한 이름으로 확인됐다
  (두 번째 서버부터는 "키가 바뀜"으로 거절).
- **고친 것:**
  - `SSHHostConfiguration`에 키 이름(`hostKeyHostname`, `hostKeyPort`)을 더했다. 직접 연결은 소켓 주소와 같고(4개 인자 생성자), 점프 서버 뒤의 서버는
    `forwardedTo(로컬 주소, 포트)`로 소켓만 포워드로 가고 키 이름은 원래 서버의 이름·포트를 가진다(OpenSSH ProxyJump와 같음).
  - `KnownHostsVerifier`: localhost 예외를 지우고 늘 키 이름으로 known_hosts를 찾는다(`verify`, `findExistingAlgorithms`). 처음 보는 키는 질문
    (`confirmAction`, 경고, 기본 단추 "아니요"), 바뀐 키는 경고만 보이고 거절(upstream과 같음).
  - 질문과 경고의 지문을 MD5(`SecurityUtils.getFingerprint`) 대신 OpenSSH 기본(`ssh-keygen -l`)과 같은 `SHA256:…`으로 보인다(`getFingerprint`).
    서버 관리자가 알려 주는 지문과 바로 비교할 수 있게 하려는 것이다. JSch 질문은 JSch가 이미 SHA-256으로 보인다.
  - `JSCHSessionController`: 호스트 키 별칭을 `hostKeyHostname`으로.
  - SSHJ 질문 문구의 한국어(`SSHJUIMessages_ko.properties`)를 더했다(전에는 한국어 화면에서도 영어).
- **같은 원인의 다른 경로:** 터널 열기(연결), Test Tunnel 단추, 점프 서버는 모두 `AbstractSessionController.acquireSession` → 구현의 `createNewSession`을 지나
  이 수정이 모두에 걸린다. 공유 터널은 처음 열 때 확인한다. 건너뛰기를 켠 연결과 headless 앱은 그대로 확인하지 않는다(`PromiscuousVerifier`, 사용자가 고른 것).
- **known_hosts:** 저장은 SWT와 같은 파일(`SSHUtils.getKnownSshHostsFileOrDefault`, 기본 `~/.ssh/known_hosts`, 환경 설정 `org.eclipse.jsch.core/SSH2HOME`).
  SSHJ는 그 뒤에 `~/.ssh/known_hosts`·`known_hosts2`를 읽기만 하는 검사기로 더 본다(키 이름 아닌 소켓 주소로 찾으므로 점프 서버 뒤의 서버에는 맞는 줄이 없다).
- 결정: JSch 별칭은 upstream처럼 포트 없이 이름만 쓴다(직접 연결과 같음). 그래서 JSch와 SSHJ가 포트가 22가 아닌 서버를 known_hosts에 다른 이름
  (`127.0.0.1` / `[127.0.0.1]:52322`)으로 적는다. 묻거나 거절하는 쪽으로만 다르므로 바꾸지 않았다.

### Proxy (`SocksProxyPanel`)

- Proxy host, Port(1080), User name, Password, Remember password(끄면 비밀번호 칸이 꺼짐). 키는 `SocksConstants.PROP_HOST`/`PROP_PORT`, 사용자·비밀번호는 핸들러의 것.
- 모델의 프록시 구현은 SOCKS4/5만 지원한다(`SocksProxyImpl`). HTTP 프록시는 모델에 구현이 없어 고를 수 없다.

### SSL (`SslHandlerPanel`)

| 핸들러 | 칸 | 속성 |
|---|---|---|
| PostgreSQL `postgre_ssl` | CA certificate, Client certificate, Client private key(파일 + Browse…), SSL mode(Driver default/disable/allow/prefer/require/verify-ca/verify-full), SSL factory | `ssl.ca.cert`, `ssl.client.cert`, `ssl.client.key`, `ssl.method=CERTIFICATES`, `sslMode`, `sslFactory` |
| MySQL/MariaDB `mysql_ssl` | 위 세 파일, Cipher suites, Require SSL, Verify server certificate(기본 켬), Allow public key retrieval | 위 세 키, `ssl.cipher.suites`, `ssl.require`, `ssl.verify.server`, `ssl.public.key.retrieve` |
| SQL Server `mssql_ssl` | Keystore(파일), Keystore password, Certificate host name | `ssl.keystore`, 핸들러 비밀번호(저장 켬), `ssl.method=KEYSTORE`, `sslKeyStoreHostname` |
| ClickHouse `clickhouse-ssl` (2026-11-08) | CA certificate, Client certificate, Client private key(파일 + Browse…), SSL mode(STRICT / NONE) | `ssl.ca.cert`, `ssl.client.cert`, `ssl.client.key`, `ssl.method=CERTIFICATES`, `ssl.mode` |
| Databend `databend-ssl`, TiDB Lake `tidblake-ssl` (2026-11-08) | 칸 없음, 안내 한 줄 | 핸들러가 켜져 있으면 모델이 `sslmode=enable`을 넣는다(`DatabendDataSource`·`TiDBLakeDataSource`의 `initSSL`) |

- 방법 속성(`ssl.method`)이 없는 예전 설정은 SWT처럼 예전 키(`rootCert` 등, `sslKeyStore`)에서 읽는다. 저장은 새 키로 한다.
- ClickHouse: 모델은 공식 드라이버(`com_clickhouse`)일 때 경로와 모드를 드라이버의 `Properties`에 그대로 넣는다. `Properties`는 `null`을 받지 않으므로
  SWT처럼 모드는 늘 저장하고 경로는 빈 문자열도 저장한다.
- TiDB Lake: SWT 화면(`TiDBLakeSSLConfigurator`)에는 클라이언트 인증서·키 칸이 있었지만 모델이 읽지 않는다(`TiDBLakeDataSource`는 `sslmode=enable`만 넣음). 효과 없는 칸이라 옮기지 않았다.
- PostgreSQL "Use proxy server (e.g. stunnel)"(`sslProxyServer`): SWT도 칸을 숨겼다(`PostgreSSLConfigurator.ENABLE_PROXY = false`). 켜져 있으면 모델이
  SSL 설정을 하나도 넘기지 않는다(`PostgreDataSource.getInternalConnectionProperties` → 빈 `initProxySSL`). 그래서 새로 켜는 칸은 두지 않고, 저장된 값이 켜져 있을 때만
  확인란을 보여 끌 수 있게 했다(보이지 않는 값이 SSL 탭 전체를 무시하게 두지 않으려고). 끄면 속성을 지운다. 값이 없던 연결은 그대로 없다.
- 데스크톱 경로만 옮겼다. 분산 제품용 `.value` 비밀 속성(인증서 내용 자체)은 쓰지 않는다(SWT도 데스크톱에서는 경로를 일반 속성에 둔다).
- PostgreSQL SSL factory: SWT는 드라이버 jar에서 `SSLSocketFactory` 구현을 찾아 콤보에 넣지만 여기서는 클래스 이름을 직접 입력한다.

## 3. DB별 주 양식 칸 (`ConnectionDriverFields`)

범용 양식은 그대로 두고 정해진 자리(Server name 앞, Database 칸 옆, 주소 묶음 끝, 인증 묶음 끝)에 칸을 더한다.

### SQL Server (Microsoft 드라이버, `SQLServerUtils.isDriverSqlServer`)

- **Encrypt**: Optional / Mandatory / Strict (TDS 8.0) → 드라이버 속성 `encrypt` = 지움 / `true` / `strict`. SQL Server 모델은 이 속성이 비어 있으면 `false`로,
  SSL 탭이 켜져 있으면 `true`로 연결한다([SQLServerDataSource.java:235-253](../../plugins/com.seizia.chromite.ext.mssql/src/com/seizia/chromite/ext/mssql/model/SQLServerDataSource.java)).
  SWT 화면에는 없고 Driver Properties 표에서만 고치던 값이다. 표보다 뒤에 저장하므로 둘 다 고치면 주 양식 값이 이긴다.
- **Trust server certificate**: 공급자 속성 `sslTrustServerCertificate`(SWT `SQLServerConnectionPage`와 같음). Provider Properties 표에서는 뺐다.
- **명명된 인스턴스**: Server name에 `호스트\인스턴스`를 쓰면 따로 처리하지 않아도 된다. 공급자가 기본 포트(1433)를 URL에 넣지 않으므로
  ([SQLServerDataSourceProvider.java:70-74](../../plugins/com.seizia.chromite.ext.mssql/src/com/seizia/chromite/ext/mssql/SQLServerDataSourceProvider.java)) 포트를 적지 않으면
  `serverName=호스트\인스턴스`가 되어 드라이버가 인스턴스 이름으로 찾아간다. 실제 명명된 인스턴스 연결은 시험 환경에 없어 확인하지 못했다.

### Oracle (`OracleDataSourceProvider`)

- **Connection type**: Basic / TNS → 공급자 속성 `@dbeaver-connection-type@`(= `BASIC`/`TNS`). Additional Connection Parameters에서 JDBC URL로 연결하면 `CUSTOM`으로 저장한다
  (SWT의 Custom 탭 = URL 직접 입력, 설정 종류 URL). URL은 공급자의 `getConnectionURL`이 이 속성으로 만든다.
- **Basic**: 범용 Server name·Database에 SID / Service name 콤보(공급자 속성 `@dbeaver-sid-service@`, 없으면 Service name. 모델도 SID가 아니면 서비스 이름 URL을 만든다). Provider Properties 표의 "Service type" 줄은 뺐다.
- **TNS**: Server name·Database 줄을 숨기고 Network alias(DB 이름에 저장)와 TNS names folder(공급자 속성 `@dbeaver-tns-path@`, tnsnames.ora가 있는 폴더)를 보인다.
  폴더를 비우면 기본 Oracle 홈, 그다음 `TNS_ADMIN` 환경 변수의 tnsnames.ora를 읽는다(`OCIUtils.readTnsNames`, SWT `getAvailableServiceNames`에서 클라이언트 홈 선택을 뺀 것).
- Oracle 클라이언트(OCI) 홈 선택은 SWT에서도 상용 기능에서만 보이는 칸이라 옮기지 않았다.

### Oracle 세션 설정 (2026-11-08)

SWT `OracleConnectionExtraPage`의 "Session settings" 묶음(D-db-specific O5)을 Options의 **Session Settings / 세션 설정** 탭(Connection Properties 다음)으로 옮겼다.

| 칸 | 공급자 속성(SWT와 같음) | 연결 때 모델이 실행 |
|---|---|---|
| Language / 언어 (콤보, `OracleLanguage` 목록, 입력 가능) | `@chromite-session-language@` | `ALTER SESSION SET NLS_LANGUAGE = '…'` |
| Territory / 지역 (콤보, `OracleTerritory` 목록, 입력 가능) | `@chromite-session-territory@` | `NLS_TERRITORY` |
| Date format / 날짜 형식 | `@chromite-session-nls-date-format@` | `NLS_DATE_FORMAT` |
| Timestamp format / 타임스탬프 형식 | `session-nls-timestamp-format` | `NLS_TIMESTAMP_FORMAT` |
| Length semantics / 길이 의미 (콤보 BYTE/CHAR, 입력 가능) | `session-nls-length-format` | `NLS_LENGTH_SEMANTICS` |
| Currency symbol / 통화 기호 | `session-nls-currency-format` | `NLS_CURRENCY` |

- 적용은 모델 그대로다(`OracleDataSource.initializeContextState`, 모든 실행 문맥). 기본값(콤보 첫 항목 "Default/기본값")이나 빈 칸이면 속성을 지운다(SWT `saveSettings`와 같음).
- 이 속성들은 `plugin.xml`의 `<provider-properties>`에 선언되지 않아 Provider Properties 표에 나오지 않았다. 그래서 전에는 이전된 연결에 저장된 값이 보이지 않은 채 적용됐다.
  표에 선언하지 않고 탭으로 둔 이유: SWT처럼 언어·지역 목록 콤보가 필요하고, 표 선언은 목록(46·76개)을 `plugin.xml`에 다시 적어야 한다.
- 고리: `ConnectionDriverFields#getOptionsPage`(드라이버별 Options 탭, 기본 없음)를 더하고 `ConnectionSettingsPanel`이 Connection Properties 다음에 넣는다.
- 값에 작은따옴표가 있으면 모델이 그대로 `'…'` 안에 넣어 ALTER SESSION이 실패한다(모델 동작, 형식 문자열은 큰따옴표로 글자를 쓴다).
- 확인(2026-11-08, Oracle Free 23ai): 지역 KOREA, 날짜 형식 `YYYY-MM-DD HH24:MI:SS`, 통화 KRW로 저장 → 다시 연결 →
  `SELECT TO_CHAR(SYSDATE), TO_CHAR(SYSTIMESTAMP), TO_CHAR(1234.5, 'L9999.99'), NLS_TERRITORY`가 `07-NOV-26 | 07-NOV-26 03.49.35… PM +00:00 | $1234.50 | AMERICA`에서
  `2026-11-07 15:50:10 | 26/11/07 15:50:10.658957 +00:00 | KRW1234.50 | KOREA`로 바뀜. 다시 열면 저장한 값이 보이고, 기본값으로 되돌려 저장하면
  `data-sources.json`에서 세 속성이 지워짐. 영어·한국어, 라이트·다크(`D:\Tools\_scratch\p3-oracle-altibase-admin\shots\` `ora-en-nls-tab.png`, `ora-ko-dark-nls-tab.png`).

### BigQuery, Snowflake, SAP HANA, Firebird Embedded, Mimer SQL (2026-11-08)

| DB | 칸 | 저장 위치 | 근거 |
|---|---|---|---|
| BigQuery | Project(Server name 앞, 필수) | DB 이름. 모델이 드라이버 속성 `ProjectId`로 넘김(`BQDataSource.getInternalConnectionProperties`) | 예시 URL `jdbc:bigquery://{host}:{port}`에 `{database}`가 없어 범용 양식으로는 넣을 수 없었다. SWT `BQConnectionPage` |
| Snowflake | Warehouse, Schema | 서버 이름, 내부 공급자 속성 `@…-schema@`(SWT와 같음) | 아래 |
| SAP HANA | Edition, Instance number, Tenant database, 포트 안내 | 공급자 속성 `edition`·`instanceNumber`, 드라이버 속성 `databaseName`, 연결 포트 | 아래 |
| Firebird Embedded(`jaybird_embedded`만) | Native library folder(폴더 Browse…) | 공급자 속성 `nativeLibraryPath`(비우면 지움). 공급자가 연결 전에 `jna.library.path`에 더함 | SWT `FireBirdEmbeddedConnectionPage` |
| Mimer SQL | Protocol(TCP/IP / Local (shared memory)), Program, Program password | 공급자 속성 `protocol`(tcp 기본은 없는 키에 넣지 않음), 드라이버 속성 `program`, 인증 속성 `programPwd`(보안 저장소) | SWT `MimerConnectionSettingsPage` |

- **Snowflake**: 모델은 창고를 서버 이름 → 공급자 속성 `warehouse` 순서로, 스키마를 `@…-schema@` → `schema` 순서로 읽는다(`SnowflakeUtils.getWarehouse`,
  `SnowflakeDataSourceProvider.getConnectionURL`). 예전 Swing 표에는 뒤의 두 키(`warehouse`, `schema`)만 선언되어 있어, SWT가 쓴 값(서버 이름, `@…-schema@`)은 보이지 않은 채 이겼다.
  그래서 두 표 줄을 빼고 모델이 실제로 쓰는 값을 칸에 보인다. 저장은 값이 모델이 쓰는 값과 다를 때만 SWT 자리에 쓰고 대체 키를 지운다
  (바뀌지 않은 연결은 그대로, 고친 값은 대체 키에 가려지지 않음). SWT의 "Test Connection 뒤 DB·창고·스키마 목록 채우기"는 옮기지 않았다.
- **SAP HANA**: 모델은 `edition`·`instanceNumber`를 읽지 않는다(검색 0건, 예전 표의 Instance Number 줄은 효과가 없었다). SWT처럼 대화상자가 값을 정한다.
  플랫폼 에디션은 인스턴스 번호로 포트 3NN15(단일 DB)·3NN13(시스템·테넌트 DB), Express는 인스턴스 90·포트 39015(다른 포트를 적었으면 그대로),
  Cloud는 443이다. 이때 Server name에 적은 포트 대신 그 포트를 쓰고, 칸 아래에 "포트 N에 연결합니다"를 보인다. 테넌트 DB는 드라이버 속성 `databaseName`(대소문자 무관하게 하나만 남김).
  인스턴스 번호가 0–99가 아니거나 테넌트 DB가 비면 Test·Connect가 꺼진다(SWT `isComplete`). 공급자 속성이 모두 칸으로 옮겨져 Provider Properties 탭은 없다.
- 공급자 클래스를 내보내지 않는 HANA·Firebird는 `plugin.xml`의 공급자 ID(`hana`, `jaybird`)와 드라이버 ID(`jaybird_embedded`)로 고른다.
- Spanner는 예시 URL에 자리 표시가 없어 Swing에서 JDBC URL로만 연결한다(키 파일은 인증 칸, 위 1절). SWT의 Project·Instance·Database 칸은 옮기지 않았다.

## 셸 명령 (2026-11-08, `ConnectionShellCommandsPage`)

Options의 **Shell Commands** 탭. SWT `ConnectionPageShellCommands`와 같은 값(`DBPConnectionConfiguration.setEvent`, `DBRShellCommand`)이다.

| 칸 | 값 |
|---|---|
| 이벤트 목록과 확인란 | 연결 전·후, 끊기 전·후(`DBPConnectionEventType`). 확인란이 그 이벤트의 명령을 켠다. 쓰는 이벤트가 있으면 탭에 확인 표시 |
| Command | 명령(여러 줄). 도구 설명에 쓸 수 있는 변수(`${host}` 등, `DBPConnectionConfiguration.INTERNAL_CONNECT_VARIABLES`) |
| Working folder | 작업 폴더(폴더 고르기) |
| Wait until the process ends / Time limit (ms) | 끝날 때까지 기다리기, 시간 제한(-1 = 제한 없음) |
| Stop the process on disconnect | 연결을 끊을 때 아직 실행 중인 프로세스를 끝냄 |
| Pause after it starts (ms) | 시작 뒤 멈춤 |
| Clear | 그 이벤트의 명령을 지움 |

- **승인(#99)은 그대로다**: 여기서 입력하거나 고친 명령도 실행할 때 승인 대화상자([shell-command-approval.md](shell-command-approval.md))를 거친다.
  SWT는 이 화면에서 새로 입력한 명령을 승인 기록에 바로 넣었지만 Chromite는 넣지 않는다. 승인은 실행 시점 한 곳에서만 받는다(Run Once / Always Allow / Cancel).
- SWT의 "프로세스 패널 보이기"는 Swing에 프로세스 보기가 없어 칸이 없다(저장된 값은 그대로 둔다).
- 바뀌지 않은 이벤트는 다시 쓰지 않아, 고치지 않은 연결을 Save해도 "바뀜"이 되지 않는다.

## 네트워크 프로필 (2026-11-08, `NetworkProfilesDialog`)

네트워크 프로필은 여러 연결이 함께 쓰는 SSH 터널·프록시 설정 묶음이다. SWT `PrefPageNetworkProfiles`(전역·프로젝트 페이지)와 같은 모델 동작이다.

- **편집**: Tools > Network Profiles…, 또는 연결 대화상자 Connection Properties의 Network profile 옆 **Manage…**.
  - 범위: Global(작업 공간 전체, `network-profiles.json`, `GlobalNetworkProfileManager`) / Project(현재 프로젝트의 연결 설정 파일). 프로젝트 연결은 두 범위의 프로필을 모두 쓸 수 있다.
  - 목록과 Add… / Copy… / Remove, 오른쪽에 드라이버와 관계없는 핸들러 탭(SSH, Proxy, "Use …" 확인란 + SSH·프록시 탭과 같은 칸). SWT도 `hasObjectTypes`가 없는 핸들러만 보였다.
  - 이름은 범위 안에서 겹치면 안 된다. 연결이 쓰는 프로필은 지우지 않고 쓰는 연결을 알린다(SWT `canDeleteProfile`). 지우기 확인은 Remove / Cancel(Cancel이 기본).
  - OK에서 저장한다: 지운 프로필을 빼고, 바꾼 프로필을 넣고(`addOrUpdateProfile`) 관리자가 저장한다(`saveSettings`). 프로젝트가 보안 저장소를 쓰면 SWT처럼 프로필 비밀번호를
    프로젝트 비밀 저장소에 쓴다(`persistSecrets`). 프로젝트 관리자는 연결 설정 파일을 비동기로(100 ms 뒤) 저장한다.
- **고르기**: Connection Properties의 **Network profile**(None / 프로필, 전역 프로필은 "(global)"). 고르면 핸들러 탭을 그 프로필로 다시 읽어, 프로필이 켜는 핸들러는
  칸을 끄고 "Configured by the network profile '…'"을 보인다. 저장은 `setConfigProfile`이고, 고른 프로필이 그대로면 다시 쓰지 않는다.
  네트워크 핸들러 탭이 없는 드라이버(내장 DB 등)에는 이 줄이 없다.

## 비밀번호 바꾸기 (2026-11-08)

연결한 사용자의 데이터베이스 비밀번호를 바꾼다(SWT `connection.changeCurrentPassword`, `ChangePasswordDialogHandler`).

- Object Explorer 연결 노드와 Registered Servers 서버의 메뉴 **Change Password…**. 연결되어 있고 연결에 사용자 이름이 있으며 데이터베이스가 `DBAUserPasswordManager`를
  줄 때만 켜진다(SWT `ChangeUserPasswordPropertyTester`). 2026-11-08 현재 SQL Server, PostgreSQL, Oracle, SAP HANA, Exasol, Vertica, YashanDB 모델이 준다.
- 모델의 `DBAuthUtils.promptAndChangePasswordForCurrentUser`를 진행 대화상자에서 실행한다: 새 비밀번호(두 번) 묻기 → 데이터베이스 문장 실행
  (SQL Server `ALTER LOGIN … WITH PASSWORD`) → 연결 설정에 저장할지 묻기.

## 4. 일반 설정 (Connection Properties 탭)

- 기존: 연결 종류, 자동 커밋, 기본 DB·스키마, 읽기 전용, keep-alive, 유휴 연결 닫기("Connect 대화상자 (SSMS식)").
- **Description**(2026-11-08, 맨 위): SWT `ConnectionPageGeneral`의 설명과 같은 값(`DataSourceDescriptor.description`, 비우면 없음). Object Explorer 연결 노드의 도구 설명
  (`DBNDataSource.getNodeDescription`)에 보인다. 세션과 관계없는 값이라 연결된 연결에서 설명만 바꾸면 다시 연결을 묻지 않는다(`ConnectionDialog.save`가 비교에서 뺌).
- **연결 종류 콤보**: 항목마다 종류 색 견본을 보인다.
- **Color**: Custom color 확인란 + 색 단추(`JColorChooser`). 켜면 연결 설정 `connectionColor`("r,g,b"), 끄면 지워서 연결 종류의 색을 쓴다(SWT `ConnectionPageGeneral`과 같은 값).
- **연결 색이 보이는 곳**(`ConnectionColors`, SWT `UIUtils.getConnectionColor` 규칙): 쿼리 창 상태 줄(`QueryStatusLine`) 바탕, Registered Servers와 Object Explorer 서버 이름의 알약
  (`ConnectionPill`, 선택·마우스 올림 줄에서도 보임, Production은 1px 테두리. 크기와 규칙은 [swing-registered-servers.md](swing-registered-servers.md) "연결 색 알약").
  연결에 정한 색이 없으면 연결 종류의 라이트/다크 색(Test 214,250,207 / 64,89,66, Production 250,207,207 / 97,61,63)이고, 흰색(Development)은 색 없음이다.
  테마 글자 색이 이 색과 4.5:1이 안 되면(다크 테마의 밝은 연결 지정 색) 그 글자만 대비 색이다(SWT `UIStyles.getContrastColor` 규칙, `ConnectionColors.getTextColor`).
  연결을 편집하면 열린 편집기가 레지스트리 이벤트로 상태 줄을 다시 그린다(`SqlEditorPanel.connectionChanged`).
- **Bootstrap queries**: Edit… (N) 단추 → `BootstrapQueriesDialog`(목록, Add/Edit/Remove, Ignore SQL errors) → `bootstrap.initQueries`, `bootstrap.ignoreErrors`.
  모델이 연결 직후 이 SQL을 실행한다(`AbstractExecutionContext`의 bootstrap 처리).

### 연결별 제한과 연결 잠금 (2026-11-08)

SWT 감사 B-C13. SWT `ConnectionPageGeneral`의 Security 묶음(읽기 전용, Edit permissions)과 `EditConnectionWizard`의 잠금 비밀번호 확인을 옮겼다.
모델은 이미 모두 있었다: 연결의 제한 목록(`DataSourceDescriptor.setModifyPermissions`, `data-sources.json`의 `security.permission-restrictions`),
판단(`hasModifyPermission`: 읽기 전용이면 데이터·구조 편집을 막고, 연결의 목록이 비어 있으면 연결 유형의 제한), 잠금 비밀번호 해시(`lockPassword`).

- **읽기 전용 연결**: 전부터 있던 확인란(Connection Properties). 바꾸면 다시 연결을 묻는다(JDBC 연결에 읽기 전용을 정하므로).
- **제한**(읽기 전용 아래): 단추 글이 지금 적용되는 쪽과 수를 보인다. "Same as the connection type (N)" / "연결 유형과 같음(N)",
  "This connection only (N)" / "이 연결만(N)". 도구 설명에 적용되는 제한 목록. 누르면 `ConnectionRestrictionsDialog`:
  - "Use the restrictions of the connection type '…'"(연결 유형의 제한을 흐린 확인란으로 보임) 또는 "Set the restrictions of this connection"
    (데이터 편집, 구조 편집, 스크립트 실행, 데이터 가져오기 제한 확인란. 처음 고르면 연결 유형의 제한에서 시작).
  - 모델에서 빈 목록은 "연결 유형을 따름"이라 "이 연결만"에서 아무것도 고르지 않으면 같은 뜻이다. 그때 "With no restriction checked, the connection type's
    restrictions apply." 줄을 보인다. SWT 대화상자는 확인란만 있어 이 차이가 보이지 않았다.
  - 값은 Save에서 저장한다(`ConnectionSettingsPanel.saveConnectionProperties` → `setModifyPermissions`). 명령마다 그때 제한을 읽으므로(쿼리 창 실행
    `SqlExecutionSafety.checkExecutePermission`, 결과 편집, 개체 명령, 가져오기) 제한만 바꾸면 다시 연결을 묻지 않는다(`ConnectionDialog.save`가 비교에서 뺌).
- **데이터 변경 확인**: 연결 유형의 "데이터 변경 확인"(Options &gt; Connection Types, 결과 저장 전 미리 보기 `ResultSetGrid`)은 2026-11-07부터 있다. 모델에 연결 단위 값이 없어
  연결 대화상자에는 두지 않았다.
- **잠긴 연결**: 연결에 `lockPassword`(다른 DBeaver 기반 제품이 쓰는 값, 비밀번호 UTF-8의 MD5 소문자 16진수)가 있으면 Save에서 로그인(사용자 이름·비밀번호) 말고 다른 값이
  바뀌었을 때 "Locked Connection" / "잠긴 연결"이 잠금 비밀번호를 묻는다(SWT `EditConnectionWizard.checkLockPassword`·`isOnlyUserCredentialChanged`와 같은 규칙).
  - 맞으면 저장한다. 틀리면 "Wrong lock password. The changes were not saved." 뒤 대화상자가 바뀐 값을 가진 채 열려 있다. 취소도 저장하지 않는다.
  - 잠금 비밀번호를 정하거나 지우는 화면은 없다. SWT(CE)에도 없었다(값을 읽고 확인만 함). 해시 형식(소금 없는 MD5)이 약해 Chromite에서 새로 만드는 기능으로 넓히지 않았다.
  - 잠금은 연결 대화상자의 저장만 막는다(SWT와 같음). 이름 바꾸기·지우기·폴더 옮기기는 막지 않는다.
- 트랜잭션: 이 기능은 세션을 닫거나 커밋 방식을 바꾸지 않는다. 읽기 전용 등으로 다시 연결할 때는 기존 다시 연결 경로가 열린 트랜잭션을 먼저 묻는다.

## 5. Test Connection

- 임시 연결에 폼 전체(인증 추가 칸, 드라이버별 칸, 핸들러)를 쓰므로 SSH 터널·SSL·프록시를 거쳐 연결해 본다.
- 성공: `Connected (N ms)`, Server, Driver에 더해 쓴 핸들러를 `Network: SSH, SSL`처럼 보인다.
- 실패: 모델 오류 대화상자(D단계와 같음). 핸들러 칸이 비어 있으면 단추가 꺼지고 대화상자 아래에 이유가 보인다.
- SSH 탭의 Test Tunnel은 DB 없이 터널만 시험한다.

## 6. 공급자 속성 선언 (2026-11-08)

Provider Properties 표는 모델 `plugin.xml`의 `<provider-properties>`에 선언된 키만 보인다. 모델은 읽지만 선언되지 않은 키는 저장된 값이 보이지 않은 채 적용되고
새로 넣을 수 없었다([swt-parity/D-db-specific.md](swt-parity/D-db-specific.md) §6). SWT 전용 화면이 쓰던 키를 선언했다. 이름과 설명은 각 번들의 `OSGI-INF/l10n/bundle*.properties`(영어·한국어).

| 번들 | 드라이버 | 키 | 표 묶음 | 모델이 읽는 곳 |
|---|---|---|---|---|
| `ext.postgresql` | 모두 | `show-database-statistics` | Database list | `PostgreDataSource` 생성자 |
| `ext.postgresql` | 모두 | `@…-use-prepared-statements-db@` | Performance(새 묶음) | `PostgreDataSource.getInternalConnectionProperties`(서버 종류가 prepared statement를 끄는 경우) |
| `ext.mssql` | Azure SQL Server, Babelfish | `show-all-databases-azure` + SQL Server 드라이버의 줄 복사(`copyFrom="microsoft"`: 모든 스키마 표시, 서버 인증서 신뢰) | Advanced | `SQLServerDataSource`의 DB 목록 쿼리(값이 없으면 이 두 드라이버는 현재 DB만) |
| `ext.mssql` | Generic 공급자(Sybase·예전 jTDS) 모두 | `@…-encrypt-password@` | Advanced | `SQLServerGenericDataSource.getInternalConnectionProperties` |
| `ext.oracle` | 모두 | `oracle-meta-optimizer-version`(SWT 목록 콤보), `@…-meta-read-column-comments@`, `oracle.meta-search-in-sequences` | Performance | `OracleDataSource`(메타데이터 최적화를 켰을 때), `OracleSchema` |

- 서버 인증서 신뢰는 주 양식 칸이 맡으므로 Azure·Babelfish 표에서도 빠진다(`ConnectionDriverFields#getHandledProviderProperties`).
- 공급자 속성이 모두 주 양식 칸에 있으면 Provider Properties 탭을 만들지 않는다(HANA).
- CUBRID shard(`shardType`·`shardValue`)는 공급자 속성이 아니라 드라이버 속성이라 지금도 Driver Properties 표에서 고칠 수 있다(SWT의 값 검사는 없음). 선언하지 않았다.

## URL 방식 연결의 주소 (2026-11-11, 앱 변경 #41)

사용자(기업 환경): "등록된 서버에서 가져오기 기능을 사용하여 서버를 등록할 경우 또는 서버를 등록해놓고 일정 시간이 지나면 localhost로 나타나는 현상이 있다"
(SSH·프록시·점프 서버는 쓰지 않음, Chromite에서 내보낸 파일을 다른 PC에서 가져옴).

재현(빈 작업 공간 A·B, B는 다른 `CHROMITE_DATA`와 빈 보안 저장소, 증거 `D:\Tools\_scratch\chromite-servers-tree\logs\`):

| 단계 | 결과 |
|---|---|
| A에서 내보내기(비밀번호 포함) → B에서 가져오기 직후 | 메모리·`data-sources.json`·Registered Servers 보조 글자 모두 원래 호스트(`127.0.0.1:55491`, `db-remote.corp.example:5432`) |
| B를 다시 시작 | 같음 |
| 연결 | 같음 |
| 유휴 연결 닫기로 끊긴 뒤(30초로 줄여 확인) | 같음 |
| **URL 방식 연결의 Properties를 열고 저장** | 저장된 호스트가 `localhost`로 바뀜, Registered Servers에 `localhost:5432` |

내보내기·가져오기, 다시 시작, 연결, 유휴 끊김은 호스트를 바꾸지 않는다. 호스트를 바꾼 것은 연결 대화상자의 저장이다.

- 원인: URL 방식(Connect with: URL)에서는 주소 칸(서버 이름, 포트, DB)이 꺼져 있고, 연결에 호스트가 없으면 `loadSettings`가 서버 이름 칸에 드라이버의 기본 호스트
  (`localhost`)를, 새 연결이면 이 드라이버로 마지막에 쓴 서버를 채운다. 그런데 `saveSettings`가 URL 방식에서도 `writeAddress`로 이 칸들을 저장해,
  URL이 가리키는 실제 서버 대신 자리 값이 연결의 호스트·포트가 되었다(데이터 문제: 설정 파일의 `host`가 바뀐다). 연결은 URL로 하므로 접속은 되지만
  호스트를 읽는 모든 곳(Registered Servers·Object Explorer 보조 글자, 쿼리 창 상태 줄의 서버, 내보내기 미리 보기, 연결 이름 자동 생성)이 `localhost`를 보였다.
  "일정 시간이 지나면"은 가져온(또는 만든) 뒤 언젠가 Properties에서 저장한 때로 본다. URL 방식 연결은 URL로 만든 연결, 다른 도구에서 가져온 사용자 URL 연결
  (SQL Developer 사용자 URL 등)에서 생긴다.
- 고친 것: URL 방식으로 저장하면 주소를 URL에서 읽는다(`ConnectionSettingsPanel.writeAddressFromUrl`: 드라이버 URL 틀의 `DatabaseURL.Pattern.tryRecognize`로
  호스트·포트·서버·DB·경로). 틀로 읽을 수 없는 URL이면 호스트와 포트를 비워 낡은 주소가 보이지 않게 한다. 호스트 방식은 그대로다.
- 이미 `localhost`로 저장된 URL 방식 연결은 Properties를 열고 한 번 저장하면 URL의 주소로 고쳐진다. 저장 파일을 자동으로 고치지는 않았다(사용자 데이터를 몰래 바꾸지 않음).
- 같은 원인으로 영향을 받던 다른 경우: 새 연결을 URL 방식으로 만들 때(마지막 서버나 `localhost`가 저장됨), 호스트 방식에서 URL 방식으로 바꿔 URL을 고친 뒤
  저장할 때(옛 호스트가 남음). 모두 같은 수정으로 URL의 주소가 저장된다.

## 설계 결정과 이유

- **SWT 화면을 그대로 옮기지 않고 범용 양식에 고리를 둠**: SWT는 DB마다 연결 화면 전체를 따로 만든다. Swing은 SSMS식 한 화면을 유지하고, DB별 칸은
  `ConnectionDriverFields`의 정해진 자리에만 넣는다. 다른 드라이버의 화면이 바뀌지 않고, 새 드라이버의 칸도 같은 고리로 더할 수 있다.
- **핸들러 칸을 구현 클래스·핸들러 ID로 고름**: SWT의 `propertyConfigurator` 확장은 SWT 번들에 있어 Swing 제품에 없다. 확장 지점을 새로 만드는 대신
  `NetworkHandlerPanel.create`의 분기로 두었다(Swing 칸이 세 종류뿐이고 모두 이 번들에 있으므로).
- **저장 순서**: 주소 → 인증 → 속성 표 → 드라이버별·인증 방식별 칸 → URL → 연결 속성 → 핸들러. 같은 속성을 표와 주 양식이 함께 가질 때 주 양식이 이기고,
  URL은 공급자 속성까지 쓴 뒤 만든다(Oracle TNS URL은 공급자 속성으로 만든다). 예전 순서는 URL을 속성 표보다 먼저 만들었다.
- **기본값을 없는 키에 넣지 않음**: SWT는 Trust server certificate `false`, SID/Service `SERVICE` 등을 항상 쓴다. 편집 대화상자는 Save할 때 `equalSettings`로 바뀜을 판단하므로,
  그대로 따르면 아무것도 고치지 않은 예전 연결도 "바뀜"이 되어 다시 연결을 묻는다. 그래서 값이 모델 기본값이고 키가 없으면 넣지 않는다.
- **SSH 점프 서버**: 처음에는 읽은 값을 보존하고 안내만 했다. 2026-11-08 목록 편집 대화상자를 더했다.
- **셸 명령 승인은 실행 시점 한 곳(2026-11-08)**: 편집 화면이 입력한 명령을 승인 기록에 넣으면, 다른 사람이 넘긴 연결 설정을 열어 Save만 눌러도 그 명령이 승인될 수 있다.
  그래서 편집은 값만 바꾸고 승인은 실행할 때 정확한 명령을 보고 받는다.
- **선언할지 칸을 만들지(2026-11-08)**: 값이 참/거짓이나 짧은 글이고 SWT도 확인란·글 칸 하나였던 키는 모델 `plugin.xml`에 선언해 Provider Properties 표로 고친다(6절).
  파일·폴더 고르기, 목록, 다른 값과 함께 정해지는 값(HANA 포트, Snowflake 대체 키), 인증 속성·드라이버 속성은 주 양식 칸으로 만든다. 표는 공급자 속성만 다루고
  고르기 단추가 없기 때문이다.
- **효과 없는 SWT 칸은 옮기지 않음(2026-11-08)**: TiDB Lake SSL 인증서 칸(모델이 읽지 않음), PostgreSQL 프록시 서비스를 새로 켜는 칸(SWT도 숨김, 켜면 SSL이 꺼짐).
  저장된 프록시 값은 끌 수 있게 보인다.

## upstream 대비 변경점

- upstream 코드는 SSH 터널의 로컬 주소 두 곳(`SSHTunnelImpl`, `SSHUtils`, 위 절)과, 2026-11-08 공급자 속성 선언(`ext.postgresql`·`ext.mssql`·`ext.oracle`의
  `plugin.xml`과 `OSGI-INF/l10n/bundle.properties`·`bundle_ko.properties`, 위 6절)만 고쳤고 나머지는 Swing 번들만 바뀌었다. 모델 Java 코드는 이번에 바꾸지 않았다.
- SWT와 다른 동작: SQL Server Encrypt를 주 양식에 둠(SWT는 드라이버 속성 표), 연결 색을 쿼리 창 상태 줄에 칠함(SWT는 편집기 탭·탐색기),
  Oracle Custom은 JDBC URL 연결 방식으로 통합, PostgreSQL SSL factory는 직접 입력.
  2026-11-08: 셸 명령 편집이 명령을 승인 기록에 넣지 않음, 네트워크 프로필 고르기는 Connection Properties의 콤보(SWT는 설정 페이지 도구 막대 메뉴),
  Server name이 `https://…` 같은 URL이면 첫 `:`를 포트 구분으로 보지 않음(BigQuery 기본 호스트, 끝의 `:숫자`만 포트).
  2026-11-08: 연결 설명은 Connection Properties 탭(SWT는 General 페이지), SSL 칸을 핸들러 ID로 고름(SWT는 구현 클래스, Databend·TiDB Lake가 겹침),
  PostgreSQL 프록시 서비스는 저장값이 켜져 있을 때만 보임, Snowflake·Spanner 빈 값은 속성을 지움(1절).
  2026-11-08: 연결별 제한은 "연결 유형을 따름"과 "이 연결만"을 고르게 함(SWT는 확인란만), 제한만 바꾸면 다시 연결을 묻지 않음(SWT는 물음),
  잠금 비밀번호가 틀리면 그 이유를 알림(SWT와 같음).
- 2026-11-08 SSH 호스트 키(모델, SWT에도 같은 결함이 있었음): upstream `KnownHostsVerifier`의 localhost 예외를 지움, 점프 서버 뒤의 서버를 자기 이름으로 확인
  (`SSHHostConfiguration.hostKeyHostname`/`forwardedTo`, JSch 별칭), 지문을 SHA-256으로 보임. 위 "SSH 호스트 키 확인".

## 확인

빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 결과물 `bundles.info` 139개, `swt`·`jface`·`org.eclipse.ui`·`org.eclipse.e4.ui` 번들 0개.
더한 `Require-Bundle`(`net.ssh`, `ext.mssql`, `ext.mysql`, `ext.oracle`, `ext.postgresql`)은 원래 결과물에 있던 번들이다.

Windows에서 in-process JVM으로 개인 작업 공간을 열어 로컬 Docker DB(`chr_conn_advanced`)에 대해 키보드·마우스로 확인했다. 화면은 `D:\Tools\_scratch\conn-advanced\shots\`.

- **SQL Server 2022**: 주 양식에 Encrypt·Trust server certificate. Optional → Test 성공. Mandatory(Trust 끔) → 드라이버의 인증서 검증 실패 오류(`encrypt=true`가 들어감).
  Mandatory + Trust → Test 성공. Options: 연결 종류 Test, Bootstrap queries에 `SET LANGUAGE British` → Connect →
  `data-sources.json`에 `properties.encrypt=true`, `provider-properties.sslTrustServerCertificate=true`, `type=test`, `bootstrap.query`. 비밀번호는 JSON에 없음.
  New Query에서 `select @@language` → `British`(연결 직후 SQL 실행 확인). 쿼리 상태 줄과 Object Explorer 연결 노드 바탕이 Test 색(라이트 214,250,207, 다크 64,89,66).
- **PostgreSQL 17**: SSL 탭 Use SSL + SSL mode require → 서버가 SSL을 지원하지 않는다는 pgjdbc 오류(핸들러가 `sslmode`를 넣음). 편집에서 prefer → Test 성공, 결과에 `Network: SSL`.
  편집 대화상자가 저장된 SSL 설정(켜짐, 모드)을 그대로 읽음. SSH 탭: 호스트 127.0.0.1:2222(듣는 서버 없음)로 Test → `Error establishing SSHJ tunnel: Connection refused`(터널을 거쳐 연결을 시도함).
- 확인하지 못한 것: 위 SSH 시험 직후 Windows 방화벽 알림 창("SSH 터널의 로컬 주소")이 데스크톱 전면을 차지해, 그 뒤의 화면 조작(SSH 설정 저장·다시 열기,
  Proxy 탭, MariaDB SSL, Oracle Basic/TNS 화면, PgPass·NTLM 추가 칸, 바뀌지 않은 편집 저장, 대화상자 다크 화면)은 하지 못했다. 실제 SSH 서버 연결(이미지 없음),
  명명된 인스턴스 연결, Oracle 서버 연결, macOS 실행도 확인하지 못했다.

### 확인 (2026-11-08, 연결 칸 4순위)

빌드: `-Pproduct-chromite-swing` BUILD SUCCESS. 앱 하나를 개인 데이터 폴더(`CHROMITE_DATA`)·작업 공간으로 열고, 같은 프로세스에 붙인 시험 코드가
`ConnectionSettingsPanel`을 화면 밖에서 만들어 칸 값을 넣고 연결 사본에 저장한 결과를 읽었다(화면 입력 없음). 화면은 화면 밖에서 그린 PNG
(`D:\Tools\_scratch\chromite-p4-connection-fields\shots\`). 클라우드 DB(BigQuery·Snowflake·Spanner·HANA Cloud)와 ClickHouse·Databend·TiDB Lake·Mimer·Firebird 서버에는
연결하지 못해 칸과 저장 값만 확인했다.

- 공급자 속성: PostgreSQL 표에 `show-database-statistics`(데이터베이스 목록)와 `@…-use-prepared-statements-db@`(성능), Azure·Babelfish에 모든 스키마 표시·서버 인증서 신뢰·
  `show-all-databases-azure`, Sybase jTDS·jConnect에 `@…-encrypt-password@`, Oracle 성능 묶음에 세 키가 한국어 이름으로 나옴. SQL Server 드라이버 목록은 바뀌지 않음.
- 설명: 읽기 → 앞뒤 공백 없이 저장, 비우면 `null`.
- BigQuery: Project가 비면 "프로젝트를 입력하십시오."로 막힘. Service account 저장은 `OAuthPvtKeyPath`만(OAuthType 0은 넣지 않음), User account는 `OAuthType=1`이고 키 경로를 지우며 키 줄을 숨김.
- Snowflake: 대체·예전 자리(`warehouse`, `schema`, `@…-role@`, `@…-authenticator@`)의 값을 칸에 보임, 표에는 `ddString`만 남음. 바꾸지 않고 저장하면 대체 키를 그대로 두고
  예전 Role·Authenticator는 인증 속성으로 옮김. 고치면 서버 이름 `WH_NEW`, 빈 Schema는 두 키 모두 지움, 목록에 없는 `username_password_mfa`를 그대로 저장.
- Spanner: 키 파일 → `PvtKeyPath`, 비우면 지움. HANA: 에디션별 포트 30715(단일, 인스턴스 07)·30713(시스템·테넌트)·39015(Express, 인스턴스 90)·443(Cloud),
  테넌트 DB → 드라이버 속성 `databaseName=TENANT1`, 인스턴스 123은 막힘. Firebird Embedded → `nativeLibraryPath`(Firebird 네트워크 드라이버에는 칸 없음).
  Mimer: 기본 저장은 아무 키도 넣지 않음, Local·Program → `protocol=local`, 드라이버 속성 `program`, 인증 속성 `programPwd`.
- ClickHouse SSL: 탭 목록 Connection Properties, SSH, SSL, Proxy, …; 저장 `ssl.ca.cert`, 빈 클라이언트 경로, `ssl.method=CERTIFICATES`, `ssl.mode=NONE`, 다시 열면 같은 값.
  Databend·TiDB Lake: SSL 탭과 안내, 켜고 저장하면 속성 없는 켜진 핸들러.
- 바꾸지 않고 저장: BigQuery·Snowflake·Spanner·HANA·Firebird Embedded·Mimer·ClickHouse·PostgreSQL·Azure SQL·Databend 모두 `equalSettings` 참(다시 연결을 묻지 않음).
- PostgreSQL SSL(공유 Docker PostgreSQL 17, 서버 SSL 꺼짐): SSL 켜짐·`sslMode=require`·저장된 `sslProxyServer=true` 연결을 열면 확인란이 보이고 켜져 있음,
  그대로 Test 연결 → 연결됨(모델이 SSL 설정을 넘기지 않음). 확인란을 끄고 저장 → 핸들러에서 `sslProxyServer`가 빠지고 연결 → "서버가 SSL을 지원하지 않습니다."
  (`sslmode=require`가 넘어감). 새 연결에는 확인란이 없음. 대화상자로 한 번 저장한 이 연결을 다시 열어 그대로 저장하면 `equalSettings` 참이고 `sslProxyServer`가 남음.
  (손으로 만든 핸들러는 SSL 탭이 빈 경로 키를 SWT처럼 써서 처음 한 번은 "바뀜"이 된다. 이번 변경과 관계없는 기존 동작.)
- HANA는 공급자 속성이 모두 칸으로 옮겨져 Provider Properties 탭이 없음(탭: Connection Properties, SSH, Proxy, Driver Properties, Additional Connection Parameters).
- 같은 확인을 한국어(OS 언어)와 영어(`-nl en_US`)로 두 번 실행: 표 이름·안내·막힘 문구가 각 언어로 나옴(예: "Connects to port 30713 (set by the edition)",
  "포트 30713에 연결합니다(에디션이 정함)"). 화면은 `shots-ko\`, `shots\`(영어).

### 확인 (2026-11-08, 연결 도구)

빌드 `-Pproduct-chromite-swing` BUILD SUCCESS. 개인 데이터 폴더·작업 공간으로 연 앱 하나(영어 `-nl en_US`)에 시험 코드를 붙여, 패널과 대화상자를 화면 밖에서 만들고
그 대화상자의 단추를 같은 프로세스 안에서 눌렀다(화면 입력 없음). 기록 `D:\Tools\_scratch\chromite-p4-connection-fields\ops-tools-*.log`, 화면 `shots\`.

- 셸 명령: 탭 순서 Connection Properties, SSH, SSL, Proxy, **Shell Commands**, Settings, …. Before Connect를 켜고 `cmd /c echo p4ct`, 기다리기 → 저장된 이벤트
  (켜짐, 기다림, 시간 제한 -1), 탭 확인 표시. 그 연결로 Docker PostgreSQL에 연결: 승인 대화상자에 명령이 보이고, Cancel → "Shell command was not approved …"로 연결 안 됨,
  Run Once → 연결됨. 한 번 저장한 연결을 그대로 저장하면 `equalSettings` 참.
- 점프 서버: 저장된 점프 서버 1개 → "1 jump server(s), first: jump1@jump1.example:22". 대화상자에서 서버를 더하고 Move Up → 순서가 바뀜.
  저장 결과 `[jump2(KeyFile), jump1(Password), 대상 호스트]`.
- 네트워크 프로필: 대화상자 범위 2개(Global, Project General), 탭 SSH·Proxy. 프로젝트 프로필 `p4ct-profile`(SSH bastion.example)을 저장 → 관리자와 `.chromite/data-sources.json`에 있음.
  연결 대화상자의 Network profile 콤보 `[None, p4ct-profile]`, 고르면 SSH 탭에 "Configured by the network profile 'p4ct-profile'", 저장된 프로필 이름, 그대로 저장하면 `equalSettings` 참.
  그 프로필을 쓰는 연결을 등록하면 지우기 검사가 그 연결 이름을 돌려줌. 시험 프로필·연결은 지움.
- 비밀번호 바꾸기(공유 Docker SQL Server, 직접 만든 로그인 `p4ct_user`): 끊긴 연결은 메뉴가 꺼짐(관리자 없음), 연결한 SQL Server·PostgreSQL은 켜짐.
  메뉴 동작 → "Change user password" 묻기에 새 비밀번호 → "Change current user password"(저장할지)에 No → 새 비밀번호로 연결됨, 옛 비밀번호로 연결 안 됨. 로그인은 지움.
- Server name: `https://www.googleapis.com/bigquery/v2` → 호스트 그대로·포트 없음, `…/v2:8443` → 포트 8443, `db.example:5433`, `db.example,1433`, `[::1]:5432`, `fe80::1`은 전과 같음.
  새 BigQuery 연결의 저장값: 호스트 `https://www.googleapis.com/bigquery/v2`, 포트 443, URL `jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;…`.
- 확인하지 못한 것: 실제 SSH 점프 서버·프로필을 거친 연결(시험 SSH 서버 없음), 승인 기록 Always Allow 뒤 묻지 않는지(#99에서 확인함), 한국어 화면.

### 확인 (2026-11-08, 연결 제한)

빌드 `-Pproduct-chromite-swing` BUILD SUCCESS. 앱 하나를 작업 공간 사본·별도 데이터 폴더(`CHROMITE_DATA`)로 한국어, 다시 `-nl en`으로 열고, 같은 프로세스에 붙인 시험
드라이버가 Registered Servers의 속성… → 연결 편집 대화상자를 조작했다(OS 입력 없음). 잠금 시험을 위해 사본의 SQL Server 연결에만 `lockPassword`(시험 비밀번호의 MD5)를 넣었다.
화면 `D:\Tools\_scratch\chromite-model-ddl-fixes\shots\`.

| 확인 | 결과 |
|---|---|
| PostgreSQL 연결 편집 → 옵션 >> → 연결 속성 | 읽기 전용 연결 아래 "제한" 줄, 단추 "연결 유형과 같음(0)", 도구 설명에 제한 설명 |
| 단추 → "연결 제한" | "연결 유형 '개발'의 제한 사용" 선택, 확인란 4개 흐림(유형 제한 없음), 확인이 기본 |
| "이 연결의 제한을 직접 정함" | 확인란이 켜지고 "아무것도 선택하지 않으면 연결 유형의 제한이 적용됩니다." 줄. 스크립트 실행 제한 선택 → 줄이 사라짐 |
| 확인 → 저장 | 단추 "이 연결만(1)", 도구 설명에 "• 스크립트 실행 제한". `data-sources.json` `security.permission-restrictions` = `["edit.execute"]` |
| 그 연결의 새 쿼리 창에서 `select 1` 실행 | "쿼리를 실행할 수 없음": 연결 "PostgreSQL 테스트" 또는 그 연결 유형의 설정(스크립트 실행 제한)으로 쿼리 실행이 제한되어 있습니다. |
| 제한에 데이터 편집 제한을 더해 저장 | `["edit.data","edit.execute"]`, 다시 연결 질문 없음 |
| "연결 유형의 제한 사용"으로 저장 | 그 연결의 `security` 항목이 없어짐 |
| 잠긴 SQL Server 연결, 바꾸지 않고 저장(처음 한 번) | "잠긴 연결" 질문(사본을 손으로 만들어 처음 저장에 `auth-model` 등이 더해져 "바뀜"이 됨). 잠금 비밀번호 칸에 포커스, 확인이 기본 |
| 틀린 비밀번호 | "잠금 비밀번호가 틀립니다. 변경 내용을 저장하지 않았습니다.", 연결 편집 대화상자는 열린 채 |
| 맞는 비밀번호 | 저장, `lockPassword` 그대로. 다시 열어 그대로 저장 → 질문 없음 |
| 로그인만 `sa` → `sa2`, 다시 `sa` | 두 번 모두 잠금 질문 없이 저장(SWT `isOnlyUserCredentialChanged`와 같음) |
| 읽기 전용 연결을 켜고 저장 → 취소 | 저장하지 않음(`read-only` 없음), 대화상자 열린 채 |
| 영어(`-nl en`) | "Restrictions", "Same as the connection type (0)", "Connection Restrictions", "Use the restrictions of the connection type 'Development'", "This connection only (1)", "With no restriction checked, the connection type's restrictions apply.", "Locked Connection", "Wrong lock password. The changes were not saved.". 읽기 전용 + 맞는 비밀번호 → `"read-only": true` |

- 확인하지 못한 것: 연결 중인 연결에서 제한만 바꿀 때 다시 연결을 묻지 않는 경로는 끊긴 연결로만 확인했다(비교 코드는 같음). 데이터 편집·구조 편집·가져오기 제한이 각 화면을 막는 것은
  기존 기능이라 다시 확인하지 않았다.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 연결 편집 대화상자의 저장과 다시 연결 질문, 연결 설정 파일의 `security` 항목.

### 확인 (2026-11-08, 실제 SSH 서버, 후속 "확인 작업")

`chromite/followups-4` 빌드의 앱 한 벌(개인 `CHROMITE_DATA`·작업 공간, 한국어)에 붙인 시험 코드가 연결 편집 대화상자의 부품을 직접 눌렀다(화면 입력 없음).
SSH 서버는 이 확인만을 위해 만든 일회용 Docker 컨테이너(`debian:bookworm-slim` + OpenSSH 9.2p1, `127.0.0.1:52222`, 암호 인증 사용자 하나)이고 끝나고 지웠다.
DB는 공용 Docker PostgreSQL 17, 연결의 호스트는 SSH 서버에서 본 이름 `host.docker.internal:55432`. 증거 `D:\Tools\_scratch\chromite-followups-4\run\s1.txt.log`, `s2.txt.log`, `shots\s-*.png`.

| 동작 | 결과 |
|---|---|
| 연결 편집 → 옵션 → SSH 탭: SSH 사용, 호스트 `127.0.0.1`, 포트 52222, 사용자, 인증 "암호", 암호, 암호 저장 | 탭 목록 연결 속성, SSH, SSL, Proxy, 셸 명령, 설정, 드라이버 속성, 공급자 속성, 추가 연결 매개 변수 |
| 터널 테스트 | "SSH 터널을 열었습니다(522ms). 클라이언트: SSHJ_0.40.0 서버: OpenSSH_9.2p1 Debian-2+deb12u10" |
| 연결 테스트 | "연결되었습니다(356ms). 서버: PostgreSQL 17.11 … 네트워크: SSH" |
| 저장 → 다시 열기 | SSH 사용·호스트·포트·사용자·인증·암호(19자)·암호 저장이 그대로. 연결 설정의 핸들러 `ssh_tunnel` 켜짐, 암호 저장됨 |
| 연결 | 연결됨. SSH 컨테이너의 `/proc/net/tcp`에 sshd(uid 1000)에서 `192.168.65.254:55432`(호스트)로 가는 ESTABLISHED 연결 2개(주·메타데이터 세션): DB 연결이 터널을 지남 |

- 이번에는 Windows 방화벽 확인 창이 뜨지 않았다(포워더가 `127.0.0.1`에서만 들음, 위 "SSH 터널의 로컬 주소").
- 같은 대화상자의 연결 속성 탭에 "로컬 클라이언트" 줄이 있다(PostgreSQL, 후속 27: `ConnectionSettingsPanel.createLocalClientField`, #156). 화면 `shots\27-local-client.png`.
- 확인하지 못한 것: 공개 키·에이전트 인증, 점프 서버를 거치는 실제 연결, 처음 보는 호스트 키의 확인 질문(이번에는 질문 없이 연결됨, 기본 구현 SSHJ).
  (2026-11-08 아래 "확인 (2026-11-08, SSH 호스트 키)"에서 점프 서버 연결과 호스트 키 질문을 확인함. 질문이 없던 원인은 위 "SSH 호스트 키 확인")
- 회귀 테스트는 실행하지 않았다(코드 변경 없음).

### 확인 (2026-11-08, SSH 호스트 키, `chromite/followups-5`)

이 브랜치 빌드의 앱 한 벌(개인 `CHROMITE_DATA`·작업 공간, 한국어, known_hosts는 작업 공간 설정 `SSH2HOME`으로 scratch 폴더)에 붙인 시험 코드가
연결 편집 대화상자의 SSH 탭과 단추를 직접 눌렀다(화면 입력 없음). SSH 서버는 일회용 Docker 컨테이너 2개(`debian:bookworm-slim` + OpenSSH 9.2p1, 같은 Docker 네트워크):
A(점프 서버) `127.0.0.1:52322`, B(대상) `127.0.0.1:52323`이자 A에서 본 `chr-fu5-ssh-b:22`. DB는 공용 PostgreSQL 17(B에서 본 `host.docker.internal:55432`).
known_hosts는 시험마다 비우거나 서버 키를 다시 만들어(`ssh-keygen -A`) "바뀐 키"를 만들었다. 증거 `D:\Tools\_scratch\chromite-followups-5\run\s*.txt.log`, `shots\a*.png`.

| 경우 | 결과 |
|---|---|
| 직접 B(`127.0.0.1:52323`), 처음 보는 키, 터널 테스트 | "호스트 키 확인: 호스트 [127.0.0.1]:52323의 진위를 확인할 수 없습니다. ssh-ed25519 키 지문은 SHA256:WoMG…JjY입니다. 계속 연결할까요?", 기본 단추 "아니오". 서버의 `ssh-keygen -l` 지문과 같음 |
| → 아니오 | "SSH 터널을 열지 못했습니다 … HOST_KEY_NOT_VERIFIABLE", known_hosts 파일 없음(아무것도 쓰지 않음) |
| 다시 테스트 → 예 | 경고 "[127.0.0.1]:52323(ssh-ed25519)을(를) 알려진 호스트 목록에 영구히 추가했습니다." → 터널 열림. known_hosts에 `[127.0.0.1]:52323 ssh-ed25519` |
| 다시 테스트, 저장 → 연결 | 질문 없이 열림, 연결됨 |
| B의 키를 다시 만듦 → 터널 테스트 | 경고 "원격 호스트의 식별 정보가 바뀌었습니다 … 키의 지문은 SHA256:tuIc…kPM입니다. 연결하지 않았습니다 …" → 실패. 저장된 연결의 연결도 같은 경고 뒤 "Error initializing tunnel" |
| 점프 A → B(`chr-fu5-ssh-b:22`), known_hosts 비움 | A 질문([127.0.0.1]:52322, A의 지문) → 예 → B 질문 "호스트 chr-fu5-ssh-b의 진위를 … SHA256:tuIc…kPM" → 아니오: 실패, known_hosts에 A만 |
| 다시 테스트 → B 예 | 터널 열림, known_hosts에 `chr-fu5-ssh-b` 추가. 다음 테스트는 질문 없음, 저장 → 연결됨 |
| B의 키를 다시 만듦 → 연결 | 바뀐 키 경고 → "Error initializing tunnel"(HOST_KEY_NOT_VERIFIABLE) |
| 구현 JSch, 같은 점프(B 키는 바뀐 상태) | A 질문("127.0.0.1", 포트 없는 별칭) → 예 → "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! … sent by the remote host chr-fu5-ssh-b" → 아니오: "HostKey has been changed: chr-fu5-ssh-b" |
| "호스트 키 확인 건너뛰기" 켬, B 키 바뀐 상태 | 질문 없이 열림(사용자가 고른 대로) |

- 고치기 전의 동작은 `chromite/followups-4` 확인(위 표, 127.0.0.1의 서버가 질문 없이 연결됨)으로 갈음했다.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: 모든 SSH 터널(직접·점프 서버·공유 터널·터널 테스트)의 호스트 키 확인,
  `127.0.0.1`·`localhost`의 SSH 서버를 쓰던 연결(이제 처음 한 번 묻는다).

## 남긴 것

- 잠금 비밀번호를 정하거나 지우는 화면(SWT CE에도 없음, 소금 없는 MD5 형식).
- HTTP 프록시(모델에 구현 없음). 2026-11-08 점프 서버 목록 편집, 네트워크 프로필 편집·고르기, 셸 명령 편집, 비밀번호 바꾸기를 더함.
- 셸 명령 출력을 보는 프로세스 보기(SWT `ShellProcessView`, A-C11)와 승인 기록 관리 화면은 없다.
- 인증서 내용 붙여 넣기(분산 제품용 `.value` 속성).
- 인증 방식별 칸이 더 필요한 방식: 셸 명령 비밀번호, ClickHouse Cloud SSO/OIDC(인증 서비스 `UIServiceAuth` 구현도 없음).
- Oracle 클라이언트(OCI) 홈 선택, PostgreSQL SSL factory 목록 찾기, Snowflake의 Test 뒤 목록 채우기, Spanner Project·Instance·Database 칸(지금은 JDBC URL),
  CUBRID shard 칸(드라이버 속성 표로는 고칠 수 있음).
- 연결 색을 문서 탭에도 칠하기(지금은 상태 줄, Registered Servers, Object Explorer).

## 변경 이력

- 2026-11-08 연결별 제한과 연결 잠금(`chromite/model-ddl-fixes`, SWT 감사 B-C13): Connection Properties의 "제한"(`ConnectionRestrictionsDialog`),
  잠긴 연결의 잠금 비밀번호 확인, 제한만 바꾸면 다시 연결을 묻지 않음. "연결별 제한과 연결 잠금", "확인 (2026-11-08, 연결 제한)" 절.
- 2026-11-08 연결 도구(`chromite/p4-connection-tools`): 셸 명령 탭, SSH 점프 서버 편집, 네트워크 프로필 편집·고르기, 비밀번호 바꾸기 메뉴,
  Server name의 URL 호스트(BigQuery) 나누기 고침. "확인 (2026-11-08, 연결 도구)" 절.
- 2026-11-08 Oracle 세션 설정(NLS) 탭, 드라이버별 Options 탭 고리(`ConnectionDriverFields#getOptionsPage`).
- 2026-11-08 연결 칸 4순위(`chromite/p4-connection-fields`): 공급자 속성 선언(PostgreSQL·SQL Server·Oracle, 6절), ClickHouse SSL 탭, Databend·TiDB Lake SSL 탭,
  저장된 PostgreSQL 프록시 서비스 끄기, BigQuery·Snowflake·Spanner 인증 칸, BigQuery·Snowflake·HANA·Firebird Embedded·Mimer 주 양식 칸, 연결 설명,
  모두 칸으로 옮겨진 공급자 속성이면 Provider Properties 탭을 만들지 않음. "확인 (2026-11-08)" 절.
- 2026-11-07 비밀번호 저장 위치를 OS 보안 저장소로 고쳐 적음([credential-storage.md](credential-storage.md)).
- 2026-11-06 최초 작성: 인증 방식별 칸, 네트워크 핸들러 탭(SSH, Proxy, SSL), SQL Server·Oracle 주 양식 칸, 연결 색, 연결 직후 SQL, Test Connection 결과.
- 2026-11-06 SSH 터널 로컬 포트를 루프백에서만 열도록 모델 수정(빈 포트 시험과 JSch의 빈 주소가 0.0.0.0에서 들음). "SSH 터널의 로컬 주소" 절 추가.
- 2026-11-06 Object Explorer 연결 노드 바탕을 이름 알약(`ConnectionPill`, Registered Servers와 같음)으로 바꿈. [swing-registered-servers.md](swing-registered-servers.md) "연결 색 알약".
- 2026-11-06 연결 색 위 글자가 읽히지 않으면 대비 색(상태 줄, 알약). 남긴 것에서 해당 항목 삭제.
- 2026-11-08 실제 SSH 서버(일회용 Docker OpenSSH)로 터널 테스트·연결 테스트·저장·다시 열기·연결 확인(`chromite/followups-4`, 위 "확인 (2026-11-08, 실제 SSH 서버, 후속 "확인 작업")").
- 2026-11-08 후속 29 a(`chromite/followups-5`): 호스트 키 확인을 끄지 않은 연결이 `127.0.0.1`의 서버와 점프 서버 뒤의 서버를 키 확인 없이 받아들이던 것
  (`KnownHostsVerifier`의 localhost 예외, 점프 세션이 서버 이름을 잃음). 키 이름(`SSHHostConfiguration.hostKeyHostname`), SHA-256 지문, JSch 별칭,
  SSHJ 질문의 한국어. 위 "SSH 호스트 키 확인", "확인 (2026-11-08, SSH 호스트 키)".
- 2026-11-11 URL 방식으로 저장하면 주소를 URL에서 읽음(앱 변경 #41, `chromite/servers-tree`): 꺼진 주소 칸의 자리 값(`localhost`, 마지막 서버)이 호스트로 저장돼 등록된 서버에 localhost로 보이던 것. 위 "URL 방식 연결의 주소"
