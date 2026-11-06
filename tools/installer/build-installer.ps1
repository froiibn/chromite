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
  Builds the Windows installer Chromite-<version>-x64-setup.exe from the built Swing product (tools/installer/chromite.nsi).

.DESCRIPTION
  The uninstaller must be signed before it is embedded, so the build has two passes
  (docs/chromite/distribution.md):
    -Stage uninstaller   pass 1: writes <OutDir>\installer-work\uninstall.exe (sign it next)
    -Stage setup         pass 2: embeds -Uninstaller and writes <OutDir>\Chromite-<version>-x64-setup.exe (sign it next)
    -Stage all           both passes, nothing signed (local test builds)
  The product folder must already hold the bundled runtime (java tools/jre/BundleJre.java windows-x64)
  and, for a release, the signed launcher: the setup copies the folder as it is.
  Prints the path of the file it wrote last. Fails when the setup's ProductVersion/FileVersion is not the version.
  OutDir defaults to product/swing/target/installer (ignored by git, removed by mvn clean).

  Design: one script for CI (release.yml) and local builds so the installer is made the same way in both.
  makensis is taken from -Makensis, else NSIS_HOME, else PATH; release.yml installs a pinned NSIS.

  Change history:
  - 2026-11-07 Created.
#>
param(
    [ValidateSet('all', 'uninstaller', 'setup')]
    [string]$Stage = 'all',
    [string]$ProductDir = 'product/swing/target/products/com.seizia.chromite.swing.product/win32/win32/x86_64/chromite-swing',
    [string]$Version,
    [string]$OutDir = 'product/swing/target/installer',
    [string]$Uninstaller,
    [string]$Makensis
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $repo

if (-not $Version) {
    # the committed product version; fails when its copies disagree (docs/chromite/versioning.md)
    $Version = (& pwsh -NoProfile -File tools/release/bump-version.ps1 | Select-Object -Last 1)
    if ($LASTEXITCODE) { throw 'bump-version.ps1 could not read the product version' }
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "version '$Version' is not major.minor.dev" }

if (-not $Makensis) {
    if ($env:NSIS_HOME) { $Makensis = Join-Path $env:NSIS_HOME 'makensis.exe' }
    else { $Makensis = (Get-Command makensis.exe -ErrorAction SilentlyContinue).Source }
}
if (-not $Makensis -or -not (Test-Path $Makensis)) { throw 'makensis.exe not found: pass -Makensis or set NSIS_HOME' }

$product = (Resolve-Path $ProductDir).Path
foreach ($need in 'chromite-swing.exe', 'jre\bin\server\jvm.dll') {
    if (-not (Test-Path (Join-Path $product $need))) { throw "$product has no $need (build the product and bundle the runtime first)" }
}
$out = (New-Item -ItemType Directory -Force $OutDir).FullName
$work = (New-Item -ItemType Directory -Force (Join-Path $out 'installer-work')).FullName
$nsi = Join-Path $PSScriptRoot 'chromite.nsi'
$icon = Join-Path $repo 'product/swing/icons/chromite.ico'

# files.nsh: the product folder's top-level entries, removed by name on upgrade and uninstall. Names
# are checked so that no NSIS syntax can come in from a file name.
$lines = foreach ($item in Get-ChildItem -Force $product | Sort-Object Name) {
    if ($item.Name -notmatch '^[A-Za-z0-9._-]+$') { throw "unexpected name in the product folder: '$($item.Name)'" }
    if ($item.PSIsContainer) { "RMDir /r `"`$INSTDIR\$($item.Name)`"" } else { "Delete `"`$INSTDIR\$($item.Name)`"" }
}
$filesNsh = Join-Path $work 'files.nsh'
[IO.File]::WriteAllLines($filesNsh, [string[]]$lines, [Text.UTF8Encoding]::new($false))

function Invoke-Makensis([string]$source, [string[]]$defines) {
    $argv = @('/V2', '/INPUTCHARSET', 'UTF8', "/DVERSION=$Version", "/DSOURCE_DIR=$source", "/DFILES_NSH=$filesNsh", "/DICON=$icon") + $defines + @($nsi)
    & $Makensis @argv
    if ($LASTEXITCODE) { throw "makensis failed ($LASTEXITCODE)" }
}

function Assert-Version([string]$exe) {
    $info = (Get-Item $exe).VersionInfo
    if ($info.ProductVersion -ne $Version -or $info.FileVersion -ne $Version) {
        throw "$exe has ProductVersion '$($info.ProductVersion)', FileVersion '$($info.FileVersion)', not $Version"
    }
}

if ($Stage -in 'all', 'uninstaller') {
    $writer = Join-Path $work 'uninstaller-writer.exe'
    $unOut = Join-Path $work 'uninstall.exe'
    Remove-Item -Force $unOut -ErrorAction SilentlyContinue
    Invoke-Makensis $product @('/DINNER', "/DUNINSTALLER_OUT=$unOut", "/DOUT_FILE=$writer")
    # the writer only runs its .onInit: WriteUninstaller, then quit
    $p = Start-Process -FilePath $writer -ArgumentList '/S' -Wait -PassThru
    if ($p.ExitCode -ne 0 -or -not (Test-Path $unOut)) { throw "the uninstaller writer failed ($($p.ExitCode))" }
    Remove-Item -Force $writer
    Assert-Version $unOut
    $Uninstaller = $unOut
    "uninstaller: $unOut"
}

if ($Stage -in 'all', 'setup') {
    if (-not $Uninstaller -or -not (Test-Path $Uninstaller)) { throw 'pass 2 needs -Uninstaller (the uninstall.exe of pass 1)' }
    $setup = Join-Path $out "Chromite-$Version-x64-setup.exe"
    # makensis cannot open files whose full path is over 260 characters, and the product folder is deep
    # (its longest file is about 145 characters below it; on a GitHub runner the whole path is over 260).
    # A junction with a short name in the work folder gives makensis short paths to the same files.
    $link = Join-Path $work 'src'
    if (Test-Path $link) { (Get-Item $link -Force).Delete() }   # a link from an earlier run: only the link goes
    New-Item -ItemType Junction -Path $link -Target $product | Out-Null
    try {
        Invoke-Makensis $link @("/DUNINSTALLER=$((Resolve-Path $Uninstaller).Path)", "/DOUT_FILE=$setup")
    } finally {
        (Get-Item $link -Force).Delete()
    }
    Assert-Version $setup
    "setup: $setup ($((Get-Item $setup).Length) bytes)"
}
