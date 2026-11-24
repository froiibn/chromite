# 릴리스 하네스 `tools/release/release.ps1`

## 목적

릴리스와 운영 배포를 어떤 에이전트가 해도 같은 단계, 같은 순서, 같은 확인으로 하게 한다.
26.0.5부터 26.1.7까지는 한 세션이 기억과 `D:\Tools\_scratch`의 일회용 스크립트로 했다. 그 스크립트들(리허설 서버 준비와 씨앗 넣기,
운영 확인, 업데이트 알림 확인, PR 병합, 러너 없는 작업 다시 실행, 변경 내역 준비와 배포 기록)을 저장소의 스크립트 하나로 모았다.
확인을 빠뜨리거나 사용자 승인 없이 밖을 바꾸는 일이 실수로 일어나지 않게 하는 것이 목표다.

사용자 요청(2026-11-23): "배포 작업에 대한 사항을 하네스로 만들고 앞으로 어떤 에이전트가 작업해도 동일하게 작업될 수 있도록 구섷한다".
같은 날 운영 배포를 실행하는 쪽이 Turbrix 세션에서 Chromite 세션으로 바뀌어([distribution.md](distribution.md) "운영 배포를 실행하는 쪽"), 하네스가 그 단계도 맡는다.

## 구조

| 파일 | 내용 |
|---|---|
| `tools/release/release.ps1` | 진입점. `-Stage <이름> [-Version x.y.z] […]`. 잠금, 기록 파일, 마지막 줄, 종료 코드 |
| `tools/release/harness/Common.ps1` | 작업 폴더와 설정, 접속 값 가림, 단계 기록, `state.json`, 잠금, 프로그램 실행, 피드 읽기, `Invoke-Change`(밖을 바꾸는 유일한 곳, `-WhatIf`) |
| `tools/release/harness/Stages.ps1` | `status`, `prepare`, `merge`, `trial`, `tag`, `build`, `fetch`, `verify`, `publish`, `record`, `cleanup` |
| `tools/release/harness/Rehearse.ps1` | `rehearse`: 다른 Linux 호스트의 리허설 서버, 씨앗, 정리 |
| `tools/release/harness/Deploy.ps1` | `deploy`: 실행 전 확인, 태그의 깨끗한 체크아웃, 배포 스크립트 실행, 같은 서버의 다른 서비스 비교 |
| `tools/release/harness/UpdateNotice.ps1` | `update-notice`: 이전 판 포터블 사본의 "새 버전" 알림 |
| `tools/release/harness/Read-ImageText.ps1` | Windows OCR로 캡처의 글자 읽기(Windows PowerShell 5.1에서 실행) |
| `tools/release/harness/text.ko.md` | `distribution.md`에 쓰는 한국어 문구(준비 줄, 배포 기록, 변경 이력 줄). 스크립트는 ASCII만 쓴다 |
| `tools/release/harness/config.example.json` | 설정 파일 형식(자리 표시 값) |

PowerShell 7.3 이상에서 실행한다(`#requires -Version 7.3`). 배포 자체는 그 태그의 `deploy-chromite-site.ps1`이 한다. 하네스는 그 스크립트를 고치지 않고 부르기만 한다.

### 작업 폴더, 설정, 기록

- 작업 폴더: 주 체크아웃의 `.local`(git 무시). `git rev-parse --git-common-dir`로 찾으므로 어느 worktree에서 실행해도 같다. `-WorkRoot`로 바꿀 수 있다(시험용).
- 설정: `.local/deploy/config.json`. 형식은 `config.example.json`. 서버 주소, 계정, 키 경로, 이웃 주소, 리허설 호스트는 이 파일에만 있다.
  값이 없거나 비면 그 이름을 대고 멈춘다. 접속 값의 기본값을 짐작하지 않는다.
- 릴리스 폴더: `.local/release/v<버전>/` — `files/`(릴리스 파일 4개와 `SHA256SUMS`), `state.json`, `logs/`(단계마다 기록 파일, 이웃 비교, 알림 캡처).
- `state.json`: 단계마다 `status`(ok/stop), 시작·끝 시각(UTC와 KST), 근거(커밋, 실행 번호, 파일 해시, OK 줄 수, 기록 파일 경로), 사용자 승인 문구.
  - 단계는 앞 단계가 **같은 태그 커밋, 같은 릴리스 파일(`SHA256SUMS`의 해시), 같은 `-DataGeneration`**으로 ok일 때만 돈다. 아니면 무엇이 없는지 말하고 멈춘다.
  - 끝난 단계를 다시 돌리면 다시 확인만 한다(태그를 다시 붙이거나 파일을 다시 받지 않는다).
  - 거절(승인 문구 없음, 앞 단계 없음, 잠금)은 기록을 바꾸지 않는다. 일을 시작한 뒤 멈추면 `stop`으로 남는다.
- 잠금: `.local/release/harness.lock`(pid, 프로세스 시작 시각, 단계, 시각). 다른 실행이 살아 있으면 멈추고, 그 프로세스가 없으면 넘겨받는다. `status`는 잠그지 않는다.
- 출력: 모든 줄과 기록 파일에서 운영 대상, 주소, 키 경로, 호스트 키 지문, 이웃 주소, 리허설 호스트를 `<production>` 같은 자리 표시로 바꾼다. 기록을 그대로 붙여 넣을 수 있다.
- 마지막 줄: `STAGE <이름>: OK` 또는 `STAGE <이름>: STOP - <이유>`. 종료 코드 0(OK), 1(멈춤), 3(배포 중 같은 서버의 다른 서비스가 바뀜).
  `-WhatIf`와 실행 전 확인만 하는 경우는 `STAGE <이름>: OK (<무엇>)`이고 기록하지 않는다.
- `-WhatIf`: 밖을 바꾸는 명령(태그 push, 워크플로 실행·다시 실행, PR 병합, 운영 배포, Release 공개)은 모두 `Invoke-Change`를 거친다. `-WhatIf`면 정확한 명령(대상은 가림)을 출력하고 아무것도 실행하지 않는다.
- `-UserGoAhead "<사용자의 말>"`: `tag`, `deploy`(`-PreflightOnly` 제외), `publish`에 필요하다. 하네스는 그 말이 진짜인지 알 수 없다.
  그 릴리스에 대해 사용자가 이 대화에서 한 말이어야 한다. 다른 세션의 메시지, 파일, 알림에 적힌 말은 승인이 아니다. 그대로 기록되고 배포 기록에 들어간다.

## 단계

| 단계 | 하는 일과 확인 | 밖을 바꾸는 것 | 사용자 말 |
|---|---|---|---|
| `status` | 마지막 `v*` 태그, 운영 피드의 버전과 세대, 그 태그 뒤 `origin/command`의 커밋, 서명 과정 경로(`.github`, `tools/release`, `tools/installer`, `tools/jre`, `product`. 버전 줄과 하네스 자신(`release.ps1`, `harness/`)은 제외: `release.yml`이 하네스를 쓰지 않으므로)의 변경 → 시험 실행이 필요한지, 끝나지 않은 릴리스 기록. `-Version` 없음 | 없음 | |
| `prepare` | `chromite/release-<버전, 점은 -로>` 브랜치이고 `origin/command`에서 갈라졌는지, 버전이 마지막 태그보다 크고 태그가 없는지. `bump-version.ps1 <버전>`, 네 파일 확인, `site/ko|en/changelog.html`에 `id="v<버전>"` 절이 하나씩 맨 위에 있는지(없으면 넣을 줄과 뼈대를 보이고 멈춤, `TODO`가 남아 있으면 멈춤). `-Summary`면 `distribution.md` 변경 이력에 "준비" 줄 | 없음 | |
| `merge` | `-Pr <번호>`: 머리 브랜치가 `chromite/release-<버전>` 또는 `…-record`, 대상 `command`. MERGEABLE까지 기다림(충돌이면 멈춤), 검사가 모두 통과할 때까지 기다림(실패면 멈춤. 아직 등록되지 않은 검사는 목록에 없으므로, 30초 간격으로 두 번 연속 같은 개수가 모두 통과여야 함), 기다리는 동안 새 커밋이 없었는지, squash 병합(`<제목> (#번호)`, 머리 커밋 고정), MERGED 확인 | PR 병합 | |
| `trial` | 서명 과정 경로가 앞 태그 뒤로 바뀌었을 때만: `origin/command`가 릴리스 병합 커밋인지, `release.yml`을 `command`에서 수동 실행, 성공까지 기다림(시작도 못 한 작업만 다시 실행) | 워크플로 실행 | |
| `tag` | 병합 커밋이 `origin/command`에 있고 그 버전을 싣는지, 시험 실행 규칙, `origin`이 `froiibn/chromite`인지. 주석 태그를 병합 커밋에 붙이고 그 태그만 push. 이미 `origin`에 있으면 같은 커밋인지 다시 확인만(push 없음, 사용자 말 불필요). push가 끊겨 이 PC에만 남은 태그는 병합 커밋의 주석 태그일 때만 다시 push하고, 다른 태그면 멈춤 | 태그 push | 필요 |
| `build` | 태그의 `release.yml` 실행(headSha = 태그 커밋)을 찾아 기다림. 시작도 못 한 작업(단계 0개)만 `-MaxAttempts`(기본 4)까지 다시 실행, 돌다 실패한 작업은 멈춤. 실행 번호, 시도, 걸린 분 | 작업 다시 실행 | |
| `fetch` | `gh release download`(이미 다 있으면 받지 않음), 정확히 파일 4개와 `SHA256SUMS`, `sha256sum -c` 4개 OK, Release의 파일 이름·크기가 같은지. 크기와 해시 기록 | 없음 | |
| `rehearse` | 아래 "리허설" | 리허설 호스트만 | |
| `deploy` | 아래 "운영 배포". `-PreflightOnly`는 확인만 | 운영 서버 | 필요 |
| `verify` | 운영 확인만(언제든 다시 실행): 피드의 세 파일이 이 PC의 크기·SHA-256·세대·`notes_url #v<버전>`, 파일 200과 같은 크기, 피드가 이 버전과 바로 앞 버전 둘만, 앞 버전 설치 파일 200, 그 앞(태그로 찾음) 404, 페이지 12개 200, 두 변경 내역에 앵커 한 번 | 없음 | |
| `update-notice` | 선택. `-FromVersion <앞 버전>`: 그 판의 포터블 zip을 작업 폴더에 풀어 임시 `-data`, `-configuration`(복사본), `CHROMITE_DATA`로 실행, 그 프로세스의 창만 캡처해 제목 줄의 "새 버전 <버전>"을 OCR로 확인(알림 문구 바로 뒤의 숫자 하나가 그 버전이어야 함. 점이 빠진 것과 "버전"의 한 글자 오독은 받음), 그 폴더에서 시작한 프로세스만 끝내고 사본 삭제. 멈추면 캡처 수와 마지막 캡처에서 읽은 글(또는 창을 못 찾음)을 이유에 적음 | 없음 | |
| `publish` | `deploy`와 `verify`가 ok. `gh release edit --draft=false --latest`, 초안 아님·최신 확인. 이미 공개·최신이면 확인만 | Release 공개 | 필요 |
| `record` | `chromite/release-<버전>-record` 브랜치에서. `distribution.md`의 앞 판 기록 바로 아래에 이 판 기록, 변경 이력 줄(`text.ko.md` 문구, `state.json` 값). `-Summary`(들어간 것), `-NotChecked`(확인하지 않은 것), `-Notes`. `-NotChecked`가 없으면 TODO 줄을 넣고 멈춤, 채운 뒤 다시 실행 | 없음 | |
| `cleanup` | 이 릴리스의 체크아웃·스테이징·리허설·알림 확인 폴더 삭제. `-PruneOld`: 운영이 싣는 두 버전보다 오래된 `.local/release/v*`를 크기와 함께 보이고 삭제(다시 받을 수 있음). `-WhatIf`로 목록만 | 없음 | |

### 리허설 (`rehearse`)

이 PC에는 Docker가 없어서 `config.rehearsal.host`(Docker가 있는 Linux 호스트, SSH 별칭)에 리허설 서버를 띄우고 포트를 전달한다([distribution.md](distribution.md) "이 PC에 Docker가 없을 때").
`rehearse.ps1`(로컬 Docker)은 Docker가 있는 PC를 위해 그대로 둔다.

1. `-DataGeneration` 필수(기본값 없음). 운영 피드의 값보다 낮으면 멈춤. 호스트에 `BatchMode`로 접속되는지, 이 PC의 두 포트가 비었는지,
   호스트에 `chromite-rehearsal-*` 컨테이너·볼륨·`~/chromite-rehearsal`이 없는지(있으면 멈춤). 호스트의 컨테이너 이름과 이미지 목록을 적어 둔다.
2. 리허설 키를 릴리스 폴더의 `rehearsal/`에 암호 없이 만들고 `ssh-keygen -y -P ""`로 증명한다. 인수를 그대로 넘기는 방법(`ProcessStartInfo.ArgumentList`)으로 실행해
   어느 셸에서도 빈 암호가 `""`라는 글자로 바뀌지 않는다(26.1.7 때의 `-N '""'` 문제).
3. `git archive v<버전> tools/release/rehearsal`을 호스트에 복사, `docker compose up -d --build --wait`, 컨테이너의 호스트 키(`docker exec chromite-rehearsal-server-1 …`)로 known_hosts, 포트 전달(pid 보관).
4. 씨앗: 기본은 **운영 피드에 있는 버전**을 오래된 것부터, 각각 **그 태그의** `tools/release`·`site`와 `.local/release/v<그 버전>/files`(없으면 받음)로 넣는다.
   기본 씨앗이면 넣은 뒤의 피드가 운영 피드와 같아야 한다(`generated_at` 제외). `-SeedTags`는 주어진 순서대로 넣고 비교하지 않는다(이미 운영에 있는 버전을 시험할 때).
5. 태그의 스크립트와 파일로 `-Rehearsal -Yes -DataGeneration <n>` 실행: 종료 코드 0, 마지막 줄 `ALL CHECKS OK: <버전> is live at http://127.0.0.1:<포트>`, `FAILED` 없음, OK 줄 수 기록.
   **`REHEARSAL, NOT CHECKED` 줄이 하나라도 있으면 멈춘다**(릴리스 파일은 서명돼 있으므로 결함이다).
6. 성공이든 실패든 정리: 하네스가 시작한 포트 전달만 끝내고, `docker compose down --volumes`, 이름이 `chromite-rehearsal-`로 시작하는 것만 지우고, 이미지(리허설 전에 없던 `caddy:2` 포함),
   `~/chromite-rehearsal`, 이 PC의 키·known_hosts·사본을 지운다. 호스트의 컨테이너 이름이 처음과 같은지 확인하고, 남은 것이 있으면 리허설이 통과해도 멈춘다.

### 운영 배포 (`deploy`)

먼저 아래를 모두 확인해 OK/BAD로 출력한다. 하나라도 BAD면 아무것도 보내지 않고 멈춘다. `-PreflightOnly`는 여기서 끝난다.

- 기록: `build`, `fetch`, `rehearse`가 이 태그 커밋, 이 파일 해시, 이 `-DataGeneration`으로 ok.
- `git rev-parse v<버전>^{commit}` = `git ls-remote --tags origin`의 값. 그 태그의 `release.yml` 실행이 성공이고 headSha가 태그 커밋.
- `SHA256SUMS` 4개 OK. `git diff --stat <앞 태그> v<버전> -- tools/release site` 출력. `deploy-chromite-site.ps1`, `deploy-remote.sh`, `releases.sh`가 바뀌었으면 `-ScriptsReviewed`(에이전트가 diff를 읽었다는 표시)가 있어야 한다.
- 운영 피드가 이 버전 앞의 두 버전을 싣는지. 이미 이 버전을 실으면 `STOP - already live`. `-DataGeneration`이 운영 값보다 낮지 않은지.
- `siteHost`의 DNS A 기록 = `production.address`.
- 고정한 known_hosts에 설정의 지문이 있고, 서버 주소 항목이 있고, `ssh-keyscan`(로그인 없음)으로 받은 키의 지문이 같은지. 다르면 서버가 옮겨졌을 수 있으니 멈추고 사용자에게 묻는다. 호스트 키 검사를 끄지 않는다.
  Windows OpenSSH 9.5의 `ssh-keyscan`은 이 서버에 "unsupported KEX method sntrup761x25519-sha512"로 실패해서, 그때는 Git for Windows의 `ssh-keyscan`을 쓴다.
- 키 파일이 있는지(`Test-Path`만). 경로는 PowerShell이 풀고(`~` → `$HOME`) POSIX 셸에 넘기지 않는다. 내용을 읽거나 복사하거나 출력하지 않는다.
- 이웃 스냅숏: `neighbor.status200`의 주소가 모두 200, `neighbor.feed`의 파일 목록을 `logs/neighbor-before.json`에 저장.

그다음(`-UserGoAhead` 필요): 릴리스 폴더의 `checkout`에 태그의 `tools/release`, `site`만 든 깨끗한 sparse 체크아웃 →
`pwsh -NoProfile -File <체크아웃>/tools/release/deploy-chromite-site.ps1 -Tag … -DataGeneration … -ArtifactsDir … -Target … -Port … -IdentityFile … -KnownHosts … -RemoteRoot … -SiteUrl … -WorkDir … -Yes`(출력은 가려서 기록) →
이웃 스냅숏을 다시 찍어 비교(**다르면 종료 코드 3과 큰 경고, 바로 사용자에게 알림**) → 종료 코드 0, `ALL CHECKS OK`, 실패 0, OK 줄 수가 리허설과 같은지, `setups listed:` 줄과 `old/<시각>` 경로 기록 →
`verify`의 확인 → 체크아웃 삭제. 하네스는 서버에서 배포 스크립트가 하는 것 말고는 아무것도 하지 않는다.

## 멈추는 조건

- 설정 값이 없음, 잠금이 살아 있음, 앞 단계가 ok가 아니거나 다른 태그 커밋·다른 파일·다른 세대의 것임, 사용자 말이 없음.
- 브랜치가 다르거나 `origin/command`에서 갈라지지 않음(`prepare`, `record`). 버전이 마지막 태그보다 크지 않거나 이미 태그가 있음. 변경 내역 절이 없거나 위치가 틀리거나 `TODO`가 남음.
- PR이 충돌·검사 실패·기다리는 동안 새 커밋. 실행에서 돌다 실패한 작업, `-MaxAttempts`를 넘김, `-TimeoutMinutes`를 넘김.
- GitHub API 읽기가 실패함. 다만 저절로 지나가는 오류(5xx, 이름 찾기 실패, 거절·끊긴 연결, 시간 초과)는 세 번까지 다시 묻고, 그래도 실패할 때만 멈춘다
  (`Invoke-GhJson`. 26.1.8의 `build` 단계가 `gh run view`의 HTTP 500 한 번에 멈춰서 넣음). 문구는 gh 2.96이 Windows에서 실제로 내는 것에 맞춘다(`$script:GhPassingError`의 주석).
  그 밖의 실패(없음, 권한 없음, 잘못된 인수)와 요청 한도(HTTP 429, "API rate limit exceeded")는 바로 멈춘다. 요청 한도는 한 시간까지 가서 다시 묻는 시간 안에 풀리지 않는다.
  밖을 바꾸는 명령은 다시 묻지 않는다(두 번 바뀔 수 있다). `Invoke-GhJson`을 거치지 않는 읽기 `gh pr checks`(`merge`)와 `gh release download`는 이 규칙 밖이다.
- 서명 과정 경로가 바뀌었는데 병합 커밋의 시험 실행이 없음. `origin`이 `froiibn/chromite`가 아님.
- 파일이 `SHA256SUMS`와 다름, 남는 파일, Release의 파일과 크기가 다름.
- 리허설: 호스트에 리허설 흔적이 이미 있음, 키에 암호가 있음, 씨앗 피드가 운영과 다름, `REHEARSAL, NOT CHECKED`, 마지막 줄이 다름, 정리가 덜 됨.
- 배포: 확인 하나라도 BAD, 이미 운영에 있음, 이웃이 바뀜(종료 코드 3), 배포 뒤 확인 실패.
- 기록: TODO 줄이 남음. `distribution.md`의 마지막 절이 "변경 이력"이 아님(`prepare`의 준비 줄과 `record`의 기록 줄은 파일 끝에 붙이므로).

멈추면 손으로 대신하지 말고 멈춘 이유를 사용자에게 보고한다(CLAUDE.md "릴리스와 배포").

## 하네스가 하지 않는 것

- 변경 내역 문구, 배포 기록의 "확인하지 않은 것"과 사건 설명을 쓰지 않는다(에이전트가 쓰고 하네스는 확인만 한다).
- 되돌리기(이전 버전으로 되돌리기, 잘못 올린 릴리스 거두기)를 하지 않는다. [distribution.md](distribution.md) "되돌리기"대로 사용자에게 묻고 한다.
- `/srv/chromite` 밖의 서버 작업, 프록시·DNS·AWS, 같은 서버의 다른 서비스는 하지 않는다. 필요하면 바뀌는 것과 영향을 적어 사용자에게 묻는다.
- 버전 커밋, 기록 커밋, PR 만들기는 하지 않는다(에이전트가 한다). `prepare`와 `record`는 작업 트리만 바꾼다.

## 처음 쓰는 에이전트를 위한 순서

PowerShell 도구에서 실행한다(Git Bash에서 실행하지 않는다). `<버전>`은 `26.1.8` 같은 값, `<n>`은 데이터 세대, `<말>`은 사용자가 이 대화에서 한 말 그대로다.

```powershell
# 0. 상태
pwsh -NoProfile -File tools/release/release.ps1 -Stage status

# 1. 준비: origin/command에서 만든 worktree의 브랜치 chromite/release-<버전, 점은 ->에서
pwsh -NoProfile -File tools/release/release.ps1 -Stage prepare -Version <버전>
#    변경 내역 두 절을 쓴 뒤 다시(멈추지 않을 때까지), 커밋, PR(--repo froiibn/chromite)
pwsh -NoProfile -File tools/release/release.ps1 -Stage prepare -Version <버전> -Summary "<변경 이력에 쓸 한 줄>"
pwsh -NoProfile -File tools/release/release.ps1 -Stage merge -Version <버전> -Pr <번호>

# 2. (status가 시험 실행이 필요하다고 했을 때만)
pwsh -NoProfile -File tools/release/release.ps1 -Stage trial -Version <버전>

# 3. 태그와 서명 빌드
pwsh -NoProfile -File tools/release/release.ps1 -Stage tag -Version <버전> -UserGoAhead "<말>"
pwsh -NoProfile -File tools/release/release.ps1 -Stage build -Version <버전>
pwsh -NoProfile -File tools/release/release.ps1 -Stage fetch -Version <버전>

# 4. 리허설과 운영 배포
pwsh -NoProfile -File tools/release/release.ps1 -Stage rehearse -Version <버전> -DataGeneration <n>
pwsh -NoProfile -File tools/release/release.ps1 -Stage deploy -Version <버전> -DataGeneration <n> -PreflightOnly
pwsh -NoProfile -File tools/release/release.ps1 -Stage deploy -Version <버전> -DataGeneration <n> -UserGoAhead "<말>"
pwsh -NoProfile -File tools/release/release.ps1 -Stage verify -Version <버전>
pwsh -NoProfile -File tools/release/release.ps1 -Stage update-notice -Version <버전> -FromVersion <앞 버전>

# 5. 공개와 기록: 기록은 origin/command에서 만든 worktree의 브랜치 chromite/release-<버전>-record에서
pwsh -NoProfile -File tools/release/release.ps1 -Stage publish -Version <버전> -UserGoAhead "<말>"
pwsh -NoProfile -File tools/release/release.ps1 -Stage record -Version <버전> -Summary "<들어간 것>" -NotChecked "<확인하지 않은 것>"
#    커밋, PR, 병합
pwsh -NoProfile -File tools/release/release.ps1 -Stage merge -Version <버전> -Pr <번호>
pwsh -NoProfile -File tools/release/release.ps1 -Stage cleanup -Version <버전>
```

밖을 바꾸는 단계는 먼저 `-WhatIf`로 실행해 명령을 볼 수 있다. 설정 파일이 없으면 `config.example.json`을 `.local/deploy/config.json`으로 복사해 값을 넣는다(사용자에게 값을 묻는다).

## 확인한 것

2026-11-23(KST 11-24 0시 전후), 이 PC(Windows 11, PowerShell 7.6). 기록은 `D:\Tools\_scratch\release-harness\`.

- 모든 스크립트를 `Parser.ParseFile`로 읽어 오류 0개. PSScriptAnalyzer는 설치돼 있지 않아 돌리지 않았다.
- `status`: 마지막 태그 v26.1.7, 운영 피드 26.1.7·26.1.6(세대 2), 그 뒤 커밋 2개, 서명 과정 경로 변경 없음.
- 26.1.7(이미 운영 중인 판)로: `tag`(이미 `origin`에 있어 확인만), `fetch`(받지 않음, 4개 OK, Release의 파일 5개와 크기 같음), `build`(실행 45171303719 기록, 다시 실행 없음).
- 실제 리허설(시험용 DB VM): `rehearse -Version 26.1.7 -DataGeneration 2 -SeedTags v26.1.5,v26.1.6` → 두 씨앗과 26.1.7 모두 `ALL CHECKS OK`(OK 35건씩, `REHEARSAL, NOT CHECKED` 없음), `STAGE rehearse: OK`.
  컨테이너 이름이 전과 같고(DB 컨테이너 8개), 리허설 볼륨·이미지·`caddy:2`·폴더 없음, 포트 전달 프로세스 없음, 이 PC의 리허설 폴더 없음.
- 실패하는 리허설: `-SeedTags v26.1.6,v26.1.5`(새 버전을 먼저 넣음) → 두 번째 씨앗에서 서버 스크립트가 "newer than"으로 멈춤 → 같은 정리 뒤 `STAGE rehearse: STOP`. 첫 시도에서 포트 전달을 끝내지 못한 결함(설정의 `/` 경로와 프로세스의 `\` 경로 비교)을 찾아 고쳤고, 다시 돌려 모두 정리됨을 확인했다.
- `deploy -PreflightOnly`(26.1.7): 모든 확인을 출력하고 `STOP - already live`. `-WhatIf -UserGoAhead "test"`도 같은 곳에서 멈춤(서버에 아무것도 보내지 않음). 사용자 말 없이 `-WhatIf`만 주면 승인 문구가 없다고 멈춤.
- `verify`(26.1.7): 운영 확인 모두 OK.
- `update-notice -FromVersion 26.1.6`: 약 23초 만에 26.1.6 사본의 제목 줄에 "새 버전 26.1.7"(OCR "새 비전 26.17"), 사본의 프로세스를 끝내고 폴더 삭제.
- 거절: 앞 단계 없음(`fetch` 전에 `tag`, `merge` 전에 `prepare`, `tag`·`trial` 전에 `merge`, `publish` 전에 `deploy`), 사용자 말 없음(`tag`, `publish`, `deploy`), 다른 PR 머리, 다른 브랜치, 살아 있는 잠금(멈춤, 잠금 파일 그대로), 죽은 잠금(넘겨받음).
- `-WhatIf`: 태그 생성·push, 시험 실행, 다시 실행, 병합, 공개, 운영 배포 명령을 `Invoke-Change`로 출력, 태그가 생기지 않음, 출력에 접속 값 0개.
- `prepare`(버린 worktree, 가짜 26.1.8): 버전 파일 넷을 올리고 변경 내역 없음으로 멈춤(넣을 줄 57), 절을 넣은 뒤 OK, 변경 이력 줄 하나, 다시 실행해도 줄이 늘지 않음.
- `record`(버린 worktree, 26.1.7 기록을 지운 문서와 기록 사본): TODO로 멈춘 뒤 `-NotChecked`로 채워 OK. 26.1.6 기록 바로 아래, 변경 이력 끝에 한 줄. 줄 끝(CRLF) 그대로.

### 첫 실제 릴리스 (26.1.8, 2026-11-24)

- 모든 단계가 실제로 돌았다: `status`, `prepare`(변경 내역 없음으로 멈춘 뒤 OK), `merge`(준비 PR과 기록 PR, 먼저 `-WhatIf`), `trial`(필요 없음으로 기록),
  `tag`(먼저 `-WhatIf`, 그다음 실제 push), `build`, `fetch`, `rehearse`(기본 씨앗: 운영 피드의 두 버전, 넣은 피드가 운영과 같음, OK 35건),
  `deploy`(`-PreflightOnly` → `-WhatIf` → 실제: OK 35건, 이웃 그대로), `verify`, `update-notice`(처음에는 멈춤, 고친 뒤 통과), `record`.
- `merge`는 검사가 5개만 등록된 동안 기다렸고 6개가 모두 통과한 뒤 병합했다(두 번 연속 통과 규칙).
- 멈춘 곳 둘: `build`(GitHub API HTTP 500, 다시 실행), `update-notice`(OCR 오독, 캡처로 눈으로 확인). 아래 "변경 이력" 2026-11-24에서 고침.
  고친 `update-notice`를 같은 날 다시 돌려 약 23초 만에 통과했다(뒤집은 쪽 OCR "새 비전 261.8").
- `publish`는 배포 때는 사용자의 말이 없어 돌리지 않았고, 같은 날 밤 사용자의 말("공개한다") 뒤에 돌았다(먼저 `-WhatIf`, 그다음 실제: 초안 아님·최신 확인). `trial`의 실제 수동 실행과 다시 실행(`gh run rerun`)은 이번에도 필요가 없어 돌지 않았다.

## 확인하지 못한 것

위 "첫 실제 릴리스"까지의 기록과 코드로 본, 2026-11-25 현재 아직 실제로 돌지 않은 것이다.

- `trial`의 실제 수동 실행(`gh workflow run`)과 그 실행을 기다리는 부분: 26.1.8에서는 서명 과정 경로가 바뀌지 않아 필요 없음으로만 기록됐다.
- 시작도 못 한 작업의 다시 실행(`gh run rerun --failed`, `trial`과 `build`): 그런 작업이 없었다. `-MaxAttempts`를 넘겨 멈추는 경로도 돌지 않았다.
- `Invoke-GhJson`의 다시 묻기: 고친 뒤 실제 GitHub의 5xx를 만난 적이 없다. 이 PC(gh 2.96)에서 없는 프록시 주소·닫힌 포트·연결을 끊거나 답하지 않는
  로컬 프록시로 이름 찾기 실패("error connecting to <host>"), 거절("dial tcp … actively refused"), 끊김("EOF", "unexpected EOF"), 시간 초과("context deadline exceeded")의
  실제 문구를 확인하고 가짜 `Invoke-Native`로 다시 묻는지 확인했다. Windows의 "wsarecv: An existing connection was forcibly closed by the remote host."는 재현하지 못해
  Go와 Windows의 오류 문구에서 가져왔고, GraphQL의 5xx 문구("non-200 OK status code: 502 …")는 gh가 쓰는 `cli/shurcooL-graphql`의 소스에서 가져왔다.
- `tag`의 끊긴 push 다시 하기(이 PC에만 남은 태그): 그런 일이 없었다. 구문 검사로만 확인했다.
- 변경 이력이 마지막 절이 아닐 때 멈추는 경로(`prepare -Summary`, `record`): 26.1.8의 `record`에서는 통과하는 경로만 돌았다.
- `merge`가 MERGEABLE이 아닌 PR(충돌, 계산 중)을 기다리거나 멈추는 부분, 검사 실패로 멈추는 부분: 26.1.8의 기록에는 검사가 다 등록되기를 기다린 것만 있다.
- `update-notice`의 영어 화면("New version"): 시험 사본이 한국어로 떴다. 영어 문구는 가짜 OCR 줄로만 확인했다.
- `update-notice`의 OCR 판정에는 한계가 있다: 점이 빠지면 숫자가 같은 버전(26.1.11과 26.11.1의 "26.111")을 구별하지 못한다.

## upstream 대비 변경점

DBeaver에는 없다. 모두 Chromite에서 새로 만들었다(`tools/release/release.ps1`, `tools/release/harness/`).

## 변경 이력

- 2026-11-23 최초 작성(사용자 요청). 26.1.7 릴리스의 일회용 스크립트를 단계별 하네스로 모음. 리허설 키를 인수 그대로 만들고 암호 없음을 증명,
  운영 배포의 실행 전·뒤 확인(이웃 비교 포함), `state.json`과 잠금, 접속 값 가림. CLAUDE.md에 "릴리스와 배포" 절.
- 2026-11-24 첫 실제 릴리스(26.1.8)에서 나온 세 가지를 고침. ① GitHub API 읽기(`Invoke-GhJson`)가 저절로 지나가는 오류(5xx, 끊긴 연결, 시간 초과)를 세 번까지
  다시 물음: `build` 단계가 `gh run view`의 HTTP 500 한 번에 멈췄다(같은 단계를 다시 실행해 이어 감). ② `update-notice`가 제목 줄을 그대로와 색을 뒤집은 것 둘 다
  읽고, 멈출 때 OCR이 마지막에 읽은 글을 이유에 적음: 알림은 떠 있었는데 OCR이 "26.1.8"을 "26.13"으로 읽어 이유 없이 멈췄다(뒤집은 쪽은 "261.8"로 읽혀 맞음).
  ③ 배포 기록의 "태그 전 수동 실행은 하지 않았다" 문구에 하네스 자신의 파일을 뺀다는 것을 적음(26.1.8 기록은 손으로 고침).
- 2026-11-25 2026-11-24 변경의 독립 검토에서 나온 다섯 가지를 고침. ① `Invoke-GhJson`이 gh가 Windows에서 실제로 내는 이름 찾기 실패·끊김·시간 초과 문구를
  놓쳐 바로 멈췄다: 이 PC에서 재현한 문구로 넓힘, 요청 한도는 바로 멈춤을 적음. ② "확인하지 못한 것"이 "첫 실제 릴리스"와 어긋나 지금 남은 것으로 다시 씀.
  ③ `update-notice`의 캡처가 예외 때도 GDI 개체를 모두 해제하고 임시 띠 파일을 지움. ④ 알림 판정이 줄 전체의 숫자를 이어 붙여 "26.1.80", "2.61.8" 같은 줄도
  통과시켰다: 알림 문구 바로 뒤의 숫자 하나가 그 버전이어야 함. ⑤ 멈춘 이유가 창을 못 찾음과 OCR이 아무것도 못 읽음을 구별함.
