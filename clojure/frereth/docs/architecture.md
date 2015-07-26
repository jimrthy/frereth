# Robust Systems

Erlang is a battle-proven language that's designed
to keep phone systems working.

It's built around the principle that a bunch of
individual functional programs, which can only
interact with
each other using a very specific IPC mechanism, is
going to be much more stable than one monolithic
program with constantly mutating state.

Clojure is built around the same principles. The
only major philosophical difference between it
and erlang is that it has a few optimizations for
the cases where the "individual programs" happen
to be running in the same process on one piece
of hardware.

# Programming Language Optimizations
