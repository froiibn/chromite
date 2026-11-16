# 연결 자격 증명 질문과 JSch SSH 질문 (Swing)

## 목적

SWT를 지운 뒤 Swing 제품에서 연결할 때 사용자에게 물어야 할 것을 묻지 않던 두 경로를 고친다.
SWT 대조 감사의 2순위 항목이다([swt-parity-audit.md](swt-parity-audit.md), [A-platform.md](swt-parity/A-platform.md) S5·A-C16,
[C-navigator-tools.md](swt-parity/C-navigator-tools.md) #54).

1. **인증 방식 자격 증명 질문.** 비밀번호를 저장하지 않은 연결이 기본 방식(`AuthModelDatabaseNative` 자체)이 아닌 인증 방식을 쓰면
   연결 시도가 아무 메시지 없이 끝났다. SQL Server 기본 "SQL Server Authentication", Oracle 기본 "Username/password",
   pgpass 등 `AuthModelDatabaseNative` 하위 클래스가 모두 해당한다.
2. **JSch SSH 질문.** SSH 구현으로 JSch를 고른 연결은 새 호스트 키에 묻지 않고 거절했고, 키보드 대화형(OTP) 질문에는 저장된 비밀번호를 답했다.

## 원인

### 인증 방식 자격 증명

- registry `DataSourceDescriptor.connect`는 비밀번호를 저장하지 않은 연결이면 `askForPassword`를 부른다.
- `askForPassword`는 인증 방식 클래스가 정확히 `AuthModelDatabaseNative`가 아니면 `DBPPlatformUI.promptAuthModelCredentials`를 부른다
  (`DataSourceDescriptor.java` `askForPassword`의 `authModel.getClass() != AuthModelDatabaseNative.class` 분기).
- 이 메서드의 인터페이스 기본값은 `false`(model `DBPPlatformUI.promptAuthModelCredentials`)이고 Swing `SwingPlatformUI`는 재정의하지 않았다.
- `false`면 `connect`가 `false`를 돌려주고, `ConnectJob`은 취소 상태로 끝나며, `SwingConnectionService.notifyFinished`는 취소를 알리지 않는다.
- 인증 방식을 고른 적 없는 옛 연결(`auth-model` 없음)은 `AuthModelDatabaseNative.INSTANCE`로 읽혀 기존 질문(`promptUserCredentials`)을 탄다.
  Swing 연결 대화상자로 저장한 연결은 고른 인증 방식 ID가 들어가므로(예: `sqlserver_database`, `oracle_native`) 문제 경로를 탄다.

실행 확인(수정 전, `D:\Chromite-Test\app` 복사본): "Save password"를 끄고 저장된 비밀번호를 지운 SQL Server·Oracle 연결에서 "연결"을 누르면
대화상자도 알림도 없이 연결되지 않은 채로 남았다.

### JSch

- model `JSCHSessionController.createNewSession`은 `GeneralUtils.adapt(this, JSCHUserInfoPromptProvider.class)`로 UI 질문 공급자를 찾는다.
- SWT는 `net.ssh.ui` 번들의 어댑터 팩터리(`JSCHUIPromptProviderAdapterFactory`)로 공급자를 등록했다. Swing 제품에는 그 번들이 없어
  모델 기본값 `JschUserInfo`가 쓰였다. 이것은 `promptYesNo`가 늘 `false`, 키보드 대화형 질문마다 저장된 비밀번호를 돌려준다.

## 구조

| 파일 | 역할 |
|---|---|
| `ui.swing` `SwingPlatformUI.promptAuthModelCredentials` | 인증 방식 자격 증명 질문 |
| `ui.swing` `JschUserInfoPrompter` | JSch `UserInfo`·`UIKeyboardInteractive`의 Swing 구현 |
| `ui.swing` `JschPromptAdapterFactory` | `JSCHSessionController` → `JSCHUserInfoPromptProvider` 어댑터 |
| `ui.swing` `plugin.xml` | 위 어댑터 팩터리 등록(`org.eclipse.core.runtime.adapters`) |
| `ui.swing` `META-INF/MANIFEST.MF` | `com.seizia.chromite.net.ssh.jsch`, `com.jcraft.jsch` 의존 추가 |
| `ui.swing` `ConnectionMessages` | `auth_prompt_*`, `ssh_prompt_*` 문구(영어, 한국어) |

## 설계 결정

### 인증 방식 자격 증명 질문

- **보이는 칸.** 연결 대화상자가 그 인증 방식에 보이는 Login·Password 칸(`ConnectionAuthModels.getFields`)을 그대로 쓴다.
  칸 이름도 같다(인증 방식이 이름을 바꾼 경우 그 이름, 예: "Token"). SWT `AuthModelCredentialsDialog`는 인증 방식의 SWT 설정 화면을
  "자격 증명 질문 모드"로 띄웠고, 범용 화면(`DatabaseNativeAuthModelConfigurator`)은 사용자 이름, 비밀번호, "Save password"였다.
- **저장 방식.** SWT 질문 모드와 같다: 사용자 이름(칸이 있을 때), 비밀번호(칸이 없으면 지움)를 연결 설정에 쓰고 "Save password" 선택을
  `setSavePassword`로 넘긴다. 값은 앞뒤 공백을 지운다(SWT와 Swing 연결 대화상자가 모두 `trimAllWhitespaces`를 쓴다).
  저장할지는 registry `askForPassword`가 정한다: 저장을 고르면 `updateDataSource`로 저장하고, 아니면 이번 연결에만 쓰고 원래 설정을 되돌린다.
- **"Save password".** 비밀번호 칸이 있고 임시 연결(Test Connection)이 아닐 때만 보인다. 기존 질문(`askForPassword`의 `canSavePassword = !isTemporary()`)과 같다.
  임시 연결은 SWT처럼 저장한 것으로 둔다.
- **Oracle Role, pgpass 이름 재정의.** 질문하지 않는다. 자격 증명이 아니라 연결 설정이므로 연결 대화상자에서 저장한 값을 그대로 쓴다.
  pgpass는 Swing 연결 대화상자가 비밀번호 저장을 켜 두므로(`ConnectionAuthExtras`) 이 질문에 오지 않는다.
- **칸이 없는 인증 방식.** 범용 칸으로 설정할 수 없는 인증 방식(셸 명령, 브라우저 로그인 등, Swing 연결 대화상자의 Authentication 목록에서
  빠지는 것)은 물을 것이 없으므로 질문 없이 연결을 계속한다(`true`). SWT는 그 인증 방식 고유의 설정 화면을 띄웠는데 Swing에는 그 화면이 없다.
  자격 증명이 정말 빠졌으면 드라이버 오류로 보인다. 조용히 취소하는 것보다 낫다고 판단했다.
- **취소.** SWT와 같이 **조용히** 끝난다. `false` → `connect`가 `false` → `ConnectJob` 취소 상태 → 오류 창 없음. 연결은 끊긴 채로 남는다.
- **기본 방식.** `AuthModelDatabaseNative` 자체는 이 메서드를 타지 않는다. 기존 `promptUserCredentials` 경로는 바꾸지 않았다.

### JSch 질문

| 질문 | 동작 |
|---|---|
| `promptYesNo`(새 호스트 키, 바뀐 호스트 키, known_hosts 파일 만들기) | 경고 대화상자 "예 / 아니요". **"아니요"가 기본 단추**다. "예"일 때만 `true`. 본문은 화면 언어(아래) |
| `promptPassword` | 비밀번호 방식이면 저장된 비밀번호를 처음 한 번 쓴다. 다시 물으면(틀렸거나 없으면) 비밀번호 대화상자 |
| `promptPassphrase` | 키 방식이면 저장된 개인 키 암호를 처음 한 번 쓴다. 다시 물으면 암호 대화상자 |
| `promptKeyboardInteractive` | 질문이 "Password:" 하나뿐이고 저장된 비밀번호를 아직 안 썼으면 그것을 답한다. 그 밖에는 서버가 보낸 질문마다 칸을 만든 대화상자. echo가 꺼진 질문은 가려진 칸(`JPasswordField`), 켜진 질문은 보이는 칸 |
| `showMessage` | 디버그 로그만 남긴다(SWT와 같음) |

- **안전 쪽 실패.** 화면이 없거나(headless), 대화상자가 예외로 끝나거나, 기다리는 스레드가 끊기거나, 사용자가 취소하면 거절한다
  (`false` 또는 `null`). JSch는 이를 인증 취소 또는 호스트 키 거절로 처리해 연결 오류가 된다.
- **저장된 값은 처음 한 번만.** Eclipse `UserInfoPrompter`가 키보드 대화형 "Password:"에 쓰는 규칙을 비밀번호·개인 키 암호에도 적용했다.
  SWT `JSCHUIPromptProvider`는 비밀번호·암호 질문에 저장된 값만 돌려줘 값이 틀리면 JSch가 같은 값을 계속 보내다 실패했다.
- **저장된 값의 쓰임.** 비밀번호 방식의 값은 로그인 비밀번호로만, 키 방식의 값은 개인 키 암호로만 쓴다.
  SWT와 모델 기본값은 같은 값을 두 질문에 모두 답해, 키 방식에서 개인 키 암호가 서버에 비밀번호로 갈 수 있었다.
- **SSHJ.** 어댑터는 `JSCHSessionController`에만 걸린다. 기본 구현 SSHJ는 그대로 `KnownHostsVerifier` → `confirmAction`을 쓴다.
  (2026-11-08: SSHJ가 `127.0.0.1`과 점프 서버 뒤의 서버를 묻지 않고 받아들이던 것을 고쳤고, JSch 별칭이 점프 서버 뒤의 서버 이름을 쓴다.
  [swing-conn-advanced.md](swing-conn-advanced.md) "SSH 호스트 키 확인")
- **예/아니요 질문의 언어(2026-11-12, 앱 변경 #81).** JSch는 질문을 고정된 영어 글로만 넘긴다(`Session.checkHost`, `KnownHosts`). 한국어 화면에
  영어 본문이 나왔다(SSHJ 쪽은 모델 `SSHJUIMessages`가 이미 한국어). `JschUserInfoPrompter.translateQuestion`이 아는 네 질문을 그 글로 알아보고
  값만 꺼내 `ConnectionMessages`(영어·한국어) 문구로 바꾼다: 처음 보는 호스트 키(`ssh_prompt_host_key_unknown`: 호스트, 키 종류, 지문),
  바뀐 호스트 키(`ssh_prompt_host_key_changed`: 호스트, 키 종류, 지문, known_hosts 파일), known_hosts 파일·폴더 만들기(`ssh_prompt_create_file`,
  `ssh_prompt_create_folder`). JSch가 다른 글로 물으면(판이 바뀌는 등) 받은 글을 그대로 보인다. 질문을 숨기거나 답을 바꾸는 일은 없다.
- JSch는 비밀번호 방식을 취소하면 다음 방식(키보드 대화형)으로 넘어간다. 그래서 비밀번호 대화상자를 취소하면 키보드 대화형 "Password:" 대화상자가
  한 번 더 나올 수 있다. 두 번째도 취소하면 연결 오류로 끝난다. Eclipse `UserInfoPrompter`와 같은 동작이다.

## upstream 대비 변경점

- upstream의 SWT 구현(`DesktopUI.promptAuthModelCredentials`, `AuthModelCredentialsDialog`, `JSCHUIPromptProvider`)은 SWT와 함께 삭제됐다
  ([swt-removal.md](swt-removal.md)). 이 문서의 구현은 Swing으로 새로 만든 것이다.
- JSch 비밀번호·암호 질문의 재시도(저장된 값은 처음 한 번만)와 저장된 값의 쓰임 분리는 upstream과 다르다(위 "설계 결정").
- 모델(registry, net.ssh.jsch) 코드는 바꾸지 않았다.

## 확인

`D:\Tools\_scratch\chromite-auth-prompts\`(작업 공간 복사본, 화면 캡처 `shots\`, 실행 기록 `*.txt.log`).
화면 조작은 앱 프로세스 안의 Swing API로만 했다(OS 입력 없음).

- 수정 전 빌드: SQL Server·Oracle 연결이 질문·알림 없이 연결되지 않음(`b1-before.txt.log`).
- SQL Server(127.0.0.1:51433)·Oracle(127.0.0.1:51521), "Save password" 끔:
  - 질문이 나온다. 사용자 이름이 채워져 있고 "Save password"는 꺼져 있다(`a1-sqlserver-prompt.png`, `a3-oracle-prompt.png`).
  - 취소: 오류 창·알림 없이 연결되지 않은 채로 남는다.
  - 맞는 비밀번호: 연결된다. 끊고 다시 연결하면 다시 묻는다(비밀번호를 저장하지 않음).
  - "Save password"를 켜고 확인: 연결되고 `save-password`와 비밀번호가 저장되며, 다시 연결할 때 묻지 않는다.
- 기본 방식(MariaDB, `auth-model` 없음, 비밀번호 저장 안 함): 기존 질문이 그대로 나온다(`a5-mariadb-native-prompt.png`).
- JSch(임시 Docker 컨테이너의 OpenSSH 9.2, 확인 뒤 삭제. known_hosts는 작업 공간 설정으로 임시 폴더에 둠):
  - 새 호스트: 지문이 있는 질문이 나온다(`j1-hostkey-prompt.png`). "아니요": "reject HostKey"로 연결 실패(`j2-error-after-no.png`).
    "예": known_hosts 파일 만들기 질문 뒤 진행(`j3-second-question.png`).
  - 서버가 저장된 비밀번호를 거절하면 비밀번호 대화상자로 다시 묻는다(`j4-ssh-auth-prompt.png`). 취소하면 키보드 대화형 "Password:"를 한 번 더 묻고,
    또 취소하면 연결 오류(`j7-*.png`).
  - 아는 호스트, 맞는 저장 비밀번호: 질문 없이 연결된다.
  - OTP(PAM `pam_google_authenticator` + 비밀번호, `AuthenticationMethods keyboard-interactive`): "Verification code:" 칸 하나가 가려진 칸으로 나오고
    (`j12-otp-prompt.png`), 코드를 넣으면 이어지는 "Password:" 질문에는 저장된 비밀번호가 쓰여 연결된다(sshd 로그 "Accepted keyboard-interactive/pam").
- 2026-11-12 질문의 언어(`chromite/conn-dblist-loop`, JSch 2.28.7 번들, 일회용 OpenSSH 9.2): 한국어 화면에서 처음 보는 호스트 키
  "호스트 127.0.0.1의 진위를 확인할 수 없습니다. / EDDSA 키 지문은 SHA256:…입니다. / 계속 연결할까요?", 이어서 "…\known_hosts 파일이 없습니다. /
  만들까요?", known_hosts의 키를 다른 키로 바꾼 뒤 "경고: 원격 호스트 127.0.0.1의 식별 정보가 바뀌었습니다. …"("아니오" → 연결하지 않음, SSH 로그인 0).
  영어 화면은 "The authenticity of host 127.0.0.1 cannot be established. …". 화면 `D:\Tools\_scratch\conn-dblist-loop\shots\a12-*`, `a13-*`, `capen-hostkey-en.png`.
- headless 실행은 확인하지 않았다(코드 경로만: `GraphicsEnvironment.isHeadless()`면 거절). headless 앱 모드는 모델이 호스트 키 확인을 끄므로 질문 자체가 없다.

## 변경 이력

- 2026-11-07 최초 작성: 인증 방식 자격 증명 질문(`SwingPlatformUI.promptAuthModelCredentials`)과 JSch SSH 질문(`JschUserInfoPrompter`, `JschPromptAdapterFactory`)
- 2026-11-08 후속 29 a: JSch 호스트 키 별칭을 점프 서버 뒤 서버의 이름으로, SSHJ의 localhost 예외 삭제([swing-conn-advanced.md](swing-conn-advanced.md) "SSH 호스트 키 확인")
- 2026-11-12 JSch 예/아니요 질문(호스트 키, known_hosts 만들기)의 본문을 화면 언어로(앱 변경 #81, `chromite/conn-dblist-loop`). 위 "JSch 질문"
