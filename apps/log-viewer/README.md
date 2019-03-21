# Frereth Log Viewer

Combing through Weald log files for my CurveCP translation is better
than writing raw strings to STDOUT, but it's still tedious.

This is an attempt to ease that burden.

## Usage

Currently, this is strictly REPL-based, going through Docker.

### Docker

This project and its dependencies are currently in enough flux that
downloading dependencies to build a docker container make it less
useful than usual.

At the command-line:

    docker build -t frereth/log-viewer .
    ./run-docker.sh
    # Watch log output (this only works if you only have 1
    # docker container running)
    docker logs -f `docker ps -q`

### Directly on your host

    ./boot.sh cider dev

### CIDER

Open the connection from the dev/user.clj ns/file.

    M-x cider-connect    ; specify localhost:43043 when prompted

This starts you in the user ns. Use `C-c C-k` to compile it.

    user> (setup-monitor! {:backend.system/routes {::routes/debug? true}
                           :backend.system/web-server {:backend.web.service/debug? true}})
    user> (go)

Then `M-x cider-connect-sibling-cljs` (the connection type is weasel)
and open a browser tab pointed to the
[log viewer](http://localhost:10555/index)

## Serious TODO items

I haven't put much thought into the order yet

### core.cljs needs to be idempotent

It's setting up a new World connection with every file save.

### Go back to CP handshake

This *is* the actual point.

### Rearrange namespaces

Move more/everything under frereth.apps.

### Switch to Pedestal

And re-present my case about websocket auth.

### Authentication

#### PAKE

Very tempting to pull in an SRP/OPAQUE library implemented in
javascript that's suitable for use in the browser.

https://github.com/simbo1905/thinbus-srp-npm seems promising.

See https://clojurescript.org/guides/javascript-modules for hints about
how to do this.

#### That's silly

Just start with simple Buddy JWE login. It's what all the cool kids are
doing.

### Authorization

Buddy seems like a good starting point

# License

Copyright 2018-2019 James Gatannah

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
