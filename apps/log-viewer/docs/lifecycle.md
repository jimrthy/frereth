# Preliminary handshake

Important note: so far, I'm just skipping authentication. That's
terribly sloppy, but I wanted to get the proof of concept slapped
together before I invested any time in that.

The design behind this is vaguely analogous to a CurveCP handshake.

Client starts up.

It configures a new Integrant System that starts with calling
`(frereth.apps.log-viewer.frontend.system/do-begin)`.

That creates several important pieces.

For now, the one that comes into play first is really the
`::web-socket/wrapper`.

It opens up a websocket to the webserver at /ws.

The webserver handler for that end-point is
`backend.web.routes/connect-renderer`.

That handler creates a new websocket and calls
renderer.lib/activate-session! on it.

That sits and waits (for half a second) for the browser to send a
message over the newly created websocket.

Back on the client, the ::session-socket/connection Component adds
an on-open handler to the websocket. That handler calls
notify-logged-in!

That sends the initial message to the browser. Currently, that
message is just a shared hard-coded array that I'm calling the
session-id.

The idea behind that was to use this as a verification that the
websocket is associated with a previously authenticated SESSION. In
hindsight, that idea seems pretty silly: the websocket request should
include the JWT in an authentication header.

That token needs to be passed along to the web socket handler, but it
should eliminate a network round-trip and work better with existing
standards rather than rolling my own.

In the meantime:

That session token is what the session was waiting for. It's vaguely
analogous to the Hello packet in a CurveCP handshake.

That takes us back to the server's renderer.lib/activate-session!

The first message triggers a call to login-finalized!

Part of the magic bypassing authentication sets up the magic session ID
as a pending session on System startup.

Once we get get this message, we update the session atom to mark this
session ::connection/active and set up the web socket so the
render.lib/on-message! multimethod handles incoming messages.

Back on the browser side, inside session-socket/init-key for its
::connection, I've skipped a couple more steps.

After the websocket's onopen handler calls notify-logged-in! it calls
worker/fork-shell! (A realistic implementation needs to wait until
a response to notify-login! tells it where to find the code for
the shell it wants to fork).

fork-shell! starts with a chain of Promises. It generates a new signing
key for proving that messages came
from (or are meant to go to) this world. It exports the public portion
so the server side can verify message sources. It will use that public
portion as the key to find this world in the world-map.

Once those operations have completed, it sets up a partial around
`spawn-worker!` and calls `build-worker-from-exported-key` with that.

`build-worker-from-exported-key` wasn't named well.

It calls:
* `send-message!` to notify the web server that the "shell" is forking
* `do-build-actual-worker` which will eventually spawn the Web Worker

`do-build-actual-worker` creates a go block that will wait for up to
a second on a trigger channel. It's very tempting to set this entire
thing up as another step in the chain of promises that started earlier
with building the public keys.

It's less tempting (though not drastically so) to wrap those calls
in core.async to hide that particular implementation detail.

That forking message passes control back to renderer.lib on the server.

That does some checks to verify that we don't currently have a world
under that pid (for this particular session), then sends back a
black-box cookie (this is where the similarity to the CurveCP handshake
really starts...I knew I'd mimicked it somewhere).

That cookie goes back to the browser and winds up unblocking the go block
from `do-build-actual-worker`.

That triggers a call to the partial we built around `spawn-worker` earlier,
which returns another future.

That future
* builds something like a CurveCP Initiate Packet
  This includes the server's preliminary Cookie
* signs it with the World's primary (secret) key
* puts together the URL for the web worker's code (this is a combination
  of the base-url, path-to-shell, Initiate Packet data, and a signature)
* creates a new classic Worker from that URL

That future updates the session manager's world-atom with the new cookie
and Web Worker.
