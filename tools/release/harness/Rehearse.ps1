# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
# Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
#
# Stage rehearse of the release harness: the rehearsal server (tools/release/rehearsal) on the Docker host of
# config.rehearsal, reached over an SSH port forward, then the tag's own deploy script with -Rehearsal, exactly as
# docs/chromite/distribution.md describes for a PC without Docker (port of the 26.1.7 setup.ps1 and seed.sh).
#
# Design:
#   * Only containers named chromite-rehearsal-* (compose project chromite-rehearsal, loopback ports only), their
#     volume and image, and ~/chromite-rehearsal are created on the host; the host's container names are recorded
#     before and must be the same after. Cleanup runs in finally, also after a failure, and its result decides
#     the stage: anything left = STOP.
#   * The rehearsal key is made with exact arguments (Invoke-Process, ArgumentList) so an empty passphrase stays
#     empty in any shell, and ssh-keygen -y -P "" proves it has none (the 26.1.7 key made with -N '""' had the
#     passphrase "" and the deploy stopped with "Permission denied (publickey)").
#   * Each seed version is deployed with its OWN tag's tools/release and site: newer scripts may refuse older files.
#   * Release files are signed, so a "REHEARSAL, NOT CHECKED" line is a real defect: STOP.
#
# Change history:
# - 2026-11-23 Created.
# - 2026-11-25 The seed files download through Invoke-GhDownload: a passing GitHub error is asked again, a broken
#   file removed.

function Get-OpenSshTool([string]$Name) {
    $ssh = Get-Cfg 'rehearsal.sshExe'
    if (-not (Test-Path -LiteralPath $ssh)) { Stop-Harness "config rehearsal.sshExe '$ssh' does not exist" }
    if ($Name -eq 'ssh') { return $ssh }
    $p = Join-Path (Split-Path $ssh) "$Name.exe"
    if (-not (Test-Path -LiteralPath $p)) { Stop-Harness "$Name.exe is not next to $ssh" }
    return $p
}

function Invoke-RehearsalHost([string]$Command, [switch]$AllowFail, [switch]$Quiet) {
    $ssh = Get-OpenSshTool 'ssh'
    $h = Get-Cfg 'rehearsal.host'
    return Invoke-Native -Exe $ssh -Arguments @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20', $h, $Command) -AllowFail:$AllowFail -Quiet:$Quiet -What "ssh <rehearsal-host> $($Command.Split(' ')[0])"
}

function Get-HostLines([string]$Command) {
    $r = Invoke-RehearsalHost $Command -Quiet
    return @($r.Lines | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object)
}

# Runs a deploy script (a tag's own copy) and reads its result lines.
function Invoke-DeployScript([string]$Script, [string[]]$Arguments, [string]$What) {
    Say "running $What"
    $r = Invoke-Native -Exe 'pwsh' -Arguments (@('-NoProfile', '-File', $Script) + $Arguments) -AllowFail -What $What
    return Read-DeployOutput $r
}

# The result lines of a deploy script run (OK/FAILED/NOT CHECKED lines, the last line, setups, the moved-aside folder).
function Read-DeployOutput($r) {
    $lines = @($r.Lines)
    $last = @($lines | Where-Object { $_.Trim() }) | Select-Object -Last 1
    $old = $null
    foreach ($l in $lines) { if ($l -match 'moved aside: (\S+?)\)?$') { $old = $Matches[1] } }
    return [pscustomobject]@{
        Code = $r.Code
        Last = "$last".Trim()
        OkCount = @($lines | Where-Object { $_ -match '^OK: ' }).Count
        Failed = @($lines | Where-Object { $_ -match '^FAILED: ' })
        NotChecked = @($lines | Where-Object { $_ -match '^REHEARSAL, NOT CHECKED' })
        Setups = (@($lines | Where-Object { $_ -match '^\s*setups listed: ' }) | Select-Object -Last 1)
        OldPath = $old
    }
}

# Exactly the result a good run ends with; anything else stops the stage.
function Assert-DeployResult($Res, [string]$Ver, [string]$SiteUrl, [string]$What) {
    if ($Res.NotChecked.Count) { Stop-Harness "$What printed $($Res.NotChecked.Count) 'REHEARSAL, NOT CHECKED' line(s) for signed release files: $($Res.NotChecked[0])" }
    if ($Res.Code) { Stop-Harness "$What exited $($Res.Code): $($Res.Last)" }
    if ($Res.Failed.Count) { Stop-Harness "$What reported $($Res.Failed.Count) FAILED line(s)" }
    $want = "ALL CHECKS OK: $Ver is live at $SiteUrl"
    if ($Res.Last -ne $want) { Stop-Harness "$What ended with '$($Res.Last)', expected '$want'" }
    Say "OK: $What - $want ($($Res.OkCount) OK lines)"
}

# The tag's tools/release and site in a clean folder (git archive), never the working tree.
function Export-TagScripts([string]$Ver, [string]$Dir) {
    if (Test-Path -LiteralPath $Dir) { Remove-Item -LiteralPath $Dir -Recurse -Force }
    New-Item -ItemType Directory -Force $Dir | Out-Null
    $tar = "$Dir.tar"
    Invoke-Git @('archive', '--format=tar', '-o', $tar, "v$Ver", 'tools/release', 'site') -Quiet | Out-Null
    Invoke-Native -Exe 'tar.exe' -Arguments @('-xf', $tar, '-C', $Dir) -Quiet -What 'tar -x' | Out-Null
    Remove-Item -LiteralPath $tar -Force
    return (Join-Path $Dir 'tools/release/deploy-chromite-site.ps1')
}

# The three site files and SHA256SUMS of an earlier release (downloaded when missing), checked.
function Get-SeedFiles([string]$Ver) {
    $dir = Get-FilesDir $Ver
    $names = Get-SiteFileNames $Ver
    $missing = @(@($names) + 'SHA256SUMS' | Where-Object { -not (Test-Path -LiteralPath (Join-Path $dir $_)) })
    if ($missing) {
        Say "v$Ver files missing ($($missing -join ', ')): downloading from its GitHub Release"
        New-Item -ItemType Directory -Force $dir | Out-Null
        Invoke-GhDownload $Ver $dir @('Chromite-*', 'SHA256SUMS')
    }
    if (-not (Test-Sums $dir $names)) { Stop-Harness "the v$Ver files in $dir do not match SHA256SUMS" }
    return $dir
}

function Compare-Feed($A, $B) {
    $x = $A | ConvertTo-Json -Depth 10 | ConvertFrom-Json -AsHashtable
    $y = $B | ConvertTo-Json -Depth 10 | ConvertFrom-Json -AsHashtable
    $x.Remove('generated_at'); $y.Remove('generated_at')
    return (($x | ConvertTo-Json -Depth 10 -Compress) -ceq ($y | ConvertTo-Json -Depth 10 -Compress))
}

function Invoke-StageRehearse {
    $ver = $script:Version
    if (-not $script:DataGeneration) { Stop-Harness 'rehearse needs -DataGeneration <n> (no default)' }
    $gen = $script:DataGeneration
    $tc = Get-TagCommitOrStop $ver
    $digest = Get-FilesDigest $ver
    Assert-Prior $ver @('build', 'fetch') $tc $digest
    $sshPort = [int](Get-Cfg 'rehearsal.sshPort')
    $httpPort = [int](Get-Cfg 'rehearsal.httpPort')
    $ssh = Get-OpenSshTool 'ssh'; $scp = Get-OpenSshTool 'scp'; $keygen = Get-OpenSshTool 'ssh-keygen'
    $rhost = Get-Cfg 'rehearsal.host'
    $feed = Get-LiveFeed
    $live = Get-FeedVersions $feed
    $liveGen = (@($feed.files) | ForEach-Object { [int]$_.data_generation } | Measure-Object -Maximum).Maximum
    if ($gen -lt $liveGen) { Stop-Harness "-DataGeneration $gen is lower than the live one ($liveGen)" }
    if ($script:SeedTags) {
        $seeds = @($script:SeedTags | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() -replace '^v', '' } | Where-Object { $_ })
        $fromLive = $false
    } else {
        if (@($live | Where-Object { [version]$_ -ge [version]$ver })) { Stop-Harness "the live feed lists $($live -join ', '): v$ver or newer is live already, rehearse it with -SeedTags" }
        $seeds = @($live | Sort-Object { [version]$_ })
        $fromLive = $true
    }
    foreach ($s in $seeds) {
        if (-not (Test-VersionText $s)) { Stop-Harness "seed '$s' is not a version" }
        if ([version]$s -ge [version]$ver) { Stop-Harness "seed $s is not older than $ver" }
        if (-not (Get-TagCommit $s)) { Stop-Harness "seed $s has no tag" }
    }
    if (@($seeds | Sort-Object -Unique).Count -ne $seeds.Count) { Stop-Harness "seeds repeat a version: $($seeds -join ', ')" }
    Say "seeds (in this order): $($seeds -join ', ')$(if ($fromLive) { ' (the live feed)' } else { ' (-SeedTags)' })"
    $siteUrl = "http://127.0.0.1:$httpPort"
    $work = Join-Path (Get-ReleaseDir $ver) 'rehearsal'
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
    New-Item -ItemType Directory -Force $work | Out-Null

    # 1. host, ports, a clean start
    $probe = Invoke-RehearsalHost 'true' -AllowFail -Quiet
    if ($probe.Code) { Stop-Harness "the rehearsal host is not reachable with BatchMode (exit $($probe.Code))" }
    foreach ($p in $sshPort, $httpPort) {
        if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { Stop-Harness "local port $p is in use" }
    }
    $before = Get-HostLines "docker ps -a --format '{{.Names}}'"
    $theirs = @($before | Where-Object { $_ -like 'chromite-rehearsal-*' })
    if ($theirs) { Stop-Harness "the host has rehearsal containers already: $($theirs -join ', ') (remove them first: docker compose -p chromite-rehearsal down --volumes)" }
    $vols = Get-HostLines "docker volume ls -q --filter name=chromite-rehearsal"
    if ($vols) { Stop-Harness "the host has a rehearsal volume already: $($vols -join ', ')" }
    $folder = Get-HostLines 'test -e ~/chromite-rehearsal -o -e ~/chromite-rehearsal.tar && echo present || echo absent'
    if ($folder -contains 'present') { Stop-Harness 'the host has ~/chromite-rehearsal or ~/chromite-rehearsal.tar already: remove it first' }
    $imagesBefore = Get-HostLines "docker image ls --format '{{.Repository}}:{{.Tag}}'"
    Say "host containers before ($($before.Count)): $($before -join ', ')"
    Set-StageStarted

    $forward = $null
    $remoteUsed = $false
    $result = $null
    $failure = $null
    try {
        # 2. the rehearsal key: no passphrase, proven
        $key = Join-Path $work 'rehearsal_ed25519'
        $k = Invoke-Process $keygen @('-q', '-t', 'ed25519', '-N', '', '-C', 'chromite-rehearsal', '-f', $key)
        if ($k.Code) { Stop-Harness "ssh-keygen failed: $($k.Err)" }
        $proof = Invoke-Process $keygen @('-y', '-P', '', '-f', $key)
        if ($proof.Code -or $proof.Out -notmatch '^ssh-ed25519 ') { Stop-Harness "the rehearsal key has a passphrase or cannot be read (ssh-keygen -y -P `"`": $($proof.Err.Trim()))" }
        Say 'OK: rehearsal key without a passphrase (ssh-keygen -y -P "" reads it)'
        $pub = (Get-Content -LiteralPath "$key.pub" -Raw).Trim()

        # 3. compose up on the host, host key, port forward
        $tar = Join-Path $work 'rehearsal.tar'
        Invoke-Git @('archive', '--format=tar', '-o', $tar, "v$ver", 'tools/release/rehearsal') -Quiet | Out-Null
        $remoteUsed = $true
        Invoke-Native -Exe $scp -Arguments @('-q', '-o', 'BatchMode=yes', $tar, "${rhost}:chromite-rehearsal.tar") -What 'scp the rehearsal files' | Out-Null
        $composeUp = "mkdir ~/chromite-rehearsal && tar -xf ~/chromite-rehearsal.tar -C ~/chromite-rehearsal --strip-components=3 && rm ~/chromite-rehearsal.tar" +
            " && cd ~/chromite-rehearsal && REHEARSAL_AUTHORIZED_KEY='$pub' REHEARSAL_SSH_PORT=$sshPort REHEARSAL_HTTP_PORT=$httpPort" +
            " docker compose -f compose.yaml up -d --build --wait > compose-up.log 2>&1; rc=`$?; tail -n 6 compose-up.log; exit `$rc"
        Invoke-RehearsalHost $composeUp | Out-Null
        $hostKey = (Invoke-RehearsalHost 'docker exec chromite-rehearsal-server-1 cat /etc/ssh/ssh_host_ed25519_key.pub' -Quiet).Lines -join ''
        $parts = $hostKey.Trim().Split(' ')
        if ($parts.Count -lt 2 -or $parts[0] -ne 'ssh-ed25519') { Stop-Harness 'could not read the rehearsal server host key' }
        $known = Join-Path $work 'known_hosts'
        [IO.File]::WriteAllText($known, "[127.0.0.1]:$sshPort $($parts[0]) $($parts[1])`n", [Text.UTF8Encoding]::new($false))
        $forward = Start-Process -FilePath $ssh -PassThru -WindowStyle Hidden -ArgumentList @('-N', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes',
            '-L', "127.0.0.1:${sshPort}:127.0.0.1:$sshPort", '-L', "127.0.0.1:${httpPort}:127.0.0.1:$httpPort", $rhost)
        Say "port forward started (pid $($forward.Id))"
        $answers = $false
        for ($i = 0; $i -lt 20 -and -not $answers; $i++) {
            Start-Sleep -Seconds 1
            if ($forward.HasExited) { Stop-Harness "the port forward exited ($($forward.ExitCode))" }
            $answers = (Get-Http "$siteUrl/downloads/releases.json").Code -eq '200'
        }
        if (-not $answers) { Stop-Harness "the rehearsal site does not answer at $siteUrl" }
        Say "OK: rehearsal site answers at $siteUrl"

        # 4. seeds, each with its own tag's scripts
        $common = @('-Target', 'ubuntu@127.0.0.1', '-Port', "$sshPort", '-IdentityFile', $key, '-KnownHosts', $known, '-SiteUrl', $siteUrl, '-Rehearsal', '-Yes')
        foreach ($s in $seeds) {
            $files = Get-SeedFiles $s
            $sgen = @($feed.files | Where-Object { $_.version -eq $s } | ForEach-Object { [int]$_.data_generation }) | Select-Object -First 1
            if (-not $sgen) { $sgen = (@($feed.files) | ForEach-Object { [int]$_.data_generation } | Measure-Object -Minimum).Minimum }
            $deployScript = Export-TagScripts $s (Join-Path $work "src-v$s")
            $res = Invoke-DeployScript $deployScript (@('-Tag', "v$s", '-DataGeneration', "$sgen", '-ArtifactsDir', $files, '-WorkDir', (Join-Path $work "deploy-v$s")) + $common) "seed v$s (its own scripts, -DataGeneration $sgen)"
            Assert-DeployResult $res $s $siteUrl "seed v$s"
            Remove-Item -LiteralPath (Join-Path $work "src-v$s"), (Join-Path $work "deploy-v$s") -Recurse -Force -ErrorAction SilentlyContinue
        }
        $feedEqual = $null
        if ($fromLive) {
            $seeded = (Get-Http "$siteUrl/downloads/releases.json").Body | ConvertFrom-Json
            $feedEqual = Compare-Feed $seeded $feed
            if (-not $feedEqual) { Stop-Harness 'the seeded rehearsal feed differs from production (ignoring generated_at)' }
            Say 'OK: the seeded feed equals production (ignoring generated_at)'
        }

        # 5. the release itself, with the tag's scripts
        $deployScript = Export-TagScripts $ver (Join-Path $work "src-v$ver")
        $res = Invoke-DeployScript $deployScript (@('-Tag', "v$ver", '-DataGeneration', "$gen", '-ArtifactsDir', (Get-FilesDir $ver), '-WorkDir', (Join-Path $work "deploy-v$ver")) + $common) "rehearsal of v$ver"
        Assert-DeployResult $res $ver $siteUrl "rehearsal of v$ver"
        $result = $res
    } catch {
        $failure = $_
    } finally {
        $left = Remove-Rehearsal -Forward $forward -RemoteUsed $remoteUsed -Before $before -ImagesBefore $imagesBefore -Work $work
    }
    if ($failure) {
        if ($left) { Say "CLEANUP INCOMPLETE: $($left -join '; ')" }
        throw $failure
    }
    if ($left) { Stop-Harness "the rehearsal passed but cleanup is incomplete: $($left -join '; ')" }
    return [ordered]@{
        tagCommit = $tc; filesDigest = $digest; dataGeneration = $gen
        seeds = @($seeds); seedsFromLiveFeed = $fromLive; feedEqualsProduction = $feedEqual
        okCount = $result.OkCount; setupsLine = "$($result.Setups)".Trim(); lastLine = $result.Last
        hostContainers = $before.Count; cleanup = 'containers, volume, image, ~/chromite-rehearsal, forward, key: removed'
    }
}

# Removes everything the rehearsal made; returns what could not be removed (empty = clean).
function Remove-Rehearsal($Forward, [bool]$RemoteUsed, [string[]]$Before, [string[]]$ImagesBefore, [string]$Work) {
    $left = @()
    if ($Forward) {
        try {
            $p = Get-CimInstance Win32_Process -Filter "ProcessId = $($Forward.Id)" -ErrorAction SilentlyContinue
            # only the forward this stage started: same pid, the configured ssh.exe, -N
            $sshPath = [IO.Path]::GetFullPath((Get-OpenSshTool 'ssh'))
            if ($p -and $p.CommandLine -match ' -N ' -and [IO.Path]::GetFullPath("$($p.ExecutablePath)") -eq $sshPath) { Stop-Process -Id $Forward.Id -Force }
            Start-Sleep -Seconds 1
            if (Get-Process -Id $Forward.Id -ErrorAction SilentlyContinue) { $left += "port forward pid $($Forward.Id)" }
            else { Say "port forward pid $($Forward.Id) stopped" }
        } catch { $left += "port forward: $($_.Exception.Message)" }
    }
    if ($RemoteUsed) {
        $caddy = if ($ImagesBefore -contains 'caddy:2') { '' } else { ' caddy:2' }
        $down = "cd ~/chromite-rehearsal 2>/dev/null && REHEARSAL_AUTHORIZED_KEY=unused docker compose -f compose.yaml down --volumes --remove-orphans 2>&1 | tail -n 4; cd ~;" +
            " docker ps -a --format '{{.Names}}' | grep '^chromite-rehearsal-' | xargs -r docker rm -f;" +
            " docker volume ls -q | grep '^chromite-rehearsal_' | xargs -r docker volume rm;" +
            " docker network ls --format '{{.Name}}' | grep '^chromite-rehearsal_' | xargs -r docker network rm;" +
            " docker image rm chromite-rehearsal-server:1$caddy 2>&1 | tail -n 3; rm -rf ~/chromite-rehearsal ~/chromite-rehearsal.tar; true"
        try {
            Invoke-RehearsalHost $down -AllowFail | Out-Null
            $after = Get-HostLines "docker ps -a --format '{{.Names}}'"
            if (($Before -join ',') -ne ($after -join ',')) { $left += "host containers differ: before $($Before -join ','), after $($after -join ',')" }
            else { Say "OK: host containers after = before ($($after.Count)): $($after -join ', ')" }
            $vol = Get-HostLines "docker volume ls -q --filter name=chromite-rehearsal"
            if ($vol) { $left += "volume $($vol -join ',')" }
            $img = Get-HostLines 'docker image ls -q chromite-rehearsal-server'
            if ($img) { $left += 'image chromite-rehearsal-server' }
            if ($caddy) { if (Get-HostLines 'docker image ls -q caddy:2') { $left += 'image caddy:2 (pulled by this rehearsal)' } }
            $f = Get-HostLines 'test -e ~/chromite-rehearsal -o -e ~/chromite-rehearsal.tar && echo present || echo absent'
            if ($f -contains 'present') { $left += '~/chromite-rehearsal' }
            if (-not $left) { Say 'OK: no rehearsal volume, image or folder left on the host' }
        } catch { $left += "host cleanup: $($_.Exception.Message)" }
    }
    try {
        if (Test-Path -LiteralPath $Work) { Remove-Item -LiteralPath $Work -Recurse -Force }
        if (Test-Path -LiteralPath $Work) { $left += "local $Work" } else { Say "deleted the local rehearsal folder (key, known_hosts, scripts, staging)" }
    } catch { $left += "local $Work`: $($_.Exception.Message)" }
    return , $left
}
