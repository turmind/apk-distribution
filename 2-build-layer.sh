#!/bin/bash
set -eo pipefail
gradle -q packageLibs
mv build/distributions/apk-distribution.zip build/apk-distribution-lib.zip