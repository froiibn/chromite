# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
# Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).

<#
.SYNOPSIS
  Rehearses tools/release/deploy-chromite-site.ps1 against a local Docker copy of the chromite.seizia.com server.

.DESCRIPTION
  Starts compose.yaml (sshd on 127.0.0.1:<SshPort>, Caddy on 127.0.0.1:<HttpPort>), makes a rehearsal SSH key and a
  known_hosts with the container's own host key under <WorkDir>, then runs the deploy script with -Rehearsal -Yes.
  -ArtifactsDir holds the release files and their SHA256SUMS (a GitHub Release download, or local unsigned test
  builds - only a rehearsal accepts those). Run it again with another version to rehearse an update; -Down removes
  the containers and the volume. Then open http://127.0.0.1:<HttpPort>/ to look at the site.

  Change history:
  - 2026-11-07 Created.
#>
param(
    [string]$Tag,
    [string]$ArtifactsDir,
    [int]$DataGeneration = 1,
    [int]$SshPort = 2222,
    [int]$HttpPort = 8088,
    [string]$WorkDir,
    [switch]$Down
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$repoRoot = (Resolve-Path (Join-Path $here '../../..')).Path
if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot '.local/rehearsal' }
$compose = @('compose', '-f', (Join-Path $here 'compose.yaml'))

if ($Down) {
    $env:REHEARSAL_AUTHORIZED_KEY = 'unused'   # compose checks the variable even when stopping
    & docker @compose down --volumes
    exit $LASTEXITCODE
}
if (-not $Tag -or -not $ArtifactsDir) { throw 'give -Tag and -ArtifactsDir (or -Down)' }

New-Item -ItemType Directory -Force $WorkDir | Out-Null
$key = Join-Path $WorkDir 'rehearsal_ed25519'
if (-not (Test-Path $key)) {
    # a key for this local container only; no passphrase
    & ssh-keygen -q -t ed25519 -N '' -C chromite-rehearsal -f $key
    if ($LASTEXITCODE) { throw 'ssh-keygen failed' }
}
$env:REHEARSAL_AUTHORIZED_KEY = (Get-Content "$key.pub" -Raw).Trim()
$env:REHEARSAL_SSH_PORT = "$SshPort"
$env:REHEARSAL_HTTP_PORT = "$HttpPort"
& docker @compose up -d --build --wait
if ($LASTEXITCODE) { throw 'docker compose up failed' }

$hostKey = (& docker @compose exec -T server cat /etc/ssh/ssh_host_ed25519_key.pub) -join ''
if ($LASTEXITCODE -or -not $hostKey) { throw 'could not read the container host key' }
$known = Join-Path $WorkDir 'known_hosts'
$parts = $hostKey.Trim().Split(' ')
[IO.File]::WriteAllText($known, "[127.0.0.1]:$SshPort $($parts[0]) $($parts[1])`n")

& pwsh -NoProfile -File (Join-Path $here '../deploy-chromite-site.ps1') -Tag $Tag -DataGeneration $DataGeneration `
    -Target ubuntu@127.0.0.1 -Port $SshPort -IdentityFile $key -KnownHosts $known `
    -SiteUrl "http://127.0.0.1:$HttpPort" -ArtifactsDir $ArtifactsDir -WorkDir (Join-Path $WorkDir "deploy-$Tag") -Rehearsal -Yes
exit $LASTEXITCODE
