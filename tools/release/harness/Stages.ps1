# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
# Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
#
# Stages of the release harness except rehearse, deploy and update-notice (their own files): status, prepare,
# merge, trial, tag, build, fetch, verify, publish, record, cleanup. docs/chromite/release-harness.md has the table.
# Each Invoke-Stage* returns the evidence for state.json (an ordered hashtable), or a hashtable with
# noState = $true when nothing may be recorded (a -WhatIf run, a preflight); it stops through Stop-Harness.
#
# Design: every check the 26.0.5 ... 26.1.7 releases did by hand (docs/chromite/distribution.md, the release records) is a
# check here, in the same order; an action outside this PC only runs through Invoke-Change.
#
# Change history:
# - 2026-11-23 Created (ports of merge-one.sh, rerun-until-ok.sh, verify-prod-26-1-7.py and the prepare/record
#   edits of the 26.1.7 release).

# ---- Korean text (text.ko.md)

$script:KoText = $null

function Get-KoText {
    if (-not $script:KoText) {
        $sections = @{}
        $name = $null
        foreach ($line in [IO.File]::ReadAllLines((Join-Path $PSScriptRoot 'text.ko.md'), [Text.UTF8Encoding]::new($false))) {
            if ($line -match '^=== ([a-z-]+) ===$') { $name = $Matches[1]; $sections[$name] = [Collections.Generic.List[string]]::new(); continue }
            if ($name) { $sections[$name].Add($line) }
        }
        $script:KoText = $sections
    }
    return $script:KoText
}

# Fills a section; a line "?key|..." stays only when key has a value. Returns the lines.
function Format-KoText([string]$Section, [hashtable]$Values) {
    $all = Get-KoText
    if (-not $all.ContainsKey($Section)) { throw "text.ko.md has no section '$Section'" }
    $out = @()
    foreach ($line in $all[$Section]) {
        $l = $line
        if ($l -match '^\?([A-Za-z]+)\|(.*)$') {
            if (-not $Values[$Matches[1]]) { continue }
            $l = $Matches[2]
        }
        $l = [regex]::Replace($l, '\{\{([A-Za-z]+)\}\}', { param($m) "$($Values[$m.Groups[1].Value])" })
        $out += $l
    }
    while ($out.Count -and $out[-1] -eq '') { $out = $out[0..($out.Count - 2)] }
    return , $out
}

function Read-TextFile([string]$Path) {
    $text = [IO.File]::ReadAllText($Path, [Text.UTF8Encoding]::new($false))
    $nl = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    return [pscustomobject]@{ Text = $text; Nl = $nl }
}

function Write-TextFile([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

function Get-TodayKst { return [DateTime]::UtcNow.AddHours(9).ToString('yyyy-MM-dd') }

# The checkout must be on $Branch, based on the current origin/command.
function Assert-ReleaseBranch([string]$Branch) {
    $current = Get-GitLine @('rev-parse', '--abbrev-ref', 'HEAD')
    if ($current -ne $Branch) { Stop-Harness "run this stage in a worktree on branch $Branch (this checkout $($script:RepoRoot) is on $current)" }
    Update-Origin
    $oc = Get-GitLine @('rev-parse', 'origin/command')
    $base = Get-GitLine @('merge-base', 'HEAD', 'origin/command')
    if ($base -ne $oc) { Stop-Harness "branch $Branch is not based on the current origin/command ($oc; merge-base $base): recreate it from origin/command" }
    Say "branch $Branch, based on origin/command $($oc.Substring(0, 10))"
}

# ---- status

function Invoke-StageStatus {
    Update-Origin
    $last = Get-LastTagVersion
    $lastCommit = Get-TagCommit $last
    Say "last tag: v$last = $($lastCommit.Substring(0, 10))"
    $feed = Get-LiveFeed
    $versions = Get-FeedVersions $feed
    $gens = @(@($feed.files) | ForEach-Object { $_.data_generation } | Sort-Object -Unique)
    Say "live feed: versions $($versions -join ', '); data_generation $($gens -join ', '); generated_at $($feed.generated_at)"
    $oc = Get-GitLine @('rev-parse', 'origin/command')
    $log = Invoke-Git @('log', '--oneline', '--no-decorate', "v$last..origin/command") -Quiet
    $commits = @($log.Lines | Where-Object { $_ })
    Say "origin/command = $($oc.Substring(0, 10)), $($commits.Count) commit(s) since v${last}:"
    foreach ($c in $commits) { Say "  $c" }
    $stat = Invoke-Git (@('diff', '--stat', "v$last", 'origin/command', '--') + $script:ProcessPaths) -Quiet
    $changed = Get-ProcessPathChanges "v$last" 'origin/command'
    if ($changed.Count) {
        Say "release-process paths changed since v$last (besides the version): a trial run is required before tag"
        foreach ($f in $changed) { Say "  $f" }
    } else {
        Say "no change in release-process paths since v$last (besides the version): no trial run needed"
    }
    if ($stat.Lines) { Say 'git diff --stat of those paths:'; foreach ($l in $stat.Lines) { Say "  $l" } }
    $open = @()
    foreach ($dir in @(Get-ChildItem -Directory (Join-Path $script:WorkRoot 'release') -Filter 'v*' -ErrorAction SilentlyContinue)) {
        $sp = Join-Path $dir.FullName 'state.json'
        if (-not (Test-Path -LiteralPath $sp)) { continue }
        $s = Get-Content -LiteralPath $sp -Raw | ConvertFrom-Json -AsHashtable
        $parts = @($s.stages.Keys | Sort-Object | ForEach-Object { "$_=$($s.stages[$_].status)" })
        $done = $s.stages['record'] -and $s.stages['record'].status -eq 'ok'
        if (-not $done) { $open += "$($dir.Name): $($parts -join ', ')" }
    }
    if ($open) { Say 'unfinished release state:'; foreach ($o in $open) { Say "  $o" } } else { Say 'no unfinished release state' }
    return @{ noState = $true }
}

# ---- prepare

function Invoke-StagePrepare {
    $ver = $script:Version
    Assert-ReleaseBranch "chromite/release-$(Get-Dashed $ver)"
    $last = Get-LastTagVersion
    if ([version]$ver -le [version]$last) { Stop-Harness "version $ver is not greater than the last tag v$last" }
    if ((Get-TagCommit $ver) -or (Get-RemoteTagCommit $ver)) { Stop-Harness "v$ver is tagged already (a number is never reused)" }
    Set-StageStarted
    $bump = Join-Path $script:RepoRoot 'tools/release/bump-version.ps1'
    $current = (Invoke-Native -Exe 'pwsh' -Arguments @('-NoProfile', '-File', $bump) -Quiet -What 'bump-version.ps1').Lines[-1].Trim()
    if ($current -ne $ver) {
        $bumpArgs = @('-NoProfile', '-File', $bump, $ver)
        if ($script:FirstUserRelease) { $bumpArgs += '-FirstUserRelease' }
        Invoke-Native -Exe 'pwsh' -Arguments $bumpArgs -What "bump-version.ps1 $ver" | Out-Null
    } else {
        Say "the four version files carry $ver already"
    }
    $check = (Invoke-Native -Exe 'pwsh' -Arguments @('-NoProfile', '-File', $bump) -Quiet -What 'bump-version.ps1').Lines[-1].Trim()
    if ($check -ne $ver) { Stop-Harness "bump-version.ps1 reports $check, not $ver" }
    Say "OK: product, MANIFEST.MF and the two pom.xml carry $ver"

    $missing = @()
    foreach ($lang in 'ko', 'en') {
        $rel = "site/$lang/changelog.html"
        $text = [IO.File]::ReadAllText((Join-Path $script:RepoRoot $rel), [Text.UTF8Encoding]::new($false))
        $n = ([regex]::Matches($text, [regex]::Escape("id=""v$ver"""))).Count
        if ($n -eq 0) {
            $m = [regex]::Match($text, '(?m)^.*<section class="sect release" id="v\d+\.\d+\.\d+">.*$')
            $line = if ($m.Success) { ($text.Substring(0, $m.Index) -split "`n").Count } else { '?' }
            $missing += "$rel (insert above line ${line}: $(if ($m.Success) { $m.Value.Trim() } else { 'the first release section' }))"
            continue
        }
        if ($n -gt 1) { Stop-Harness "$rel holds id=""v$ver"" $n times" }
        $first = [regex]::Match($text, 'id="v(\d+\.\d+\.\d+)"')
        if ($first.Groups[1].Value -ne $ver) { Stop-Harness "$rel`: the v$ver section is not above v$($first.Groups[1].Value)" }
        $sectionEnd = $text.IndexOf('</section>', $first.Index)
        if ($text.Substring($first.Index, $sectionEnd - $first.Index) -match 'TODO') { Stop-Harness "$rel`: the v$ver section still holds TODO" }
        Say "OK: $rel holds one v$ver section, above v$last"
    }
    if ($missing) {
        Say 'changelog sections are missing. Write them (the harness never writes changelog text):'
        foreach ($m in $missing) { Say "  $m" }
        Say 'skeleton (same in both languages, text in the page language):'
        foreach ($l in @(
                "  <section class=""sect release"" id=""v$ver"">", "    <h2>$ver</h2>", '    <div class="body">',
                '      <p>TODO one sentence</p>', '      <ul>', '        <li>TODO item</li>', '      </ul>', '    </div>', '  </section>', '')) {
            Say $l
        }
        Stop-Harness 'changelog sections missing'
    }

    $summaryLine = $null
    if ($script:Summary) {
        $docPath = Join-Path $script:RepoRoot 'docs/chromite/distribution.md'
        $doc = Read-TextFile $docPath
        $marker = (Format-KoText 'prepare-history-marker' @{ version = $ver })[0]
        if ($doc.Text.Contains($marker)) {
            Say "distribution.md has a prepare line for $ver already"
        } else {
            $summaryLine = (Format-KoText 'prepare-history' @{
                    date = Get-TodayKst; version = $ver; branch = "chromite/release-$(Get-Dashed $ver)"; summary = $script:Summary.Trim()
                })[0]
            $text = $doc.Text
            if (-not $text.EndsWith($doc.Nl)) { $text += $doc.Nl }
            Write-TextFile $docPath ($text + $summaryLine + $doc.Nl)
            Say "distribution.md history: $summaryLine"
        }
    }
    $status = Invoke-Git @('status', '--short') -Quiet
    Say 'changed files (commit them as one commit on this branch):'
    foreach ($l in $status.Lines) { Say "  $l" }
    return [ordered]@{
        branch = "chromite/release-$(Get-Dashed $ver)"
        base = Get-GitLine @('rev-parse', 'origin/command')
        previousTag = "v$last"
        historyLine = $summaryLine
    }
}

# ---- merge

function Invoke-StageMerge {
    $ver = $script:Version
    if (-not $script:Pr) { Stop-Harness 'merge needs -Pr <number>' }
    $n = $script:Pr
    $fields = 'number,headRefName,baseRefName,state,title,mergeable,mergeCommit,headRefOid,url'
    $pr = Invoke-GhJson @('pr', 'view', "$n", '--repo', $script:Repo, '--json', $fields) "gh pr view $n"
    $releaseHead = "chromite/release-$(Get-Dashed $ver)"
    $kind = if ($pr.headRefName -eq $releaseHead) { 'release' } elseif ($pr.headRefName -eq "$releaseHead-record") { 'record' } else { $null }
    if (-not $kind) { Stop-Harness "PR #$n head is $($pr.headRefName), expected $releaseHead or $releaseHead-record" }
    if ($pr.baseRefName -ne 'command') { Stop-Harness "PR #$n targets $($pr.baseRefName), not command" }
    $script:StateKey = if ($kind -eq 'record') { 'merge-record' } else { 'merge' }
    Assert-Prior $ver @($(if ($kind -eq 'record') { 'record' } else { 'prepare' }))
    if ($pr.state -eq 'MERGED') {
        Say "PR #$n is merged already: $($pr.mergeCommit.oid)"
        return [ordered]@{ pr = $n; head = $pr.headRefName; mergeCommit = $pr.mergeCommit.oid; title = $pr.title; mergedBy = 'earlier' }
    }
    if ($pr.state -ne 'OPEN') { Stop-Harness "PR #$n is $($pr.state)" }
    Set-StageStarted
    $headOid = $pr.headRefOid
    # GitHub can report a stale CONFLICTING/UNKNOWN right after a push; a real conflict stays.
    $mergeable = $pr.mergeable
    for ($i = 0; $i -lt 16 -and $mergeable -ne 'MERGEABLE'; $i++) {
        Say "mergeable: $mergeable, waiting"
        Start-Sleep -Seconds 15
        $mergeable = (Invoke-GhJson @('pr', 'view', "$n", '--repo', $script:Repo, '--json', 'mergeable') 'gh pr view').mergeable
    }
    if ($mergeable -ne 'MERGEABLE') { Stop-Harness "PR #$n is $mergeable (never merged on a conflict)" }
    Say 'OK: mergeable'
    $deadline = [DateTime]::UtcNow.AddMinutes($script:TimeoutMinutes)
    while ($true) {
        $r = Invoke-Native -Exe 'gh' -Arguments @('pr', 'checks', "$n", '--repo', $script:Repo, '--json', 'name,bucket') -Quiet -AllowFail
        $checks = @()
        try { $checks = @(((@($r.Lines) -join "`n") | ConvertFrom-Json)) } catch { }
        $red = @($checks | Where-Object { $_.bucket -in @('fail', 'cancel') })
        $pending = @($checks | Where-Object { $_.bucket -eq 'pending' })
        if ($red) { Stop-Harness "checks are red: $(($red | ForEach-Object { "$($_.name)=$($_.bucket)" }) -join ', ') (never merged on red)" }
        if ($checks.Count -and -not $pending) { break }
        if ([DateTime]::UtcNow -gt $deadline) { Stop-Harness "checks still pending after $($script:TimeoutMinutes) min" }
        Say "checks: $($checks.Count) reported, $($pending.Count) pending"
        Start-Sleep -Seconds 30
    }
    Say "OK: $($checks.Count) checks green"
    $now = Invoke-GhJson @('pr', 'view', "$n", '--repo', $script:Repo, '--json', 'headRefOid,state') 'gh pr view'
    if ($now.headRefOid -ne $headOid) { Stop-Harness "PR #$n got a new commit during the wait ($($now.headRefOid)): run merge again" }
    $title = "$($pr.title) (#$n)"
    $res = Invoke-Change 'squash-merge the PR' 'gh' @('api', '-X', 'PUT', "repos/$($script:Repo)/pulls/$n/merge",
        '-f', 'merge_method=squash', '-f', "commit_title=$title", '-f', "sha=$headOid")
    if ($script:WhatIfMode) { return @{ noState = $true } }
    $done = Invoke-GhJson @('pr', 'view', "$n", '--repo', $script:Repo, '--json', 'state,mergeCommit') 'gh pr view'
    if ($done.state -ne 'MERGED') { Stop-Harness "PR #$n is $($done.state) after the merge call" }
    Say "OK: merged as $($done.mergeCommit.oid)"
    return [ordered]@{ pr = $n; head = $pr.headRefName; mergeCommit = $done.mergeCommit.oid; title = $title; mergedBy = 'harness' }
}

# ---- workflow runs (trial and build)

function Get-StepCount($Job) { if ($null -eq $Job.steps) { return 0 }; return @($Job.steps).Count }

# Waits for a release.yml run; re-runs only jobs that never started (no steps: no runner was assigned). A job that
# ran and failed stops the stage.
function Wait-ReleaseRun([long]$RunId) {
    $deadline = [DateTime]::UtcNow.AddMinutes($script:TimeoutMinutes)
    $reruns = 0
    while ($true) {
        $run = Invoke-GhJson @('run', 'view', "$RunId", '--repo', $script:Repo, '--json', 'status,conclusion,headSha,attempt,createdAt,updatedAt,jobs,url') "gh run view $RunId"
        if ($run.status -ne 'completed') {
            if ([DateTime]::UtcNow -gt $deadline) { Stop-Harness "run $RunId not finished after $($script:TimeoutMinutes) min" }
            Say "run $RunId attempt $($run.attempt): $($run.status)"
            Start-Sleep -Seconds 30
            continue
        }
        $jobs = @($run.jobs)
        foreach ($j in $jobs) { Say "  job '$($j.name)': $($j.conclusion), $(Get-StepCount $j) steps" }
        if ($run.conclusion -eq 'success') {
            $minutes = [int][Math]::Round(([DateTime]$run.updatedAt - [DateTime]$run.createdAt).TotalMinutes)
            return [pscustomobject]@{ Run = $run; Reruns = $reruns; Minutes = $minutes; WhatIf = $false }
        }
        $bad = @($jobs | Where-Object { $_.conclusion -notin @('success', 'skipped') })
        $never = @($bad | Where-Object { (Get-StepCount $_) -eq 0 })
        if ($bad.Count -ne $never.Count) {
            Stop-Harness "run $RunId failed in a job that ran: $(($bad | Where-Object { Get-StepCount $_ } | ForEach-Object { $_.name }) -join ', ') - not re-run"
        }
        if ($run.attempt -ge $script:MaxAttempts) { Stop-Harness "run ${RunId}: jobs never got a runner after $($run.attempt) attempts (-MaxAttempts $($script:MaxAttempts))" }
        Invoke-Change "re-run the jobs of run $RunId that never started ($(($never | ForEach-Object { $_.name }) -join ', '))" 'gh' @('run', 'rerun', "$RunId", '--repo', $script:Repo, '--failed') | Out-Null
        if ($script:WhatIfMode) { return [pscustomobject]@{ WhatIf = $true } }
        $reruns++
        Start-Sleep -Seconds 20
    }
}

# ---- trial

function Invoke-StageTrial {
    $ver = $script:Version
    Assert-Prior $ver @('merge')
    $merge = (Get-StageEvidence $ver 'merge').mergeCommit
    Update-Origin
    $prev = Get-PrevTagVersion $ver
    $changed = Get-ProcessPathChanges "v$prev" $merge
    if (-not $changed.Count) {
        Say "no change in release-process paths between v$prev and $($merge.Substring(0, 10)) (besides the version): no trial run needed"
        return [ordered]@{ needed = $false; commit = $merge; previousTag = "v$prev" }
    }
    Say "release-process paths changed since v${prev}:"
    foreach ($f in $changed) { Say "  $f" }
    $oc = Get-GitLine @('rev-parse', 'origin/command')
    if ($oc -ne $merge) { Stop-Harness "origin/command ($oc) is not the release merge commit ($merge): a trial now would test another commit" }
    Set-StageStarted
    $list = Invoke-GhJson @('run', 'list', '--repo', $script:Repo, '--workflow', 'release.yml', '--event', 'workflow_dispatch', '--branch', 'command',
        '--json', 'databaseId,headSha,status,conclusion,createdAt', '--limit', '20') 'gh run list'
    $existing = @($list | Where-Object { $_.headSha -eq $merge -and ($_.status -ne 'completed' -or $_.conclusion -eq 'success') } | Select-Object -First 1)
    if ($existing) {
        $runId = [long]$existing[0].databaseId
        Say "a trial run of this commit exists: $runId ($($existing[0].status) $($existing[0].conclusion))"
    } else {
        $since = [DateTime]::UtcNow.AddMinutes(-1)
        Invoke-Change 'start the trial run of release.yml on command' 'gh' @('workflow', 'run', 'release.yml', '--repo', $script:Repo, '--ref', 'command') | Out-Null
        if ($script:WhatIfMode) { return @{ noState = $true } }
        $runId = 0
        for ($i = 0; $i -lt 18 -and -not $runId; $i++) {
            Start-Sleep -Seconds 10
            $list = Invoke-GhJson @('run', 'list', '--repo', $script:Repo, '--workflow', 'release.yml', '--event', 'workflow_dispatch', '--branch', 'command',
                '--json', 'databaseId,headSha,createdAt', '--limit', '5') 'gh run list'
            $new = @($list | Where-Object { $_.headSha -eq $merge -and [DateTime]$_.createdAt -ge $since } | Select-Object -First 1)
            if ($new) { $runId = [long]$new[0].databaseId }
        }
        if (-not $runId) { Stop-Harness 'the trial run did not appear within 3 minutes' }
        Say "trial run $runId"
    }
    $res = Wait-ReleaseRun $runId
    if ($res.WhatIf) { return @{ noState = $true } }
    return [ordered]@{ needed = $true; commit = $merge; runId = $runId; attempt = $res.Run.attempt; reruns = $res.Reruns; minutes = $res.Minutes; changedPaths = @($changed); previousTag = "v$prev" }
}

# ---- tag

function Invoke-StageTag {
    $ver = $script:Version
    Assert-OriginIsChromite
    Update-Origin
    $remote = Get-RemoteTagCommit $ver
    if ($remote) {
        # Re-run, or a release tagged before the harness: re-verify, push nothing.
        $local = Get-TagCommit $ver
        if ($local -ne $remote) { Stop-Harness "the local tag v$ver ($local) differs from origin ($remote)" }
        if (-not (Test-CommitCarriesVersion $remote $ver)) { Stop-Harness "v$ver points at $remote, which does not carry version $ver" }
        $merge = Get-StageEvidence $ver 'merge'
        if ($merge -and $merge.mergeCommit -ne $remote) { Stop-Harness "v$ver is on $remote, the release merge commit is $($merge.mergeCommit)" }
        $before = Get-StageEvidence $ver 'tag'
        Say "v$ver is on origin already at $remote and carries ${ver}: nothing pushed"
        $ev = [ordered]@{ tagCommit = $remote; pushed = $false; pushedEarlier = $false }
        if ($before) {
            $ev.pushedEarlier = [bool]($before['pushed'] -or $before['pushedEarlier'])
            foreach ($k in 'goAhead', 'trial', 'mergeCommit') { if ($before[$k]) { $ev[$k] = $before[$k] } }
        }
        return $ev
    }
    Assert-GoAhead 'tag'
    Assert-Prior $ver @('merge')
    $merge = (Get-StageEvidence $ver 'merge').mergeCommit
    $inCommand = Invoke-Git @('merge-base', '--is-ancestor', $merge, 'origin/command') -AllowFail -Quiet
    if ($inCommand.Code) { Stop-Harness "the release merge commit $merge is not on origin/command" }
    if (-not (Test-CommitCarriesVersion $merge $ver)) { Stop-Harness "the release merge commit $merge does not carry version $ver" }
    if (Get-TagCommit $ver) { Stop-Harness "a local tag v$ver exists that is not on origin: look at it (git show v$ver) before anything else" }
    $prev = Get-PrevTagVersion $ver
    $changed = Get-ProcessPathChanges "v$prev" $merge
    if ($changed.Count) {
        $trial = Get-StageEvidence $ver 'trial'
        if (-not $trial -or -not $trial.needed -or $trial.commit -ne $merge) {
            Stop-Harness "release-process paths changed since v$prev ($($changed -join ', ')): an ok trial of $merge is required (stage trial)"
        }
        $trialNote = "trial run $($trial.runId) of $merge ok"
    } else {
        $trialNote = "trial not needed: no change in release-process paths since v$prev"
    }
    Say $trialNote
    Set-StageStarted
    Invoke-Change "create the annotated tag v$ver on $merge" 'git' @('-C', $script:RepoRoot, 'tag', '-a', "v$ver", $merge, '-m', "Chromite $ver") | Out-Null
    Invoke-Change "push only the tag v$ver to origin" 'git' @('-C', $script:RepoRoot, 'push', 'origin', "refs/tags/v$ver") | Out-Null
    if ($script:WhatIfMode) { return @{ noState = $true } }
    $now = Get-RemoteTagCommit $ver
    if ($now -ne $merge) { Stop-Harness "origin's v$ver is '$now' after the push, expected $merge" }
    Say "OK: v$ver = $merge on origin"
    return [ordered]@{ tagCommit = $merge; mergeCommit = $merge; pushed = $true; trial = $trialNote; goAhead = $script:GoAhead }
}

# ---- build

function Get-TagCommitOrStop([string]$Ver) {
    $tc = Get-TagCommit $Ver
    if (-not $tc) { Update-Origin; $tc = Get-TagCommit $Ver }
    if (-not $tc) { Stop-Harness "no tag v$Ver" }
    return $tc
}

function Invoke-StageBuild {
    $ver = $script:Version
    $tc = Get-TagCommitOrStop $ver
    Assert-Prior $ver @('tag') $tc
    $runId = 0
    for ($i = 0; $i -lt 20 -and -not $runId; $i++) {
        $list = Invoke-GhJson @('run', 'list', '--repo', $script:Repo, '--workflow', 'release.yml', '--event', 'push',
            '--json', 'databaseId,headSha,headBranch,status,conclusion,createdAt', '--limit', '50') 'gh run list'
        $mine = @($list | Where-Object { $_.headSha -eq $tc -and $_.headBranch -eq "v$ver" } | Sort-Object { [DateTime]$_.createdAt } -Descending)
        if ($mine) { $runId = [long]$mine[0].databaseId; break }
        Say 'no release.yml run of the tag yet, waiting'
        Start-Sleep -Seconds 30
    }
    if (-not $runId) { Stop-Harness "no release.yml run for v$ver ($tc) within 10 minutes" }
    Say "release.yml run $runId (tag v$ver, $($tc.Substring(0, 10)))"
    Set-StageStarted
    $res = Wait-ReleaseRun $runId
    if ($res.WhatIf) { return @{ noState = $true } }
    if ($res.Run.headSha -ne $tc) { Stop-Harness "run $runId is for $($res.Run.headSha), not $tc" }
    Say "OK: run $runId success (attempt $($res.Run.attempt), about $($res.Minutes) min, re-runs by this stage: $($res.Reruns))"
    return [ordered]@{ tagCommit = $tc; runId = $runId; attempt = $res.Run.attempt; reruns = $res.Reruns; minutes = $res.Minutes; url = $res.Run.url }
}

# ---- fetch

function Invoke-StageFetch {
    $ver = $script:Version
    $tc = Get-TagCommitOrStop $ver
    Assert-Prior $ver @('tag') $tc
    $dir = Get-FilesDir $ver
    $names = Get-ReleaseFileNames $ver
    $expected = @($names) + 'SHA256SUMS'
    Set-StageStarted
    $complete = (Test-Path -LiteralPath $dir) -and -not @($expected | Where-Object { -not (Test-Path -LiteralPath (Join-Path $dir $_)) })
    $downloaded = $false
    if ($complete) {
        Say "all $($expected.Count) files are in $dir already: no download"
    } else {
        New-Item -ItemType Directory -Force $dir | Out-Null
        Invoke-Native -Exe 'gh' -Arguments @('release', 'download', "v$ver", '--repo', $script:Repo, '--dir', $dir, '--skip-existing') -What "gh release download v$ver" | Out-Null
        $downloaded = $true
    }
    $extra = @(Get-ChildItem -LiteralPath $dir -File | Where-Object { $expected -notcontains $_.Name } | ForEach-Object { $_.Name })
    if ($extra) { Stop-Harness "unexpected files in ${dir}: $($extra -join ', ')" }
    $listed = @((Read-Sums $dir).Keys)
    if (@(Compare-Object ($listed | Sort-Object) ($names | Sort-Object)).Count) { Stop-Harness "SHA256SUMS lists $($listed -join ', '), expected $($names -join ', ')" }
    if (-not (Test-Sums $dir $names)) { Stop-Harness "the files in $dir do not match SHA256SUMS (delete them and run fetch again)" }
    Say "OK: sha256sum -c, $($names.Count) OK"
    $rel = Invoke-GhJson @('release', 'view', "v$ver", '--repo', $script:Repo, '--json', 'assets,isDraft') "gh release view v$ver"
    $files = [ordered]@{}
    $sums = Read-Sums $dir
    foreach ($n in $expected) {
        $size = (Get-Item -LiteralPath (Join-Path $dir $n)).Length
        $asset = @($rel.assets | Where-Object { $_.name -eq $n })
        if ($asset.Count -ne 1 -or [long]$asset[0].size -ne $size) { Stop-Harness "the Release asset $n differs from the local file ($size bytes)" }
        $files[$n] = [ordered]@{ size = $size; sha256 = $sums[$n] }
    }
    if (@($rel.assets).Count -ne $expected.Count) { Stop-Harness "the Release has $(@($rel.assets).Count) assets, expected $($expected.Count)" }
    Say "OK: the Release (draft: $($rel.isDraft)) holds exactly these $($expected.Count) files with these sizes"
    return [ordered]@{ tagCommit = $tc; filesDigest = Get-FilesDigest $ver; downloaded = $downloaded; files = $files }
}

# ---- verify (also the last part of deploy)

function Resolve-DataGeneration([string]$Ver) {
    if ($script:DataGeneration) { return $script:DataGeneration }
    foreach ($k in 'deploy', 'rehearse') {
        $e = Get-StageEvidence $Ver $k
        if ($e -and $e.dataGeneration) { return [int]$e.dataGeneration }
    }
    Stop-Harness 'give -DataGeneration (no deploy or rehearse state to take it from)'
}

# The post-deploy production checks (port of verify-prod-26-1-7.py). Returns the BAD list.
function Test-Production([string]$Ver, [int]$Generation) {
    $script:Bad = @()
    $site = (Get-Cfg 'production.siteUrl').TrimEnd('/')
    $dir = Get-FilesDir $Ver
    $sums = Read-Sums $dir
    $feed = Get-LiveFeed
    $versions = Get-FeedVersions $feed
    Say "live feed versions: $($versions -join ', ')"
    foreach ($n in Get-SiteFileNames $Ver) {
        $entry = @($feed.files | Where-Object { $_.file -eq $n })
        $p = Join-Path $dir $n
        $size = if (Test-Path -LiteralPath $p) { (Get-Item -LiteralPath $p).Length } else { -1 }
        $ok = $entry.Count -eq 1 -and [long]$entry[0].size -eq $size -and $entry[0].sha256 -eq $sums[$n] -and
            $entry[0].version -eq $Ver -and [int]$entry[0].data_generation -eq $Generation -and "$($entry[0].notes_url)".EndsWith("#v$Ver")
        Test-Check "feed lists $n with the local size, sha256, data_generation $Generation and notes_url #v$Ver" $ok ($entry | ConvertTo-Json -Compress)
        $h = Get-HttpHead "$site/downloads/$n"
        Test-Check "$n answers 200 with $size bytes" ($h.Code -eq '200' -and $h.Length -eq $size) "$($h.Code), $($h.Length) bytes"
    }
    $ok = $versions.Count -eq 2 -and $versions[0] -eq $Ver
    Test-Check "the feed lists $Ver and one older version" $ok ($versions -join ', ')
    if ($versions.Count -ge 2) {
        $prev = $versions[1]
        $h = Get-HttpHead "$site/downloads/Chromite-$prev-x64-setup.exe"
        Test-Check "the previous setup ($prev) answers 200 (rollback)" ($h.Code -eq '200') $h.Code
        $older = Get-PrevTagVersion $prev
        if ($older) {
            $h = Get-HttpHead "$site/downloads/Chromite-$older-x64-setup.exe"
            Test-Check "the setup before it ($older) answers 404" ($h.Code -eq '404') $h.Code
        }
    }
    foreach ($path in '/', '/ko/', '/en/', '/ko/changelog.html', '/en/changelog.html', '/ko/privacy.html', '/en/privacy.html',
        '/ko/support.html', '/en/support.html', '/assets/site.js', '/assets/support.js', '/assets/site.css') {
        $r = Get-Http "$site$path"
        Test-Check "$path answers 200" ($r.Code -eq '200') $r.Code
    }
    foreach ($lang in 'ko', 'en') {
        $r = Get-Http "$site/$lang/changelog.html"
        $count = ([regex]::Matches($r.Body, [regex]::Escape("id=""v$Ver"""))).Count
        Test-Check "/$lang/changelog.html holds the anchor v$Ver once" ($count -eq 1) "$count"
    }
    return [pscustomobject]@{ Bad = @($script:Bad); Versions = $versions }
}

function Invoke-StageVerify {
    $ver = $script:Version
    $tc = Get-TagCommitOrStop $ver
    $gen = Resolve-DataGeneration $ver
    $names = Get-SiteFileNames $ver
    if (-not (Test-Sums (Get-FilesDir $ver) $names)) { Stop-Harness "the local release files of $ver do not match SHA256SUMS (run fetch)" }
    Set-StageStarted
    $res = Test-Production $ver $gen
    if ($res.Bad.Count) { Stop-Harness "$($res.Bad.Count) production check(s) BAD: $($res.Bad -join '; ')" }
    Say "ALL PRODUCTION CHECKS OK: $ver"
    return [ordered]@{ tagCommit = $tc; filesDigest = Get-FilesDigest $ver; dataGeneration = $gen; feedVersions = @($res.Versions) }
}

# ---- publish

function Invoke-StagePublish {
    $ver = $script:Version
    $tc = Get-TagCommitOrStop $ver
    $digest = Get-FilesDigest $ver
    Assert-GoAhead 'publish'
    Assert-Prior $ver @('deploy', 'verify') $tc $digest
    $rel = Invoke-GhJson @('release', 'view', "v$ver", '--repo', $script:Repo, '--json', 'isDraft,publishedAt,assets') "gh release view v$ver"
    $latest = @(Invoke-GhJson @('release', 'list', '--repo', $script:Repo, '--json', 'tagName,isLatest', '--limit', '20') 'gh release list' |
        Where-Object { $_.isLatest } | ForEach-Object { $_.tagName })
    if (-not $rel.isDraft -and $latest -contains "v$ver") {
        Say "v$ver is published and latest already ($($rel.publishedAt))"
        return [ordered]@{ tagCommit = $tc; filesDigest = $digest; publishedAt = $rel.publishedAt; publishedBy = 'earlier' }
    }
    Set-StageStarted
    Invoke-Change "publish the GitHub Release v$ver as latest" 'gh' @('release', 'edit', "v$ver", '--repo', $script:Repo, '--draft=false', '--latest') | Out-Null
    if ($script:WhatIfMode) { return @{ noState = $true } }
    $rel = Invoke-GhJson @('release', 'view', "v$ver", '--repo', $script:Repo, '--json', 'isDraft,publishedAt') "gh release view v$ver"
    $latest = @(Invoke-GhJson @('release', 'list', '--repo', $script:Repo, '--json', 'tagName,isLatest', '--limit', '20') 'gh release list' |
        Where-Object { $_.isLatest } | ForEach-Object { $_.tagName })
    if ($rel.isDraft -or $latest -notcontains "v$ver") { Stop-Harness "after the edit: draft $($rel.isDraft), latest $($latest -join ',')" }
    Say "OK: v$ver published ($($rel.publishedAt)) and latest"
    return [ordered]@{ tagCommit = $tc; filesDigest = $digest; publishedAt = $rel.publishedAt; publishedBy = 'harness'; goAhead = $script:GoAhead }
}

# ---- record

function Invoke-StageRecord {
    $ver = $script:Version
    Assert-ReleaseBranch "chromite/release-$(Get-Dashed $ver)-record"
    $tc = Get-TagCommitOrStop $ver
    $digest = Get-FilesDigest $ver
    Assert-Prior $ver @('build', 'rehearse', 'deploy', 'verify') $tc $digest
    $docPath = Join-Path $script:RepoRoot 'docs/chromite/distribution.md'
    $doc = Read-TextFile $docPath
    $nl = $doc.Nl
    $lines = [Collections.Generic.List[string]]::new([string[]]($doc.Text -split "`r?`n"))
    $marker = (Format-KoText 'block-marker' @{ version = $ver })[0]
    $todo = (Format-KoText 'record-todo' @{})[0]
    $at = -1
    for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i].StartsWith($marker)) { $at = $i; break } }
    Set-StageStarted
    if ($at -ge 0) {
        # written before: fill the TODO line when -NotChecked is given now, otherwise only check it
        $end = $at + 1
        while ($end -lt $lines.Count -and $lines[$end] -match '^\s+\S') { $end++ }
        $todoAt = -1
        for ($i = $at; $i -lt $end; $i++) { if ($lines[$i].Contains($todo)) { $todoAt = $i } }
        if ($todoAt -ge 0 -and $script:NotChecked) {
            $lines[$todoAt] = $lines[$todoAt].Replace($todo, $script:NotChecked.Trim())
            Write-TextFile $docPath (($lines -join $nl))
            Say "filled the TODO line of the $ver record with -NotChecked"
        } elseif ($todoAt -ge 0) {
            Stop-Harness "the $ver record has a TODO line ($docPath line $($todoAt + 1)): fill it or pass -NotChecked, then run record again"
        } else {
            Say "distribution.md holds the $ver record already (lines $($at + 1)-$end)"
        }
        return [ordered]@{ tagCommit = $tc; filesDigest = $digest; line = $at + 1 }
    }
    $state = Read-State $ver
    $ev = @{}
    foreach ($k in $state.stages.Keys) { if ($state.stages[$k].status -eq 'ok') { $ev[$k] = $state.stages[$k] } }
    $tagE = $ev['tag'].evidence; $buildE = $ev['build'].evidence; $rehE = $ev['rehearse'].evidence
    $depE = $ev['deploy'].evidence; $verE = $ev['verify'].evidence
    $prevTag = Get-PrevTagVersion $ver
    $trialE = if ($ev['trial']) { $ev['trial'].evidence } else { $null }
    $trialLine = if ($trialE -and $trialE.needed) {
        $rr = if ([int]$trialE.reruns) { (Format-KoText 'reruns-part' @{ reruns = $trialE.reruns })[0] } else { '' }
        (Format-KoText 'trial-done' @{ trialRun = $trialE.runId; trialCommit = "$($trialE.commit)".Substring(0, 10); trialMinutes = $trialE.minutes; trialReruns = $rr; trialPaths = (@($trialE.changedPaths) -join ', ') })[0]
    } else { (Format-KoText 'trial-not-needed' @{ previousTag = $prevTag })[0] }
    $runLine = if ([int]$buildE.reruns -or [int]$buildE.attempt -gt 1) {
        (Format-KoText 'run-reruns' @{ reruns = [Math]::Max([int]$buildE.reruns, [int]$buildE.attempt - 1); minutes = $buildE.minutes })[0]
    } else { (Format-KoText 'run-first-try' @{ minutes = $buildE.minutes })[0] }
    $seeds = (@($rehE.seeds) -join ', ')
    $seedLine = (Format-KoText $(if ($rehE.seedsFromLiveFeed) { 'seed-live' } else { 'seed-given' }) @{ seeds = $seeds })[0]
    $pubE = if ($ev['publish']) { $ev['publish'] } else { $null }
    $noticeE = if ($ev['update-notice']) { $ev['update-notice'].evidence } else { $null }
    $versionsLive = @($verE.feedVersions)
    $previousVersion = if ($versionsLive.Count -ge 2) { $versionsLive[1] } else { $prevTag }
    $values = @{
        version = $ver
        date = ([DateTime]$ev['tag'].start.utc).ToUniversalTime().AddHours(9).ToString('yyyy-MM-dd')
        tagShort = $tc.Substring(0, 10)
        summaryPart = $(if ($script:Summary) { ", $($script:Summary.Trim())" } else { '' })
        goAheadTag = $(if ($tagE.goAhead) { $tagE.goAhead } else { $ev['tag'].goAhead })
        goAheadDeploy = $ev['deploy'].goAhead
        goAheadPublishPart = $(if ($pubE -and $pubE.goAhead) { (Format-KoText 'publish-part' @{ goAheadPublish = $pubE.goAhead })[0] } else { '' })
        trialLine = $trialLine
        runId = $buildE.runId
        runLine = $runLine
        seedLine = $seedLine
        rehearsalOk = $rehE.okCount
        rehearsalSetups = ("$($rehE.setupsLine)" -replace '\s*\(.*\)$', '')
        dataGeneration = $depE.dataGeneration
        siteUrl = (Get-Cfg 'production.siteUrl').TrimEnd('/')
        deployOk = $depE.okCount
        oldPath = $depE.oldPath
        deploySetups = ("$($depE.setupsLine)" -replace '\s*\(.*\)$', '')
        olderVersion = Get-PrevTagVersion $previousVersion
        previousVersion = $previousVersion
        noticeFrom = $(if ($noticeE) { $noticeE.fromVersion } else { '' })
        noticeSeconds = $(if ($noticeE) { $noticeE.seconds } else { '' })
        noticeText = $(if ($noticeE) { $noticeE.text } else { '' })
        publishedKst = $(if ($pubE) { ([DateTime]$pubE.evidence.publishedAt).ToUniversalTime().AddHours(9).ToString('yyyy-MM-dd HH:mm') + ' KST' } else { '' })
        notChecked = $(if ($script:NotChecked) { $script:NotChecked.Trim() } else { $todo })
        notes = $script:Notes
    }
    if (-not $values.goAheadTag) { $values.goAheadTag = (Format-KoText 'tag-before-harness' @{})[0] }
    $block = Format-KoText 'record-block' $values
    # after the block of the newest earlier release record
    $best = $null; $bestAt = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^- (\d+\.\d+\.\d+) ' -and $lines[$i].StartsWith((Format-KoText 'block-marker' @{ version = $Matches[1] })[0])) {
            $v = $Matches[1]
            if ([version]$v -lt [version]$ver -and (-not $best -or [version]$v -gt [version]$best)) { $best = $v; $bestAt = $i }
        }
    }
    if ($bestAt -lt 0) { Stop-Harness 'no earlier release record found in distribution.md' }
    $insert = $bestAt + 1
    while ($insert -lt $lines.Count -and $lines[$insert] -match '^\s+\S') { $insert++ }
    $lines.InsertRange($insert, [string[]]$block)
    $history = (Format-KoText 'record-history' @{
            today = Get-TodayKst; version = $ver; previousBlock = $best
            publishHistoryPart = $(if ($pubE) { (Format-KoText 'publish-history-part' @{})[0] } else { '' })
        })[0]
    while ($lines.Count -and $lines[$lines.Count - 1] -eq '') { $lines.RemoveAt($lines.Count - 1) }
    $lastHeading = @($lines | Where-Object { $_ -match '^## ' })[-1]
    if ($lastHeading -notmatch '^## \S') { Stop-Harness 'distribution.md has no last section' }
    $lines.Add($history)
    Write-TextFile $docPath (($lines -join $nl) + $nl)
    Say "distribution.md: the $ver record after the $best record (line $($insert + 1)), history: $history"
    foreach ($l in $block) { Say "  | $l" }
    if (-not $script:NotChecked) { Stop-Harness "the $ver record has a TODO line: fill it or pass -NotChecked, then run record again" }
    return [ordered]@{ tagCommit = $tc; filesDigest = $digest; line = $insert + 1; previousRecord = $best }
}

# ---- cleanup

function Get-FolderSizeMb([string]$Path) {
    [long]$sum = 0
    foreach ($f in @(Get-ChildItem -LiteralPath $Path -Recurse -File -Force -ErrorAction SilentlyContinue)) { $sum += $f.Length }
    return [Math]::Round([double]$sum / 1MB, 1)
}

function Remove-LocalFolder([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $size = Get-FolderSizeMb $Path
    if ($script:WhatIfMode) { Say "WHATIF: would delete $Path ($size MB)"; return }
    Remove-Item -LiteralPath $Path -Recurse -Force
    Say "deleted $Path ($size MB)"
}

function Invoke-StageCleanup {
    $ver = $script:Version
    $rd = Get-ReleaseDir $ver
    Set-StageStarted
    $checkout = Join-Path $rd 'checkout'
    if (Test-Path -LiteralPath $checkout) {
        if ($script:WhatIfMode) { Say "WHATIF: would remove the worktree $checkout" }
        else { Invoke-Git @('worktree', 'remove', '--force', $checkout) -AllowFail | Out-Null }
    }
    if (-not $script:WhatIfMode) { Invoke-Git @('worktree', 'prune') -Quiet | Out-Null }
    foreach ($sub in 'checkout', 'stage', 'deploy-work', 'rehearsal', 'update-notice') { Remove-LocalFolder (Join-Path $rd $sub) }
    Remove-LocalFolder (Join-Path $script:WorkRoot 'release/tmp')
    $pruned = @()
    if ($script:PruneOld) {
        $live = Get-FeedVersions (Get-LiveFeed)
        if ($live.Count -lt 2) { Stop-Harness "the live feed lists $($live.Count) version(s): nothing is pruned" }
        $oldest = [version]$live[-1]
        foreach ($d in @(Get-ChildItem -Directory (Join-Path $script:WorkRoot 'release') -Filter 'v*')) {
            if ($d.Name -notmatch '^v(\d+\.\d+\.\d+)$') { continue }
            if ([version]$Matches[1] -ge $oldest) { continue }
            Say "older than the versions production lists ($($live -join ', ')); the files can be downloaded again:"
            Remove-LocalFolder $d.FullName
            $pruned += $d.Name
        }
        if (-not $pruned) { Say 'nothing to prune' }
    }
    if ($script:WhatIfMode) { return @{ noState = $true } }
    return [ordered]@{ pruned = @($pruned) }
}
