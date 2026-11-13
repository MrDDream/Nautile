; Nautile installer for Windows: per-user, no administrator rights needed.
; makensis /DVERSION=0.4.0 /DEXE=path\to\nautile.exe /DOUT=Nautile-0.4.0-setup.exe nautile.nsi
Unicode true
SetCompressor /SOLID lzma
!include "MUI2.nsh"

Name "Nautile ${VERSION}"
OutFile "${OUT}"
InstallDir "$LOCALAPPDATA\Programs\Nautile"
InstallDirRegKey HKCU "Software\Nautile" "InstallDir"
RequestExecutionLevel user
BrandingText "Nautile ${VERSION}"

!define MUI_ICON "..\nautile.ico"
!define MUI_UNICON "..\nautile.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\nautile.exe"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "French"
!insertmacro MUI_LANGUAGE "English"

!define UNINST "Software\Microsoft\Windows\CurrentVersion\Uninstall\Nautile"

Section "Nautile"
  SetOutPath "$INSTDIR"
  File "/oname=nautile.exe" "${EXE}"
  File "..\nautile.ico"
  WriteUninstaller "$INSTDIR\uninstall.exe"
  CreateShortCut "$SMPROGRAMS\Nautile.lnk" "$INSTDIR\nautile.exe" "" "$INSTDIR\nautile.ico"
  CreateShortCut "$DESKTOP\Nautile.lnk" "$INSTDIR\nautile.exe" "" "$INSTDIR\nautile.ico"
  WriteRegStr HKCU "Software\Nautile" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${UNINST}" "DisplayName" "Nautile"
  WriteRegStr HKCU "${UNINST}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST}" "Publisher" "Nautile"
  WriteRegStr HKCU "${UNINST}" "DisplayIcon" "$INSTDIR\nautile.ico"
  WriteRegStr HKCU "${UNINST}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKCU "${UNINST}" "URLInfoAbout" "https://mrddream.github.io/Nautile/"
  WriteRegDWORD HKCU "${UNINST}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST}" "NoRepair" 1
SectionEnd

; The data (%APPDATA%\Nautile) stays: it is encrypted and may be needed again.
Section "Uninstall"
  Delete "$INSTDIR\nautile.exe"
  Delete "$INSTDIR\nautile.ico"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\Nautile.lnk"
  Delete "$DESKTOP\Nautile.lnk"
  DeleteRegKey HKCU "${UNINST}"
  DeleteRegKey HKCU "Software\Nautile"
SectionEnd
