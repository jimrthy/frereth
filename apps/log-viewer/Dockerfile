FROM clojure:boot-2.7.2-alpine

RUN apk add git

ENV VIEWER_DIR=/opt/frereth/log-viewer

RUN mkdir -p ${VIEWER_DIR}
WORKDIR ${VIEWER_DIR}

# Make the directories that build.boot requires
RUN mkdir -p src/clj/frontend && \
    mkdir src/cljc && \
    mkdir src/cljs && \
    mkdir src/less && \
    mkdir src/scss && \
    mkdir dev && \
    mkdir -p test/clj && \
    mkdir test/cljs

COPY .boot-jvm-options boot.properties profile.boot build.boot ./

# Pull in extras that go with day-to-day dev work.
RUN boot cider repl -s less

COPY src/cljs/ ./src/cljs
COPY src/clj/frontend/ ./src/clj/frontend
RUN boot less cljs --ids js/main target

COPY . .

# This still doesn't seem to cache all the dependencies from maven.
# Q: Why not?
# A: At least part of it stems from the fact that one of the pieces
# in cider dev adds a dependency on http-kit
RUN chmod u+x boot.sh && \
    boot lint

# Want to run local boot.sh to pick up local overrides.
# So override base image entrypoint to nothing.
ENTRYPOINT []

# This is a pretty safe bet for dev work
# Obviously need to arrange things differently for
# production.
# Then again, for production, we should probably start with this as a
# base builder image, create an uberjar, and then copy that into a
# second stage minimalist image.
CMD ./boot.sh cider dev

# Q: Worth running `apk update && apt-upgrade` in
# this vicinity?
