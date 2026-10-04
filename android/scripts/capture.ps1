# Capture only the app's backend and save it as <name>.flow (+ .har).
#   .\capture.ps1 "mapy\.(cz|com)|seznam\.cz" mapy
# The first argument is a regex of hosts to intercept; other hosts pass through untouched.
# (PowerShell keeps the | inside the regex; capture.bat run from PowerShell does not.)
param([Parameter(Mandatory=$true)][string]$Hosts, [string]$Name = "capture")
mitmweb --listen-port 8080 --allow-hosts $Hosts --set "save_stream_file=$Name.flow" --set "hardump=$Name.har"
