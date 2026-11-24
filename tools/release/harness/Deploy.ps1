# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
# Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
#
# Stage deploy of the release harness: the production deploy of chromite.seizia.com from a clean sparse checkout
# of the tag (docs/chromite/distribution.md, the section on who runs the production deploy: the checks the Turbrix session made before
# and after each deploy up to 26.1.7).
#
# Design:
#   * Every check runs and prints OK/BAD before anything is sent; any BAD stops. -PreflightOnly stops after them.
#   * The only contact with the server is the tag's deploy-chromite-site.ps1 (its scp and ssh to /srv/chromite).
#     The harness itself only uses ssh-keyscan (no login) and public HTTPS. The key is passed by path; the harness
#     tests that the path exists and never reads, copies or prints the file.
#   * The same server runs another service: its status URLs and download feed are compared before and after.
#     A difference exits with its own code (3) and a loud message.
#   * The network reads of the preflight and the snapshots ask again on no answer only (Invoke-ReadAgain). An answer
#     (another key, another DNS address, another status or feed) is a result at once. The host key read is only
#     repeated: it never falls back to accepting a key.
#   * The output of the deploy script is masked like every other line, so the log can be pasted.
#
# Change history:
# - 2026-11-23 Created.
# - 2026-11-25 ssh-keyscan, the DNS lookup and the neighbour reads ask again on no answer (one passing failure made a
#   preflight BAD or, after the deploy, a false "neighbour changed"). A neighbour's 5xx stays an answer, and the tries
#   that got none are printed and kept in neighbor-*.json outside the compared part.

$script:NeighborChangedCode = 3

# Error codes of Resolve-DnsName (Win32Exception.NativeErrorCode) that mean no answer: 1460 timeout, 9002 server
# failure, 10054 connection reset (seen on this PC, asking 127.0.0.1 where no DNS server runs), 10060 timed out,
# 10051/10065 unreachable. Not here, so they are a result: 9003 the name does not exist (seen), 9501 no record.
$script:DnsNoAnswerCodes = @(1460, 9002, 10051, 10054, 10060, 10065)

function Get-NeighborSnapshot {
    $snap = [ordered]@{ status = [ordered]@{}; feed = $null; retried = [ordered]@{} }
    $i = 0
    foreach ($u in @(Get-Cfg 'neighbor.status200')) {
        $i++
        $r = Get-Http "$u" -Keep5xx
        $snap.status["$i"] = $r.Code
        if ($r.Retried) { $snap.retried["status $i"] = @($r.Retried) }
    }
    $r = Get-Http "$(Get-Cfg 'neighbor.feed')" -Keep5xx
    if ($r.Retried) { $snap.retried['feed'] = @($r.Retried) }
    $list = $null
    try { $list = @(($r.Body | ConvertFrom-Json).files | ForEach-Object { "$($_.file) $($_.size) $($_.sha256)" }) } catch { }
    $snap.feed = [ordered]@{ code = $r.Code; files = $list }
    foreach ($k in $snap.retried.Keys) { Say "NOTE: neighbor $k got no answer before its last try ($($snap.retried[$k] -join '; ')), kept in the snapshot file" }
    return $snap
}

# The part of a snapshot that is compared: what the neighbour answered, not how many tries it took.
function Get-NeighborAnswers($Snap) { return ([ordered]@{ status = $Snap.status; feed = $Snap.feed } | ConvertTo-Json -Depth 5 -Compress) }

# The exact production command; the target and the key path are masked when printed.
function Get-ProductionDeployArgs([string]$Script, [string]$Ver, [int]$Gen, [string]$Files, [string]$WorkDir) {
    return @('-NoProfile', '-File', $Script, '-Tag', "v$Ver", '-DataGeneration', "$Gen", '-ArtifactsDir', $Files,
        '-Target', (Get-Cfg 'production.target'), '-Port', "$(Get-Cfg 'production.port')",
        '-IdentityFile', (Resolve-UserPath (Get-Cfg 'production.identityFile')), '-KnownHosts', (Resolve-UserPath (Get-Cfg 'production.knownHosts')),
        '-RemoteRoot', (Get-Cfg 'production.root'), '-SiteUrl', (Get-Cfg 'production.siteUrl').TrimEnd('/'), '-WorkDir', $WorkDir, '-Yes')
}

function Invoke-StageDeploy {
    $ver = $script:Version
    if (-not $script:DataGeneration) { Stop-Harness 'deploy needs -DataGeneration <n> (no default)' }
    $gen = $script:DataGeneration
    if (-not $script:PreflightOnly) { Assert-GoAhead 'deploy' }
    Assert-OriginIsChromite
    Update-Origin
    $tc = Get-TagCommitOrStop $ver
    $digest = Get-FilesDigest $ver
    $files = Get-FilesDir $ver
    $siteUrl = (Get-Cfg 'production.siteUrl').TrimEnd('/')
    $address = Get-Cfg 'production.address'
    $port = [int](Get-Cfg 'production.port')
    $knownHosts = Resolve-UserPath (Get-Cfg 'production.knownHosts')
    $fingerprint = Get-Cfg 'production.hostKeyFingerprint'
    $identity = Resolve-UserPath (Get-Cfg 'production.identityFile')
    $siteHost = Get-Cfg 'production.siteHost'
    [void](Get-Cfg 'production.target'); [void](Get-Cfg 'production.root')
    $script:Bad = @()
    $alreadyLive = $false
    Say "preflight of the production deploy of v$ver (-DataGeneration $gen)"

    $p = Get-PriorProblems $ver @('build', 'fetch', 'rehearse') $tc $digest $gen
    Test-Check 'build, fetch and rehearse are ok for this tag commit, these files and this -DataGeneration' ($p.Count -eq 0) ($p -join '; ')
    $remote = Get-RemoteTagCommit $ver
    Test-Check "v$ver^{commit} here equals origin's ($($tc.Substring(0, 10)))" ($remote -eq $tc) "origin: $remote"
    $build = Get-StageEvidence $ver 'build'
    if ($build) {
        $run = Invoke-GhJson @('run', 'view', "$($build.runId)", '--repo', $script:Repo, '--json', 'status,conclusion,headSha,jobs') "gh run view $($build.runId)"
        $jobsOk = -not @($run.jobs | Where-Object { $_.conclusion -notin @('success', 'skipped') })
        Test-Check "release run $($build.runId) is success with headSha = the tag commit" ($run.status -eq 'completed' -and $run.conclusion -eq 'success' -and $jobsOk -and $run.headSha -eq $tc) "$($run.status) $($run.conclusion) $($run.headSha)"
    } else { Test-Check 'the release run is known (stage build)' $false 'no build state' }
    Test-Check 'SHA256SUMS: 4 OK' (Test-Sums $files (Get-ReleaseFileNames $ver)) 'see the lines above'

    $prev = Get-PrevTagVersion $ver
    $stat = Invoke-Git @('diff', '--stat', "v$prev", "v$ver", '--', 'tools/release', 'site') -Quiet
    Say "git diff --stat v$prev v$ver -- tools/release site:"
    foreach ($l in $stat.Lines) { Say "  $l" }
    $scripts = @((Invoke-Git @('diff', '--name-only', "v$prev", "v$ver", '--', 'tools/release/deploy-chromite-site.ps1', 'tools/release/deploy-remote.sh', 'tools/release/releases.sh') -Quiet).Lines | Where-Object { $_ })
    if ($scripts) {
        Test-Check "the changed deploy scripts were read (-ScriptsReviewed): $($scripts -join ', ')" ([bool]$script:ScriptsReviewed) 'read the diff, then pass -ScriptsReviewed'
    } else { Say "OK: the deploy scripts are unchanged since v$prev" }

    $feed = Get-LiveFeed
    $live = Get-FeedVersions $feed
    if ($live -contains $ver) {
        $alreadyLive = $true
        Say "BAD: the live feed lists $ver already ($($live -join ', '))"
    } else {
        $ok = $live.Count -eq 2 -and -not @($live | Where-Object { [version]$_ -ge [version]$ver })
        Test-Check "the live feed lists the two versions before $ver ($($live -join ', '))" $ok "$($live -join ', ')"
    }
    $liveGen = (@($feed.files) | ForEach-Object { [int]$_.data_generation } | Measure-Object -Maximum).Maximum
    Test-Check "-DataGeneration $gen is not lower than the live one ($liveGen)" ($gen -ge $liveGen) "$liveGen"

    $lookup = Invoke-ReadAgain "DNS A of $siteHost" {
        $a = @(); $err = $null; $code = 0
        try { $a = @(Resolve-DnsName -Name $siteHost -Type A -DnsOnly -ErrorAction Stop | Where-Object { $_.Type -eq 'A' } | ForEach-Object { $_.IPAddress }) }
        catch { $err = $_.Exception.Message; if ($_.Exception -is [ComponentModel.Win32Exception]) { $code = $_.Exception.NativeErrorCode } }
        [pscustomobject]@{ A = $a; Error = $err; Code = $code }
    } { param($d) if ($script:DnsNoAnswerCodes -contains $d.Code) { "error $($d.Code): $($d.Error)" } }
    $dns = @($lookup.A)
    Test-Check "DNS: $siteHost A = $address" ($dns.Count -ge 1 -and -not @($dns | Where-Object { $_ -ne $address })) ((@($dns) + @($lookup.Error | Where-Object { $_ })) -join ', ')

    $keygen = Get-OpenSshTool 'ssh-keygen'
    $keyscan = Get-OpenSshTool 'ssh-keyscan'
    $pinned = @()
    if (Test-Path -LiteralPath $knownHosts) {
        $pinned = @((Invoke-Native -Exe $keygen -Arguments @('-lf', $knownHosts) -AllowFail -Quiet).Lines)
    }
    $pinnedOk = @($pinned | Where-Object { $_ -match [regex]::Escape($fingerprint) }).Count -ge 1
    Test-Check 'the pinned known_hosts holds the host key fingerprint of the config' $pinnedOk ($pinned -join ' | ')
    $hostName = if ($port -eq 22) { $address } else { "[$address]:$port" }
    $found = Invoke-Native -Exe $keygen -Arguments @('-F', $hostName, '-f', $knownHosts) -AllowFail -Quiet
    Test-Check 'the pinned known_hosts has an entry for the server address' ($found.Code -eq 0) "exit $($found.Code)"
    $keyType = 'ed25519'
    foreach ($l in $pinned) { if ($l -match '\((\w+)\)\s*$') { $keyType = $Matches[1].ToLowerInvariant() } }
    # Windows OpenSSH 9.5 ssh-keyscan stops with "choose_kex: unsupported KEX method sntrup761x25519-sha512" against
    # newer servers; then Git for Windows' ssh-keyscan is used. Neither logs in.
    # Both exit 1 and print no key line for every failure (on this PC: name lookup, refused, reset, no answer within
    # its 5 s, a banner and then a closed connection; Git's prints its error texts on stderr, merged into Lines), so
    # "no line '<host> <type> AAAA...'" is no answer and the read is asked again (Invoke-ReadAgain). Any key line is
    # an answer and goes to the fingerprint check below as it is.
    $scan = Invoke-ReadAgain 'ssh-keyscan of the server' {
        $s = Invoke-Native -Exe $keyscan -Arguments @('-p', "$port", '-t', $keyType, $address) -AllowFail -Quiet
        if ($s.Code -or -not @($s.Lines | Where-Object { $_ -notmatch '^#' -and $_.Trim() })) {
            $gitScan = Join-Path (Split-Path (Split-Path (Get-Command git -ErrorAction Stop).Source)) 'usr/bin/ssh-keyscan.exe'
            if (Test-Path -LiteralPath $gitScan) {
                Say "ssh-keyscan of $keyscan failed ($((@($s.Lines) | Select-Object -Last 1))): using $gitScan"
                $s = Invoke-Native -Exe $gitScan -Arguments @('-p', "$port", '-t', $keyType, $address) -AllowFail -Quiet
            }
        }
        $s
    } { param($k) if (-not @($k.Lines | Where-Object { $_ -match '^\S+\s+\S+\s+AAAA[0-9A-Za-z+/]+=*\s*$' })) { "exit $($k.Code), no key line" } }
    $scanFile = Join-Path (Get-ReleaseDir $ver) 'keyscan.tmp'
    [IO.File]::WriteAllText($scanFile, ((@($scan.Lines) | Where-Object { $_ -notmatch '^#' }) -join "`n") + "`n")
    $scanned = (Invoke-Native -Exe $keygen -Arguments @('-lf', $scanFile) -AllowFail -Quiet).Text
    Remove-Item -LiteralPath $scanFile -Force
    $scanOk = $scanned -match [regex]::Escape($fingerprint)
    Test-Check "ssh-keyscan of the server returns the pinned key ($fingerprint)" $scanOk "$scanned (a different key: the server may have moved - ask the user, never weaken host key checking)"
    Test-Check 'the identity file exists (path only, never read)' (Test-Path -LiteralPath $identity -PathType Leaf) 'missing'

    $before = Get-NeighborSnapshot
    foreach ($k in $before.status.Keys) { Test-Check "neighbor status URL $k answers 200" ($before.status[$k] -eq '200') $before.status[$k] }
    Test-Check "neighbor feed answers 200 and lists $(@($before.feed.files).Count) file(s)" ($before.feed.code -eq '200' -and $null -ne $before.feed.files) $before.feed.code
    $logs = Join-Path (Get-ReleaseDir $ver) 'logs'
    New-Item -ItemType Directory -Force $logs | Out-Null
    [IO.File]::WriteAllText((Join-Path $logs 'neighbor-before.json'), ($before | ConvertTo-Json -Depth 5))

    if ($alreadyLive) { Stop-Harness 'already live' }
    if ($script:Bad.Count) { Stop-Harness "$($script:Bad.Count) preflight check(s) BAD: $($script:Bad -join '; ')" }
    Say 'ALL PREFLIGHT CHECKS OK'
    if ($script:PreflightOnly) {
        Say 'preflight only: nothing was sent to the server'
        return @{ noState = $true; note = 'preflight only' }
    }

    Set-StageStarted
    $checkout = Join-Path (Get-ReleaseDir $ver) 'checkout'
    $deployWork = Join-Path (Get-ReleaseDir $ver) 'deploy-work'
    $main = Split-Path -Parent $script:WorkRoot
    $args0 = Get-ProductionDeployArgs (Join-Path $checkout 'tools/release/deploy-chromite-site.ps1') $ver $gen $files $deployWork
    if ($script:WhatIfMode) {
        Say "WHATIF: would make a clean sparse checkout of v$ver (tools/release, site) in $checkout"
        Invoke-Change "deploy v$ver to production" 'pwsh' $args0 | Out-Null
        Say 'WHATIF: nothing was sent to the server'
        return @{ noState = $true }
    }
    $res = $null
    try {
        if (Test-Path -LiteralPath $checkout) { Invoke-Git @('worktree', 'remove', '--force', $checkout) -AllowFail -Dir $main | Out-Null; Remove-Item -LiteralPath $checkout -Recurse -Force -ErrorAction SilentlyContinue }
        Invoke-Git @('worktree', 'add', '--no-checkout', '--detach', $checkout, "v$ver") -Dir $main | Out-Null
        Invoke-Git @('sparse-checkout', 'set', 'tools/release', 'site') -Dir $checkout | Out-Null
        Invoke-Git @('checkout', '--detach', "v$ver") -Dir $checkout -Quiet | Out-Null
        $head = Get-GitLine @('rev-parse', 'HEAD') $checkout
        $dirty = (Invoke-Git @('status', '--porcelain') -Dir $checkout -Quiet).Lines
        if ($head -ne $tc -or $dirty) { Stop-Harness "the clean checkout is not v$ver ($head) or not clean" }
        Say "clean sparse checkout of v$ver in $checkout"
        $r = Invoke-Change "deploy v$ver to production" 'pwsh' $args0 -AllowFail
        $res = Read-DeployOutput $r
    } finally {
        Invoke-Git @('worktree', 'remove', '--force', $checkout) -AllowFail -Quiet -Dir $main | Out-Null
        Remove-Item -LiteralPath $deployWork -Recurse -Force -ErrorAction SilentlyContinue
    }
    $after = Get-NeighborSnapshot
    [IO.File]::WriteAllText((Join-Path $logs 'neighbor-after.json'), ($after | ConvertTo-Json -Depth 5))
    if ((Get-NeighborAnswers $before) -ne (Get-NeighborAnswers $after)) {
        Say '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'
        Say '!!! THE OTHER SERVICE ON THE SAME SERVER CHANGED DURING THE DEPLOY   !!!'
        Say '!!! Do nothing more. Tell the user at once (neighbor-before/after.json) !!!'
        Say '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!'
        Stop-Harness 'the neighbor service changed during the deploy - tell the user now' $script:NeighborChangedCode
    }
    Say 'OK: the neighbor service is unchanged (status URLs and download feed list)'
    Assert-DeployResult $res $ver $siteUrl "production deploy of v$ver"
    $reh = Get-StageEvidence $ver 'rehearse'
    if ($reh -and [int]$reh.okCount -ne $res.OkCount) { Stop-Harness "the production deploy printed $($res.OkCount) OK lines, the rehearsal $($reh.okCount)" }
    $verify = Test-Production $ver $gen
    if ($verify.Bad.Count) { Stop-Harness "deployed, but $($verify.Bad.Count) production check(s) BAD: $($verify.Bad -join '; ')" }
    Say "DEPLOYED AND VERIFIED: $ver"
    return [ordered]@{
        tagCommit = $tc; filesDigest = $digest; dataGeneration = $gen
        okCount = $res.OkCount; setupsLine = "$($res.Setups)".Trim(); oldPath = $res.OldPath; lastLine = $res.Last
        neighborUnchanged = $true; scriptsReviewed = [bool]$script:ScriptsReviewed; goAhead = $script:GoAhead
    }
}
