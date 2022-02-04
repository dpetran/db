#!/usr/bin/env bash

echo "compiling..."
make nodejs
echo "removing shebang..."
tail -n +2 out/flureenjs.js > out/content.js
echo "packaging..."
cat prelude.node out/content.js postlude.node > out/nodejs/flureenjs.js
