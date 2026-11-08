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
| 에이전트 기능 이름 | `Chromite Agent`. 영어·한국어 모두 옮기지 않는다. "AI 도우미"·"AI Assistant"라고 쓰지 않는다(사용자 결정 2026-11-07, [ai-agent.md](ai-agent.md) "명칭") |

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
| `plugins/com.seizia.chromite.splash/splash.png` | 560×320 | 시작 화면. 네이티브 런처가 JVM보다 먼저 띄운다(아래 "시작 화면") |
| `plugins/com.seizia.chromite.splash/splash@2x.png` | 1120×640 | 같은 시작 화면의 HiDPI 판. Java가 돈 뒤 Swing 시작 화면(`StartupSplash`)이 그린다 |
| `site/favicon.ico`, `site/assets/icons/icon-{32,192}.png`, `apple-touch-icon.png`, `site/assets/og/og-{ko,en}-3.png` | 16/32/48, 32, 192, 180, 1200×630 | 다운로드 사이트 파비콘과 공유 이미지. `tools/branding/gen_site_images.py`가 이 스크립트의 크기별 그림으로 만든다([distribution.md](distribution.md) "파비콘과 공유 이미지") |
| `site/assets/brand/wordmark-{light,dark}.svg` | 807×93 (벡터) | 다운로드 사이트 머리글의 워드마크(아래 "사이트의 로고"). `gen_site_images.py`가 가로 로고의 글자 도형으로 만든다 |
| `site/assets/brand/logo-{light,dark}.svg` | 1136×250 (벡터) | 다운로드 사이트 홈 첫 화면과 언어 선택 페이지의 가로 로고. `lockup_svg(pad=0)` 그대로(Swing `brand/lockup-*.svg`와 같은 그림) |

- 설치 프로그램 측면 이미지: 타일 그라데이션 바탕 위쪽에 아이콘(112px).

### 사이트의 로고 (2026-11-08)

사용자 요청: "헤더는 글자만 잘라서 배치하고, 페이지 상단에 풀 로고 배치". 다운로드 사이트에서 글자로 쓰던 이름 "Chromite"(아이콘 + 글자)를 로고 그림으로 바꿨다.

| 자리 | 그림 | 밝은 테마 / 어두운 테마 |
|---|---|---|
| 모든 페이지 머리글 왼쪽(`.brand`, 홈·변경 내역·개인정보·지원 범위) | 워드마크: `CHROMITE` 글자와 E의 파란 강조 획만(아이콘·구분선·부제 없음), 높이 16px | `wordmark-light.svg`(남색 `#18253B`) / `wordmark-dark.svg`(흰색). `<picture>`의 `prefers-color-scheme` |
| 홈 첫 화면 맨 위(`h1`, 예전 큰 글자 "Chromite" 자리) | 가로 로고(아이콘 + `CHROMITE` + `DATABASE WORKBENCH`), 폭 `clamp(260px, 40vw, 560px)` | 무대가 테마와 상관없이 늘 어두워서 두 테마 모두 `logo-dark.svg`(흰 글자) |
| 언어 선택 페이지 `/`(아이콘 + "Chromite" 자리) | 가로 로고, 폭 320px | `logo-light.svg` / `logo-dark.svg` |

- 벡터(SVG)라 화면 배율(HiDPI)과 상관없이 선명하다. CSP의 `img-src 'self'`로 같은 출처 그림이라 그대로 된다.
- 손으로 자른 파일이 아니라 `gen_app_icons.py`의 가로 로고 도형(`TITLE_GLYPHS`, `TITLE_ACCENT`, `lockup_svg`)을 `gen_site_images.py`가 불러 만든다.
  워드마크는 같은 글자 경로를 글자에 딱 맞는 viewBox(`WORDMARK_BOX`, x 434~1241, y 125~218: 기울인 C의 둥근 왼쪽 아래 모서리 434.6부터 강조 획 끝 1240.25까지)로 자른 것이다.
  가로 로고의 부제 글꼴(Inter)이 필요해서 `gen_site_images.py`도 `--inter-jar`를 받는다.
- `h1`과 링크는 그림의 `alt="Chromite"`로 이름을 가진다(화면 읽기 프로그램에는 예전처럼 "Chromite").
- `site/assets/chromite-icon.svg`는 파비콘 링크로 계속 쓴다.
- SWT 제품의 창 아이콘·About 이미지·스플래시·`UIIcon` 로고는 SWT 프런트엔드와 함께 삭제했다([swt-removal.md](swt-removal.md)).
  Swing 제품의 시작 화면은 2026-11-08에 새로 만들었다(아래 "시작 화면").
  제품 아이콘(`.ico`, `.icns`, 아이콘 세트, 설치 이미지)은 `product/community/icons*`에서 `product/swing/icons*`로 옮겼다.

### 사이트의 출처 고지 (2026-11-08)

사용자 결정(사이트 2차 수정 요청 15번): 다운로드 사이트에서 원래 프로젝트 이름은 **법적 출처 고지로만** 쓴다. 이것이 사이트에서 "DBeaver"를 쓸 수 있는 유일한 자리다.

- 자리: 모든 페이지의 바닥글(`footer.foot`의 `p.attribution`, 홈·변경 내역·개인정보·지원 범위, 한국어·영어)과 지원 범위 페이지 맨 아래 "라이선스와 출처" 절.
  언어 선택 페이지 `/`는 바닥글이 없는 가운데 정렬 화면이라 넣지 않았다.
- 문구(고치지 않고 그대로 쓴다):
  - ko: "Chromite는 DBeaver Community(Apache License 2.0)를 바탕으로 만들었습니다. DBeaver는 DBeaver Corp의 상표이며, Chromite는 DBeaver Corp과 관계가 없습니다."
  - en: "Chromite is based on DBeaver Community (Apache License 2.0). DBeaver is a trademark of DBeaver Corp; Chromite is not affiliated with DBeaver Corp."
  - 뒤에 저장소 `NOTICE` 링크(`https://github.com/froiibn/chromite/blob/devel/NOTICE`).
- 쓰지 않는 곳: 홈 본문, 첫 화면, 메타 태그(`description`, `og:*`, `twitter:*`), 공유 이미지, 그림 설명. 홈의 오픈 소스 장면은 지금처럼 "다른 오픈 소스 프로젝트에서 출발했다"와 `NOTICE` 링크만 둔다.
- 이름 변경 게이트(`tools/rename/gate.py`)는 이 문구를 막지 않는다: G3은 `DBeaver` 바로 뒤에 대문자가 붙은 형식 이름(`DBeaver<Name>`)만 찾고, "DBeaver Community"·"DBeaver Corp"·"DBeaver는"은
  공백이나 한글이 뒤따른다. 게이트의 예외 목록은 고치지 않았다(2026-11-08 `ALL GATES PASSED`).

### 시작 화면

앱이 뜨기까지 몇 초 걸려서 사용자가 로딩 화면을 요청했다(2026-11-08). SWT 제품의 스플래시는 SWT와 함께 없어졌다(감사 행 A-S15).
시작 화면은 두 단계로 보인다.

1. **런처 시작 화면(JVM 전):** `chromite-swing.ini`의 `-showsplash com.seizia.chromite.splash`(제품 파일의 `programArgs`)로
   네이티브 런처가 `plugins/com.seizia.chromite.splash_<버전>/splash.png`를 JVM을 띄우기 전에 보여 준다. 그림만 보이고 글자는 없다.
2. **Swing 시작 화면(Java가 돈 뒤):** Look and Feel을 설치한 직후 `StartupSplash`가 같은 그림을 같은 자리(주 화면 가운데)에 띄우고
   아래 줄에 버전(`버전 0.1.3` / `Version 0.1.3`, About과 같은 문구)과 `로드하는 중…` / `Loading…`(공용 문구 `common_loading`)을 얹는다.
   그 직후 애플리케이션이 런처 시작 화면을 내린다(`IApplicationContext.applicationRunning`). 메인 창이나 시작 대화상자가 열리면 닫힌다.

| 구성 | 위치 | 하는 일 |
|---|---|---|
| 그림 번들 | `plugins/com.seizia.chromite.splash` (`Eclipse-BundleShape: dir`, 코드 없음) | `splash.png`(1x), `splash@2x.png`(2x). 기능 `com.seizia.chromite.swing.feature`에 들어 있다 |
| 런처 인수 | `product/swing/chromite-swing.product` `<programArgs>-showsplash com.seizia.chromite.splash</programArgs>` | Windows·macOS ini 모두에 들어간다(빌드 결과로 확인) |
| Swing 시작 화면 | `plugins/com.seizia.chromite.ui.swing` `StartupSplash` | 런처가 둔 시스템 속성 `org.eclipse.equinox.launcher.splash.location`의 그림과 옆의 `@2x` 그림으로 창을 만든다 |
| 시작 순서 | `ChromiteSwingApplication.run` | Look and Feel → (업데이트 적용 중이면 런처 시작 화면을 내리고 안내) → `StartupSplash.open`(업데이트 뒤 첫 시작이면 그 대신 업데이트 진행 창, [auto-update.md](auto-update.md) "업데이트 진행 창") → `applicationRunning` → 작업 공간 잠금 … 메인 창. `runWithSplash`가 어떻게 끝나도 닫는다 |
| 그림 생성 | `tools/branding/gen_app_icons.py` `splash_png` | 다크 가로 로고를 다크 테마 `bg.chrome`(`#2B2D30`) 바탕, `border.popup`(`#43454A`) 1px 테두리 위에. 아래 줄은 비워 둔다 |
| 시간 기록 | `ChromiteSwingApplication.StartupTimes` | 프로세스 시작부터 단계별 ms를 디버그 로그 한 줄로: `Startup times (ms after the process started): application …, look and feel …, splash …, platform …, services …, workspace …, main window …` |

#### 설계 결정

- **런처 시작 화면을 쓴다.** JVM보다 먼저 뜨므로 가장 빠르다(아래 측정: 실행 뒤 약 80 ms).
  런처는 `-showsplash`의 값이 파일·폴더가 아니면 번들 ID로 보고 `plugins/<ID>_<버전>/`에서 `splash.png`를 찾는다
  (equinox `eclipse.c` `findSplash`). 현재 폴더와 상관없이 찾는 방법은 이것뿐이다. 상대 경로는 먼저 현재 폴더 기준으로 찾고,
  없으면 `<경로>_<버전>` 형식으로만 찾는다. 번들이 jar면 런처가 그 안을 읽지 못하므로 그림만 담은 작은 폴더형 번들을 따로 두었다
  (Eclipse 제품의 스플래시 번들과 같은 방식). Swing 번들을 폴더형으로 바꾸는 방법은 설치 구조와 업데이트·서명 경로를 건드려서 쓰지 않았다.
- **Swing 시작 화면이 이어받는다.** 런처 시작 화면만 쓰면 세 가지가 안 된다.
  - 글자(버전, 상태 줄)를 그릴 수 없다. SWT 앱은 SWT로 그 창 위에 그렸다. 버전을 그림에 넣으면 버전을 올릴 때마다 그림을 다시 만들어야 하고, 잊으면 틀린 버전이 보인다.
  - Windows 런처는 창을 첫 스레드에서 만들고, 그 스레드는 곧바로 Java(Equinox, 앱 `run`)를 돌려 창의 메시지를 처리하지 않는다.
    플랫폼 초기화가 길어지면 Windows가 창을 "응답 없음"으로 바꿀 수 있다. Swing 창은 EDT가 처리한다.
  - Windows 런처는 그림을 언제나 96 DPI로 보고 화면 배율만큼 늘린다(WIC 3차 보간, `eclipseWin.c` `loadImage`). 2x 그림을 주면 두 배 크기로 나온다.
    그래서 런처용은 1x, Swing용은 2x 그림이다. macOS 런처는 그림을 포인트 크기로 보여 주므로(`NSImage`) 1x 그림은 Retina에서 흐리다가 Swing 창에서 선명해진다.
- **같은 자리, 빈틈 없이.** Swing 창은 주 화면 전체의 가운데에 놓는다(작업 표시줄을 뺀 영역이 아님). Windows 런처도 그렇게 놓는다.
  Swing 창을 띄우고 한 번 그린 뒤(`paintImmediately`) 런처 창을 내린다. Windows 150%에서 두 창의 위치·크기가 픽셀까지 같았다(아래 확인 기록).
  macOS 런처는 창을 가운데보다 조금 위에 놓아(`[NSWindow center]`) 넘어갈 때 조금 움직일 수 있다.
- **런처 창은 앱 스레드에서 내린다.** Windows의 `DestroyWindow`는 창을 만든 스레드에서만 된다. 런처 창은 첫 스레드가 만들었고 Windows에서는 `run`도 첫 스레드에서 돈다.
  그래서 `applicationRunning`을 `run`에서 부른다(메인 창 직전에 부르던 것을 Swing 시작 화면 직후로 옮김). macOS에서는 `run`이 작업 스레드(`MacOSMainThread`)에서 돌고,
  런처가 닫기를 AppKit 스레드로 넘긴다(`performSelectorOnMainThread`, 기다리지 않음). AppKit 스레드는 막지 않는다.
- **창은 활성화될 수 있는 `JFrame`이다.** Windows 런처 창은 뜰 때 활성화된다(`SW_SHOW`). 프로세스에 활성 창이 없는 채로 그 창을 내리면 활성 창이 다른 앱으로 넘어가고,
  뒤에 뜨는 메인 창이 앞으로 나오지 못할 수 있다. 보이지 않는 소유자를 가진 `JWindow`는 활성화되지 않아서 꾸밈 없는 `JFrame`을 쓴다.
  `Window.Type.UTILITY`라 작업 표시줄 단추가 없다. macOS에서는 다른 앱이 앞에 있어도 숨지 않게 `Window.hidesOnDeactivate`를 끈다. 확인 기록에서 메인 창이 전경 창이었다.
- **닫는 시점은 "다른 창이 열릴 때" 하나다.** 모든 창의 `WINDOW_OPENED`를 보는 AWT 이벤트 리스너가 시작 화면이 아닌 창이 열리면 닫는다.
  메인 창, 작업 공간 잠금 오류, 플랫폼 초기화 오류, 모델 코드가 띄우는 대화상자가 모두 같은 경로다. 쿼리 창 복원 질문(#167)은 메인 창이 열린 뒤라 시작 화면이 이미 닫혀 있다.
  업데이트 적용 중 안내는 Swing 시작 화면보다 앞이라 런처 시작 화면만 먼저 내린다. 시작이 어떻게 끝나도 `runWithSplash`가 닫는다(보이는 창이 남으면 AWT 이벤트 스레드가 프로세스를 붙잡는다).
- **`-nosplash`를 따른다.** 런처가 그림을 띄우지 않았으면 시스템 속성이 없어서 Swing 시작 화면도 띄우지 않는다. 개발 실행(런처 없음)도 같다.
- **그림 하나가 두 테마에 맞는다.** 바탕과 테두리는 다크 테마 토큰이다. 어두운 카드라 밝은 바탕화면과 어두운 바탕화면 모두에서 경계가 보인다.
  Windows 런처는 그림을 24비트 RGB로 바꾸므로(알파 없음) 투명 모서리 없이 불투명하게 만든다. 글자 색은 다크 테마의 상태 표시줄 글자(`#9DA0A8`)와 `text.primary`(`#DFE1E5`)다.

#### 시작 시간 (Windows 11, 150%, 이 PC, 빈 작업 공간, 2026-11-08)

다른 에이전트 약 15개가 같은 PC에서 빌드·실행하는 중에 잰 값이라 흔들림이 크다. 단위는 프로세스 시작부터 ms다(`Startup times` 줄, 런처 창은 창 목록으로 잼).

| 실행 | 런처 시작 화면 | `application`(Equinox가 `start` 호출) | `look and feel` | `splash` | `platform` | `workspace` | `main window` |
|---|---|---|---|---|---|---|---|
| 1 (첫 실행, 한국어) | 83 | 1269 | 1885 | 2018 | 2884 | 2959 | 3767 |
| 2 (한국어) | 81 | 678 | 1349 | 1479 | 1835 | 1916 | 2751 |
| 3 (`-nl en`) | 80 | 1095 | 1753 | 1888 | 2232 | 2314 | 3154 |

- 가장 큰 몫: JVM·Equinox 시작(0.7~1.3초), 메인 창 만들기(EDT에서 0.8초 안팎, `MainFrame` 생성과 도킹 배치 복원), Look and Feel 설치(약 0.65초, AWT 초기화와 FlatLaf·글꼴).
  `services`(`postInitialize`)는 몇 ms, `workspace`(연결 수 세기)는 빈 작업 공간에서 약 0.08초다. 연결이 많은 작업 공간에서는 늘어난다.
- 바로 적용할 만큼 싸고 안전한 단축은 찾지 못해 바꾸지 않았다. 후보: 저장된 테마가 다크면 Look and Feel을 라이트로 한 번, 다크로 한 번 설치한다
  (작업 공간을 잠그기 전에는 설정을 읽을 수 없어서). JVM 클래스 데이터 공유(CDS) 보관 파일은 설치 폴더에 쓰기가 필요해 미뤘다.
- 시작 화면이 없을 때 사용자는 약 2.7~3.8초 동안 아무것도 못 봤다. 이제 약 0.08초에 런처 시작 화면, 1.4~2.0초에 버전과 상태 줄이 보인다.

#### 확인 기록 (Windows 11, 2026-11-08, 브랜치 `chromite/startup-splash`)

이 PC에서 빌드한 제품을 `CHROMITE_DATA`·작업 공간을 scratch에 두고 띄우고, 이 프로세스의 최상위 창만 40 ms마다 읽었다(`EnumWindows`, `PrintWindow`로 창 그림 저장, 입력은 보내지 않음).
현재 폴더는 설치 폴더가 아닌 곳이었다.

| 경우 | 결과 |
|---|---|
| 정상 시작(한국어·영어) | +80 ms 런처 창(`Static`, 840×480 = 560×320 × 1.5) → +1.4~2.0초 Swing 창(`SunAwtFrame`, 같은 자리·크기) → 약 45 ms 뒤 런처 창 사라짐 → 메인 창 → 약 0.2초 뒤 Swing 창 사라짐. 끝에 전경 창은 메인 창. 한국어 `버전 0.1.3` `로드하는 중…`, `-nl en`은 `Version 0.1.3` `Loading…`. "응답 없음" 창 없음(`IsHungAppWindow`) |
| 쿼리 창 복원 질문(#167) | 시작 화면이 닫힌 뒤 질문이 뜨고 전경 창이다 |
| 작업 공간 잠김(두 번째 실행) | 시작 화면 → 오류 대화상자가 열리며 시작 화면이 닫힘(대화상자가 전경). 대화상자를 닫으면 프로세스가 끝남(종료 코드 0, 남은 창 없음) |
| 업데이트 적용 중(scratch `LOCALAPPDATA`의 `apply.lock`) | 런처 창이 안내 대화상자보다 먼저 사라짐, 대화상자를 닫으면 종료 코드 0 |
| `-nosplash` | 시작 화면 없이 메인 창만 |

플랫폼 초기화 오류 대화상자는 일부러 일으키지 못했다. 작업 공간 잠김과 같은 `showStartupError` → `WINDOW_OPENED` 경로다.
macOS는 `macos-smoke.yml`의 `launch` 단계가 시작 화면 스크린숏과 "메인 창 뒤에 시작 화면 크기의 창이 없음"을 확인한다([ci.md](ci.md) "macOS 설치·실행 시험").

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
- 2026-11-07 결정 사항에 에이전트 기능 이름 `Chromite Agent` 추가(사용자 결정, [ai-agent.md](ai-agent.md) "명칭").
- 2026-11-07 다운로드 사이트 파비콘·공유 이미지와 생성기 `tools/branding/gen_site_images.py`를 파생 이미지 표에 추가(사용자 요청, [distribution.md](distribution.md)).
- 2026-11-08 공유 이미지 파일 이름을 `og-{ko,en}-2.png`로 바꿈(메신저 미리 보기 캐시, [distribution.md](distribution.md) "파비콘과 공유 이미지").
- 2026-11-08 다운로드 사이트의 머리글을 워드마크, 홈 첫 화면과 언어 선택 페이지를 가로 로고 그림으로 바꿈(사용자 요청, "사이트의 로고" 절, `site/assets/brand/`). 공유 이미지를 `og-{ko,en}-3.png`로 다시 만듦(작은 줄 문구).
- 2026-11-08 Swing 제품의 시작 화면(감사 행 A-S15, `chromite/startup-splash`): 그림 번들 `com.seizia.chromite.splash`와 런처 `-showsplash`, Swing이 이어받는 `StartupSplash`(버전·상태 줄),
  `gen_app_icons.py`의 `splash_png`, 단계별 시작 시간 디버그 로그. "시작 화면" 절 추가.
- 2026-11-08 "사이트의 출처 고지" 절 추가(사이트 2차 수정 요청 15번, `chromite/site-round2`): 바닥글과 지원 범위 페이지의 출처 고지 문구가 사이트에서 원래 프로젝트 이름을 쓰는 유일한 자리.
- 2026-11-08 업데이트 뒤 첫 시작에는 Swing 시작 화면 대신 업데이트 진행 창이 뜬다(앱 항목 #11, `chromite/update-window`, "시작 순서" 행). 앱의 사본은 런처를 `-nosplash`로 띄운다.
