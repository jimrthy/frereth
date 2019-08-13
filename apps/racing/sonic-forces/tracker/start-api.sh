#! /bin/bash

clj -A:dev -R:cider -J-Dtrace -m nrepl.cmdline --middleware "[cider.nrepl/cider-middleware]"
