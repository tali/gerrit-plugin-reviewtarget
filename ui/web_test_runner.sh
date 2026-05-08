#!/bin/bash

set -euo pipefail
./$1 --config $2 \
  --test-files 'plugins/reviewtarget/ui/_bazel_ts_out_tests/*_test.js' \
  --ts-config="plugins/reviewtarget/ui/tsconfig.json"
