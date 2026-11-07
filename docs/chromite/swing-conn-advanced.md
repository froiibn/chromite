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
| `ConnectionAuthExtras` (새로) | 인증 방식별 추가 칸(SQL Server NTLM integrated security, Oracle Role, PgPass 호스트 이름) | `SQLServerAuthConfigurator`, `OracleAuthDatabaseNativeConfigurator`, `PostgreAuthPgPassConfigurator` |
| `ConnectionDriverFields` (새로) | 드라이버별 주 양식 칸(SQL Server, Oracle) | `SQLServerConnectionPage`, `OracleConnectionPage` |
| `ConnectionNetworkPage` (새로) | Options의 핸들러 탭 하나("Use …" 확인란 + 설정 칸), 탭 목록 규칙 | `ConnectionPageNetworkHandler`, `ConnectionPageSettings.getDialogPages` |
| `NetworkHandlerPanel` (새로) | 핸들러 설정 칸의 공통 틀(load/save/오류)과 핸들러별 칸 고르기 | `IObjectPropertyConfigurator`, `UIPropertyConfiguratorRegistry` |
| `SshTunnelPanel` (새로) | SSH 터널 칸과 Test Tunnel | `SSHTunnelDefaultConfiguratorUI` |
| `SocksProxyPanel` (새로) | SOCKS 프록시 칸 | `SocksProxyConfiguratorUI` |
| `SslHandlerPanel` (새로) | PostgreSQL·MySQL/MariaDB·SQL Server SSL 칸 | `SSLConfiguratorTrustStoreUI`, `PostgreSSLConfigurator`, `MySQLSSLConfigurator`, `SQLServerSSLConfigurator` |
| `ConnectionColors` (새로) | 연결 색(연결에 정한 색, 없으면 연결 종류 색) | `UIUtils.getConnectionColor`, `getConnectionTypeColor`, `getConnectionColorByRGB` |
| `BootstrapQueriesDialog` (새로) | 연결 직후 SQL 목록 편집 | `EditBootstrapQueriesDialog` |
| `ConnectionSettingsPanel` (변경) | 위 칸·탭을 주 양식과 Options에 넣고 읽기·저장 순서를 정함 | `ConnectionPageGeneral`, `ConnectionPageInitialization` |
| `ConnectionDialog` (변경) | Test Connection 결과에 쓴 네트워크 핸들러 표시 | `ConnectionWizard.testConnection` |
| `QueryStatusLine` (변경) | 쿼리 창 상태 줄 바탕 = 연결 색 | — |
| `ObjectExplorerPanel` (변경) | 연결 노드 이름의 알약 = 연결 색(`ConnectionPill`, 2026-11-06 노드 바탕에서 바꿈) | `DatabaseNavigatorLabelProvider`의 연결 색 |

`MANIFEST.MF`의 `Require-Bundle`에 UI 없는 모델 번들 `com.seizia.chromite.net.ssh`, `ext.mssql`, `ext.mysql`, `ext.oracle`, `ext.postgresql`을 더했다.
상수(속성 키, 핸들러 ID)와 모델 클래스(`SSHTunnelImpl`, `SSHUtils`, 인증 방식 클래스, `OCIUtils`)를 문자열로 베끼지 않고 그대로 쓰기 위해서다.
다섯 번들 모두 이미 Swing 피처(`com.seizia.chromite.runtime.feature`, `com.seizia.chromite.db.feature`)에 들어 있으므로 결과물의 번들 수는 바뀌지 않고, SWT·JFace 번들은 여전히 없다.

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

- 칸은 그 인증 방식을 골랐을 때만 보인다. 인증 방식 클래스는 `instanceof`로 판단한다(SWT 등록도 클래스 계층으로 고른다).
- 주 양식에 칸이 생긴 공급자 속성(Oracle `oracle.logon-as`)은 Provider Properties 표에서 뺀다. 한 값을 두 곳에서 고치지 않게 하기 위해서다.
- 바뀌지 않은 연결을 열어 Save만 눌러도 "바뀜"(다시 연결 질문)이 되지 않도록, SWT가 쓰는 기본값(빈 인증 속성 맵, `null` 공급자 속성)은 없는 키에 새로 넣지 않는다.

## 2. 네트워크 핸들러

### 탭 목록과 저장 (`ConnectionNetworkPage`)

- 목록: `NetworkHandlerRegistry.getDescriptors(driver)`(대체된 핸들러 제외, 드라이버의 `objectType`에 맞는 것, SSH는 작업 공간이 터널 기능을 허용할 때만).
  내장 DB 드라이버는 `enableNetworkParameters`가 없으면, `disableNetworkParameters` 드라이버는 탭이 없다. 드라이버 대체가 지원하지 않는 핸들러도 뺀다.
  ([ConnectionPageSettings.java:1046-1056](../../plugins/com.seizia.chromite.ui.editors.connection/src/com/seizia/chromite/ui/dialogs/connection/ConnectionPageSettings.java)와 같은 조건)
- Swing 칸이 있는 핸들러만 탭을 만든다: SSH(`SSHTunnelImpl`), Proxy(`SocksProxyImpl`), SSL(`postgre_ssl`, `mysql_ssl`, `mssql_ssl`). SWT도 설정 화면이 없는 핸들러는 탭을 만들지 않는다.
- 읽기: 연결의 핸들러 설정(없으면 새 설정)을 칸에 채우고, "Use SSH"/"Use SSL"/"Use Proxy" 확인란은 그 설정의 `enabled`다. 확인란을 끄면 칸이 꺼진다.
  확인란 이름은 핸들러의 짧은 이름(`codeName`)이다. 핸들러의 `label`은 모델 번들이 OS 언어로 바꿔(예: "SSH 터널") 영어 화면에 섞이기 때문이다.
- 저장: 켜져 있으면 연결의 핸들러 설정 사본에 칸 값을 쓰고 `updateHandler`, 꺼져 있으면 `removeHandler`
  ([ConnectionPageNetworkHandler.java](../../plugins/com.seizia.chromite.ui.editors.connection/src/com/seizia/chromite/ui/dialogs/connection/ConnectionPageNetworkHandler.java) `saveSettings`).
  연결 레지스트리도 꺼진 핸들러는 파일에 쓰지 않는다([DataSourceSerializerModern.java](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/DataSourceSerializerModern.java) "Save network handlers' configurations").
- 비밀번호: 핸들러 비밀번호(`setPassword`)와 비밀 속성(`setSecureProperty`)에만 넣는다. 레지스트리가 저장할 때 이 값들을 `SecureCredentials`로 묶어
  OS 보안 저장소에 쓴다(2026-11-07부터, [credential-storage.md](credential-storage.md). 그 전에는 고정 키로 암호화한 `credentials-config.json`)
  ([DataSourceParser.java](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/DataSourceParser.java) `saveNetworkHandlerConfiguration`). 설정 파일·환경 설정에는 평문으로 남지 않는다.
- 켜진 핸들러의 칸이 비어 있으면(`getErrorMessage`) 대화상자 아래 빠진 값 안내에 `SSH: Enter the SSH host.`처럼 보이고 Test·Connect가 꺼진다(SWT `isPageComplete`).
- 네트워크 프로필: 연결이 프로필을 쓰고 그 프로필에 이 핸들러가 켜져 있으면 칸을 끄고 프로필 이름을 보인다. 이때 연결의 핸들러 설정은 건드리지 않는다(프로필 편집 화면은 없다).

### SSH (`SshTunnelPanel`)

- 칸: SSH host + Port(22), User name(새 터널은 OS 사용자 이름), Authentication(Password / Public key / Agent, `SSHConstants.AuthType` 순서),
  Private key(파일 + Browse…, 공개 키일 때), Password 또는 Passphrase, Remember password(새 터널은 켬), Agent socket(Agent이고 Windows가 아닐 때).
- 고급: Implementation(SSHJ 기본, `SSHSessionControllerRegistry`), Keep-alive interval(ms), Connect timeout(ms, 기본 10000), Local host/port, Remote host/port,
  Bypass host key verification, Share this tunnel with other connections.
- 읽기·쓰기: 대상 호스트는 `SSHUtils.loadHostConfigurations`/`saveHostConfigurations`. 공개 키는 데스크톱 SWT처럼 키 파일 경로(`KeyFile`)로 저장한다.
  고급 값의 저장 규칙(0 이하는 지움, 기본 시간 초과는 저장하지 않음)은 `SSHTunnelDefaultConfiguratorUI.saveSettings`와 같다.
- 점프 서버: 읽은 점프 서버는 그대로 다시 저장하고 개수만 안내한다(목록 편집 화면은 옮기지 않음).
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

### Proxy (`SocksProxyPanel`)

- Proxy host, Port(1080), User name, Password, Remember password(끄면 비밀번호 칸이 꺼짐). 키는 `SocksConstants.PROP_HOST`/`PROP_PORT`, 사용자·비밀번호는 핸들러의 것.
- 모델의 프록시 구현은 SOCKS4/5만 지원한다(`SocksProxyImpl`). HTTP 프록시는 모델에 구현이 없어 고를 수 없다.

### SSL (`SslHandlerPanel`)

| 핸들러 | 칸 | 속성 |
|---|---|---|
| PostgreSQL `postgre_ssl` | CA certificate, Client certificate, Client private key(파일 + Browse…), SSL mode(Driver default/disable/allow/prefer/require/verify-ca/verify-full), SSL factory | `ssl.ca.cert`, `ssl.client.cert`, `ssl.client.key`, `ssl.method=CERTIFICATES`, `sslMode`, `sslFactory` |
| MySQL/MariaDB `mysql_ssl` | 위 세 파일, Cipher suites, Require SSL, Verify server certificate(기본 켬), Allow public key retrieval | 위 세 키, `ssl.cipher.suites`, `ssl.require`, `ssl.verify.server`, `ssl.public.key.retrieve` |
| SQL Server `mssql_ssl` | Keystore(파일), Keystore password, Certificate host name | `ssl.keystore`, 핸들러 비밀번호(저장 켬), `ssl.method=KEYSTORE`, `sslKeyStoreHostname` |

- 방법 속성(`ssl.method`)이 없는 예전 설정은 SWT처럼 예전 키(`rootCert` 등, `sslKeyStore`)에서 읽는다. 저장은 새 키로 한다.
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

## 4. 일반 설정 (Connection Properties 탭)

- 기존: 연결 종류, 자동 커밋, 기본 DB·스키마, 읽기 전용, keep-alive, 유휴 연결 닫기("Connect 대화상자 (SSMS식)").
- **연결 종류 콤보**: 항목마다 종류 색 견본을 보인다.
- **Color**: Custom color 확인란 + 색 단추(`JColorChooser`). 켜면 연결 설정 `connectionColor`("r,g,b"), 끄면 지워서 연결 종류의 색을 쓴다(SWT `ConnectionPageGeneral`과 같은 값).
- **연결 색이 보이는 곳**(`ConnectionColors`, SWT `UIUtils.getConnectionColor` 규칙): 쿼리 창 상태 줄(`QueryStatusLine`) 바탕, Registered Servers와 Object Explorer 서버 이름의 알약
  (`ConnectionPill`, 선택·마우스 올림 줄에서도 보임, Production은 1px 테두리. 크기와 규칙은 [swing-registered-servers.md](swing-registered-servers.md) "연결 색 알약").
  연결에 정한 색이 없으면 연결 종류의 라이트/다크 색(Test 214,250,207 / 64,89,66, Production 250,207,207 / 97,61,63)이고, 흰색(Development)은 색 없음이다.
  테마 글자 색이 이 색과 4.5:1이 안 되면(다크 테마의 밝은 연결 지정 색) 그 글자만 대비 색이다(SWT `UIStyles.getContrastColor` 규칙, `ConnectionColors.getTextColor`).
  연결을 편집하면 열린 편집기가 레지스트리 이벤트로 상태 줄을 다시 그린다(`SqlEditorPanel.connectionChanged`).
- **Bootstrap queries**: Edit… (N) 단추 → `BootstrapQueriesDialog`(목록, Add/Edit/Remove, Ignore SQL errors) → `bootstrap.initQueries`, `bootstrap.ignoreErrors`.
  모델이 연결 직후 이 SQL을 실행한다(`AbstractExecutionContext`의 bootstrap 처리).

## 5. Test Connection

- 임시 연결에 폼 전체(인증 추가 칸, 드라이버별 칸, 핸들러)를 쓰므로 SSH 터널·SSL·프록시를 거쳐 연결해 본다.
- 성공: `Connected (N ms)`, Server, Driver에 더해 쓴 핸들러를 `Network: SSH, SSL`처럼 보인다.
- 실패: 모델 오류 대화상자(D단계와 같음). 핸들러 칸이 비어 있으면 단추가 꺼지고 대화상자 아래에 이유가 보인다.
- SSH 탭의 Test Tunnel은 DB 없이 터널만 시험한다.

## 설계 결정과 이유

- **SWT 화면을 그대로 옮기지 않고 범용 양식에 고리를 둠**: SWT는 DB마다 연결 화면 전체를 따로 만든다. Swing은 SSMS식 한 화면을 유지하고, DB별 칸은
  `ConnectionDriverFields`의 정해진 자리에만 넣는다. 다른 드라이버의 화면이 바뀌지 않고, 새 드라이버의 칸도 같은 고리로 더할 수 있다.
- **핸들러 칸을 구현 클래스·핸들러 ID로 고름**: SWT의 `propertyConfigurator` 확장은 SWT 번들에 있어 Swing 제품에 없다. 확장 지점을 새로 만드는 대신
  `NetworkHandlerPanel.create`의 분기로 두었다(Swing 칸이 세 종류뿐이고 모두 이 번들에 있으므로).
- **저장 순서**: 주소 → 인증 → 속성 표 → 드라이버별·인증 방식별 칸 → URL → 연결 속성 → 핸들러. 같은 속성을 표와 주 양식이 함께 가질 때 주 양식이 이기고,
  URL은 공급자 속성까지 쓴 뒤 만든다(Oracle TNS URL은 공급자 속성으로 만든다). 예전 순서는 URL을 속성 표보다 먼저 만들었다.
- **기본값을 없는 키에 넣지 않음**: SWT는 Trust server certificate `false`, SID/Service `SERVICE` 등을 항상 쓴다. 편집 대화상자는 Save할 때 `equalSettings`로 바뀜을 판단하므로,
  그대로 따르면 아무것도 고치지 않은 예전 연결도 "바뀜"이 되어 다시 연결을 묻는다. 그래서 값이 모델 기본값이고 키가 없으면 넣지 않는다.
- **SSH 점프 서버**: 목록 편집 화면 대신 읽은 값을 보존하고 안내만 한다(한 번에 다 옮기면 완성도가 떨어져서).

## upstream 대비 변경점

- upstream 코드는 SSH 터널의 로컬 주소 두 곳(`SSHTunnelImpl`, `SSHUtils`, 위 절)만 고쳤고 나머지는 Swing 번들만 바뀌었다.
- SWT와 다른 동작: SQL Server Encrypt를 주 양식에 둠(SWT는 드라이버 속성 표), 연결 색을 쿼리 창 상태 줄에 칠함(SWT는 편집기 탭·탐색기),
  Oracle Custom은 JDBC URL 연결 방식으로 통합, PostgreSQL SSL factory는 직접 입력, 네트워크 프로필 편집 없음.

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

## 남긴 것

- SSH 점프 서버 목록 편집, 네트워크 프로필 편집·선택, HTTP 프록시(모델에 구현 없음).
- ClickHouse·Databend·TiDB Lake의 SSL 탭(Swing 칸 없음), 인증서 내용 붙여 넣기(분산 제품용 `.value` 속성).
- 인증 방식별 칸이 더 필요한 방식: 셸 명령 비밀번호, ClickHouse SSO/OIDC, BigQuery·Snowflake·Spanner 전용 칸(키 파일 등).
- Oracle 클라이언트(OCI) 홈 선택, PostgreSQL SSL factory 목록 찾기.
- 연결 색을 문서 탭에도 칠하기(지금은 상태 줄, Registered Servers, Object Explorer).

## 변경 이력

- 2026-11-08 Oracle 세션 설정(NLS) 탭, 드라이버별 Options 탭 고리(`ConnectionDriverFields#getOptionsPage`).
- 2026-11-07 비밀번호 저장 위치를 OS 보안 저장소로 고쳐 적음([credential-storage.md](credential-storage.md)).
- 2026-11-06 최초 작성: 인증 방식별 칸, 네트워크 핸들러 탭(SSH, Proxy, SSL), SQL Server·Oracle 주 양식 칸, 연결 색, 연결 직후 SQL, Test Connection 결과.
- 2026-11-06 SSH 터널 로컬 포트를 루프백에서만 열도록 모델 수정(빈 포트 시험과 JSch의 빈 주소가 0.0.0.0에서 들음). "SSH 터널의 로컬 주소" 절 추가.
- 2026-11-06 Object Explorer 연결 노드 바탕을 이름 알약(`ConnectionPill`, Registered Servers와 같음)으로 바꿈. [swing-registered-servers.md](swing-registered-servers.md) "연결 색 알약".
- 2026-11-06 연결 색 위 글자가 읽히지 않으면 대비 색(상태 줄, 알약). 남긴 것에서 해당 항목 삭제.
