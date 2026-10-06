@echo off
rem Builds bin\probe9300.jar with the JDK 8, stub jars and ProGuard bundled in pubtran-j2me
rem (cloned next to this kit: phoneapk\pubtran-j2me). Set SDK to use another copy.
cd /d "%~dp0"
if "%SDK%"=="" set SDK=..\..\..\pubtran-j2me\sdk
set J=%SDK%\jdk8u504-b01\bin
set L=%SDK%\lib
if not exist "%J%\javac.exe" (echo JDK not found in %J% & exit /b 1)
if exist classes rmdir /s /q classes
mkdir classes bin 2>nul
"%J%\javac" -source 1.2 -target 1.2 -Xlint:-options -encoding UTF-8 -bootclasspath "%L%\cldcapi11.jar;%L%\midpapi20.jar;%L%\jsr82.jar" -d classes src\probe\*.java || exit /b 1
"%J%\jar" cfm bin\in.jar manifest.mf -C classes . || exit /b 1
"%J%\java" -jar "%SDK%\proguard.jar" -injars bin\in.jar -outjars bin\out.jar -libraryjars "%L%\midpapi20.jar" -libraryjars "%L%\cldcapi11.jar" -libraryjars "%L%\jsr82.jar" -microedition -target 1.2 -dontoptimize -dontobfuscate -dontnote -keep "public class probe.Probe" || exit /b 1
del bin\in.jar
move /y bin\out.jar bin\probe9300.jar >nul
for %%F in (bin\probe9300.jar) do set SIZE=%%~zF
(
echo MIDlet-1: Probe 9300, , probe.Probe
echo MIDlet-Jar-Size: %SIZE%
echo MIDlet-Jar-URL: probe9300.jar
echo MIDlet-Name: Probe 9300
echo MIDlet-Vendor: android-to-j2me-kit
echo MIDlet-Version: 3.4
echo MicroEdition-Configuration: CLDC-1.1
echo MicroEdition-Profile: MIDP-2.0
) > bin\probe9300.jad
echo bin\probe9300.jar %SIZE% bytes
