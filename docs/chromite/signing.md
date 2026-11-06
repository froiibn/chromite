# 릴리스 서명 (Windows · macOS)

## 목적

Chromite 릴리스 파일을 GitHub Actions에서 빌드하고 서명·확인한 뒤, 서명과 확인을 통과한 파일만 GitHub Release에 올린다.

- Windows: 서명하지 않은 exe는 SmartScreen이 "알 수 없는 게시자"로 경고하고, 회사 PC 정책으로 실행이 막힐 수 있다.
- macOS: Developer ID 서명과 공증(notarization)이 없으면 Gatekeeper가 앱을 열지 않는다. 공증 결과를 앱과 dmg에
  붙여 두면(staple) 인터넷이 없는 환경에서도 열린다([runtime.md](runtime.md) "플랫폼별 사항").
- `build.yml`(devel push·PR 빌드)은 지금처럼 서명하지 않는다. 서명 빌드는 새 워크플로 `release.yml`이 한다.

출처: Turbrix 서명 안내서(Turbrix root 세션, 2026-11-06)와 그 참고 구현(Turbrix `docs/SIGNING.md`,
`.github/workflows/release.yml`, `tools/release-macos.sh`, `apps/desktop/scripts/entitlements.mac.plist`)을 Chromite에 맞게 옮겼다.
비밀값은 어디에도 적지 않는다.

## 구조

| 파일 | 하는 일 |
|---|---|
| `.github/workflows/release.yml` | 작업 5개: `version`, `windows`, `macos-build`, `macos-sign`, `release` (아래 "흐름") |
| `tools/release/release-macos.sh` | macOS 단계를 하위 명령으로 모은 스크립트: `keychain-create`, `sign`, `check-signed`, `notarize`, `package`, `sign-dmg`, `verify`, `keychain-delete` |
| `tools/release/macos_natives.py` | 앱 안의 모든 Mach-O를 찾는다(낱개 파일과 jar 안의 항목, 중첩 jar 포함). `scan`(목록, 어느 OS에서나), `sign`(서명 후 jar에 다시 넣기), `check`(모두 팀 서명인지 확인) |
| `tools/release/entitlements.mac.plist` | 런처(JVM)의 hardened runtime 권한(entitlements) 3개 |
| `.github/workflows/build.yml` | 바꾸지 않았다(서명 없음) |

## 흐름

### 실행 조건

- `v*` 태그 push 때 돈다. 수동 실행(`workflow_dispatch`)은 `devel`에서만 된다(`version` 작업의 `if`).
  다른 브랜치에서 수동 실행하면 모든 작업이 건너뛰어진다.
- 서명하는 작업(`windows`, `macos-sign`)은 GitHub 환경 `signing`을 쓰고 `permissions: { contents: read, id-token: write }`를 둔다.
  `signing` 환경은 `devel` 브랜치와 `v*` 태그에서만 쓸 수 있게 제한한다(사용자가 만든다, 아래 체크리스트).
- 버전: `version` 작업이 `product/community/Chromite.product`와 `product/swing/chromite-swing.product`의
  `version="X.Y.Z.qualifier"`를 읽는다(현재 26.2.5). 두 값이 다르면 실패하고, 태그는 `v<버전>`이어야 한다(예: `v26.2.5`).
- 동시 실행: 같은 ref의 릴리스는 하나씩 돈다(`cancel-in-progress: false`, 서명 도중 취소하지 않는다).

### Windows (작업 `windows`, `windows-latest`)

1. Azure 변수 6개 확인: 모두 있으면 서명, 모두 없으면 서명 없는 빌드(`UNSIGNED`), 일부만 있으면 실패.
2. 빌드: `build.yml`의 Windows 빌드와 같은 명령(`mvnw.cmd ... '-Pproduct-chromite-swt,product-chromite-swing'`).
   Maven 캐시는 `build.yml`이 `devel`에서 저장한 것을 읽기만 한다(`actions/cache/restore`; 태그 실행은 캐시를 새로 저장하지 않는다).
   이어서 `build.yml`과 같은 내장 Java 런타임 단계(`tools/jre/BundleJre.java windows-x64`, JBR SDK 다운로드 캐시도 읽기만 함, [runtime.md](runtime.md)).
3. 서명 전 상태를 로그에 남긴다(모든 exe·dll의 Authenticode 상태).
4. `azure/login@v3`(OIDC, 비밀값 없음) → `azure/artifact-signing-action@v2`로 파일마다 한 단계씩 서명
   (`file-digest: SHA256`, `timestamp-rfc3161: http://timestamp.acs.microsoft.com`, `timestamp-digest: SHA256`).
   이 액션은 기존 서명을 덧붙이지 않고 바꾼다(`append-signature` 기본값 false).
5. 확인: 우리 런처 2개가 `Valid`이고 타임스탬프가 있고 발급자가 Azure Artifact Signing(`Microsoft ID Verified CS ...`)인지 확인한다.
   나머지 exe·dll(제3자)에 우리 서명(같은 인증서 지문)이 없는지도 확인한다.
6. zip: `build.yml` 결과물과 같은 구성(zip 안에 `chromite/`, `chromite-swing/` 폴더). zip의 파일 수가 폴더와 같은지,
   zip 안의 런처가 서명한 파일과 바이트 단위로 같은지 확인한다.
   - 서명: `chromite-<버전>-windows-x64.zip`, `chromite-swing-<버전>-windows-x64.zip` → 결과물 `chromite-<버전>-windows-x64`
   - 서명 없음: 이름 끝에 `-UNSIGNED` → 결과물 `UNSIGNED-chromite-<버전>-windows-x64-not-for-release`(보관 3일)
- 설치 프로그램(MSI·NSIS)은 아직 없어서 만들지 않는다. 생기면 설치 프로그램도 같은 작업에서 서명한다.

### macOS (작업 `macos-build` → `macos-sign`)

- `macos-build`(`ubuntu-latest`): `build.yml`의 Linux 작업과 같은 빌드, 내장 Java 런타임 단계(`BundleJre.java osx-aarch64`), 런처 실행 권한 확인 후 두 앱을 tar로 묶어
  중간 결과물 `intermediate-macos-apps-unsigned`(보관 1일)로 올린다. 이 결과물은 Release에 올리지 않는다.
  Linux에서 만드는 이유는 macOS arm64 러너 용량 문제다([ci.md](ci.md) "macOS 제품을 Linux 러너에서 만드는 이유").
- `macos-sign`(`macos-15`, arm64, 환경 `signing`): 저장소는 `tools/release`만 받는다(sparse checkout). 단계마다 `release-macos.sh` 하위 명령을 부른다.
  1. Apple 비밀값 6개 확인(모두/없음/일부, Windows와 같은 규칙).
  2. `keychain-create`: `$RUNNER_TEMP`에 임시 키체인(`security create-keychain -p <난수>`, `set-keychain-settings -lut 21600`,
     `unlock-keychain`, `import ... -T /usr/bin/codesign` 후 .p12 파일 바로 삭제, `set-key-partition-list -S apple-tool:,apple:,codesign:`,
     `list-keychains -d user -s`). 키체인 암호는 `::add-mask::`로 가린다. 팀의 Developer ID Application 인증서가 정확히 하나인지 확인한다.
     .p8 키는 `$RUNNER_TEMP/notary-key.p8`(umask 077)에 둔다.
  3. `sign <app>`: `xattr -cr` → `macos_natives.py sign`이 앱 안의 모든 Mach-O(주 실행 파일 제외)를 안쪽부터 서명
     (`codesign --force --options runtime --timestamp`, jar 안의 것은 꺼내 서명하고 다시 넣음) → 마지막에 앱을
     `--options runtime --timestamp --entitlements tools/release/entitlements.mac.plist`로 서명(주 실행 파일이 이때 서명됨) →
     `codesign --verify --deep --strict --verbose=2`.
  4. `check-signed <app>`: 앱이 팀의 Developer ID·타임스탬프·hardened runtime으로 서명됐는지(`codesign -dvv`), 서명에 든 권한이
     저장소의 plist와 같은지, 앱 안의 모든 Mach-O(jar 안 포함)가 같은 조건인지(`macos_natives.py check`) 확인한다.
  5. `notarize <app>`: `ditto -c -k --keepParent`로 zip → `xcrun notarytool submit --key <p8> --key-id --issuer --wait`
     (실패하면 `notarytool log` 출력) → `stapler staple` → `stapler validate`(실패하면 20초 간격 최대 4번).
  6. `package <app> <이름> dist`: 공증·staple된 앱으로 zip(`ditto`)과 dmg(`hdiutil create -fs HFS+ -format UDZO`, 앱과 `Applications` 링크)를 만든다.
  7. `sign-dmg` → `notarize <dmg>`: dmg를 `--timestamp`로 서명하고 공증·staple한다.
  8. `verify signed`: 서명한 앱, dmg 안의 앱, zip을 푼 앱 세 벌 모두 `codesign --verify --deep --strict`, 팀 서명, 모든 Mach-O 서명,
     `stapler validate`, `spctl -a -t exec -vv`가 "accepted"이고 `source=Notarized Developer ID`인지 확인한다.
     dmg는 `stapler validate`와 `spctl -a -t open --context context:primary-signature -vv`. Gatekeeper가 꺼져 있으면 켠 뒤 확인한다.
  9. `keychain-delete`(`if: always()`): 키체인과 .p12·.p8 파일을 지운다.
  - 결과물: 서명이면 `chromite-<버전>-macos-aarch64`(dmg 2개, zip 2개), 서명 없음이면 `-UNSIGNED` 이름으로
    `UNSIGNED-chromite-<버전>-macos-aarch64-not-for-release`(보관 3일).

### Release (작업 `release`)

- `v*` 태그이고 `windows`·`macos-sign`이 모두 성공했고 둘 다 서명(`signed == 'true'`)일 때만 돈다.
- 서명 결과물 두 개만 이름으로 받는다(중간 결과물·`UNSIGNED` 결과물은 받지 않고, 이름에 `UNSIGNED`가 있으면 실패).
  `SHA256SUMS`를 만든다.
- 태그의 Release가 없으면 **초안(draft)**으로 만들고, 초안이 있으면 파일을 바꾼다(`--clobber`). 이미 공개된 Release는 바꾸지 않고 실패한다.
  공개(publish)는 사용자가 한다.
- 모든 `gh` 명령에 `--repo "$GITHUB_REPOSITORY"`(froiibn/chromite)를 붙인다(CLAUDE.md "Git 작업 방식").

## 서명 대상 파일

아래 목록과 개수는 2026-11-06 로컬 빌드(Windows에서 빌드한 `editor-font-colors` 작업 트리 결과물, devel 기준 같은 구성)로 확인했다.
macOS는 `python tools/release/macos_natives.py scan <앱>`(서명 상태는 Mach-O의 `LC_CODE_SIGNATURE` CMS에서 읽음),
Windows는 `Get-AuthenticodeSignature`로 확인했다. 번들·드라이버 구성이 바뀌면 개수도 바뀐다. 스크립트는 릴리스 때마다 다시 찾는다.

### Windows: 우리 런처 2개만

| 파일 | 서명 | 이유 |
|---|---|---|
| `chromite/chromite.exe` | **서명** | Tycho(p2 BrandingIron, `IconExe`)가 Eclipse 런처에 Chromite 아이콘을 넣어 만든 파일이다([tools/branding/gen_app_icons.py](../../tools/branding/gen_app_icons.py)). 이때 원래 붙어 있던 Eclipse 서명이 깨진다(`HashMismatch`, 서명자 Eclipse.org Foundation) |
| `chromite-swing/chromite-swing.exe` | **서명** | 같은 이유(`HashMismatch`) |
| `chromitec.exe`, `chromite-swingc.exe` (콘솔 런처) | 안 함 | 이름만 바뀐 Eclipse 파일이고 Eclipse 서명이 그대로 유효하다(`Valid`, Eclipse.org Foundation) |
| `plugins/org.eclipse.equinox.launcher.win32.win32.x86_64_*/eclipse_11924.dll` | 안 함 | Eclipse 파일, Eclipse 서명 `Valid` |
| `plugins/com.sun.jna_*/com/sun/jna/win32-*/jnidispatch.dll` (3개) | 안 함 | JNA 파일, 서명 없음(`NotSigned`) |
| jar 안의 DLL(SWT 등), JDBC 드라이버 jar | 안 함 | Authenticode 대상이 아니다(jar 안의 항목) |
| `jre/bin/**/*.exe`, `*.dll` (내장 런타임 88개, [runtime.md](runtime.md)) | 안 함 | 제3자 파일. JetBrains가 만든 JBR 파일 42개는 서명이 없고(`NotSigned`, JBR 배포본 그대로), Microsoft C 런타임 45개(`ucrtbase.dll`, `api-ms-win-*.dll`, `vcruntime140*.dll`, `msvcp140.dll`)와 NVDA 화면 낭독기 연결 DLL 1개는 각 회사 서명이 `Valid`다(2026-11-07 로컬 빌드, `Get-AuthenticodeSignature`) |

### macOS: 앱 안의 모든 Mach-O

| 앱 | Mach-O 수 | 낱개 파일 | jar(압축 파일) 안 |
|---|---|---|---|
| `Chromite.app` | 12 | 4 | 8 |
| `ChromiteSwing.app` | 11 | 4 | 7 |

2026-11-07부터 내장 런타임(`Contents/Eclipse/jre`, [runtime.md](runtime.md))의 Mach-O 39개(모두 낱개 파일, JBR 배포본의 ad-hoc 서명)가
두 앱에 더해진다(`Chromite.app` 51개, `ChromiteSwing.app` 50개). `macos_natives.py scan`으로 런타임 폴더를 확인했다.

낱개 파일(두 앱 같음): `Contents/MacOS/chromite`(Swing: `chromite-swing`, 주 실행 파일), `com.sun.jna_*/.../darwin-aarch64/libjnidispatch.jnilib`,
`.../darwin-x86-64/libjnidispatch.jnilib`, `org.eclipse.equinox.launcher.cocoa.macosx.aarch64_*/eclipse_11924.so`(이름은 .so지만 Mach-O).
지금 모두 Eclipse Foundation의 Developer ID(`JCDTMS22B4`)로 서명돼 있다.

jar 안(개수, `[jar 서명]`은 다시 넣을 때 jar 서명이 지워지는 jar):

| jar | Chromite | Swing | 현재 서명 |
|---|---|---|---|
| `p2/org.eclipse.equinox.p2.core/cache/binary/<제품>.executable.cocoa.macosx.aarch64_<버전>` (p2가 보관한 런처 zip) | 1 | 1 | Eclipse Developer ID |
| `plugins/com.jcraft.jsch_2.28.7.jar` (junixsocket, arm64·x86_64) | 2 | 2 | DBeaver Corporation Developer ID |
| `plugins/org.eclipse.core.filesystem.macosx_*.jar` `[jar 서명]` | 1 | 1 | Eclipse Developer ID |
| `plugins/org.eclipse.equinox.security.macosx_*.jar` `[jar 서명]` | 1 | 1 | Eclipse Developer ID |
| `plugins/org.eclipse.swt.cocoa.macosx.aarch64_*.jar` `[jar 서명]` (libswt-cocoa, -pi-cocoa, -awt-cocoa) | 3 | - | Eclipse Developer ID |
| `plugins/com.seizia.chromite.ui.swing_*.jar!/lib/flatlaf.jar` (jar 안의 jar, FlatLaf arm64·x86_64) | - | 2 | arm64: ad-hoc, x86_64: **서명 없음** |

FlatLaf 라이브러리는 서명이 없거나 ad-hoc이라 이대로는 공증이 반드시 실패한다. jar 안의 Mach-O를 서명해야 하는 직접적인 근거다.
x86_64 조각(Intel용)은 Chromite가 쓰지 않지만 공증은 앱 안의 모든 Mach-O를 검사하므로 함께 서명한다.

## 제3자 바이너리 정책

- **Windows: 우리가 만든 파일만 서명한다.** SmartScreen 평판은 게시자(SEIZIA) 단위라 한 파일의 문제가 SEIZIA 전 제품에 번진다.
  - 우리 파일 = Chromite 빌드가 만들거나 바꾼 실행 파일. 브랜딩으로 바이트가 바뀌어 원래 서명이 깨진 런처(`chromite.exe`,
    `chromite-swing.exe`)는 우리 빌드 산출물로 본다. 바뀌지 않은 콘솔 런처(`chromitec.exe` 등)는 Eclipse 서명을 그대로 둔다.
  - **미결정**: 브랜딩한 Eclipse 런처를 "우리 바이너리"로 볼지는 사용자 확인이 필요하다. 코드는 Eclipse 런처 그대로이고 아이콘·버전 리소스만 바뀌었다.
    서명하지 않으면 깨진 Eclipse 서명이 붙은 exe가 배포되어 Windows가 "서명이 손상됨"으로 표시한다.
- **macOS: 앱 안의 모든 Mach-O를 우리 Developer ID로 서명한다.** Apple은 번들 안의 모든 실행 코드(압축 파일 안 포함)가
  Developer ID·보안 타임스탬프·hardened runtime으로 서명돼야 공증한다. 또 앱 번들의 서명은 곧 주 실행 파일의 서명이라
  `Contents/MacOS/chromite`(Eclipse 런처 그대로)는 앱을 서명하면 반드시 우리 서명이 된다.
  Turbrix가 Electron 프레임워크·도우미 앱을 우리 Developer ID로 서명하는 것과 같은 선례를 따른다(Turbrix `docs/SIGNING.md` "Windows 규칙 4와 다른 점").
  Eclipse·JNA·SWT·junixsocket·FlatLaf의 네이티브 라이브러리가 여기에 해당한다. 이 차이도 사용자 확인 대상이다.

## jar 안의 Mach-O 처리

`codesign --deep`은 jar 안의 항목과 `Contents/Eclipse` 아래 낱개 파일을 서명하지 않는다. 그래서 `macos_natives.py`가 직접 처리한다.

- 찾기: 파일 이름이 아니라 첫 바이트(magic)로 판단한다. thin Mach-O 4종과 universal(fat) 헤더. fat 헤더 `0xCAFEBABE`는
  Java class 파일과 같으므로 다음 4바이트(아키텍처 수)가 1~20일 때만 Mach-O로 본다(class 파일은 major 버전 45 이상).
  zip 형식(`PK\3\4`)이면 jar로 보고 안으로 들어간다(확장자 없는 p2 캐시 zip, jar 안의 jar 포함).
- 다시 넣기: 원래 jar의 항목 순서, 이름, 시각, 속성, 압축 방식(stored/deflated)을 그대로 두고 서명한 바이트만 바꾼다(`MANIFEST.MF`가 계속 첫 항목).
  Mach-O가 없는 jar는 건드리지 않는다. 안쪽 jar부터 처리해 바깥 jar에 넣는다.
- **jar 서명**: Eclipse 번들 jar 중 Mach-O를 담은 3개(SWT, core.filesystem.macosx, equinox.security.macosx)는
  Eclipse가 jar 서명(`META-INF/ECLIPSE_.SF`, `ECLIPSE_.RSA`)을 해 두었다. 항목 하나를 바꾸면 그 항목의 digest가 맞지 않아
  검증하며 여는 `JarFile`(URLClassLoader 등)이 `SecurityException`을 던진다. 그래서 바꾼 jar에서만 서명 파일
  (`META-INF/*.SF`, `*.RSA`, `*.DSA`, `*.EC`, `SIG-*`)을 지워 서명 없는 jar로 만든다. `MANIFEST.MF`의 항목별 digest는 서명 파일이 없으면 검사하지 않으므로 그대로 둔다.
  - Equinox는 서명된 번들 검사(`osgi.signedcontent.support`)를 켜지 않았다(제품의 `config.ini`, `chromite.ini`에 없음). 그래서 번들 jar 서명이 없어져도 동작이 바뀌지 않는다.
- 로컬 확인(가짜 서명기로 바이트를 바꿔 다시 쓴 사본):
  - 바뀐 jar는 `jarsigner -verify`에서 "jar is unsigned", `new JarFile(f, true)`로 모든 항목(SWT jar 859개)을 검증하며 읽어도 예외가 없다.
  - 서명 파일을 남긴 채 바꾸면 `java.lang.SecurityException: SHA-256 digest error for libswt-cocoa-4974r10.jnilib`가 난다(지우는 이유의 근거).
  - 바뀌지 않은 jar의 항목 목록(이름·압축 방식·시각)은 그대로였고, 모든 jar가 `testzip`을 통과했다.

## 권한(entitlements)

`tools/release/entitlements.mac.plist`. 런처가 자기 프로세스 안에서 JVM을 띄우므로 JVM이 필요한 권한이다.

| 키 | 이유 |
|---|---|
| `com.apple.security.cs.allow-jit` | HotSpot JIT가 실행 중에 기계어를 만든다 |
| `com.apple.security.cs.allow-unsigned-executable-memory` | HotSpot과 JNI/JNA로 불러온 네이티브 코드(libffi 클로저)가 MAP_JIT 없이 쓰기·실행 메모리를 만든다(JDK jpackage의 기본 권한에도 들어 있다고 알려져 있으나 이 작업에서 확인하지 않음) |
| `com.apple.security.cs.disable-library-validation` | JDBC 드라이버·JNA가 실행 중에 풀어 쓰는 네이티브 라이브러리는 우리 서명이 아니다. library validation이 켜져 있으면 모두 거부된다. 내장 런타임(`Contents/Eclipse/jre`)의 `libjli`/`libjvm`은 앱과 함께 우리 서명이 되므로 이 권한이 필요 없다(2026-11-07 전에는 앱에 JRE가 없어 사용자가 설치한 JDK를 불러오는 것도 이유였다) |

App Sandbox는 쓰지 않는다(사용자 파일, 네트워크, 드라이버를 제한 없이 쓴다). 낱개·jar 안의 라이브러리에는 권한을 주지 않는다(권한은 주 실행 파일의 것).

## 비밀값과 변수 이름

값은 이 문서·로그·저장소 어디에도 적지 않는다.

- 저장소 Variables(비밀 아님, Windows): `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`,
  `ARTIFACT_SIGNING_ENDPOINT`, `ARTIFACT_SIGNING_ACCOUNT`, `ARTIFACT_SIGNING_PROFILE`
- `signing` 환경의 Environment secrets(macOS): `APPLE_DEVID_P12_BASE64`, `APPLE_DEVID_P12_PASSWORD`,
  `APPLE_API_KEY_P8_BASE64`, `APPLE_API_KEY_ID`, `APPLE_API_ISSUER_ID`, `APPLE_TEAM_ID`
- 비밀값은 단계의 `env`로만 넘기고 `run:` 안에 직접 쓰지 않는다. 스크립트는 비밀값을 출력하지 않는다. 키체인 암호는 작업마다 새 난수이고 로그에서 가린다.

## UNSIGNED 동작

| 상태 | Windows(변수 6개) | macOS(비밀값 6개) |
|---|---|---|
| 모두 있음 | 서명·확인 후 서명 결과물 | 서명·공증·확인 후 서명 결과물 |
| 모두 없음 | 경고, 서명 없이 zip, 이름에 `UNSIGNED`, 결과물 `UNSIGNED-...-not-for-release`(3일) | 경고, 서명 없이 zip·dmg, 이름에 `UNSIGNED`, 구조만 확인 |
| 일부만 있음 | 작업 실패 | 작업 실패 |

한쪽이라도 `UNSIGNED`이면 `release` 작업이 돌지 않는다(Release 없음).

## 설계 결정과 이유

- **`build.yml`을 그대로 두고 `release.yml`을 따로 만들었다.** PR·devel 빌드는 서명 자격이 필요 없고 `signing` 환경도 쓰지 않는다.
  빌드 명령은 두 파일에 같게 적어 두었다(둘 다 고쳐야 한다).
- **macOS는 Linux에서 빌드하고 macOS 러너에서는 서명만 한다.** macOS 러너에서 빌드(수십 분)를 다시 하지 않고, ci.md에서 계획한 방식이다.
  두 앱을 macOS 작업 하나에서 처리한다(러너 배정을 한 번만 기다리고 키체인도 한 번 만든다).
- **콘솔 런처는 서명하지 않는다.** 원래 Eclipse 서명이 유효하므로 제3자 바이너리 규칙대로 둔다(로컬 빌드에서 `Valid` 확인).
- **macOS는 모든 Mach-O를 우리 서명으로 바꾼다.** 이미 다른 팀의 유효한 Developer ID가 있는 파일(Eclipse, DBeaver Corp)을 그대로 두는 방법도 있지만,
  FlatLaf처럼 서명 없는 파일은 어차피 jar를 고쳐야 하고, 다른 팀 서명에 hardened runtime·타임스탬프가 있는지 파일마다 따로 보장할 수 없어서 하나의 규칙으로 정했다.
- **Release는 초안으로 만든다.** 공개는 되돌리기 어렵다. 사용자가 확인한 뒤 공개한다. 이미 공개된 Release는 워크플로가 바꾸지 않는다.
- **두 OS가 모두 서명돼야 Release를 만든다.** 한쪽만 있는 릴리스를 막는다. 한쪽만 서명된 경우 서명 결과물은 워크플로 결과물로 7일 남는다.
- **태그는 제품 버전과 같아야 한다.** 파일 이름의 버전과 앱 안의 버전(Info.plist `CFBundleShortVersionString` 등)이 어긋나지 않게 한다.
  버전 체계는 아직 미결정이다([product-direction.md](product-direction.md)). 정해지면 이 검사도 그에 맞춘다.
- **dmg 파일 시스템은 HFS+(UDZO 압축)로 했다.** Apple Silicon이 지원하는 모든 macOS에서 열리고 압축 dmg로 가장 흔한 형식이다.
- **`macos-sign`에도 `id-token: write`를 두었다.** Apple은 OIDC를 쓰지 않지만 안내서의 서명 작업 틀(Windows와 같은 틀)을 따랐다.

## 확인한 것과 확인하지 못한 것

- 확인함(이 PC, Windows):
  - `release.yml`: PyYAML로 구문, 작업 간 `needs`·`steps.<id>.outputs` 참조, `run:` 안에 비밀값 직접 사용 없음을 검사했다.
  - `release-macos.sh`: `bash -n` 구문 검사. `macos_natives.py`: 컴파일, `scan` 실행(위 목록), 가짜 서명기로 jar 다시 쓰기 확인(위 "jar 안의 Mach-O 처리").
  - Windows 묶기 단계: 로컬 빌드로 그대로 실행해 zip 2개(676개·398개 파일, 런처 바이트 일치)를 확인했다(서명 단계 제외).
  - `version` 단계: `v26.2.5` 통과, `v0.1.0` 실패, `devel` 통과.
- 확인하지 못함:
  - `actionlint`, `shellcheck`: 이 PC에 없고 내려받지 않았다.
  - `codesign`, `notarytool`, `stapler`, `spctl`, `hdiutil`, Azure 서명: Windows PC라 실행할 수 없다. 워크플로도 실행하지 않았다(push·태그 없음).
  - 첫 `v*` 태그 실행(또는 `devel` 수동 실행)이 실제 확인이다.

## 남은 위험

- **실행 중 번들 수정**: Eclipse가 `Contents/Eclipse/configuration` 등 앱 안에 쓰면 봉인(seal)이 깨질 수 있다. 서명한 앱을 처음 실행한 뒤
  `codesign --verify --deep --strict`가 계속 통과하는지 Mac에서 확인해야 한다.
- **비표준 위치의 코드**: Mach-O가 `Contents/Eclipse/plugins` 아래에 있다. Eclipse IDE의 macOS 판도 같은 `Contents/Eclipse` 구조로 서명·공증되어 배포되는 것으로 알고 있으나, 이 작업에서는 확인하지 않았고 실행으로도 확인하지 않았다.
- **p2 캐시**: `p2/.../cache/binary/`의 런처 zip을 다시 쓰면 p2 메타데이터의 체크섬과 달라질 수 있다. Chromite는 p2 업데이트를 쓰지 않아 실행에는 영향이 없다고 보지만 확인하지 않았다.
- **macOS 러너 배정**: `macos-15`도 arm64 러너라 배정이 늦을 수 있다([ci.md](ci.md)). 필요하면 Intel 러너(`macos-15-intel`)로 바꿔도 서명·공증은 같다.
- **내장 런타임(2026-11-07)**: `Contents/Eclipse/jre`의 Mach-O(`lib/*.dylib`, `lib/server/libjvm.dylib`, `bin/java`, `bin/keytool` 등, `lib/jspawnhelper`)도
  `macos_natives.py`가 첫 바이트로 찾아 다른 낱개 파일처럼 안쪽부터 서명한다(권한 없이). JVM은 런처 프로세스 안에서 돌기 때문에 JIT 권한(`allow-jit`)은
  런처(주 실행 파일)의 권한으로 충분하다. 따로 실행되는 런타임 실행 파일은 `lib/jspawnhelper`(ProcessBuilder가 자식 프로세스를 띄울 때 거침)뿐이고
  JIT를 쓰지 않으므로 권한이 필요 없다고 판단했다. `bin/java`는 Chromite가 실행하지 않는다. Mac에서 실행해 확인하지는 못했다.

## 미결정 사항 (사용자 확인)

- 브랜딩한 Eclipse 런처(`chromite.exe`, `chromite-swing.exe`)를 SEIZIA 게시자로 서명해도 되는지.
- macOS에서 제3자 네이티브 라이브러리(Eclipse, JNA, SWT, junixsocket, FlatLaf)를 우리 Developer ID로 서명하는 것(Turbrix Electron 선례).
- Turbrix와 같은 Apple Developer ID·App Store Connect API 키를 쓸지.
- 버전 체계와 태그 이름(지금은 `v26.2.5`처럼 제품 버전).

## 사용자가 해야 할 일

- [x] Azure: Entra 앱에 GitHub OIDC 연합 자격 증명 `github-chromite-signing-env` 추가(2026-11-07),
      subject `repo:froiibn@101344212/chromite@1447161703:environment:signing`.
      이 저장소는 변경 불가 OIDC subject를 쓴다(`gh api repos/froiibn/chromite/actions/oidc/customization/sub`가
      `use_immutable_subject: true`). 그래서 subject 앞부분이 `repo:<소유자>/<저장소>`가 아니라
      `repo:<소유자>@<소유자 ID>/<저장소>@<저장소 ID>`이고, 저장소 이름을 바꿔도 subject는 그대로다.
      `AADSTS700213 No matching federated identity record ... subject '...'` 오류가 나오면 오류에 찍힌 subject와 위 값을 비교한다.
- [x] GitHub 저장소 Variables 6개(2026-11-07): `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`,
      `ARTIFACT_SIGNING_ENDPOINT`, `ARTIFACT_SIGNING_ACCOUNT`, `ARTIFACT_SIGNING_PROFILE`.
- [x] GitHub 환경 `signing`(2026-11-07): Deployment branches and tags "Selected", 브랜치 `devel`, 태그 `v*`만 허용.
- [ ] `signing` 환경의 Environment secrets 6개(아직 없음, Apple 계정 필요): `APPLE_DEVID_P12_BASE64`, `APPLE_DEVID_P12_PASSWORD`,
      `APPLE_API_KEY_P8_BASE64`, `APPLE_API_KEY_ID`, `APPLE_API_ISSUER_ID`, `APPLE_TEAM_ID`.
- [ ] Turbrix의 Developer ID·API 키를 같이 쓸지 결정.
- [ ] 위 "미결정 사항" 답변. 그다음 `devel`에서 수동 실행으로 서명 경로를 한 번 확인(Release는 만들지 않음)하고, `v*` 태그로 첫 릴리스.

## upstream 대비 변경점

| 파일 | 변경 |
|---|---|
| `.github/workflows/release.yml` | 추가 |
| `tools/release/release-macos.sh`, `tools/release/macos_natives.py`, `tools/release/entitlements.mac.plist` | 추가 |

DBeaver는 이 저장소에 릴리스·서명 설정이 없다(별도 빌드 저장소에서 한다).

## 변경 이력

- 2026-11-06 최초 작성. Turbrix 서명 안내서(2026-11-06)를 따라 `release.yml`(Windows: Azure Artifact Signing, macOS: Developer ID 서명·공증·staple),
  macOS 서명 스크립트, jar 안 Mach-O 처리, 권한 plist를 추가했다. 로컬 빌드로 서명 대상 목록을 확인했다.
- 2026-11-06 Windows 아이콘 생성 스크립트 이름 변경(`gen_launcher_ico.py` → `gen_app_icons.py`)에 맞춰 링크 수정.
- 2026-11-07 "사용자가 해야 할 일" 갱신: Azure 연합 자격 증명(변경 불가 OIDC subject), 저장소 Variables, `signing` 환경은 완료,
  Apple 비밀 값은 남음. 내부 이름 변경([internal-rename.md](internal-rename.md))으로 `release.yml`의 SWT 제품 폴더가
  `product/community/target/products/com.seizia.chromite.core.product`, 버전을 읽는 파일이 `product/community/Chromite.product`가 됐다.
  이 경로들은 CI의 `tools/rename/gate.py`(G8)가 제품 uid·저장소 경로와 맞는지 검사한다.
- 2026-11-07 내장 런타임 반영: Windows 런타임 exe·dll(제3자, 서명하지 않음), macOS 런타임 Mach-O 39개(기존 단계가 서명),
  `disable-library-validation` 이유 갱신(`entitlements.mac.plist` 주석 포함), `release.yml`의 런타임 단계, "남은 위험"의 JRE 항목.
