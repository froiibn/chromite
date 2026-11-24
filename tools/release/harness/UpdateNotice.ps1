# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
# Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
#
# Stage update-notice of the release harness: an isolated copy of the previous release (its portable zip) shows
# "New version <ver>" after the deploy (port of the 26.1.7 check-update.ps1).
#
# Design:
#   * The copy runs only from a scratch folder with its own -data, -configuration (a copy of its configuration)
#     and CHROMITE_DATA, so the user's install, workspace and secure storage are never touched.
#   * Only windows of processes started from that folder are read and captured (PrintWindow of that window, never
#     the screen), and only those processes are stopped.
#   * The notice is a button in the app's own title bar, not the OS window title: the window is captured and its
#     title strip read with Windows OCR (Read-ImageText.ps1). OCR may drop a dot ("26.17"), so a line matches when
#     its digits hold the version's digits and it holds the Korean or English word of the message.
#
# Change history:
# - 2026-11-23 Created.
# - 2026-11-24 The title strip is read plain and with inverted colours, and a stop says what OCR read last: at the
#   26.1.8 release the plain strip gave "26.13" for the notice that was there, and the stage stopped without saying why.

$script:WinApiLoaded = $false

function Initialize-WinApi {
    if ($script:WinApiLoaded) { return }
    Add-Type -AssemblyName System.Drawing
    Add-Type @'
using System; using System.Text; using System.Runtime.InteropServices; using System.Collections.Generic;
public static class ChromiteHarnessWin {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc p, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public static List<IntPtr> Windows(uint[] pids) {
    var list = new List<IntPtr>();
    EnumWindows((h, l) => { uint p; GetWindowThreadProcessId(h, out p); if (Array.IndexOf(pids, p) >= 0 && IsWindowVisible(h)) list.Add(h); return true; }, IntPtr.Zero);
    return list;
  }
  public static string Title(IntPtr h) { var s = new StringBuilder(512); GetWindowText(h, s, 512); return s.ToString(); }
}
'@
    [void][ChromiteHarnessWin]::SetProcessDPIAware()
    $script:WinApiLoaded = $true
}

function Get-CopyProcesses([string]$Root) {
    return @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($Root, [StringComparison]::OrdinalIgnoreCase) })
}

# The largest visible window of the copy, captured (PrintWindow) to $Png. $null when there is none.
function Save-CopyWindow([string]$Root, [string]$Png) {
    [uint32[]]$pids = @(Get-CopyProcesses $Root | ForEach-Object { [uint32]$_.ProcessId })
    if (-not $pids) { return $null }
    $best = $null; $bestArea = 0
    foreach ($h in [ChromiteHarnessWin]::Windows($pids)) {
        $rc = New-Object ChromiteHarnessWin+RECT
        [void][ChromiteHarnessWin]::GetWindowRect($h, [ref]$rc)
        $area = ($rc.R - $rc.L) * ($rc.B - $rc.T)
        if ($area -gt $bestArea) { $best = @{ H = $h; W = $rc.R - $rc.L; Ht = $rc.B - $rc.T }; $bestArea = $area }
    }
    if (-not $best -or $best.W -lt 200 -or $best.Ht -lt 100) { return $null }
    $bmp = New-Object System.Drawing.Bitmap $best.W, $best.Ht
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $dc = $g.GetHdc()
    [void][ChromiteHarnessWin]::PrintWindow($best.H, $dc, 2)
    $g.ReleaseHdc($dc); $g.Dispose()
    $bmp.Save($Png)
    # the title strip, right half, three times larger: OCR reads small text better
    $h = [Math]::Min(90, $bmp.Height); $w = [int]($bmp.Width / 2)
    $strip = New-Object System.Drawing.Bitmap ($w * 3), ($h * 3)
    $g = [System.Drawing.Graphics]::FromImage($strip)
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($bmp, [System.Drawing.Rectangle]::new(0, 0, $w * 3, $h * 3), [System.Drawing.Rectangle]::new($bmp.Width - $w, 0, $w, $h), 'Pixel')
    $g.Dispose()
    $stripPng = [IO.Path]::ChangeExtension($Png, '.strip.png')
    $strip.Save($stripPng)
    # The same strip with inverted colours: OCR reads light text on a dark title bar differently (at 26.1.8 the plain
    # strip gave "26.13" for "26.1.8", the inverted one "261.8"). Both are read; one that matches is enough.
    $inverted = New-Object System.Drawing.Bitmap $strip.Width, $strip.Height
    $g = [System.Drawing.Graphics]::FromImage($inverted)
    $matrix = New-Object System.Drawing.Imaging.ColorMatrix
    $matrix.Matrix00 = -1; $matrix.Matrix11 = -1; $matrix.Matrix22 = -1; $matrix.Matrix33 = 1; $matrix.Matrix44 = 1
    $matrix.Matrix40 = 1; $matrix.Matrix41 = 1; $matrix.Matrix42 = 1
    $attributes = New-Object System.Drawing.Imaging.ImageAttributes
    $attributes.SetColorMatrix($matrix)
    $g.DrawImage($strip, [System.Drawing.Rectangle]::new(0, 0, $strip.Width, $strip.Height), 0, 0, $strip.Width, $strip.Height,
        [System.Drawing.GraphicsUnit]::Pixel, $attributes)
    $g.Dispose()
    $invertedPng = [IO.Path]::ChangeExtension($Png, '.strip-inverted.png')
    $inverted.Save($invertedPng); $inverted.Dispose(); $strip.Dispose(); $bmp.Dispose()
    return [pscustomobject]@{ Title = [ChromiteHarnessWin]::Title($best.H); Strips = @($stripPng, $invertedPng) }
}

function Find-NoticeLine([string[]]$Lines, [string]$Ver) {
    $digits = $Ver.Replace('.', '')
    $sae = [string][char]0xC0C8   # the first syllable of the Korean message
    foreach ($l in $Lines) {
        $d = $l -replace '[^0-9]', ''
        if ($d.Contains($digits) -and ($l.Contains($sae) -or $l -match '(?i)\bnew\b|version')) { return $l }
    }
    return $null
}

function Invoke-StageUpdateNotice {
    $ver = $script:Version
    $from = "$($script:FromVersion)".Trim() -replace '^v', ''
    if (-not (Test-VersionText $from)) { Stop-Harness 'update-notice needs -FromVersion <previous version>' }
    if ([version]$from -ge [version]$ver) { Stop-Harness "-FromVersion $from is not older than $ver" }
    $zip = Join-Path (Get-FilesDir $from) "chromite-swing-$from-windows-x64.zip"
    if (-not (Test-Path -LiteralPath $zip)) { Stop-Harness "the portable zip of $from is not in $(Get-FilesDir $from)" }
    $live = Get-FeedVersions (Get-LiveFeed)
    if ($live -notcontains $ver) { Stop-Harness "the live feed does not list $ver ($($live -join ', ')): deploy first" }
    $ocr = Join-Path $PSScriptRoot 'Read-ImageText.ps1'
    $root = Join-Path (Get-ReleaseDir $ver) 'update-notice'
    if (Test-Path -LiteralPath $root) {
        if (Get-CopyProcesses $root) { Stop-Harness "a copy from $root is running: stop it first" }
        Remove-Item -LiteralPath $root -Recurse -Force
    }
    Initialize-WinApi
    Set-StageStarted
    $logs = Join-Path (Get-ReleaseDir $ver) 'logs'
    New-Item -ItemType Directory -Force $logs | Out-Null
    $app = Join-Path $root 'app'
    $found = $null; $seconds = 0; $title = ''; $lastRead = ''
    $oldData = $env:CHROMITE_DATA
    try {
        Say "unzipping $zip"
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        [IO.Compression.ZipFile]::ExtractToDirectory($zip, $app)
        $exe = @(Get-ChildItem -LiteralPath $app -Recurse -Filter 'Chromite.exe' | Select-Object -First 1)
        if (-not $exe) { Stop-Harness 'no Chromite.exe in the portable zip' }
        $exeDir = $exe[0].DirectoryName
        $cfg = Join-Path $root 'configuration'
        Copy-Item -LiteralPath (Join-Path $exeDir 'configuration') $cfg -Recurse
        $env:CHROMITE_DATA = Join-Path $root 'data'
        $quote = { param($p) '"' + $p + '"' }
        Start-Process -FilePath $exe[0].FullName -WorkingDirectory $exeDir -ArgumentList @('-data', (& $quote (Join-Path $root 'workspace')),
            '-configuration', (& $quote $cfg), '-nosplash') | Out-Null
        Say "started $from from $exeDir (scratch -data, -configuration, CHROMITE_DATA); waiting up to $($script:WaitSeconds) s"
        $start = [DateTime]::UtcNow
        $png = Join-Path $logs "update-notice-$from-to-$ver.png"
        while (([DateTime]::UtcNow - $start).TotalSeconds -lt $script:WaitSeconds) {
            Start-Sleep -Seconds 10
            $cap = Save-CopyWindow $app $png
            if (-not $cap) { continue }
            $title = $cap.Title
            $read = @()
            foreach ($strip in $cap.Strips) {
                $lines = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $ocr -Path $strip 2>$null)
                Remove-Item -LiteralPath $strip -Force -ErrorAction SilentlyContinue
                $read += $lines
                if (-not $found) { $found = Find-NoticeLine $lines $ver }
            }
            $lastRead = ($read | Where-Object { $_ }) -join ' | '
            if ($found) { $seconds = [int]([DateTime]::UtcNow - $start).TotalSeconds; break }
        }
    } finally {
        foreach ($p in Get-CopyProcesses $app) {
            Say "stopping $($p.Name) (pid $($p.ProcessId)), started from the scratch copy"
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }
        $env:CHROMITE_DATA = $oldData
        for ($i = 0; $i -lt 10 -and (Test-Path -LiteralPath $root); $i++) {
            Start-Sleep -Seconds 2
            Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
        }
        if (Test-Path -LiteralPath $root) { Say "could not delete $root" } else { Say "deleted the scratch copy $root" }
    }
    if (Test-Path -LiteralPath $root) { Stop-Harness "the scratch copy $root is left" }
    # What OCR read last is part of the reason: a misread ("26.13" for "26.1.8") and a notice that is not there look the
    # same otherwise. The capture stays for a look.
    if (-not $found) { Stop-Harness "no 'New version $ver' read in the title bar of $from within $($script:WaitSeconds) s (window title '$title'; OCR read last: '$lastRead'; look at the capture $png)" }
    $korean = $found.Contains([string][char]0xC0C8)
    $text = if ($korean) { [string]::new([char[]](0xC0C8, 0x20, 0xBC84, 0xC804)) + " $ver" } else { "New version $ver" }
    Say "OK: the $from copy shows the update notice after about $seconds s (OCR: '$found'); capture $png"
    return [ordered]@{ fromVersion = $from; seconds = $seconds; text = $text; ocr = $found; capture = $png; windowTitle = $title }
}
