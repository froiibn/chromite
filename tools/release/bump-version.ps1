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
    Shows, checks or sets the Chromite product version (YY.seq.dev, docs/chromite/versioning.md).

.DESCRIPTION
    The product version lives in product/swing/chromite-swing.product (the source of truth, read by
    release.yml). Tycho needs the same number as a literal in three more places, so this script is the
    only way to change it:
      - product/swing/chromite-swing.product            version="X.Y.Z.qualifier"
      - plugins/com.seizia.chromite.ui.swing/META-INF/MANIFEST.MF
                                                         Bundle-Version: X.Y.Z.qualifier (the product-defining
                                                         bundle: About shows its version)
      - plugins/com.seizia.chromite.ui.swing/pom.xml    <version>X.Y.Z-SNAPSHOT</version> (Tycho requires it to
                                                         match the manifest)
      - product/swing/pom.xml                           <version>X.Y.Z-SNAPSHOT</version> (the archive name uses
                                                         ${unqualifiedVersion})
    Everything else (Info.plist, .eclipseproduct, release file names, the release tag check) is derived from
    these during the build or the release workflow.

    Without -Version the script prints the current version and fails if the four places disagree
    (release.yml runs it this way). With -Version it refuses a version that is not X.Y.Z, not higher than
    the current version and every local v* tag, or that breaks the scheme YY.seq.dev:
      - the first number is the two digits of this year (a new year starts at YY.1.0);
      - before the first release for general users the versions are YY.0.dev and the third number goes on
        from the current one (0.1.4 -> 26.0.5 -> 26.0.6, the one-time move from the 0.x numbers included);
      - leaving YY.0.dev for YY.1.0 needs -FirstUserRelease (26.1.0 is the user's decision);
      - after it the second number goes up with every release and the third number (urgent fixes) resets
        to 0 when the second goes up.
    It writes all four files and changes nothing else; commit them as one "version" commit.

.EXAMPLE
    pwsh tools/release/bump-version.ps1
    pwsh tools/release/bump-version.ps1 26.0.6
    pwsh tools/release/bump-version.ps1 26.1.0 -FirstUserRelease
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string] $Version,
    [switch] $FirstUserRelease
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$versionPattern = '\d+\.\d+\.\d+'

# Each location: a regex with three groups (text before, the version, text after) that must match exactly once.
$locations = @(
    @{ path = 'product/swing/chromite-swing.product'
       regex = "(<product\b[^>]*?\sversion="")($versionPattern)(\.qualifier"")" },
    @{ path = 'plugins/com.seizia.chromite.ui.swing/META-INF/MANIFEST.MF'
       regex = "(?m)(^Bundle-Version: )($versionPattern)(\.qualifier)(?=\r?$)" },
    @{ path = 'plugins/com.seizia.chromite.ui.swing/pom.xml'
       regex = "(?s)(<artifactId>com\.seizia\.chromite\.ui\.swing</artifactId>(?:(?!</version>).)*?<version>)($versionPattern)(-SNAPSHOT</version>)" },
    @{ path = 'product/swing/pom.xml'
       regex = "(?s)(<artifactId>com\.seizia\.chromite\.swing\.product</artifactId>\s*<version>)($versionPattern)(-SNAPSHOT</version>)" }
)

function Read-Location($location) {
    $file = Join-Path $root $location.path
    $text = [IO.File]::ReadAllText($file)
    $found = [regex]::Matches($text, $location.regex)
    if ($found.Count -ne 1) {
        throw "$($location.path): expected one version, found $($found.Count) (pattern $($location.regex))"
    }
    return @{ file = $file; text = $text; version = $found[0].Groups[2].Value }
}

function ConvertTo-VersionTuple([string] $value) {
    $parts = $value.Split('.') | ForEach-Object { [int] $_ }
    return [version]::new($parts[0], $parts[1], $parts[2])
}

$read = @($locations | ForEach-Object { Read-Location $_ })
$found = @($read | ForEach-Object { $_.version } | Sort-Object -Unique { ConvertTo-VersionTuple $_ })
# With differing places the highest one counts as current, so setting a version repairs them.
$current = $found[-1]

if (-not $Version) {
    if ($found.Count -ne 1) {
        $detail = for ($i = 0; $i -lt $locations.Count; $i++) { "$($locations[$i].path)=$($read[$i].version)" }
        throw "the version differs between files ($($detail -join ', ')); set it with this script"
    }
    $current
    exit 0
}

if ($Version -notmatch "^$versionPattern$") {
    throw "version '$Version' is not YY.seq.dev (for example 26.0.6)"
}
$old = ConvertTo-VersionTuple $current
$new = ConvertTo-VersionTuple $Version
if ($new -le $old) {
    throw "version $Version is not higher than the current version $current"
}
$year = (Get-Date).Year % 100
if ($new.Major -ne $year) {
    throw "version ${Version}: the first number is the year, $year now"
}
# Before the first release for general users: the 0.x numbers (0.1.4 and earlier) and YY.0.dev.
if ($old.Major -eq 0 -or $old.Minor -eq 0) {
    if ($FirstUserRelease) {
        if ($new.Minor -ne 1 -or $new.Build -ne 0) {
            throw "version ${Version}: the first release for general users is $year.1.0"
        }
    } elseif ($new.Minor -ne 0) {
        throw "version ${Version} leaves $year.0.dev; the first release for general users ($year.1.0) is the user's decision, pass -FirstUserRelease when they made it"
    } elseif ($new.Build -le $old.Build) {
        # also the move from 0.1.4: the third number goes on (26.0.5), it does not start again
        throw "version ${Version}: before the first release for general users the third number goes on from $current ($year.0.$($old.Build + 1))"
    }
} elseif ($FirstUserRelease) {
    throw "version ${Version}: -FirstUserRelease is for leaving the 0.dev numbers, and $current is past them"
} elseif ($new.Major -ne $old.Major -and ($new.Minor -ne 1 -or $new.Build -ne 0)) {
    throw "version ${Version}: a new year starts at $year.1.0"
} elseif ($new.Minor -ne $old.Minor -and $new.Build -ne 0) {
    throw "version ${Version}: the third number resets to 0 when the second goes up ($($new.Major).$($new.Minor).0)"
}
# A number that ever got a tag is never reused, even if its commit never reached devel.
$tags = @(git -C $root tag --list 'v*')
if ($LASTEXITCODE) { throw 'git tag --list failed' }
foreach ($tag in $tags) {
    if ($tag -match "^v($versionPattern)$" -and (ConvertTo-VersionTuple $Matches[1]) -ge $new) {
        throw "tag $tag exists: version $Version must be higher (run 'git fetch --tags' to see every tag)"
    }
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
for ($i = 0; $i -lt $locations.Count; $i++) {
    $updated = [regex]::Replace($read[$i].text, $locations[$i].regex, { param($m) $m.Groups[1].Value + $Version + $m.Groups[3].Value })
    [IO.File]::WriteAllText($read[$i].file, $updated, $utf8)
    "$($locations[$i].path): $($read[$i].version) -> $Version"
}
