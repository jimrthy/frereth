# Overview

This directory structure is odd, at best.

Like the rest of this project, it's evolved organically while I found 
an hour here or there to invest, rather than getting a lot of thought 
and planning.

## backend/ 

This covers the core web server framework catch-all sort of stuff.

It includes the actual entrypoint, the System (in the Stuart Sierra
sense) definition, the web server, and an internal event bus.

## client/

This is the part that's supposed to handle communication between the
Renderer and the Server.

In a lot of ways, this is supposed to be something like an RPC proxy.

## frereth/

I've started moving pieces under here, under the theory that the
packaging needs at least a little hierarchy.

## frontend/

This is really a placeholder for any macros the frontend might need.

## renderer/

This is the logical server-side portion that's supposed to handle the
presentation.

At least in theory.

In practice, this really holds pieces that would probably have made
more sense in the web server.

Except for lib, which is where this started. The other pieces have
mostly gotten refactored out of there as it grew far too large.

## server/

This is supposed to be the "bottom-end" layer that handles the actual
shared world implementation. Everything from the game rules to 
database interactions. This includes the AI.

There's obviously quite a bit more to do here.

Then again, that's true of all these pieces.

## server-sample/

This is really a placeholder for holding the server's private key.

It's a leftover from my original translation work on CurveCP.
