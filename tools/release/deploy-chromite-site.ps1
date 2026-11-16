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
  Deploys a signed Chromite release and the download site to https://chromite.seizia.com (docs/chromite/distribution.md "Deploy").

.DESCRIPTION
  1. Gets the release files of a tag from its GitHub Release (gh, froiibn/chromite; a draft works) or from -ArtifactsDir.
  2. Verifies them here: SHA256SUMS; the setup's Authenticode signature (Valid, timestamp, exact signer subject) and
     ProductVersion/FileVersion; the macOS zip's app (bundle, signature folder, stapled ticket, version; codesign and
     spctl too on a Mac); the changelog anchors that notes_url points at.
  3. After a typed "yes" (or -Yes), copies files, site, releases.sh and deploy-remote.sh to <root>/incoming/<stamp>/
     over scp and runs deploy-remote.sh there: it moves old files aside (never deletes), puts the new ones in,
     regenerates releases.json and replaces the site.
  4. Checks the live site: releases.json lists the files with these sizes and hashes, releases.js (the page's copy)
     matches it, the files and pages answer.

  Server access (host, key, known_hosts) is passed in; nothing is assumed. Production needs the user's go-ahead and a
  rehearsal first: tools/release/rehearsal/rehearse.ps1 runs this script with -Rehearsal against a local Docker copy
  of the server, where unsigned test files are accepted (and only there).

  Design: tracked and parameterised (unlike Turbrix's per-version scripts in .local/) because it holds no server
  secret; the same script is rehearsed and run for production.

  Change history:
  - 2026-11-07 Created.
  - 2026-11-08 The live check also asks for the support pages and support.js (the new feature/database table).
  - 2026-11-09 The macOS zip's bundle is Chromite.app; ChromiteSwing.app is accepted for 0.1.4 only (app change #22).
  - 2026-11-12 The macOS zip's app holds Contents/MacOS/Chromite (app change #65); chromite-swing is accepted for 0.1.4 and earlier.
  - 2026-11-12 The macOS zip's bundle must be Chromite.app with Contents/MacOS/Chromite (26.0.5): the 0.1.4-only
    allowance of ChromiteSwing.app is gone, and with it chromite-swing, which only an old-name zip could hold.

.EXAMPLE
  pwsh tools/release/deploy-chromite-site.ps1 -Tag v0.1.0 -DataGeneration 1 -Target ubuntu@<host> -IdentityFile <key> -KnownHosts <file>
#>
param(
    [Parameter(Mandatory)] [ValidatePattern('^v\d+\.\d+\.\d+$')] [string]$Tag,
    [Parameter(Mandatory)] [ValidateRange(1, 1000000)] [int]$DataGeneration,
    [Parameter(Mandatory)] [ValidatePattern('^[A-Za-z0-9._-]+@[A-Za-z0-9.:-]+$')] [string]$Target,
    [Parameter(Mandatory)] [string]$KnownHosts,
    [string]$IdentityFile,
    [int]$Port = 22,
    [string]$RemoteRoot = '/srv/chromite',
    [string]$SiteUrl = 'https://chromite.seizia.com',
    [string]$NotesUrl,
    [string]$ArtifactsDir,
    [string]$WorkDir,
    [switch]$Rehearsal,
    [switch]$Yes
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3

# The signer of every Windows release file (docs/chromite/signing.md "Publisher"). Never changes.
$Publisher = 'CN=Seizia, O=Seizia, L=Cheongju-si, S=North Chungcheong, C=KR'
$Repo = 'froiibn/chromite'
$version = $Tag.Substring(1)
$names = @("Chromite-$version-x64-setup.exe", "Chromite-$version-arm64.zip", "Chromite-$version-arm64.dmg")
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not $NotesUrl) { $NotesUrl = "https://chromite.seizia.com/ko/changelog.html#v$version" }
if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot ".local/release/$Tag" }
if ($RemoteRoot -notmatch '^/[A-Za-z0-9._/-]+$') { throw "RemoteRoot '$RemoteRoot' is not a plain absolute path" }
if ($NotesUrl -notmatch '^https://[^"\\\s]+$') { throw "NotesUrl '$NotesUrl' is not an https address without quotes, backslashes or spaces" }
$siteHost = ([Uri]$SiteUrl).Host
$sshHost = $Target.Split('@')[1]
$local = @('127.0.0.1', 'localhost', '[::1]', '::1')
if ($Rehearsal -and (($local -notcontains $siteHost) -or ($local -notcontains $sshHost))) {
    throw '-Rehearsal accepts unsigned files and is allowed against this PC only (Target and SiteUrl on 127.0.0.1 / localhost)'
}

$failed = 0
function Ok([string]$what) { Write-Host "OK: $what" }
function Bad([string]$what) { Write-Host "FAILED: $what"; $script:failed++ }
function Expect([string]$what, [bool]$ok, [string]$detail) { if ($ok) { Ok $what } else { Bad "$what ($detail)" } }
function Lenient([string]$what, [bool]$ok, [string]$detail) {
    # a release check that only a rehearsal may skip (unsigned local test files)
    if ($ok) { Ok $what } elseif ($Rehearsal) { Write-Host "REHEARSAL, NOT CHECKED: $what ($detail)" } else { Bad "$what ($detail)" }
}
function Stop-IfFailed([string]$step) {
    if ($script:failed) { Write-Host "STOP: $($script:failed) check(s) failed in $step - nothing was changed on the server"; exit 1 }
}

# ---- 1. the release files
Write-Host "1/5 release files of $Tag..."
$release = Join-Path $WorkDir 'release'
if ($ArtifactsDir) {
    $release = (Resolve-Path $ArtifactsDir).Path
} else {
    New-Item -ItemType Directory -Force $release | Out-Null
    & gh release download $Tag --repo $Repo --dir $release --skip-existing --pattern 'Chromite-*' --pattern SHA256SUMS
    if ($LASTEXITCODE) { throw "gh release download $Tag failed" }
}
Write-Host "  from $release"

# ---- 2. checks here
Write-Host '2/5 checking the files here...'
$sumsFile = Join-Path $release 'SHA256SUMS'
$sums = @{}
if (Test-Path $sumsFile) {
    foreach ($line in Get-Content $sumsFile) {
        if ($line -match '^([0-9a-f]{64}) [ *]?(.+)$') { $sums[$Matches[2]] = $Matches[1] }
    }
}
foreach ($n in $names) {
    $p = Join-Path $release $n
    if (-not (Test-Path $p)) { Bad "$n is missing"; continue }
    $h = (Get-FileHash $p -Algorithm SHA256).Hash.ToLowerInvariant()
    Expect "$n matches SHA256SUMS" ($sums[$n] -eq $h) "listed '$($sums[$n])', file $h"
}
Stop-IfFailed 'the file list'

$setup = Join-Path $release $names[0]
$sig = Get-AuthenticodeSignature $setup
Lenient "$($names[0]) Authenticode Valid" ($sig.Status -eq 'Valid') "$($sig.Status)"
Lenient "$($names[0]) has a timestamp" ($null -ne $sig.TimeStamperCertificate) 'no timestamp'
$subject = if ($sig.SignerCertificate) { $sig.SignerCertificate.Subject } else { '' }
Lenient "$($names[0]) signer is exactly '$Publisher'" ($subject -eq $Publisher) "'$subject'"
$vi = (Get-Item $setup).VersionInfo
Expect "$($names[0]) ProductVersion and FileVersion are $version" ($vi.ProductVersion -eq $version -and $vi.FileVersion -eq $version) "$($vi.ProductVersion) / $($vi.FileVersion)"

# The macOS update payload: the stapled, signed app as release-macos.sh "package" zipped it (ditto).
$zip = Join-Path $release $names[1]
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($zip)
try {
    $entries = @{}
    foreach ($e in $archive.Entries) { $entries[$e.FullName] = $e }
    # The zip's one bundle: Chromite.app (release.yml "Zip and disk image"; 0.1.4 alone held ChromiteSwing.app).
    $tops = @($entries.Keys | ForEach-Object { ($_ -split '/')[0] } | Sort-Object -Unique)
    Expect "$($names[1]) holds one app bundle, Chromite.app" ($tops.Count -eq 1 -and $tops[0] -eq 'Chromite.app') "$($tops -join ', ')"
    $bundle = if ($tops.Count -eq 1) { $tops[0] } else { 'Chromite.app' }
    $app = "$bundle/Contents"
    # The launcher is Chromite since app change #65 (docs/chromite/launcher-name.md).
    Expect "$($names[1]) holds $app/MacOS/Chromite" ($entries.ContainsKey("$app/MacOS/Chromite")) 'missing'
    Lenient "$($names[1]) app is signed ($app/_CodeSignature/CodeResources)" ($entries.ContainsKey("$app/_CodeSignature/CodeResources")) 'missing'
    Lenient "$($names[1]) app carries its notarization ticket ($app/CodeResources)" ($entries.ContainsKey("$app/CodeResources")) 'missing'
    $plist = $entries["$app/Info.plist"]
    $short = ''
    if ($plist) {
        $reader = [IO.StreamReader]::new($plist.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($text -match '<key>CFBundleShortVersionString</key>\s*<string>([^<]*)</string>') { $short = $Matches[1] }
    }
    Expect "$($names[1]) app version (CFBundleShortVersionString) is $version" ($short -eq $version) "'$short'"
} finally { $archive.Dispose() }
if (Get-Command codesign -ErrorAction SilentlyContinue) {
    # on a Mac: the same checks as release-macos.sh "verify" and the updater
    $x = Join-Path $WorkDir 'zip-check'
    Remove-Item -Recurse -Force $x -ErrorAction SilentlyContinue
    & ditto -x -k $zip $x
    & codesign --verify --deep --strict "$x/$bundle"
    Lenient "$($names[1]) codesign --verify --deep --strict" ($LASTEXITCODE -eq 0) "exit $LASTEXITCODE"
    $spctl = (& spctl -a -t exec -vv "$x/$bundle" 2>&1) -join ' '
    Lenient "$($names[1]) Gatekeeper: Notarized Developer ID" ($spctl -match 'accepted' -and $spctl -match 'source=Notarized Developer ID') $spctl
} else {
    Write-Host "  (codesign is not on this PC: the macOS signature and notarization were checked by release.yml only)"
}

$site = Join-Path $repoRoot 'site'
foreach ($lang in 'ko', 'en') {
    $page = Get-Content -Raw (Join-Path $site "$lang/changelog.html")
    Expect "site/$lang/changelog.html has the anchor v$version (notes_url)" ($page.Contains("id=`"v$version`"")) 'missing'
}
Stop-IfFailed 'the release check'

# ---- 3. the staging folder: files/, site/, the two scripts (LF), SUMS
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$stage = Join-Path $WorkDir "stage/$stamp"
New-Item -ItemType Directory -Force (Join-Path $stage 'files') | Out-Null
foreach ($n in $names) { Copy-Item (Join-Path $release $n) (Join-Path $stage "files/$n") }
Copy-Item -Recurse $site (Join-Path $stage 'site')
foreach ($s in 'releases.sh', 'deploy-remote.sh') {
    $text = [IO.File]::ReadAllText((Join-Path $PSScriptRoot $s)).Replace("`r`n", "`n")
    [IO.File]::WriteAllText((Join-Path $stage $s), $text, [Text.UTF8Encoding]::new($false))
}
$sumLines = Get-ChildItem -Recurse -File $stage | ForEach-Object {
    $rel = $_.FullName.Substring($stage.Length + 1).Replace('\', '/')
    if ($rel -notmatch '^[A-Za-z0-9._/-]+$') { throw "unexpected file name in the staging folder: $rel" }
    "$((Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $rel"
}
[IO.File]::WriteAllText((Join-Path $stage 'SUMS'), (($sumLines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
Write-Host "3/5 staging folder $stage ($(@($sumLines).Count) files)"

# ---- 4. copy and apply
$ssh = Get-Command ssh -ErrorAction Stop
$scp = Get-Command scp -ErrorAction Stop
$o = @('-o', 'StrictHostKeyChecking=yes', '-o', "UserKnownHostsFile=$((Resolve-Path $KnownHosts).Path.Replace('\', '/'))",
       '-o', 'GlobalKnownHostsFile=none', '-o', 'ConnectTimeout=20')
if ($IdentityFile) { $o += @('-i', (Resolve-Path $IdentityFile).Path, '-o', 'IdentitiesOnly=yes') }
if ($Yes) { $o += @('-o', 'BatchMode=yes') }
Write-Host "4/5 deploy $version (data_generation $DataGeneration, notes $NotesUrl) to ${Target}:$RemoteRoot, port $Port; site check at $SiteUrl"
if (-not $Yes) {
    $go = Read-Host 'copy and apply now? type yes'
    if ($go -ne 'yes') { Write-Host 'Nothing changed on the server.'; exit 0 }
}
# (no double quotes in remote commands: Windows PowerShell 5.1 does not pass them to ssh.exe intact)
& $ssh @o -p $Port $Target "mkdir -p $RemoteRoot/incoming && test ! -e $RemoteRoot/incoming/$stamp"
if ($LASTEXITCODE) { Write-Host 'STOP: the server could not be reached, or the staging folder exists - nothing was changed'; exit 1 }
& $scp @o -P $Port -q -r $stage "${Target}:$RemoteRoot/incoming/"
if ($LASTEXITCODE) { Write-Host "STOP: copy failed - nothing was changed but $RemoteRoot/incoming/$stamp"; exit 1 }
& $ssh @o -p $Port $Target "sh $RemoteRoot/incoming/$stamp/deploy-remote.sh $RemoteRoot $stamp $version $DataGeneration $NotesUrl"
if ($LASTEXITCODE) { Write-Host "STOP: the server step stopped (its message is above); files it moved aside, if any, are in $RemoteRoot/old/$stamp/"; exit 1 }

# ---- 5. the live site
Write-Host "5/5 checking $SiteUrl..."
$feedText = (curl.exe -sS -m 20 "$SiteUrl/downloads/releases.json") -join "`n"
$feed = $null
try { $feed = $feedText | ConvertFrom-Json } catch { }
Expect 'releases.json is JSON, format 1' ($null -ne $feed -and $feed.format -eq 1) $feedText.Substring(0, [Math]::Min(200, $feedText.Length))
if ($feed) {
    foreach ($n in $names) {
        $entry = @($feed.files | Where-Object { $_.file -eq $n })
        $p = Join-Path $release $n
        $okEntry = $entry.Count -eq 1 -and $entry[0].size -eq (Get-Item $p).Length -and
            $entry[0].sha256 -eq (Get-FileHash $p -Algorithm SHA256).Hash.ToLowerInvariant() -and
            $entry[0].version -eq $version -and $entry[0].data_generation -eq $DataGeneration -and $entry[0].notes_url -eq $NotesUrl
        Expect "releases.json lists $n with its size, sha256, data_generation and notes_url" $okEntry ($entry | ConvertTo-Json -Compress)
        $head = @(curl.exe -sS -m 20 -I "$SiteUrl/downloads/$n")
        $len = ($head | Where-Object { $_ -match '^content-length:' }) -replace '^content-length:\s*', ''
        Expect "$n answers 200 with $((Get-Item $p).Length) bytes" ($head[0] -match ' 200' -and "$len".Trim() -eq "$((Get-Item $p).Length)") "$($head[0]) length $len"
    }
    $setups = @($feed.files | Where-Object { $_.kind -eq 'setup' } | ForEach-Object { $_.version })
    Write-Host "  setups listed: $($setups -join ', ') (the previous one stays for rollback)"
}
foreach ($path in '/', '/ko/', '/en/', '/ko/changelog.html', '/en/changelog.html', '/ko/privacy.html', '/en/privacy.html', '/ko/support.html', '/en/support.html', '/assets/site.js', '/assets/support.js', '/assets/site.css') {
    $code = curl.exe -s -m 20 -o NUL -w '%{http_code}' "$SiteUrl$path"
    Expect "$path answers 200" ($code -eq '200') $code
}
$js = (curl.exe -sS -m 20 "$SiteUrl/downloads/releases.js") -join "`n"
Expect 'releases.js (the page''s copy of the feed) holds the same JSON as releases.json' ($feedText -and $js.StartsWith("window.chromiteReleases = $feedText")) $js.Substring(0, [Math]::Min(120, $js.Length))
$csp = (@(curl.exe -sS -m 20 -D - -o NUL "$SiteUrl/ko/") | Where-Object { $_ -match '^content-security-policy:' }) -join ' '
Expect "the CSP lets the page run its same-origin scripts (script-src 'self')" ($csp -match "script-src 'self'") "'$csp'"
if ($failed) { Write-Host "$failed live check(s) FAILED - the files are on the server; see docs/chromite/distribution.md for rolling back"; exit 1 }
Write-Host "ALL CHECKS OK: $version is live at $SiteUrl"
exit 0
