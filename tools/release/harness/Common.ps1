# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
# Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
#
# Shared helpers of the release harness (tools/release/release.ps1, docs/chromite/release-harness.md):
# the work root and its config, masking of connection values, the stage log, state.json, the lock, running
# programs, the live feed, and Invoke-Change - the one function that runs a command changing something
# outside this PC (it honours -WhatIf).
#
# Design:
#   * A stage stops by throwing an exception that carries 'HarnessStop' in its Data (Stop-Harness); release.ps1
#     turns it into the last line "STAGE <name>: STOP - <reason>" and the exit code. No other way out.
#   * Every line goes through Hide-Secret before it reaches the console or a log: the repository is public and
#     logs are pasted into reports, so the production target, address, key path, neighbour URLs and the
#     rehearsal host never appear in them.
#   * Connection values come only from <work root>/deploy/config.json. A missing value stops the stage and names
#     the field; nothing is guessed.
#   * State is written only after a stage really started (Set-StageStarted): a refusal (missing go-ahead, missing
#     earlier stage, lock held) never overwrites what an earlier run recorded.
#
# Change history:
# - 2026-11-23 Created (consolidates the throw-away helpers of the 26.1.7 release).
# - 2026-11-24 Invoke-GhJson asks again after a passing GitHub API error (the 26.1.8 build stage stopped on an HTTP 500).
# - 2026-11-25 The passing errors include the texts gh really prints on Windows for a failed name lookup, a dropped
#   connection and a timeout (an independent review found that the 2026-11-24 pattern missed them).
# - 2026-11-25 The retry moved into Invoke-GhRead; Invoke-GhDownload runs the release downloads of fetch and rehearse
#   through it (they stopped at once on a passing error) and removes what a failed try wrote.
# - 2026-11-25 The other network reads ask again on "no answer" too (Invoke-ReadAgain): curl (Get-Http, Get-HttpHead),
#   git fetch and ls-remote (Invoke-GitRead); a review found that one passing failure stopped a stage or printed BAD.

$script:Repo = 'froiibn/chromite'
$script:RepoUrlPattern = '^(https://github\.com/|git@github\.com:|ssh://git@github\.com/)froiibn/chromite(\.git)?/?$'
$script:UserAgent = 'chromite-release-harness'
$script:Masks = @()
$script:Config = $null
$script:LogFile = $null
$script:StageStarted = $false
# The three files the site serves per version, and the fourth one release.yml publishes (the portable zip).
function Get-SiteFileNames([string]$Ver) { @("Chromite-$Ver-x64-setup.exe", "Chromite-$Ver-arm64.zip", "Chromite-$Ver-arm64.dmg") }
function Get-ReleaseFileNames([string]$Ver) { (Get-SiteFileNames $Ver) + "chromite-swing-$Ver-windows-x64.zip" }
# Release-process paths: a change there since the previous tag means the signing run is tried on command first.
# The harness itself (release.ps1, harness/) is left out: release.yml does not use it, so a change to it cannot
# change what the signing run does, and counting it would force a trial run after every harness fix.
$script:ProcessPaths = @('.github', 'tools/release', 'tools/installer', 'tools/jre', 'product',
    ':(exclude)tools/release/release.ps1', ':(exclude)tools/release/harness')
# Files that bump-version.ps1 changes inside those paths; only a change besides the version number counts.
$script:VersionFiles = @('product/swing/chromite-swing.product', 'product/swing/pom.xml')

function Stop-Harness([string]$Reason, [int]$Code = 1) {
    $e = [Exception]::new($Reason)
    $e.Data['HarnessStop'] = $Code
    throw $e
}

function Set-StageStarted { $script:StageStarted = $true }

# ---- work root, config, masking

function Resolve-WorkRoot([string]$Given, [string]$RepoRoot) {
    if ($Given) {
        New-Item -ItemType Directory -Force $Given | Out-Null
        return (Resolve-Path $Given).Path
    }
    # <main checkout>/.local, whichever worktree the harness runs from
    $common = & git -C $RepoRoot rev-parse --path-format=absolute --git-common-dir 2>&1
    if ($LASTEXITCODE) { Stop-Harness "git rev-parse --git-common-dir failed: $common" }
    $main = Split-Path -Parent ([IO.Path]::GetFullPath("$common".Trim()))
    return (Join-Path $main '.local')
}

function Get-ConfigPath { Join-Path $script:WorkRoot 'deploy/config.json' }

function Get-Config {
    if ($null -eq $script:Config) {
        $path = Get-ConfigPath
        if (-not (Test-Path -LiteralPath $path)) {
            Stop-Harness "no config at $path (format: tools/release/harness/config.example.json)"
        }
        try { $script:Config = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json }
        catch { Stop-Harness "config $path is not valid JSON: $($_.Exception.Message)" }
    }
    return $script:Config
}

# A value by its dotted name, e.g. 'production.address'. Missing or empty = STOP naming the field.
function Get-Cfg([string]$Name) {
    $node = Get-Config
    foreach ($part in $Name.Split('.')) {
        if ($null -eq $node -or -not ($node.PSObject.Properties.Name -contains $part)) {
            Stop-Harness "config value '$Name' is missing in $(Get-ConfigPath)"
        }
        $node = $node.$part
    }
    $empty = ($null -eq $node) -or ($node -is [string] -and -not $node.Trim()) -or ($node -is [array] -and $node.Count -eq 0)
    if ($empty) { Stop-Harness "config value '$Name' is empty in $(Get-ConfigPath)" }
    return $node
}

# '~' and '$HOME' resolved by PowerShell (never handed to a POSIX shell, docs/chromite/distribution.md).
function Resolve-UserPath([string]$Path) {
    $p = $Path
    if ($p -match '^~([\\/]|$)') { $p = $HOME + $p.Substring(1) }
    $p = $p -replace '^\$HOME', $HOME
    return [IO.Path]::GetFullPath($p)
}

function Add-Mask([string]$Value, [string]$Placeholder) {
    if (-not $Value -or $Value.Length -lt 3) { return }
    foreach ($v in @($Value, $Value.Replace('\', '/'), $Value.Replace('/', '\'))) {
        $script:Masks += [pscustomobject]@{ Value = $v; Placeholder = $Placeholder }
    }
    $script:Masks = @($script:Masks | Sort-Object { $_.Value.Length } -Descending)
}

# Registers the connection values of the config (when there is one) before anything is printed.
function Initialize-Masks {
    if (-not (Test-Path -LiteralPath (Get-ConfigPath))) { return }
    try { $c = Get-Config } catch { return }
    $p = $c.PSObject.Properties
    if ($p.Name -contains 'production') {
        $prod = $c.production
        if ($prod.PSObject.Properties.Name -contains 'target') { Add-Mask "$($prod.target)" '<production>' }
        if ($prod.PSObject.Properties.Name -contains 'address') { Add-Mask "$($prod.address)" '<production-address>' }
        if ($prod.PSObject.Properties.Name -contains 'hostKeyFingerprint') { Add-Mask "$($prod.hostKeyFingerprint)" '<pinned-host-key>' }
        if ($prod.PSObject.Properties.Name -contains 'identityFile' -and $prod.identityFile) {
            Add-Mask "$($prod.identityFile)" '<identity-file>'
            Add-Mask (Resolve-UserPath "$($prod.identityFile)") '<identity-file>'
        }
    }
    if ($p.Name -contains 'neighbor') {
        $i = 0
        foreach ($u in @($c.neighbor.status200) + @($c.neighbor.feed)) {
            if (-not $u) { continue }
            $i++
            Add-Mask "$u" "<neighbor-url-$i>"
            try { Add-Mask ([Uri]"$u").Host "<neighbor-host>" } catch { }
        }
    }
    if ($p.Name -contains 'rehearsal' -and $c.rehearsal.PSObject.Properties.Name -contains 'host') {
        Add-Mask "$($c.rehearsal.host)" '<rehearsal-host>'
    }
}

function Hide-Secret([string]$Text) {
    if ($null -eq $Text) { return '' }
    foreach ($m in $script:Masks) {
        $Text = [regex]::Replace($Text, [regex]::Escape($m.Value), $m.Placeholder, 'IgnoreCase')
    }
    return $Text
}

# ---- log

function Open-Log([string]$Folder, [string]$Name) {
    New-Item -ItemType Directory -Force $Folder | Out-Null
    $script:LogFile = Join-Path $Folder ("$Name-" + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '.log')
    [IO.File]::WriteAllText($script:LogFile, '', [Text.UTF8Encoding]::new($false))
}

function Say([string]$Text) {
    $t = Hide-Secret $Text
    Write-Host $t
    if ($script:LogFile) { [IO.File]::AppendAllText($script:LogFile, $t + "`n", [Text.UTF8Encoding]::new($false)) }
}

function Get-Stamp {
    $u = [DateTime]::UtcNow
    return [ordered]@{ utc = $u.ToString('yyyy-MM-ddTHH:mm:ssZ'); kst = $u.AddHours(9).ToString('yyyy-MM-dd HH:mm:ss') + ' KST' }
}

# ---- running programs

function Format-Command([string]$Exe, [string[]]$Arguments) {
    $parts = @([IO.Path]::GetFileName($Exe)) + @($Arguments | ForEach-Object {
        if ($_ -eq '') { '""' } elseif ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
    })
    return ($parts -join ' ')
}

# Runs a program, streams its output (masked) into the log, returns code and lines. Non-zero = STOP unless -AllowFail.
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments = @(), [string]$What, [switch]$AllowFail, [switch]$Quiet)
    if (-not $What) { $What = Format-Command $Exe $Arguments }
    $lines = [Collections.Generic.List[string]]::new()
    & $Exe @Arguments 2>&1 | ForEach-Object {
        $l = "$_"
        $lines.Add($l)
        if (-not $Quiet) { Say "  $l" }
    }
    $rc = $LASTEXITCODE
    if ($rc -and -not $AllowFail) {
        Stop-Harness "$What failed (exit $rc): $((@($lines) | Select-Object -Last 3) -join ' | ')"
    }
    return [pscustomobject]@{ Code = $rc; Lines = @($lines); Text = (@($lines) -join "`n") }
}

# Runs a program with exact arguments (an empty string stays an empty argument in every shell): ssh-keygen -N "".
function Invoke-Process([string]$Exe, [string[]]$Arguments) {
    $psi = [Diagnostics.ProcessStartInfo]::new($Exe)
    foreach ($a in $Arguments) { $psi.ArgumentList.Add($a) }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = [Diagnostics.Process]::Start($psi)
    $p.StandardInput.Close()
    $out = $p.StandardOutput.ReadToEndAsync()
    $err = $p.StandardError.ReadToEndAsync()
    $p.WaitForExit()
    return [pscustomobject]@{ Code = $p.ExitCode; Out = $out.Result; Err = $err.Result }
}

# The only place that runs a command changing something outside this PC (tag push, workflow run, re-run, merge,
# Release edit, production deploy). With -WhatIf it prints the exact command (masked) and runs nothing.
function Invoke-Change([string]$What, [string]$Exe, [string[]]$Arguments, [switch]$AllowFail) {
    $shown = Format-Command $Exe $Arguments
    if ($script:WhatIfMode) {
        Say "WHATIF: would $What`: $shown"
        return $null
    }
    Say "RUN ($What): $shown"
    return Invoke-Native -Exe $Exe -Arguments $Arguments -What $What -AllowFail:$AllowFail
}

function Invoke-Git([string[]]$Arguments, [switch]$AllowFail, [switch]$Quiet, [string]$Dir) {
    if (-not $Dir) { $Dir = $script:RepoRoot }
    return Invoke-Native -Exe 'git' -Arguments (@('-C', $Dir) + $Arguments) -AllowFail:$AllowFail -Quiet:$Quiet
}

function Get-GitLine([string[]]$Arguments, [string]$Dir) {
    $r = Invoke-Git $Arguments -AllowFail -Quiet -Dir $Dir
    if ($r.Code) { return $null }
    return ("$($r.Lines | Select-Object -First 1)").Trim()
}

# Answers of GitHub's API that pass by themselves, as gh 2.96 prints them on Windows: a 5xx (REST "HTTP 502: ...",
# GraphQL "non-200 OK status code: 502 ..."), a failed name lookup (gh prints only "error connecting to <host>"), a
# refused connection ("dial tcp ...: connectex: ... actively refused"), a dropped one (Go prints "EOF" or "unexpected
# EOF"; Windows "wsarecv: An existing connection was forcibly closed ..." or "... was aborted ..."; elsewhere
# "connection reset"), a timeout ("context deadline exceeded", "i/o timeout", "TLS handshake timeout").
# Not here, so they stop at once: 404, 401, 403, a GraphQL "Could not resolve", a wrong argument, and rate limits
# (HTTP 429, "API rate limit exceeded"): a rate limit lasts up to an hour, longer than the retries wait.
$script:GhPassingError = 'HTTP 5\d\d|non-200 OK status code: 5\d\d|error connecting to |timeout|timed out|deadline exceeded|connection reset|forcibly closed|connection was aborted|connection refused|actively refused|\bEOF\b|TLS handshake|dial tcp|temporarily unavailable'

# A read of GitHub through gh. A stage that polls for many minutes must not stop on one passing error (the build
# stage of 26.1.8 stopped on an HTTP 500 of "gh run view"), so such an answer is asked for again, three times at
# most. Every other failure (not found, not authorised, a rate limit, a wrong argument) stops at once. Only reads go
# through here: a change asked again could happen twice. $AfterFailure runs after every failed try, before the next
# one and before the stop, so a read that leaves something behind (a download) can remove it first.
function Invoke-GhRead([string[]]$Arguments, [string]$What, [scriptblock]$AfterFailure) {
    $r = $null
    for ($try = 1; $try -le 4; $try++) {
        $r = Invoke-Native -Exe 'gh' -Arguments $Arguments -Quiet -AllowFail
        if ($r.Code -and $AfterFailure) { & $AfterFailure }
        if (-not $r.Code -or $try -eq 4 -or $r.Text -notmatch $script:GhPassingError) { break }
        Say "${What}: GitHub answered '$((@($r.Lines) | Select-Object -Last 1))', asking again ($try of 3)"
        Start-Sleep -Seconds (15 * $try)
    }
    if ($r.Code) { Stop-Harness "$What failed (gh exit $($r.Code)): $((@($r.Lines) | Select-Object -Last 2) -join ' | ')" }
    return $r
}

# A read of GitHub's API as JSON (Invoke-GhRead).
function Invoke-GhJson([string[]]$Arguments, [string]$What) {
    $r = Invoke-GhRead $Arguments $What
    $json = (@($r.Lines) | Where-Object { $_ -notmatch '^\s*$' }) -join "`n"
    try { return ($json | ConvertFrom-Json) } catch { Stop-Harness "$What returned no JSON: $($json.Substring(0, [Math]::Min(200, $json.Length)))" }
}

# The files of a GitHub Release into $Dir (Invoke-GhRead), only those not there yet (--skip-existing).
# gh 2.96 writes each file under its final name (no temporary name), up to five at a time, and leaves a broken one
# where it is (cli/cli v2.96.0 pkg/cmd/release/download/download.go, destinationWriter.Copy). --skip-existing then
# takes such a file for finished, on the next try and in the next run. So after every failed try each file that was
# not in $Dir before the download is removed (finished ones of that try are downloaded again): a file that stays in
# $Dir came from a download that ended without an error. The SHA256SUMS check after the download stays the proof.
function Invoke-GhDownload([string]$Ver, [string]$Dir, [string[]]$Patterns = @()) {
    $present = @(Get-ChildItem -LiteralPath $Dir -File | ForEach-Object { $_.Name })
    $arguments = @('release', 'download', "v$Ver", '--repo', $script:Repo, '--dir', $Dir, '--skip-existing')
    foreach ($p in $Patterns) { $arguments += @('--pattern', $p) }
    Invoke-GhRead $arguments "gh release download v$Ver" {
        foreach ($f in @(Get-ChildItem -LiteralPath $Dir -File | Where-Object { $present -notcontains $_.Name })) {
            try { Remove-Item -LiteralPath $f.FullName -Force -ErrorAction Stop }
            catch { Stop-Harness "could not remove $($f.Name) of the failed download ($($_.Exception.Message)); delete it before the next run" }
            Say "removed $($f.Name) of the failed download (it may be incomplete)"
        }
    } | Out-Null
}

# A network read that is asked again, three times at most (waits 5, 10, 15 s), while $NoAnswer says the try got no
# answer: no connection, a timeout, a dropped connection, a 5xx. $NoAnswer gets the result and returns the reason
# ($null = an answer). An answer - a 404, another size, hash or key - is returned at once, never asked again into
# another one, so a check that compares it with an expectation is as strict as before. The last result is returned
# whatever it is (the caller prints BAD or stops as before) with the reasons of the failed tries in 'Retried', so a
# caller that compares answers over time (the neighbour snapshot) can show them. Only reads: a change asked again
# could happen twice. gh has its own loop (Invoke-GhRead: longer waits, cleanup after a failed download).
function Invoke-ReadAgain([string]$What, [scriptblock]$Read, [scriptblock]$NoAnswer) {
    $readRetried = @()
    for ($readTry = 1; ; $readTry++) {
        $readResult = & $Read
        $readWhy = & $NoAnswer $readResult
        if (-not $readWhy -or $readTry -ge 4) { break }
        $readRetried += "$readWhy"
        Say "${What}: no answer ($readWhy), asking again ($readTry of 3)"
        Start-Sleep -Seconds (5 * $readTry)
    }
    if ($null -ne $readResult) { $readResult | Add-Member -NotePropertyName Retried -NotePropertyValue $readRetried -Force }
    return $readResult
}

# Answers of git (2.37 for Windows, https origin) that pass by themselves, as it prints them for an http(s) remote:
# the libcurl texts after "unable to access '<url>': " (a failed name lookup "Could not resolve host", a refused
# connection "Failed to connect to <host> port <n> after <ms> ms: Connection refused", "Empty reply from server",
# "Recv failure: Connection was reset", "Operation too slow", a 5xx "The requested URL returned error: 503"), seen on
# this PC against local fakes; and, from git's source, the texts of a fetch cut off midway ("RPC failed; HTTP 5xx" or
# "; curl <n>", "early EOF", "unexpected disconnect while reading sideband packet").
# Not here, so they stop at once: "repository '<url>' not found" (404), "Authentication failed", "could not read
# Username" (401), "The requested URL returned error: 403", a missing ref.
$script:GitPassingError = 'Could not resolve (host|proxy)|Failed to connect to|Connection refused|Connection timed out|Operation timed out|Operation too slow|returned error: 5\d\d|RPC failed; (HTTP 5\d\d|curl )|Empty reply from server|(Recv|Send) failure|Connection was reset|connection reset|early EOF|unexpected disconnect while reading sideband'

# A git command that only reads origin (fetch, ls-remote), asked again on a passing error (Invoke-ReadAgain).
# Every other failure stops at once, as Invoke-Git does without -AllowFail.
function Invoke-GitRead([string[]]$Arguments, [string]$What) {
    $r = Invoke-ReadAgain $What { Invoke-Git $Arguments -AllowFail -Quiet } {
        param($g)
        if ($g.Code -and $g.Text -match $script:GitPassingError) { "git exit $($g.Code): $((@($g.Lines) | Select-Object -Last 1))" }
    }
    if ($r.Code) { Stop-Harness "$What failed (exit $($r.Code)): $((@($r.Lines) | Select-Object -Last 3) -join ' | ')" }
    return $r
}

# ---- versions, tags, origin

function Test-VersionText([string]$Ver) { return $Ver -match '^\d+\.\d+\.\d+$' }
function ConvertTo-Ver([string]$Ver) { return [version]$Ver }
function Get-Dashed([string]$Ver) { return $Ver.Replace('.', '-') }

function Update-Origin {
    Invoke-GitRead @('fetch', '--quiet', '--tags', 'origin') 'git fetch --tags origin' | Out-Null
}

function Get-TagVersions {
    $r = Invoke-Git @('tag', '--list', 'v*') -Quiet
    return @($r.Lines | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } | ForEach-Object { $_.Substring(1) } |
        Sort-Object { [version]$_ })
}

function Get-LastTagVersion {
    $all = Get-TagVersions
    if (-not $all) { Stop-Harness 'no v* tag found' }
    return $all[-1]
}

# The highest tagged version below $Ver.
function Get-PrevTagVersion([string]$Ver) {
    $lower = @(Get-TagVersions | Where-Object { [version]$_ -lt [version]$Ver })
    if (-not $lower) { return $null }
    return $lower[-1]
}

function Get-TagCommit([string]$Ver) { return Get-GitLine @('rev-parse', '--verify', '--quiet', "refs/tags/v$Ver^{commit}") }

# The commit the tag points at on origin (the peeled line of an annotated tag), or $null.
function Get-RemoteTagCommit([string]$Ver) {
    $r = Invoke-GitRead @('ls-remote', '--tags', 'origin', "refs/tags/v$Ver", "refs/tags/v$Ver^{}") "git ls-remote --tags origin v$Ver"
    $plain = $null; $peeled = $null
    foreach ($l in $r.Lines) {
        if ($l -match '^([0-9a-f]{40})\s+refs/tags/v[\d.]+\^\{\}$') { $peeled = $Matches[1] }
        elseif ($l -match '^([0-9a-f]{40})\s+refs/tags/v[\d.]+$') { $plain = $Matches[1] }
    }
    if ($peeled) { return $peeled }
    return $plain
}

function Assert-OriginIsChromite {
    foreach ($kind in @(@('remote', 'get-url', 'origin'), @('remote', 'get-url', '--push', 'origin'))) {
        $url = Get-GitLine $kind
        if ($url -notmatch $script:RepoUrlPattern) { Stop-Harness "origin is '$url', not $($script:Repo) (never upstream)" }
    }
}

# True when the product version at $Commit is $Ver.
function Test-CommitCarriesVersion([string]$Commit, [string]$Ver) {
    $r = Invoke-Git @('show', "${Commit}:product/swing/chromite-swing.product") -AllowFail -Quiet
    return ($r.Code -eq 0 -and $r.Text -match "<product\b[^>]*?\sversion=""$([regex]::Escape($Ver))\.qualifier""")
}

# Files under the release-process paths that changed between two commits; the version files count only when
# something besides the version number changed.
function Get-ProcessPathChanges([string]$From, [string]$To) {
    $r = Invoke-Git (@('diff', '--name-only', $From, $To, '--') + $script:ProcessPaths) -Quiet
    $changed = @()
    foreach ($f in @($r.Lines | Where-Object { $_ })) {
        if ($script:VersionFiles -contains $f) {
            $a = (Invoke-Git @('show', "${From}:$f") -AllowFail -Quiet).Text
            $b = (Invoke-Git @('show', "${To}:$f") -AllowFail -Quiet).Text
            $norm = { param($t) [regex]::Replace($t, '\d+\.\d+\.\d+(?=\.qualifier|-SNAPSHOT)', 'X.Y.Z') }
            if ((& $norm $a) -ceq (& $norm $b)) { continue }
        }
        $changed += $f
    }
    return , $changed
}

# ---- HTTP (curl.exe, as the deploy script; public URLs only)

function Get-HttpTempFile {
    $dir = Join-Path $script:WorkRoot 'release/tmp'
    New-Item -ItemType Directory -Force $dir | Out-Null
    return (Join-Path $dir ("http-" + [Guid]::NewGuid().ToString('N') + '.tmp'))
}

# Exit codes of curl.exe (8.x in System32, which pwsh finds first) that mean no answer, seen on this PC against
# local fakes: 6 "Could not resolve host", 7 "Failed to connect ... Could not connect to server", 28 "Operation timed
# out" / "Connection timed out", 52 "Empty reply from server", 56 "Recv failure: Connection was reset", 35 a TLS
# handshake that did not finish ("schannel: ..."); from curl's list of exit codes: 5 proxy name lookup, 16 HTTP/2
# framing, 18 a body cut short, 55 "Send failure", 92 an HTTP/2 stream cut. A certificate failure (60) and every
# other code stop at once, as before. An HTTP status is an answer; only a 5xx counts as none (unless -Keep5xx).
$script:CurlNoAnswerExit = @(5, 6, 7, 16, 18, 28, 35, 52, 55, 56, 92)

function Get-HttpNoAnswer($R, [bool]$Keep5xx) {
    if ($script:CurlNoAnswerExit -contains $R.Exit) { return "curl exit $($R.Exit)" }
    if (-not $R.Exit -and -not $Keep5xx -and $R.Code -match '^5\d\d$') { return "HTTP $($R.Code)" }
    return $null
}

# GET: status code and body (UTF-8), asked again on no answer (Invoke-ReadAgain). -Once: a single try, for a caller
# that polls by itself. -Keep5xx: a 5xx is an answer (the neighbour snapshot records the neighbour's own state).
# When curl got no status, Code holds curl's message and '000', as before.
function Get-Http([string]$Url, [switch]$Once, [switch]$Keep5xx) {
    $read = {
        $tmp = Get-HttpTempFile
        try {
            $code = (& curl.exe -sS -m 60 -A $script:UserAgent -o $tmp -w '%{http_code}' $Url 2>&1) -join ' '
            $rc = $LASTEXITCODE
            $body = if (Test-Path $tmp) { [IO.File]::ReadAllText($tmp, [Text.UTF8Encoding]::new($false)) } else { '' }
            [pscustomobject]@{ Code = "$code".Trim(); Body = $body; Exit = $rc }
        } finally { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    }
    if ($Once) { return (& $read) }
    return Invoke-ReadAgain "GET $Url" $read { param($h) Get-HttpNoAnswer $h $Keep5xx }
}

# HEAD: status code and Content-Length, asked again on no answer (Invoke-ReadAgain). When curl got no status, Code
# holds curl's message.
function Get-HttpHead([string]$Url) {
    return Invoke-ReadAgain "HEAD $Url" {
        $lines = @(& curl.exe -sS -m 60 -A $script:UserAgent -I $Url 2>&1 | ForEach-Object { "$_" })
        $rc = $LASTEXITCODE
        $code = ''; $len = -1
        foreach ($l in $lines) {
            if ($l -match '^HTTP/\S+\s+(\d{3})') { $code = $Matches[1] }
            elseif ($l -match '^content-length:\s*(\d+)') { $len = [long]$Matches[1] }
        }
        if (-not $code -and $rc) { $code = (@($lines) | Select-Object -Last 1) }
        [pscustomobject]@{ Code = $code; Length = $len; Exit = $rc }
    } { param($h) Get-HttpNoAnswer $h $false }
}

function Get-LiveFeed {
    $url = "$((Get-Cfg 'production.siteUrl').TrimEnd('/'))/downloads/releases.json"
    $r = Get-Http $url
    if ($r.Code -ne '200') { Stop-Harness "the live feed answered $($r.Code)" }
    try { $feed = $r.Body | ConvertFrom-Json } catch { Stop-Harness 'the live feed is not JSON' }
    if ($feed.format -ne 1) { Stop-Harness "the live feed is not format 1" }
    return $feed
}

function Get-FeedVersions($Feed) {
    return @(@($Feed.files) | ForEach-Object { $_.version } | Sort-Object -Unique { [version]$_ } | Sort-Object { [version]$_ } -Descending)
}

# ---- release folder, files, state

function Get-ReleaseDir([string]$Ver) { return (Join-Path $script:WorkRoot "release/v$Ver") }
function Get-FilesDir([string]$Ver) { return (Join-Path (Get-ReleaseDir $Ver) 'files') }

function Read-Sums([string]$Dir) {
    $file = Join-Path $Dir 'SHA256SUMS'
    $sums = [ordered]@{}
    if (-not (Test-Path -LiteralPath $file)) { return $sums }
    foreach ($line in Get-Content -LiteralPath $file) {
        if ($line -match '^([0-9a-f]{64}) [ *]?(.+)$') { $sums[$Matches[2]] = $Matches[1] }
    }
    return $sums
}

# SHA256SUMS lists exactly $Names and each file has that hash. Prints one OK/BAD line per file.
function Test-Sums([string]$Dir, [string[]]$Names) {
    $sums = Read-Sums $Dir
    $ok = $true
    if (-not (Test-Path -LiteralPath (Join-Path $Dir 'SHA256SUMS'))) { Say "BAD: no SHA256SUMS in $Dir"; return $false }
    foreach ($n in $Names) {
        $p = Join-Path $Dir $n
        if (-not (Test-Path -LiteralPath $p)) { Say "BAD: $n is missing"; $ok = $false; continue }
        $h = (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($sums[$n] -eq $h) { Say "OK: $n" } else { Say "BAD: $n does not match SHA256SUMS"; $ok = $false }
    }
    return $ok
}

# The identity of the release files: the hash of SHA256SUMS (fetch verified every file against it).
function Get-FilesDigest([string]$Ver) {
    $sums = Join-Path (Get-FilesDir $Ver) 'SHA256SUMS'
    if (-not (Test-Path -LiteralPath $sums)) { return $null }
    return (Get-FileHash -LiteralPath $sums -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-StatePath([string]$Ver) { return (Join-Path (Get-ReleaseDir $Ver) 'state.json') }

function Read-State([string]$Ver) {
    $path = Get-StatePath $Ver
    if (Test-Path -LiteralPath $path) {
        $s = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
        if (-not $s.ContainsKey('stages')) { $s['stages'] = @{} }
        return $s
    }
    return @{ version = $Ver; stages = @{} }
}

function Save-StageState([string]$Ver, [string]$Key, [string]$Status, $Evidence, $Start, [string]$GoAhead, [string]$Reason) {
    $state = Read-State $Ver
    $entry = [ordered]@{
        status = $Status
        start = $Start
        end = Get-Stamp
        evidence = $Evidence
        log = $script:LogFile
    }
    if ($GoAhead) { $entry['goAhead'] = $GoAhead }
    if ($Reason) { $entry['reason'] = $Reason }
    $state.stages[$Key] = $entry
    New-Item -ItemType Directory -Force (Get-ReleaseDir $Ver) | Out-Null
    [IO.File]::WriteAllText((Get-StatePath $Ver), ($state | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
}

# Stages whose evidence is bound to the tag commit, the file hashes, the data generation.
$script:TagBound = @('tag', 'build', 'fetch', 'rehearse', 'deploy', 'verify', 'publish')
$script:FilesBound = @('fetch', 'rehearse', 'deploy', 'verify', 'publish')
$script:GenerationBound = @('rehearse', 'deploy')

# Problems with the earlier stages $Need (empty = all ok for this tag commit, these files, this generation).
function Get-PriorProblems([string]$Ver, [string[]]$Need, [string]$TagCommit, [string]$Digest, [int]$DataGeneration) {
    $state = Read-State $Ver
    $problems = @()
    foreach ($n in $Need) {
        $e = $state.stages[$n]
        if (-not $e) { $problems += "$n has not run"; continue }
        if ($e.status -ne 'ok') { $problems += "$n is '$($e.status)'"; continue }
        $ev = $e.evidence
        if ($TagCommit -and $script:TagBound -contains $n -and $ev.tagCommit -ne $TagCommit) {
            $problems += "$n was for tag commit $($ev.tagCommit), the tag is now $TagCommit"
        }
        if ($Digest -and $script:FilesBound -contains $n -and $ev.filesDigest -ne $Digest) {
            $problems += "$n was for other release files (SHA256SUMS $($ev.filesDigest), now $Digest)"
        }
        if ($DataGeneration -and $script:GenerationBound -contains $n -and [int]$ev.dataGeneration -ne $DataGeneration) {
            $problems += "$n was for -DataGeneration $($ev.dataGeneration), now $DataGeneration"
        }
    }
    return , $problems
}

function Assert-Prior([string]$Ver, [string[]]$Need, [string]$TagCommit, [string]$Digest, [int]$DataGeneration) {
    $p = Get-PriorProblems $Ver $Need $TagCommit $Digest $DataGeneration
    if ($p.Count) { Stop-Harness ("earlier stage missing: " + ($p -join '; ')) }
    Say "earlier stages ok: $($Need -join ', ')"
}

function Get-StageEvidence([string]$Ver, [string]$Key) {
    $e = (Read-State $Ver).stages[$Key]
    if ($e -and $e.status -eq 'ok') { return $e.evidence }
    return $null
}

# ---- lock

function Get-LockPath { return (Join-Path $script:WorkRoot 'release/harness.lock') }

function Enter-Lock([string]$StageName, [string]$Ver) {
    $lock = Get-LockPath
    New-Item -ItemType Directory -Force (Split-Path $lock) | Out-Null
    if (Test-Path -LiteralPath $lock) {
        $held = $null
        try { $held = Get-Content -LiteralPath $lock -Raw | ConvertFrom-Json } catch { }
        $alive = $false
        if ($held -and $held.pid) {
            $p = Get-Process -Id ([int]$held.pid) -ErrorAction SilentlyContinue
            # same pid AND same start time: a reused pid is not the holder (ConvertFrom-Json may return a DateTime)
            if ($p) {
                try { $alive = [Math]::Abs(($p.StartTime.ToUniversalTime() - ([DateTime]$held.processStart).ToUniversalTime()).TotalSeconds) -lt 1 } catch { $alive = $false }
            }
        }
        if ($alive) { Stop-Harness "lock held by pid $($held.pid) (stage $($held.stage) $($held.version), since $($held.time)): $lock" }
        Say "taking over a stale lock (pid $($held.pid) is not running): $lock"
        Remove-Item -LiteralPath $lock -Force
    }
    $me = Get-Process -Id $PID
    $body = [ordered]@{ pid = $PID; processStart = $me.StartTime.ToUniversalTime().ToString('o'); stage = $StageName; version = $Ver; time = (Get-Stamp).utc } |
        ConvertTo-Json -Compress
    try {
        $fs = [IO.File]::Open($lock, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try { $bytes = [Text.Encoding]::UTF8.GetBytes($body); $fs.Write($bytes, 0, $bytes.Length) } finally { $fs.Dispose() }
    } catch [IO.IOException] {
        Stop-Harness "lock taken by another run just now: $lock"
    }
    $script:LockTaken = $true
}

function Exit-Lock {
    if (-not $script:LockTaken) { return }
    $lock = Get-LockPath
    try {
        $held = Get-Content -LiteralPath $lock -Raw | ConvertFrom-Json
        if ([int]$held.pid -eq $PID) { Remove-Item -LiteralPath $lock -Force }
    } catch { }
    $script:LockTaken = $false
}

# ---- go-ahead, checks

function Assert-GoAhead([string]$StageName) {
    if (-not $script:GoAhead -or -not $script:GoAhead.Trim()) {
        Stop-Harness "$StageName needs -UserGoAhead `"<the user's own words in this conversation for this release>`""
    }
    Say "user go-ahead (as given, not verifiable by the harness): `"$($script:GoAhead)`""
}

# One check line: OK/BAD, collected in $script:Bad.
function Test-Check([string]$What, [bool]$Ok, [string]$Detail) {
    if ($Ok) { Say "OK: $What" } else { Say "BAD: $What ($Detail)"; $script:Bad += $What }
}
