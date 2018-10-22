#! /bin/sh

echo Trying to mount "`pwd`/src"
# 9001:  websocket REPL
# 9009:  nREPL
# 10555: web server
# 43043: nrepl port
# 43140: Boot reload port
docker run -d \
       -p 9001:9001 \
       -p 9009:9009 \
       -p 10555:10555 \
       -p 43043:43043 \
       -p 43140:43140 \
       --mount type=bind,source="`pwd`/src",target=/opt/frereth/log-viewer/src \
       frereth/log-viewer:latest
