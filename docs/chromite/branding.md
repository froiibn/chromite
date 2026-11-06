# 브랜딩

## 목적

DBeaver Community 포크를 **Chromite** 제품으로 배포하기 위해, 사용자에게 보이는 이름, 로고, 아이콘, 제품 정보를 바꾼다.

- Apache License 2.0은 상표권을 허락하지 않는다(제6조). 따라서 "DBeaver" 이름과 로고는 사용자에게 보이는 곳에서 빼야 한다.
- 출처 표기("DBeaver Community 기반")와 원본 저작권 표시는 유지해야 한다.

## 결정 사항

| 항목 | 값 |
|---|---|
| 제품명 | `Chromite` |
| 부제 | `Database Workbench` (로고 문구와 같음). 한국어 `데이터베이스 워크벤치` |
| 실행 파일 | `chromite` (`chromite.exe`) |
| macOS 앱 폴더 | `Chromite.app` |
| 배포 파일 이름 | `chromite-<버전>-<플랫폼>` |
| 웹사이트 | https://github.com/froiibn/chromite |
| 저작권 표기 | `© 2026 Chromite contributors. Based on DBeaver Community © DBeaver Corp` |

## 구조

### 아이콘

**결정 (2026-11-06):** 사용자가 직접 고른 디자인을 쓴다. 남색 둥근 사각형 타일, 흰색 3단 데이터베이스 원통,
원통 오른쪽 아래에서 나아가는 흰색 현대 군함 실루엣(원통과는 남색 틈으로 분리), 그 아래 하늘색→파란색 물결 두 줄.
사용자가 준 래스터 이미지를 손으로 작성한 벡터 도형으로 다시 그렸다(구도, 비율, 색, 함선 실루엣은 원본을 따름).
디자인은 사용자 소유라 `NOTICE`는 바뀌지 않는다. 1·2차 시안(육각 C, 등대 등)은 쓰지 않는다.

- 원본에서 잰 값: 타일 모서리 반경 23.8%, 원통 좌우 239~749(1000 기준), 띠 사이 틈 37~39, 갑판 틈 15.
  타일과 원통 음영은 원본 픽셀에 맞춘 방사형 그라데이션이다. 곡선은 원본 경계 점에 맞춘 3차 베지에다(오차 2 이하).
- 원본에서 그대로 옮기지 않은 것: 함선 둘레의 남색 틈은 원본이 덩어리진 모양(AI 생성 흔적)이라 폭 20(1000 기준)의
  일정한 틈으로 바꿨다. 돛대 오른쪽 위의 푸른 점 같은 생성 잡티도 뺐다.
  원본 타일은 829×816으로 약간 납작해서 정사각형으로 맞췄고, 원통 위치는 128px 격자에 맞추려고 최대 5(1000 기준) 옮겼다.

**크기별 단계.** 한 그림으로 16~1024px를 다 그리면 작은 크기에서 뭉개지므로 4단계로 나눈다.
작은 단계는 쓰이는 크기마다 그 크기의 픽셀 격자에 맞춰 그린다(원통 옆선은 정수 열, 띠 경계는 가운데 열에서 정수 행이라 틈이 회색이 아닌 남색으로 나온다).

| 단계 | 크기 | 내용 |
|---|---|---|
| full | 128px 이상 | 원본 재현: 돛대, 활대, 레이더, 깃대, 창, 갑판 틈, 항적, 물결 |
| simple | 40, 48, 64 | 같은 구도. 창·레이더·깃대 없음, 틈을 넓히고 물결을 굵게 |
| mark | 24, 32 | 원통 + 갑판실 하나와 돛대만 있는 선체 + 굵은 물결 하나 |
| tiny | 16, 20 | 원통(윗면 + 띠 2개) + 물결 하나. 이 크기에서 함선은 뭉개져서 뺐다 |
| glyph | 16 | 원통 + 물결, `currentColor` 단색, 타일 없음 (단색 아이콘용, 아직 쓰는 곳 없음) |

- Windows `.ico`, Swing 창 아이콘: 타일이 정사각형을 채운다.
- macOS `.icns`: Apple 격자(1024 안에 824 둥근 사각형 타일, 여백 100, 그림자). 16·32·64px 항목은 여백 안 타일의 픽셀 격자에 맞춰 따로 그린다.
- 가로 로고(워드마크): 새 타일 아이콘 + 얇은 세로 구분선 + 원래 로고의 글자. 글자는 1단계 로고(`chromite_full_*.png`, devel)의 배치와 모양을 따른다(2026-11-06 사용자 결정).
  - `CHROMITE`: 원래 로고를 따라 그린 굵은 기울임 기하 글자 8개를 도형으로 만들었다(글꼴 없음, `TITLE_GLYPHS`). 기울임을 펴고 재서
    대문자 높이 93, 세로획 20, 가로획 19, 바깥 모서리 반경 23으로 정리한 뒤 원래 기울기 0.17(9.6도)로 다시 기울인다.
    E의 윗가로획은 원래처럼 오른쪽으로 더 나온 파란 강조 획(`#1D95FD`→`#1478F5`)이다.
  - `DATABASE WORKBENCH`: 원래 글자는 디스크에 있는 글꼴이 아니라서, 같은 대문자 높이(25)와 굵기의 Inter SemiBold(SIL OFL 1.1, FlatLaf 글꼴 jar)
    글자를 원래 글자 17개의 가운데 위치에 하나씩 놓았다. 그래서 넓은 자간이 원래와 같다.
  - 색: 어두운 판은 흰 글자, 회색 부제(`#B9BCC6`)와 구분선(`#BDC3CF`). 밝은 판은 글자, 부제, 구분선 모두 남색(`#18253B`). 원래 로고와 같다.
  - 원래 로고와 겹쳐 비교하면 글자 경계 차이는 1px 안팎이다.

### 원본(SVG)과 다시 만드는 방법

모든 이미지는 `tools/branding/gen_app_icons.py`가 만든다. 도형은 이 스크립트 안에 좌표로 있고,
스크립트가 SVG 원본을 `chromite_logo_pack/`에 쓴 뒤 그 SVG를 렌더링해 아래 파생 이미지를 모두 다시 쓴다.
SVG는 생성 파일이라 손으로 고치지 않는다(`gen_ui_icons.py`와 같은 규칙). 바꾸려면 스크립트를 고치고 다시 실행한다.

```powershell
# 한 번만: D 드라이브에 가상 환경을 만들고 렌더링 패키지를 설치
py -3 -m venv D:\Tools\_venvs\chromite-branding
D:\Tools\_venvs\chromite-branding\Scripts\pip install resvg-py Pillow fonttools
# 실행 (저장소 루트에서). Swing 번들을 한 번 빌드했으면 --inter-jar는 생략할 수 있다
D:\Tools\_venvs\chromite-branding\Scripts\python tools/branding/gen_app_icons.py --inter-jar D:\Tools\m2\repository\com\formdev\flatlaf-fonts-inter\4.1\flatlaf-fonts-inter-4.1.jar
```

확인한 버전: Python 3.13, resvg-py 0.5.0, Pillow 13.0.0, fonttools 4.68.0. 같은 버전이면 결과가 바이트 단위로 같다.

| `chromite_logo_pack/` 파일 | 내용 |
|---|---|
| `chromite_icon.svg` | full 단계, 타일 포함 (1000 격자) |
| `chromite_icon_{64,48,40}.svg`, `_{32,24}.svg`, `_{20,16}.svg` | simple, mark, tiny 단계 (각 크기의 픽셀 격자) |
| `chromite_icon_macos.svg`, `chromite_icon_macos_{64,32,16}.svg` | macOS Apple 격자 |
| `chromite_glyph16.svg` | 단색 글리프 |
| `chromite_wordmark_{light,dark}.svg` | 가로 로고 (투명 배경, 밝은 바탕용 남색 글자 / 어두운 바탕용 흰 글자) |
| `chromite_symbol_{dark,light,transparent}.png` | 360×395, 아이콘을 어두운 바탕 / 흰 바탕 / 투명 위에 |
| `chromite_full_{dark,light,transparent}.png` | 1275×395, 가로 로고를 어두운 바탕(`#01041A`, 원래 로고 바탕) / 흰 바탕 / 투명 위에. 투명 판은 원래처럼 흰 글자. `README.md`가 `chromite_full_dark.png`를 쓴다 |

### 파생 이미지

파일 이름은 upstream과 같게 유지하고 내용만 바꿨다(내부 파일 이름은 바꾸지 않는다는 원칙).

| 파일 | 크기 | 쓰이는 곳 |
|---|---|---|
| `product/swing/icons/chromite.ico` | 256/48/32/16 32비트, 48/32/16 8비트 (BMP 7개) | Windows 실행 파일 아이콘 `chromite-swing.exe` (`chromite-swing.product`) |
| `product/swing/icons/chromite.icns` | 16~1024 (PNG 10개) | macOS 앱 아이콘 |
| `product/swing/icons/chromite_back.bmp` | 165×314 | 설치 프로그램 측면 이미지 (저장소 안에서는 참조 없음) |
| `product/swing/icons-sources/icon_*.png` | 16~1024 | macOS 아이콘 세트 (빌드에서는 쓰지 않음) |
| `plugins/com.seizia.chromite.ui.swing/icons/chromite{16,20,24,32,40,48,64,128,256}.png` | 각 크기 | Swing 창·작업 표시줄 아이콘, 시작 탭 로고(128), About 로고(64) (`ChromiteIcons`) |

- 설치 프로그램 측면 이미지: 타일 그라데이션 바탕 위쪽에 아이콘(112px).
- SWT 제품의 창 아이콘·About 이미지·스플래시·`UIIcon` 로고는 SWT 프런트엔드와 함께 삭제했다([swt-removal.md](swt-removal.md)).
  제품 아이콘(`.ico`, `.icns`, 아이콘 세트, 설치 이미지)은 `product/community/icons*`에서 `product/swing/icons*`로 옮겼다.

### 제품 정보 파일

| 파일 | 바꾼 내용 |
|---|---|
| `product/swing/chromite-swing.product` | 제품 `name="Chromite"`, 실행 파일 `launcher name="chromite-swing"`, 아이콘 `icons/chromite.ico`·`icons/chromite.icns` |
| `product/swing/pom.xml` | 배포 파일 이름 `chromite-swing-${dbeaver-version}`, 루트 폴더 `ChromiteSwing.app` / `chromite-swing` |
| `plugins/com.seizia.chromite.ui.swing/plugin.xml` | 제품 확장: 이름 `Chromite`, `appName`, 부제 `Database Workbench`, 저작권 |
| `NOTICE` | 출처, 원본 저작권, 상표 고지 (신규) |

1단계 리브랜딩에서 바꾼 SWT 제품 파일(`product/community/product-branding.properties`, `Chromite.product`, `Chromite.p2.inf`,
`category.xml`, `ui.app.standalone`의 `plugin.xml`·`about.properties`·`OSGI-INF/l10n/bundle*.properties`)은 2026-11-07 SWT 제품과 함께 삭제했다
([swt-removal.md](swt-removal.md)). 아래 "업데이트 확인을 끈다", "p2 업데이트 사이트를 뺀다", "버전 표시 위치" 결정은 그 파일들에 대한 기록이다.
| `README.md` | Chromite 소개, 빌드 방법, 라이선스 안내로 새로 작성 |

## 설계 결정과 이유

- **내부 이름은 별도 작업에서 한꺼번에 바꿨다.** 1단계에서는 패키지명, 번들 ID, 제품 ID, Maven 프로파일, 이미지 파일 이름을
  유지했다. 사용자 설정과 플러그인 참조가 이 이름에 묶여 있어 일부만 바꾸면 기능이 깨지기 때문이다.
  2026-11-07 [internal-rename.md](internal-rename.md)에서 모두 Chromite 이름으로 바꿨다(`com.seizia.chromite.*`,
  당시 SWT 제품 ID `com.seizia.chromite.core.product`, 프로파일 `product-chromite-swt`, 이미지 `chromite*.png/ico/icns`. SWT 제품은 이후 삭제).
- **업데이트 확인을 끈다.** `versionUpdateURL`과 `earlyAccessURL`을 지웠다.
  `ChromiteVersionChecker`는 URL이 없으면 확인을 건너뛴다. 남겨 두면 DBeaver 새 버전을 안내한다.
- **DBeaver CE p2 업데이트 사이트를 뺀다.** 남겨 두면 "업데이트 확인"에서 DBeaver 번들이 Chromite를 덮어쓴다.
- **dbeaver.io 확장 업데이트 사이트도 뺀다.** 처음에는 Office, Git, Debug 확장 사이트
  (`https://dbeaver.io/update/{office,git,debug}/latest/`)를 기능 유지를 위해 남겼으나 2026-11-06 사용자 결정으로 뺐다.
  이 세 확장은 Chromite 제품 기능(`com.seizia.chromite.ce.feature`)에 들어 있지 않고 DBeaver Corp가 빌드해 올린 번들이라,
  Chromite 사용자가 DBeaver 서버에서 받을 이유가 없다. 이제 Install New Software에서 이 확장을 설치할 수 없다.
- **category.xml의 dbeaver.io 사이트 설명도 뺀다.** `<description name="DBeaver ..." url="https://dbeaver.io/update/...">`는
  빌드한 p2 저장소의 설명으로만 쓰이고, DBeaver가 운영하는 사이트를 가리킨다. 설명 요소는 선택 항목이라 지웠다.
- **이메일은 비운다.** `productEmail`은 About 대화상자에서 쓰이지 않는다(`AboutBoxDialog`의 이메일 속성은 주석 처리돼 있음).
- **Windows 아이콘(`chromite.ico`)은 BMP 7개로 만든다.** 빌드 중 p2 `BrandingIron`이 `IconExe`로 launcher.exe의
  아이콘 리소스를 바꾼다. `IconExe`는 ICO 항목을 BMP(DIB)로만 읽고 PNG 항목이 하나라도 있으면 파일 전체를 읽지 못하며
  (`Error 40`), 실행 파일 아이콘과 크기와 색 깊이가 정확히 같은 이미지만 같은 자리에 덮어쓴다.
  Eclipse 4.x launcher의 아이콘은 256×256, 48×48, 32×32, 16×16 32비트와 48×48, 32×32, 16×16 8비트(256색)다
  ([IconExe.java](https://github.com/eclipse-equinox/p2/blob/master/bundles/org.eclipse.equinox.p2.publisher.eclipse/src/org/eclipse/pde/internal/swt/tools/IconExe.java) "Note 3").
  Pillow의 ICO 저장은 모든 항목을 32비트 PNG로 쓰므로 `tools/branding/gen_app_icons.py`가 ICO를 직접 쓴다.
  각 항목은 그 크기의 단계 그림이고(16은 tiny, 32는 mark, 48은 simple, 256은 full), 8비트 항목은 256색으로 줄이고 투명은 AND 마스크로 표현한다.
- **macOS 아이콘(`chromite.icns`)도 직접 쓴다.** Pillow의 ICNS 저장은 16px(`icp4`)과 32px(`icp5`) 항목을 넣지 않아
  Finder 목록 보기 등에서 큰 그림을 줄여 쓴다. 스크립트는 PNG 항목 10개(`icp4`, `icp5`, `ic11`, `ic12`, `ic07`, `ic08`,
  `ic13`, `ic09`, `ic14`, `ic10`)를 쓴다.
- **아이콘 스크립트는 하나로 둔다.** `gen_launcher_ico.py`를 `gen_app_icons.py`로 이름을 바꿔 모든 아이콘·로고 이미지를 만들게 했다.
  ICO만 따로 만드는 스크립트를 두면 같은 그림을 두 곳에서 그리게 되어 서로 달라질 수 있기 때문이다.
- **Swing 화면의 오류 문구에는 클래스·패키지 이름을 보이지 않는다.** 예외를 `toString()`째 보여 주면 `com.seizia.chromite.…Exception:`이 화면에 나온다.
  Swing 번들은 예외 문구를 모두 `ErrorText`로 만들고, 패키지 이름이 있는 스택 추적은 오류 대화상자의 접힌 "자세히"에만 둔다
  ([swing-frontend.md](swing-frontend.md) "오류 문구"). 오류 대화상자 제목에 쓰이던 모델 Job 이름(`Connect to '…'`)도 지역화된 문구로 바꿨다.
- **버전 표시 위치를 옮긴다.** upstream 위치(154,53)는 새 로고와 겹쳐서 로고 아래(60,225)로 옮겼다.

## upstream 대비 남은 차이와 미완료 항목

- 메뉴, 대화상자, 메시지 등 화면 문구의 "DBeaver"는 아직 바꾸지 않았다(2단계 예정).
  properties 198개 파일, Java 93개 파일에 있다.
- `product/testing` 제품 구성(테스트 전용)은 아직 바꾸지 않았다.
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
- 2026-11-06 Windows 실행 파일 아이콘 수정: 1단계 `chromite.ico`가 PNG 항목만 있어 `IconExe`가 읽지 못했고
  (`[IconExe] Error - 7 original icon(s) not replaced`), `chromite.exe`에 Eclipse 아이콘이 남아 있었다.
  upstream과 같은 BMP 7개 구성으로 다시 만들고 생성 스크립트 `tools/branding/gen_launcher_ico.py`를 추가했다.
- 2026-11-06 다국어 부제(`productSubTitle`, `aboutText`)에 남아 있던 DBeaver 문구("Universal Database Manager"의 번역,
  한국어 "통합 데이터베이스 관리툴")를 Chromite 부제로 변경. 한국어 `데이터베이스 워크벤치`, 독일어 `Datenbank-Workbench`,
  프랑스어 `Atelier de bases de données`, 러시아어 `Рабочая среда для баз данных`, 우크라이나어
  `Робоче середовище для баз даних`, 중국어 간체 `数据库工作台`, 번체 `資料庫工作台`. 확실한 번역이 없는 스페인어,
  이탈리아어(`aboutText`), 폴란드어, 포르투갈어(브라질), 루마니아어는 영어 `Database Workbench`를 쓴다.
- 2026-11-06 Linux를 빌드하지 않게 되어([runtime.md](runtime.md)) Linux 전용 아이콘 `product/community/icons/chromite.xpm`,
  `chromite.png`를 삭제.
- 2026-11-06 `Chromite.p2.inf`에서 dbeaver.io Office, Git, Debug 확장 업데이트 사이트(type 0, 1 각 3개) 제거.
  `category.xml` 5개(`product/community`, `product/repositories/*`)에서 dbeaver.io를 가리키던 `<description>` 제거.
- 2026-11-06 앱 아이콘을 사용자가 고른 디자인(원통 + 군함 + 물결)으로 교체. 사용자가 준 래스터를 벡터로 다시 그리고
  크기별 단계(full, simple, mark, tiny, 단색 글리프)와 워드마크를 만들었다. `chromite_logo_pack/`에 SVG 원본을 추가하고
  PNG 6개, `.ico`, `.icns`, 아이콘 세트, SWT 창 아이콘·About·스플래시·설치 이미지, `UIIcon` 로고, Swing 창 아이콘을 모두
  다시 만들었다. `tools/branding/gen_launcher_ico.py`를 `gen_app_icons.py`로 바꿔 이 전부를 만들게 했다.
- 2026-11-06 가로 로고 글자를 Inter 워드마크에서 원래 로고의 글자로 바꿈(사용자 결정): 기울임 기하 글자 `CHROMITE`와 E의 파란 강조 획,
  얇은 세로 구분선, 원래 위치에 놓은 넓은 자간 부제. `chromite_full_*.png`, `chromite_wordmark_*.svg`, `chromite_symbol_dark.png`(바탕색), 스플래시를 다시 만듦.
- 2026-11-07 Swing 오류 문구에서 Java 클래스·패키지 이름(`com.seizia.chromite…`)이 보이지 않게 함(설계 결정 항목 추가). [swing-frontend.md](swing-frontend.md) "오류 문구".
- 2026-11-07 내부 이름 변경([internal-rename.md](internal-rename.md))에 맞춰 "내부 이름은 유지한다" 결정을 갱신.
  표와 경로의 파일 이름(`Chromite.product`, `chromite*.png` 등)은 이름 변경 도구가 함께 바꿨다.
- 2026-11-07 Microsoft Store·Eclipse 플러그인 제품 삭제([build.md](build.md) "빌드하는 제품")로 `ui.app.eclipse` 아이콘과 `community-msstore` 미완료 항목을 지움.
- 2026-11-07 SWT 프런트엔드 삭제([swt-removal.md](swt-removal.md)): 제품 아이콘을 `product/swing/icons*`로 옮기고, 파생 이미지·제품 정보 파일 표를 Swing 제품 기준으로 고침.
