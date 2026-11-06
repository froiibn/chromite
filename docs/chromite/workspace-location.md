# 작업 공간 위치

## 목적

`-data` 없이 실행한 Swing 제품이 현재 폴더와 관계없이 사용자별 기본 작업 공간을 쓰게 한다. 그리고 이 수정 전 빌드가 설치 폴더나
현재 폴더 아래 `workspace\`에 만든 작업 공간을 처음 한 번 그 위치로 복사한다.

- 사용자별 설치는 `%LOCALAPPDATA%\Programs\Chromite`다([distribution.md](distribution.md)). 프로그램 폴더에 사용자 데이터가 섞이면
  제거·복구에 약하다(설치 프로그램은 `workspace\`를 지우지 않도록 맞춰 두었을 뿐이다).
- 다른 현재 폴더에서 실행하면 빈 작업 공간이 열렸다(연결이 없어 보임).
- 이전 검증들은 모두 `-data`를 줘서 실행해 이 문제가 드러나지 않았다.

## 기본 작업 공간

| 실행 | 작업 공간 |
|---|---|
| `-data <폴더>` | 그 폴더(이전·복사 없음) |
| 환경 변수 `CHROMITE_WORKSPACE` | 그 폴더 |
| 그 외 | `<데이터 폴더>\workspace6` |

데이터 폴더는 `RuntimeUtils.getWorkingDirectory("ChromiteData")`다(환경 변수 `CHROMITE_DATA`가 있으면 그 아래).

| OS | 데이터 폴더 | 기본 작업 공간 |
|---|---|---|
| Windows | `%APPDATA%\ChromiteData` | `%APPDATA%\ChromiteData\workspace6` |
| macOS | `~/Library/ChromiteData` | `~/Library/ChromiteData/workspace6` |
| Linux | `~/.local/share/ChromiteData` | `~/.local/share/ChromiteData/workspace6` |

이 위치는 새로 정한 것이 아니라 코드가 원래 의도한 곳이다.

- `BaseApplicationImpl`(`com.seizia.chromite.model`)의 `CHROMITE_DATA_DIR = "ChromiteData"`, `DEFAULT_WORKSPACE_FOLDER = "workspace6"`와
  생성자가 기본 작업 공간을 이렇게 계산한다. `ChromiteSwingApplication.lockWorkspace`는 위치가 정해지지 않았을 때 이 값을 쓰도록 되어 있었다
  (Javadoc "the default workspace (shared with the SWT frontend)", [swing-frontend.md](swing-frontend.md) 시작 순서 3번).
- 같은 데이터 폴더에 내려받은 드라이버(`drivers\`)가 이미 있다.
- `workspace6`라는 이름은 DBeaver에서 온 것이지만 바꾸지 않았다. 바꾸면 SWT 제품 시절 이 폴더를 쓰던 작업 공간과 갈라지고, 내부 이름은
  계획한 작업에서만 바꾼다(CLAUDE.md "브랜딩").
- macOS는 `~/Library/Application Support/ChromiteData`가 아니라 `~/Library/ChromiteData`다. `RuntimeUtils.getWorkingDirectory`의 기존 규칙
  (DBeaver의 `~/Library/DBeaverData`와 같은 방식)이고 드라이버도 그곳에 있어 그대로 두었다. Application Support로 옮기려면 드라이버 폴더와
  함께 별도 작업으로 한다.
- 설치된 DBeaver의 `DBeaverData\workspace6`은 다른 폴더라 건드리지 않는다.

## 원인

`ChromiteSwingActivator.start`가 앱보다 먼저 인스턴스 설정(`InstanceScope`)을 읽어, Equinox가 정해지지 않은 인스턴스 위치를 기본값
`<user.dir>/workspace`로 고정했다. 그래서 `lockWorkspace`의 "위치가 정해지지 않았으면 기본 작업 공간" 분기를 타지 못하고, `-data`를 받은
것처럼 현재 폴더 아래 `workspace`를 잠갔다.

근거(수정 전 devel `b0d5932780`을 빌드해 jdb로 `InstancePreferences.getBaseLocation`에 중단점을 걸고 실행, 현재 폴더는 설치 폴더가 아닌 시험 폴더):

```
[1]  org.eclipse.core.internal.preferences.InstancePreferences.getBaseLocation (InstancePreferences.java:40)
...
[12] org.eclipse.core.runtime.preferences.AbstractScope.getNode (AbstractScope.java:37)
[13] com.seizia.chromite.model.impl.preferences.BundlePreferenceStore.<init> (BundlePreferenceStore.java:44)
[15] com.seizia.chromite.ModelPreferences.setMainBundle (ModelPreferences.java:308)
[16] com.seizia.chromite.ui.swing.ChromiteSwingActivator.start (ChromiteSwingActivator.java:52)
...
[27] org.eclipse.osgi.internal.hooks.EclipseLazyStarter.postFindLocalClass (EclipseLazyStarter.java:122)
...
[40] org.eclipse.equinox.internal.app.EclipseAppHandle.run (EclipseAppHandle.java:214)
```

- 순서: Equinox가 앱 클래스(`ChromiteSwingApplication`)를 읽을 때 지연 활성화(`Bundle-ActivationPolicy: lazy`)로 이 번들의 활성화 클래스가
  먼저 시작된다(`EclipseLazyStarter.postFindLocalClass`). `start`의 52행 `ModelPreferences.setMainBundle`(53행 `new BundlePreferenceStore`도 같다)이
  `InstanceScope.INSTANCE.getNode(...)`를 부르고, `InstancePreferences.getBaseLocation` → `DataArea.assertLocationInitialized` →
  `BasicLocation.getURL`(100행)이 기본값으로 `set`한다. 같은 실행의 `BasicLocation.set` 중단점에서 `this.property = "osgi.instance.area"`,
  값 `file:/D:/Tools/_scratch/workspace-location/base/cwd/workspace/`를 확인했다.
- 결과: 로그 `Instance path: 'file:/D:/tools/_scratch/workspace-location/base/cwd/workspace/'`, 현재 폴더에 `workspace\.metadata`, `General`,
  `QueryLibrary`가 생기고 시험 `APPDATA\ChromiteData`에는 `drivers\`만 생겼다(설치 담당 작업의 관찰과 같다).
- 제품 설정(`chromite-swing.ini`, `config.ini`)에는 `-data`나 `osgi.instance.area(.default)`가 없다. `osgi.instance.area.default`로 고치는 방법은
  `@user.home` 같은 변수만 쓸 수 있어 `%APPDATA%`(이동·재지정된 경우 포함)를 표현할 수 없어 쓰지 않았다.

## 수정

- `ChromiteSwingActivator.start`는 더 이상 설정 저장소를 만들지 않는다. 새 메서드 `initPreferences()`가 `ModelPreferences.setMainBundle`과
  이 번들의 `BundlePreferenceStore`를 만든다.
- `ChromiteSwingApplication.start`의 순서:
  1. 기본 라이트 테마 설치(설정을 읽지 않는다)
  2. `-data` 여부 확인(`Location.isSet()`, 이 시점에 위치를 정하는 것은 `-data`뿐이다)
  3. `lockWorkspace`: `-data`가 없으면 기본 작업 공간으로 정하고 잠근다
  4. `-data`가 없었으면 `WorkspaceLocationMigration.migrate`(아래)
  5. `initPreferences()`: 여기서 처음 인스턴스 설정을 읽는다
  6. 디버그 로그, 이전 결과 한 줄 기록, 플랫폼 초기화(여기서 `LegacyNameMigration`)
- `-data`는 Equinox가 시작할 때 인스턴스 위치를 정하므로 그대로 우선한다. 기본 작업 공간 계산에는 현재 폴더가 들어가지 않는다
  (사용자가 상대 경로로 준 `-data`·환경 변수는 사용자 선택이라 그대로 현재 폴더 기준이다).

## 기존 작업 공간 복사 (`WorkspaceLocationMigration`)

`plugins/com.seizia.chromite.ui.swing/.../WorkspaceLocationMigration.java`. 규칙은 `LegacyNameMigration`([internal-rename.md](internal-rename.md)
"사용자 데이터 이전")과 같다.

- **언제:** `-data` 없이 실행해 기본 작업 공간을 잠근 직후, 인스턴스 설정을 처음 읽기 전. 설정 파일(`.metadata/.plugins/org.eclipse.core.runtime/.settings/*.prefs`)은
  처음 읽을 때 메모리에 올라가고 저장할 때 다시 쓰이므로 그 뒤에 복사하면 무시되거나 덮인다. 잠근 뒤라 다른 인스턴스와 동시에 복사하지 않는다.
- **새 위치가 비었을 때만:** 잠금 파일 `.metadata/.lock`과 `.metadata` 바로 아래 로그 외에 아무것도 없을 때. 이미 쓰던 기본 작업 공간은 덮지 않는다.
- **찾는 곳(순서):** `<설치 폴더>\workspace`(시작 메뉴 바로 가기의 시작 위치), `<현재 폴더>\workspace`. 처음 찾은 하나만 복사하고 두 작업 공간을
  섞지 않는다. 설치 폴더는 Equinox 설치 위치(`Platform.getInstallLocation()`, macOS는 `Chromite.app/Contents/Eclipse`)다.
- **Chromite 작업 공간만:** `.metadata/chromite-workspace.properties`(이름 변경 뒤) 또는 Swing 번들 설정
  `.metadata/.plugins/org.eclipse.core.runtime/.settings/com.seizia.chromite.ui.swing.prefs`(이름 변경 전후 같은 이름)가 있어야 한다.
  DBeaver와 다른 Eclipse 기반 프로그램의 작업 공간은 이 파일이 없어 가져오지 않는다. 기본 작업 공간 자신(예: `CHROMITE_WORKSPACE`가 옛 폴더를 가리킴)과
  서로 포함하는 경로도 건너뛴다.
- **복사만:** 옛 폴더는 읽기만 한다(고치기 전 빌드로 돌아가도 그대로 쓸 수 있다). 있는 파일은 덮지 않는다. 파일은 임시 이름으로 쓴 뒤 옮기고,
  수정 시각을 유지한다(Eclipse 리소스 트리 스냅숏이 시각으로 동기화를 판단한다). 빈 폴더도 만든다.
- **복사하지 않는 것:** 옛 `.metadata/.lock`, `.metadata` 바로 아래 로그(`chromite-debug*.log`, `dbeaver-debug*.log`, `.log`, 진단용).
- **한 번만:** `.metadata/workspace-location-migration.properties`에 `state`, `source`, 시각, 결과를 남긴다. 복사 전에 `state=copying`,
  실패 없이 끝나면 `state=done`. `copying`이 남아 있으면(중간에 끊김, 실패한 파일) 다음 시작 때 같은 원본에서 없는 파일만 다시 복사한다.
  `done`이면 아무것도 하지 않는다. 다시 복사하려면 표시 파일을 지우고 작업 공간을 비운다.
- **절대 경로는 바꾸지 않는다.** 사용자가 바꿔 저장한 Query Library 루트처럼 옛 작업 공간 안을 가리키는 절대 경로는 그대로다. 옛 폴더를 지우지 않으므로
  계속 열린다. 기본 Query Library 루트(`<작업 공간>\QueryLibrary`)와 작업 공간 안의 프로젝트(`General`)는 상대 위치라 새 위치를 따른다.
- **로그:** 복사 결과 한 줄(`Workspace <원본> copied to <대상>: copied N file(s); kept N existing; N failed`)을 `chromite-debug.log`에 남긴다.
  복사는 디버그 로그를 열기 전에 하므로 결과를 들고 있다가 로그를 연 뒤 쓴다. 실패한 파일은 그때 바로 오류로 남는다(표준 오류, Eclipse `.log`).

### 이름 변경 이전과의 순서

위치 이전 → 이름 이전 순서다. 이름 변경 전 빌드(`.dbeaver`, `org.jkiss.dbeaver.*`)가 설치 폴더에 남긴 작업 공간은 먼저 그대로 기본 작업 공간으로
복사되고, 플랫폼 초기화의 `LegacyNameMigration`이 새 위치에서 이름을 바꿔 복사한다. 두 이전 모두 옛 파일을 고치지 않으므로 옛 폴더는 처음 그대로다.
반대 순서(옛 폴더에서 이름을 바꾼 뒤 복사)는 옛 폴더를 고치게 되어 택하지 않았다.

## 검증

빌드 `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `java tools/jre/BundleJre.java windows-x64`로
번들 JRE를 넣은 제품을 시험 폴더(`D:\Tools\_scratch\workspace-location\fixed\install\Chromite`)에 복사해 `-data` 없이 실행했다. 모든 실행에서
`APPDATA`를 시험 폴더로 바꾸고, 현재 폴더는 설치 폴더가 아닌 빈 시험 폴더로 했다. 연결 확인은 EDT 시험 에이전트(창 안에서 트리를 펼치고
열린 창 목록을 읽음)로 했다. 회귀 테스트는 실행하지 않았다.

| 확인 | 결과 |
|---|---|
| 순서(jdb) | 인스턴스 위치의 `BasicLocation.set`은 `ChromiteSwingApplication.lockWorkspace`(177행)에서, 첫 `InstancePreferences.getBaseLocation`은 그 뒤 `ChromiteSwingActivator.initPreferences`(`start` 108행)에서 불린다 |
| 다른 현재 폴더 | 로그 `Instance path: '…/appdata/ChromiteData/workspace6/'`, 작업 공간(`.metadata`, `General`, `QueryLibrary`)이 `APPDATA\ChromiteData\workspace6`에 생기고 현재 폴더는 비어 있다 |
| `-data` | 로그 `Instance path`가 `-data` 폴더, `ChromiteData`에는 `drivers`만(작업 공간 없음), 표시 파일 없음. 현재 폴더의 옛 작업 공간은 복사되지 않고 해시가 같다 |
| 설치 폴더의 이름 변경 전 작업 공간 | [internal-rename.md](internal-rename.md) 검증용 작업 공간(연결 4개, 저장 비밀번호, 폴더, 색, 필터, 설정)을 `<설치 폴더>\workspace`에 두고 실행. 로그 순서: `Workspace …\install\Chromite\workspace copied to …\workspace6: copied 20 file(s); kept 0 existing; 0 failed`(23개 중 `.lock`·로그 2개 제외) → `Workspace data written before the internal rename: …` → `Project data … .dbeaver -> … .chromite`. 연결 4개(SQL Server, PostgreSQL, MariaDB, Oracle)가 비밀번호 대화상자 없이 연결됐다(펼친 뒤 열린 창은 메인 창 하나, 하위 폴더 표시). 다크 테마, 글꼴 17, 필터, `@chromite-…` 속성, 폴더, 색 유지. 표시 파일 `state=done` |
| 두 번째 실행 | 복사 로그 없음, 표시 파일의 해시·수정 시각이 같다. 첫 실행 뒤 지운 Query Library 파일이 되살아나지 않는다. 이름 이전도 "already migrated"만 남긴다 |
| 옛 폴더 | 위 실행들 전후로 `<설치 폴더>\workspace` 23개 파일의 SHA-256·크기·수정 시각이 같다 |
| 현재 폴더의 작업 공간 | 설치 폴더에 `workspace`가 없을 때 `<현재 폴더>\workspace`(이름 변경 뒤 작업 공간)를 복사(30개), 연결 4개와 비밀번호 유지, 원본 해시 같음 |
| Chromite 작업 공간이 아님 | `.metadata`에 `dbeaver-workspace.properties`만 있는 작업 공간(DBeaver와 같은 모양)은 복사하지 않음(표시 파일 없음, 연결 없음) |
| 중단 뒤 이어 하기 | 표시 파일 `state=copying`과 파일 하나만 있는 기본 작업 공간: 나머지 19개를 복사하고 1개는 두며(`kept 1 existing`) `state=done` |

수정 전 빌드(devel `b0d5932780`)로 같은 조건에서 실행하면 위 "원인"처럼 현재 폴더 아래 `workspace`가 생긴다(같은 날 확인).

## upstream 대비 변경점

- DBeaver의 SWT 앱은 활성화 클래스가 앱보다 먼저 설정을 읽지 않아 이 문제가 없다. Swing 앱은 Chromite에서 만든 것이라 upstream에 해당 코드가 없다.
- `WorkspaceLocationMigration`은 Chromite에서 새로 만들었다.

## 변경 이력

- 2026-11-07 최초 작성. 작업 공간이 현재 폴더를 따라가던 원인(jdb 추적), 설정 저장소 생성 시점 변경, 옛 작업 공간 한 번 복사, 이름 변경 이전과의 순서.
