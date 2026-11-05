# 브랜딩

## 목적

DBeaver Community 포크를 **Chromite** 제품으로 배포하기 위해, 사용자에게 보이는 이름, 로고, 아이콘, 제품 정보를 바꾼다.

- Apache License 2.0은 상표권을 허락하지 않는다(제6조). 따라서 "DBeaver" 이름과 로고는 사용자에게 보이는 곳에서 빼야 한다.
- 출처 표기("DBeaver Community 기반")와 원본 저작권 표시는 유지해야 한다.

## 결정 사항

| 항목 | 값 |
|---|---|
| 제품명 | `Chromite` |
| 부제 | `Database Workbench` (로고 문구와 같음) |
| 실행 파일 | `chromite` (`chromite.exe`) |
| macOS 앱 폴더 | `Chromite.app` |
| 배포 파일 이름 | `chromite-<버전>-<플랫폼>` |
| 웹사이트 | https://github.com/froiibn/chromite |
| 저작권 표기 | `© 2026 Chromite contributors. Based on DBeaver Community © DBeaver Corp` |

## 구조

### 로고 원본

`chromite_logo_pack/`에 원본 PNG 6개가 있다.

| 파일 | 크기 | 용도 |
|---|---|---|
| `chromite_full_dark.png` | 1275×395 | 스플래시, README |
| `chromite_full_light.png`, `chromite_full_transparent.png` | 1275×395 | 현재 사용하지 않음 |
| `chromite_symbol_dark.png` | 360×395 | 모든 아이콘의 원본 |
| `chromite_symbol_light.png`, `chromite_symbol_transparent.png` | 360×395 | 현재 사용하지 않음 |

### 파생 이미지

모든 파생 이미지는 로고 원본에서 생성했다. 파일 이름은 upstream과 같게 유지하고 내용만 바꿨다.

| 파일 | 크기 | 쓰이는 곳 |
|---|---|---|
| `product/community/icons/dbeaver.ico` | 16~256 (7단계) | Windows 실행 파일 아이콘 |
| `product/community/icons/dbeaver.icns` | 16~1024 | macOS 앱 아이콘 |
| `product/community/icons/dbeaver.xpm` | 128×128, 2 cpp | Linux 실행 파일 아이콘 |
| `product/community/icons/dbeaver.png` | 256×256 | Linux 패키지 루트 아이콘 (`features/org.jkiss.dbeaver.ce.feature/build.properties`) |
| `product/community/icons/dbeaver_back.bmp` | 165×314 | 설치 프로그램 측면 이미지 |
| `product/community/icons-sources/icon_*.png` | 16~1024 | 아이콘 원본 세트 |
| `plugins/org.jkiss.dbeaver.ui.app.standalone/icons/dbeaver{16,32,48,64,256}.png` | 각 크기 | 창 아이콘 (`plugin.xml`의 `windowImages`), `about.ini`, 설정 가져오기 대화상자 |
| `plugins/org.jkiss.dbeaver.ui.app.standalone/icons/dbeaver_about.png` | 256×256 | About 대화상자 (`AboutBoxDialog`) |
| `plugins/org.jkiss.dbeaver.ui.app.standalone/splash.png` | 600×300 | 시작 화면 |
| `plugins/org.jkiss.dbeaver.ui/icons/dbeaver_logo_{medium,small,small@2x}.png` | 48, 16, 32 | `UIIcon.DBEAVER_LOGO*` |
| `plugins/org.jkiss.dbeaver.ui/icons/dbeaver_perspective{,@2x}.png` | 16, 32 | 퍼스펙티브 아이콘 (`org.jkiss.dbeaver.core/plugin.xml`) |

- 아이콘 모양: `chromite_symbol_dark.png`를 남색 둥근 사각형(모서리 반경 20%) 위에 높이 84%로 올렸다.
  `chromite_symbol_transparent.png`는 배경이 완전히 투명하지 않고 반투명 남색이 남아 있어서 아이콘에 쓰지 않았다.
- 스플래시: 남색 배경에 전체 로고를 폭 520px로 위쪽(y=40)에 배치했다.
  진행 메시지와 진행 막대 위치(`startupMessageRect`, `startupProgressRect`)는 upstream 값을 그대로 쓴다.

### 제품 정보 파일

| 파일 | 바꾼 내용 |
|---|---|
| `product/community/product-branding.properties` | 패키지명, 앱 이름, 제품명, 설명, 상표 문구, 저작권, 회사, 웹사이트, 저장소. 이메일은 비움 |
| `product/community/DBeaver.product` | 제품 `name="Chromite"`, 실행 파일 `launcher name="chromite"` |
| `product/community/pom.xml` | 배포 파일 이름 `chromite-${dbeaver-version}`, 루트 폴더 `Chromite.app` / `chromite` |
| `product/community/DBeaver.p2.inf` | DBeaver CE 업데이트 사이트 제거 |
| `plugins/org.jkiss.dbeaver.ui.app.standalone/plugin.xml` | 제품 이름, `appName`, 저작권, 버전 표시 위치(`versionInfoCoord`), 업데이트 확인 URL 제거 |
| `plugins/org.jkiss.dbeaver.ui.app.standalone/about.properties` | About 문구와 출처 표기 |
| `plugins/org.jkiss.dbeaver.ui.app.standalone/OSGI-INF/l10n/bundle*.properties` (13개 언어) | `productName`, `aboutText`의 제품명, `productWebsite`, `productEmail`, 영어로 남아 있던 `productSubTitle` |
| `NOTICE` | 출처, 원본 저작권, 상표 고지 (신규) |
| `README.md` | Chromite 소개, 빌드 방법, 라이선스 안내로 새로 작성 |

## 설계 결정과 이유

- **내부 이름은 유지한다.** 패키지명(`org.jkiss.dbeaver.*`), 번들 ID, 제품 ID(`org.jkiss.dbeaver.core.product`),
  Maven 프로파일(`product-dbeaver-ce`), 이미지 파일 이름은 바꾸지 않았다. 사용자에게 보이지 않고,
  사용자 설정과 플러그인 참조가 이 이름에 묶여 있어 바꾸려면 별도 작업으로 한꺼번에 해야 하기 때문이다.
  (처음에는 upstream 병합 충돌을 줄이려는 이유도 있었으나, 2026-11-06 독립 개발로 정해 그 이유는 없어졌다.)
- **업데이트 확인을 끈다.** `versionUpdateURL`과 `earlyAccessURL`을 지웠다.
  `DBeaverVersionChecker`는 URL이 없으면 확인을 건너뛴다. 남겨 두면 DBeaver 새 버전을 안내한다.
- **DBeaver CE p2 업데이트 사이트를 뺀다.** 남겨 두면 "업데이트 확인"에서 DBeaver 번들이 Chromite를 덮어쓴다.
- **확장 업데이트 사이트는 남긴다.** Office, Git, Debug 확장(dbeaver.io)은 기능 유지를 위해 그대로 두었다.
- **이메일은 비운다.** `productEmail`은 About 대화상자에서 쓰이지 않는다(`AboutBoxDialog`의 이메일 속성은 주석 처리돼 있음).
- **버전 표시 위치를 옮긴다.** upstream 위치(154,53)는 새 로고와 겹쳐서 로고 아래(60,225)로 옮겼다.

## upstream 대비 남은 차이와 미완료 항목

- 메뉴, 대화상자, 메시지 등 화면 문구의 "DBeaver"는 아직 바꾸지 않았다(2단계 예정).
  properties 198개 파일, Java 93개 파일에 있다.
- `product/community-msstore`, `product/testing` 제품 구성은 아직 바꾸지 않았다.
- 빌드와 실행은 아직 확인하지 않았다(JDK 21과 Maven 필요).

## 변경 이력

- 2026-11-06 1단계 리브랜딩: 제품명, 실행 파일, 배포 파일 이름, 아이콘, 스플래시, About 화면, 다국어 제품명을 Chromite로 변경.
  DBeaver 업데이트 확인과 CE 업데이트 사이트 제거. `NOTICE` 추가, `README.md` 재작성. (PR froiibn/chromite#1)
- 2026-11-06 일본어 `aboutText`에 영어로 남아 있던 부제를 `Database Workbench`로 수정.
- 2026-11-06 독립 개발 결정에 따라 "내부 이름 유지" 결정의 이유를 갱신.
- 2026-11-06 `plugins/*/META-INF/MANIFEST.MF`의 `Bundle-Name` 접두어 `DBeaver`를 `Chromite`로 변경(157개 번들,
  Help > About > Installation Details > Plug-ins에 표시됨). `Bundle-Vendor: DBeaver Corp`는 출처 표기로 유지.
- 2026-11-06 실행 파일 설정 파일 경로 수정: `BasePlatformImpl.getApplicationConfiguration()`이 없는 `dbeaver.ini`를
  가리키던 문제를 고쳤다. `eclipse.ini`가 없으면 `eclipse.launcher`(네이티브 실행 파일 경로) 이름으로 `<이름>.ini`를 찾고,
  없으면 `chromite.ini`를 쓴다. ZIP 업데이트 스크립트의 콘솔 실행 파일 이름도 `chromitec` → `chromite`로 수정.
- 2026-11-06 진단 정보 수집(`DiagnosticInfoSanitizer`)이 시작 로그 줄(`<제품명> <버전> is starting`)의 버전을
  IP 주소로 보고 가리던 문제 수정: `GeneralUtils.getProductName()`도 확인한다. 같은 작업공간을 쓰던 DBeaver의
  이전 로그를 위해 "DBeaver"도 계속 인식한다.
- 2026-11-06 AI 프롬프트(`AIPromptUtils`)의 제품명을 Chromite로 변경: "You are the Chromite AI assistant.",
  "Chromite connection name: ".
- 2026-11-06 `plugin.xml`의 Microsoft Store 제품 정의(`msstore`) 저작권을 standalone 제품과 같은
  `© 2026 Chromite contributors. Based on DBeaver Community © DBeaver Corp`로 변경. 이름과 `appName`은 이미 Chromite다.
