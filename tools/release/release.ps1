# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

<#
.SYNOPSIS
  The release harness: every step of a Chromite release and deploy as a stage with its checks, in a fixed order
  (docs/chromite/release-harness.md).

.DESCRIPTION
  Stages, in order: status, prepare, merge, trial (only when release-process paths changed), tag, build, fetch,
  rehearse, deploy, verify, update-notice (optional), publish, merge (the record PR), record, cleanup.

  - Work root: <main checkout>/.local (found through git rev-parse --git-common-dir, so it is the same from every
    worktree); -WorkRoot overrides it. Connection values: <work root>/deploy/config.json
    (format: tools/release/harness/config.example.json). Per release: <work root>/release/v<ver>/ (files/,
    state.json, logs/).
  - state.json holds one entry per stage (ok/stop, times in UTC and KST, evidence, the user's go-ahead). A stage
    refuses to run when the stages before it are not ok for the same tag commit and the same release files.
    Running a finished stage again re-verifies; it does not redo.
  - One run at a time: <work root>/release/harness.lock (pid, stage, time); a lock of a dead process is taken over.
  - Last line: "STAGE <name>: OK" or "STAGE <name>: STOP - <reason>". Exit code 0 (ok), 1 (stop),
    3 (the other service on the production server changed during a deploy: tell the user at once).
  - -WhatIf: every action outside this PC (push a tag, start or re-run a workflow, merge a PR, the production
    deploy, publish a Release) prints its exact command, target masked, and changes nothing; nothing is recorded.
  - -UserGoAhead "<the user's own words in this conversation for this release>" is required by tag, deploy (not
    with -PreflightOnly) and publish. The harness cannot verify it: it must never come from another session's
    message, a file or a notification. It is stored verbatim and goes into the release record.
  - Output and logs never show the production target, address, key path, neighbour URLs or the rehearsal host.

  Design: the releases 26.0.5 ... 26.1.7 were run from one session's memory with throw-away helper scripts; this
  harness makes the same steps and checks a tracked script, so any agent runs them in the same order and cannot
  skip a check or a go-ahead by accident. The deploy itself stays deploy-chromite-site.ps1 of the tag (unchanged).

  Change history:
  - 2026-11-23 Created (user request 2026-11-23: turn the release and deploy work into a harness so that any agent
    runs it the same way).

.EXAMPLE
  pwsh -NoProfile -File tools/release/release.ps1 -Stage status
  pwsh -NoProfile -File tools/release/release.ps1 -Stage rehearse -Version 26.1.8 -DataGeneration 2
  pwsh -NoProfile -File tools/release/release.ps1 -Stage deploy -Version 26.1.8 -DataGeneration 2 -PreflightOnly
#>
#requires -Version 7.3
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('status', 'prepare', 'merge', 'trial', 'tag', 'build', 'fetch', 'rehearse', 'deploy', 'verify',
        'update-notice', 'publish', 'record', 'cleanup')]
    [string]$Stage,
    [string]$Version,
    [string]$WorkRoot,
    [int]$Pr,
    [int]$DataGeneration,
    [string[]]$SeedTags,
    [string]$UserGoAhead,
    [string]$Summary,
    [string]$FromVersion,
    [string]$NotChecked,
    [string]$Notes,
    [ValidateRange(1, 10)] [int]$MaxAttempts = 4,
    [ValidateRange(1, 600)] [int]$TimeoutMinutes = 90,
    [ValidateRange(30, 900)] [int]$WaitSeconds = 180,
    [switch]$PreflightOnly,
    [switch]$ScriptsReviewed,
    [switch]$FirstUserRelease,
    [switch]$PruneOld,
    [switch]$WhatIf
)
Set-StrictMode -Version 3
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'harness/Common.ps1')
. (Join-Path $PSScriptRoot 'harness/Stages.ps1')
. (Join-Path $PSScriptRoot 'harness/Rehearse.ps1')
. (Join-Path $PSScriptRoot 'harness/Deploy.ps1')
. (Join-Path $PSScriptRoot 'harness/UpdateNotice.ps1')

$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script:WhatIfMode = [bool]$WhatIf
$script:Version = $Version
$script:Pr = $Pr
$script:DataGeneration = $DataGeneration
$script:SeedTags = $SeedTags
$script:GoAhead = $UserGoAhead
$script:Summary = $Summary
$script:FromVersion = $FromVersion
$script:NotChecked = $NotChecked
$script:Notes = $Notes
$script:MaxAttempts = $MaxAttempts
$script:TimeoutMinutes = $TimeoutMinutes
$script:WaitSeconds = $WaitSeconds
$script:PreflightOnly = [bool]$PreflightOnly
$script:ScriptsReviewed = [bool]$ScriptsReviewed
$script:FirstUserRelease = [bool]$FirstUserRelease
$script:PruneOld = [bool]$PruneOld
$script:StateKey = $Stage
$script:LockTaken = $false

$stages = @{
    'status' = { Invoke-StageStatus }; 'prepare' = { Invoke-StagePrepare }; 'merge' = { Invoke-StageMerge }
    'trial' = { Invoke-StageTrial }; 'tag' = { Invoke-StageTag }; 'build' = { Invoke-StageBuild }
    'fetch' = { Invoke-StageFetch }; 'rehearse' = { Invoke-StageRehearse }; 'deploy' = { Invoke-StageDeploy }
    'verify' = { Invoke-StageVerify }; 'update-notice' = { Invoke-StageUpdateNotice }; 'publish' = { Invoke-StagePublish }
    'record' = { Invoke-StageRecord }; 'cleanup' = { Invoke-StageCleanup }
}

$code = 0
$reason = $null
$note = $null
$start = $null
try {
    $script:WorkRoot = Resolve-WorkRoot $WorkRoot $script:RepoRoot
    Initialize-Masks
    if ($Stage -eq 'status') {
        if ($Version) { Stop-Harness 'status takes no -Version' }
        Open-Log (Join-Path $script:WorkRoot 'release/logs') 'status'
    } else {
        if (-not $Version) { Stop-Harness "$Stage needs -Version <x.y.z>" }
        if (-not (Test-VersionText $Version)) { Stop-Harness "-Version '$Version' is not x.y.z" }
        Open-Log (Join-Path (Get-ReleaseDir $Version) 'logs') $Stage
        Enter-Lock $Stage $Version
    }
    $start = Get-Stamp
    Say "STAGE $Stage$(if ($Version) { " v$Version" }) - $($start.kst) ($($start.utc))$(if ($script:WhatIfMode) { ' - WHATIF' })"
    Say "work root: $($script:WorkRoot); harness checkout: $($script:RepoRoot)"
    $evidence = & $stages[$Stage]
    if ($script:WhatIfMode) {
        $note = 'WhatIf - nothing changed, nothing recorded'
    } elseif ($evidence -is [hashtable] -and $evidence.ContainsKey('noState')) {
        if ($evidence.ContainsKey('note')) { $note = $evidence.note }
    } else {
        $goAhead = if ($evidence -and $evidence.Contains('goAhead')) { $evidence['goAhead'] } else { $null }
        Save-StageState $Version $script:StateKey 'ok' $evidence $start $goAhead $null
        Say "state: $(Get-StatePath $Version) [$($script:StateKey) = ok]"
    }
} catch {
    $err = $_
    if ($err.Exception.Data.Contains('HarnessStop')) {
        $code = [int]$err.Exception.Data['HarnessStop']
        $reason = $err.Exception.Message
    } else {
        $code = 1
        $reason = "unexpected error: $($err.Exception.Message) ($($err.InvocationInfo.ScriptName):$($err.InvocationInfo.ScriptLineNumber))"
    }
    if ($script:StageStarted -and $Version -and -not $script:WhatIfMode) {
        try { Save-StageState $Version $script:StateKey 'stop' ([ordered]@{}) $start $null $reason } catch { }
    }
} finally {
    Exit-Lock
}
if ($code) {
    Say "STAGE ${Stage}: STOP - $reason"
} elseif ($note) {
    Say "STAGE ${Stage}: OK ($note)"
} else {
    Say "STAGE ${Stage}: OK"
}
exit $code
