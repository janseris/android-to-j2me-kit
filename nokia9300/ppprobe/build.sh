#!/bin/sh
# Builds bin/ppprobe.jar and bin/ppprobe.sis. Needs a JDK (javac -target 1.2 against any rt.jar:
# the code keeps to Java 1.3 APIs, which Personal Profile 1.0 has) and makesis from the S80 toolchain.
set -e
cd "$(dirname "$0")"
rm -rf classes && mkdir -p classes bin
javac -source 1.2 -target 1.2 -Xlint:-options -encoding UTF-8 -d classes src/ppprobe/*.java
jar cfm bin/ppprobe.jar manifest.mf -C classes .
cd sis && makesis ppprobe.pkg ../bin/ppprobe.sis
ls -l ../bin
