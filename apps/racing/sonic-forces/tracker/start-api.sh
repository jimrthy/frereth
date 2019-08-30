#! /bin/bash

# This script really isn't named all that well, due to the CIDER pieces

clj -A:dev -R:cider -J-Dtrace -m nrepl.cmdline \
  --middleware "[cider.nrepl/cider-middleware refactor-nrepl.middleware/wrap-refactor]"
