; Chromite - Database Workbench
; Copyright (C) 2026 Chromite contributors
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;
; The old-name starter chromite-swing.exe: starts Chromite.exe next to it with the same arguments and ends.
; REMOVE IN THE NEXT VERSION (with LEGACY_LAUNCHER in chromite.nsi, build-installer.ps1 and release.yml):
; docs/chromite/launcher-name.md "다음 버전에서 지울 것".
;
; Design: the launcher was renamed from chromite-swing to Chromite (app change #65). The updater of 0.1.4 and
; earlier starts <install folder>\chromite-swing.exe again after the setup has run (UpdateHandOver.startApp and the
; PowerShell helper of UpdateApplier, the path recorded from eclipse.launcher before the update). The setup puts this
; starter under the old name for one version, so that the restart after the update ends with Chromite.exe running
; (not a second copy of the Eclipse launcher, which would run the app as chromite-swing.exe). A taskbar pin of the
; old exe keeps working for the same version. Silent: no window, no file, no registry; exit code 0 when Chromite.exe
; was started, 2 when it could not be.
;
; Defines (from build-installer.ps1): VERSION, OUT_FILE, ICON.
;
; Change history:
; - 2026-11-12 Created (app change #65).

Unicode true
ManifestDPIAware true
RequestExecutionLevel user
SilentInstall silent
SetCompressor /SOLID lzma

!macro Require NAME
  !ifndef ${NAME}
    !error "${NAME} is not defined: build with tools/installer/build-installer.ps1"
  !endif
!macroend
!insertmacro Require VERSION
!insertmacro Require OUT_FILE
!insertmacro Require ICON

!define PRODUCT "Chromite"
!define TARGET "Chromite.exe"

Name "${PRODUCT}"
OutFile "${OUT_FILE}"
Icon "${ICON}"

VIProductVersion "${VERSION}.0"
VIFileVersion "${VERSION}.0"
VIAddVersionKey /LANG=0 "ProductName" "${PRODUCT}"
VIAddVersionKey /LANG=0 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=0 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=0 "FileDescription" "${PRODUCT}"
VIAddVersionKey /LANG=0 "CompanyName" "Seizia"
VIAddVersionKey /LANG=0 "LegalCopyright" "Copyright (C) 2026 Chromite contributors"
VIAddVersionKey /LANG=0 "OriginalFilename" "chromite-swing.exe"

!include "FileFunc.nsh"
!include "LogicLib.nsh"

Section
  ${GetParameters} $0
  ; the working folder of Chromite: this folder, as the Start menu shortcut and the updater give it
  SetOutPath "$EXEDIR"
  ClearErrors
  Exec '"$EXEDIR\${TARGET}" $0'
  ${If} ${Errors}
    SetErrorLevel 2
  ${EndIf}
SectionEnd
