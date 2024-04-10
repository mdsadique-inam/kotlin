#!/bin/bash
set -x #echo on

java -jar ./build/libs/abi-comparator-2.0.255-SNAPSHOT.jar jar ./abi-comparator-2.0.255-SNAPSHOT.jar ./build/libs/abi-comparator-2.0.255-SNAPSHOT.jar ./report.html
#java -jar ./build/libs/abi-comparator-2.0.255-SNAPSHOT.jar dir /Users/aleksandr.shalygin/IdeaProjects/kotlin/libraries/tools/abi-comparator/build /Users/aleksandr.shalygin/IdeaProjects/kotlin/libraries/tools/abi-comparator/build ./reportDir
