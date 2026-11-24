Korean text the release harness writes into docs/chromite/distribution.md (tools/release/release.ps1, stages
prepare and record). UTF-8 without BOM. A section starts with a line "=== name ===". {{key}} is filled from the
state; a line that starts with "?key|" is kept only when that key has a value (the "?key|" prefix is removed).
The scripts stay ASCII; every Korean word they write comes from here.

=== prepare-history ===
- {{date}} {{version}} 준비(`{{branch}}`): {{summary}}
=== history-heading ===
## 변경 이력
=== prepare-history-marker ===
{{version}} 준비(
=== block-marker ===
- {{version}} 릴리스(
=== record-block ===
- {{version}} 릴리스({{date}}, 태그 `v{{version}}` = `{{tagShort}}`{{summaryPart}}).
  사용자 승인(이 대화에서 사용자가 한 말 그대로): 태그 "{{goAheadTag}}", 운영 배포 "{{goAheadDeploy}}"{{goAheadPublishPart}}.
  - {{trialLine}}
  - 태그 실행({{runId}}): {{runLine}}. 초안 Release에 파일 4개와 `SHA256SUMS`, 내려받아 `sha256sum -c` 4개 OK.
  - 리허설(하네스 `rehearse`, 다른 Linux 호스트의 리허설 서버): {{seedLine}}, 태그 `v{{version}}`의 스크립트와 파일로 실행: `ALL CHECKS OK: {{version}} is live`(OK {{rehearsalOk}}건, 실패 없음, "REHEARSAL, NOT CHECKED" 없음).
    `{{rehearsalSetups}}`. 끝난 뒤 컨테이너·볼륨·이미지·복사한 폴더, 리허설 키 삭제(호스트의 컨테이너 목록이 리허설 전과 같음).
  - 운영 배포(Chromite 세션이 하네스 `deploy`로 같은 태그의 스크립트와 같은 파일로 실행, `-DataGeneration {{dataGeneration}}`): `ALL CHECKS OK: {{version}} is live at {{siteUrl}}`(OK {{deployOk}}건, 실패 없음),
    옮겨 둔 이전 파일 `{{oldPath}}`. `{{deploySetups}}`. 같은 서버의 다른 서비스: 상태 주소 200, 내려받기 피드 목록이 배포 전과 같음.
  - 배포 뒤 확인(하네스 `verify`): 운영 `releases.json`의 {{version}} 세 파일의 크기·SHA-256이 `SHA256SUMS`와 같음(세대 {{dataGeneration}}, `notes_url`은 `#v{{version}}`), 설치 파일·dmg·zip 200과 같은 크기,
    `/`, `/ko/`, `/en/`, 변경 내역·지원 범위·개인정보 두 쪽씩 200, `#v{{version}}` 있음. {{olderVersion}} 설치 파일은 운영에서 내려감(404), {{previousVersion}} 설치 파일은 되돌리기용으로 남음(200).
?noticeFrom|  - {{noticeFrom}}에서 보이는지(하네스 `update-notice`): v{{noticeFrom}} 릴리스의 포터블 zip을 풀어 임시 `-data`, `-configuration`, `CHROMITE_DATA`로 실행하니 약 {{noticeSeconds}}초 안에 제목 줄에 "{{noticeText}}" 표시가 떴다
?noticeFrom|    (알림 모드. 내려받기·설치는 실행하지 않음).
?publishedKst|  - GitHub Release v{{version}}은 배포 확인 뒤 공개했다({{publishedKst}}, 최신 릴리스로 표시, 파일 4개와 `SHA256SUMS`).
  - 확인하지 않은 것: {{notChecked}} 회귀 테스트는 실행하지 않았다.
?notes|  - {{notes}}
=== tag-before-harness ===
(태그는 하네스 전에 붙었음)
=== record-todo ===
TODO(record): 확인하지 않은 것을 적는다(-NotChecked).
=== trial-not-needed ===
태그 전 수동 실행은 하지 않았다: `v{{previousTag}}` 뒤로 서명 과정 경로(`.github`, `tools/release`, `tools/installer`, `tools/jre`, `product`)에 변경이 없음(버전과, `release.yml`이 쓰지 않는 하네스 자신의 파일 `tools/release/release.ps1`·`tools/release/harness/` 제외).
=== trial-done ===
태그 전 `release.yml` 수동 실행({{trialRun}}, `{{trialCommit}}`): 성공(약 {{trialMinutes}}분{{trialReruns}}). 바뀐 서명 과정 경로: {{trialPaths}}.
=== run-first-try ===
모든 작업이 첫 시도에 성공(약 {{minutes}}분, 다시 실행 없음)
=== run-reruns ===
러너를 배정받지 못한 작업을 {{reruns}}번 다시 실행해 모든 작업 성공(약 {{minutes}}분)
=== reruns-part ===
, 러너를 배정받지 못한 작업 {{reruns}}번 다시 실행
=== seed-live ===
운영 피드에 있는 {{seeds}} 릴리스를 각 태그의 스크립트로 차례로 넣어 운영과 같은 상태로 만들고(`releases.json`이 `generated_at`만 빼고 운영과 같음)
=== seed-given ===
지정한 {{seeds}} 릴리스를 각 태그의 스크립트로 차례로 넣고(`-SeedTags`, 운영 피드와 비교하지 않음)
=== publish-part ===
, 공개 "{{goAheadPublish}}"
=== record-history ===
- {{today}} {{version}} 릴리스 기록(하네스 `record`): {{previousBlock}} 기록 아래에 태그, 리허설, 운영 배포, 배포 뒤 확인{{publishHistoryPart}}.
=== publish-history-part ===
, GitHub Release 공개
