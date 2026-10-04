# Patch app.apks with apk-mitm (trust user CAs, remove pinning) and install it on the phone.
#   .\patch_apk.ps1 cz.example.app [-ApktoolJar apktool.jar] [-NoInstall]
# Uninstalling the original deletes the app's data (different signature).
param([Parameter(Mandatory=$true)][string]$Package, [string]$ApktoolJar = "apktool.jar", [switch]$NoInstall)
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path $ApktoolJar) { npx apk-mitm app.apks --apktool $ApktoolJar }
else {
    Write-Warning "$ApktoolJar not found: using apk-mitm's bundled apktool (may be too old, get the newest from github.com/iBotPeaches/Apktool/releases)"
    npx apk-mitm app.apks
}
if ($LASTEXITCODE -ne 0) { throw "apk-mitm failed" }
if (Test-Path app-patched.zip) { Remove-Item app-patched.zip }
Rename-Item app-patched.apks app-patched.zip
Expand-Archive app-patched.zip -DestinationPath patched -Force
if ($NoInstall) { return }
adb uninstall $Package
adb install-multiple (Get-ChildItem patched -Recurse -Filter *.apk).FullName
