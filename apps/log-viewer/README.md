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

### CIDER

Open the connection:

    M-x cider-connect    ; specify port 43043 when prompted
    boot.user> (start-repl)

Then open a browser tab pointed to [Your App](http://localhost:10555/index)
