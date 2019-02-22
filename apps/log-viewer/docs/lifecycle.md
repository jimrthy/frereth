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

fork-shell! generates a new signing key for proving that messages came
from (or are meant to go to) this world. It exports the public portion
so the server side can verify message sources. It will use that public
portion as the key to find this world in the world-map.

Once those operations have completed, it sets up a partial around
spawn-worker! ond calls build-worker-from-exported-key with that.
