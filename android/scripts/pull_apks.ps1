# Pull an installed app (base + split APKs) from the phone and zip them as app.apks for apk-mitm.
#   .\pull_apks.ps1 cz.example.app [target folder]
# Find the package name:  adb shell pm list packages | Select-String example
param([Parameter(Mandatory=$true)][string]$Package, [string]$Dir = ".")
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $Dir | Out-Null
Push-Location $Dir
try {
    $paths = adb shell pm path $Package | ForEach-Object { $_ -replace '^package:','' }
    if (-not $paths) { throw "package $Package not installed (adb shell pm list packages)" }
    foreach ($p in $paths) { adb pull $p }
    if (Test-Path app.apks) { Remove-Item app.apks }
    Compress-Archive *.apk app.zip -Force
    Rename-Item app.zip app.apks
    # Flutter apps ignore the system proxy: check before patching
    $flutter = $false
    foreach ($a in Get-ChildItem *.apk) {
        $z = [IO.Compression.ZipFile]::OpenRead($a.FullName)
        if ($z.Entries | Where-Object { $_.Name -eq 'libflutter.so' }) { $flutter = $true }
        $z.Dispose()
    }
    Write-Host "pulled: $($paths.Count) APK(s) -> app.apks"
    if ($flutter) { Write-Warning "libflutter.so found: Flutter app, proxy + apk-mitm won't be enough (see CAPTURE.md)" }
} finally { Pop-Location }
