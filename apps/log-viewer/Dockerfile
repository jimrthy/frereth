
# FIXME: Go back to this base image
#FROM clojure:boot-2.7.2-alpine
# This is really only until I get curve-cp into a state that's releasable
# from clojars
FROM frereth/curve-cp:latest

RUN apk add git

ENV VIEWER_DIR=/opt/frereth/log-viewer

RUN mkdir -p ${VIEWER_DIR}
WORKDIR ${VIEWER_DIR}

# Make the directories that build.boot requires
RUN mkdir -p src/clj && \
    mkdir src/cljc && \
    mkdir src/cljs && \
    mkdir src/less && \
    mkdir src/scss && \
    mkdir dev && \
    mkdir -p test/clj && \
    mkdir test/cljs

COPY .boot-jvm-options boot.properties profile.boot build.boot ./

# The way this is set up is obnoxious.
# Any changes to the backend mean downloading a slew of new
# dependencies when we run `boot dev` in the next step.
# Need this because build.boot requires backend.boot at the
# top level, and its start-app task depends on backend.main,
# which depends on everything else in the web's "backend."
COPY src/clj/backend/ ./src/clj/backend/

# Pull in extras that go with day-to-day dev work.
RUN boot cider repl -s less

# Honestly, this should probably happen in multiple steps.
# Copy over the *.clj and *.cljc files, do something to cache
# their dependencies. Then the *.cljs and cache theirs.
# Or maybe vice versa. Since the server portions seem likely
# to change more often than the front-end, after the churn
# settles down and the applications change to something served
# by the Server.
COPY . .

RUN chmod u+x boot.sh

# These still don't seem to cache all the dependencies from maven.
# Q: Why not?
# TODO: Copy just the front-end pieces, run `boot less cljs`,
# then copy the rest and run `boot lint`.
RUN boot less cljs --ids js/main target && \
    boot lint

# Want to run local boot.sh to pick up local overrides.
# So override base image entrypoint to nothing.
ENTRYPOINT []

# This is a pretty safe bet for dev work
CMD ./boot.sh cider dev

# Q: Worth running `apk update && apt-upgrade` in
# this vicinity?
