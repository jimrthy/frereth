#!/usr/bin/bash

# This can't be right.
# Aside from basically being pointless. Still, the idea halfway fits.
cd ../../frereth-client; lein repl &
cd ../frereth-server; lein repl &

# And this is pretty much where it falls apart.
echo "Now start the Frereth Renderer with your common lisp installation.
