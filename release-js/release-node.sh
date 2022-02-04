#!/bin/sh

# remove shebang from clojurescript compiler output
tail -n +2 out/flureenjs.js > release-js/flureenjs.bare.js;
# add UMD wrapper https://groups.google.com/g/clojurescript/c/vNTGZht1XhE
cat release-js/umd-wrapper.prefix release-js/flureenjs.bare.js release-js/umd-wrapper.suffix > release-js/flureenjs.js;
# make sure we've got the correct deps specified
bb release-js/release_node.clj
