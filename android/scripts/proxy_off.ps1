# Turn the phone's proxy off again. Without this the phone has no internet when mitmproxy isn't running.
adb shell settings put global http_proxy :0
adb reverse --remove-all
Write-Host "Proxy OFF"
