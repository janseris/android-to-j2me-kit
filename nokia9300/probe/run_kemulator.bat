@echo off
rem Runs the probe in KEmulator from pubtran-j2me (cloned next to this kit).
rem Tests servers, URLs and image formats quickly; NOT the phone's TLS patch, memory or Bluetooth.
"%~dp0..\..\..\pubtran-j2me\tools\kemnnx64\KEmulator_Console.bat" "%~dp0bin\probe9300.jar"
