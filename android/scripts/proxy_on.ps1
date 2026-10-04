# Route the phone's traffic over USB to mitmproxy on this PC (port 8080).
adb reverse tcp:8080 tcp:8080
adb shell settings put global http_proxy 127.0.0.1:8080
Write-Host "Proxy ON. Run capture.bat, and install the CA from http://mitm.it once. Turn off with proxy_off.ps1"
