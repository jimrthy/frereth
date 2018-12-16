# Frereth Log Viewer

Combing through Weald log files for my CurveCP translation is better
than writing raw strings to STDOUT, but it's still tedious.

This is an attempt to ease that burden.

## Usage

Currently, this is strictly REPL-based, going through Docker.

### Docker

At the command-line:

    docker build -t frereth/log-viewer .
    ./run-docker.sh
    # Watch log output
    docker logs -f `docker ps -q`

### Directly on your host

    boot cider repl

### CIDER

Open the connection:

    M-x cider-connect    ; specify port 43043 when prompted
    boot.user> (start-repl)

Then open a browser tab pointed to [Your App](http://localhost:10555/index)

## Serious TODO items

Need to pull in an SRP library implemented in javascript that's suitable
for use in the browser.

https://github.com/simbo1905/thinbus-srp-npm seems promising.

See https://clojurescript.org/guides/javascript-modules for hints about
how to do this.

# License

Copyright 2018 James Gatannah

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
