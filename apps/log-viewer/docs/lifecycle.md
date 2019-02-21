# Message Flows

The design behind this is vaguely analogous to a CurveCP handshake.

Client starts up.

It configures a new Integrant System that starts with calling
`(frereth.apps.log-viewer.frontend.system/do-begin)`.

That creates several important pieces, which are all important.

For now, the one that comes into play first is really the
`::web-socket/wrapper`.

It opens up a websocket to the webserver at /ws.

This is in the same ballpark as a CurveCP `Hello` Packet.

The webserver handler for that end-point is
`backend.web.routes/connect-renderer`.

That handler creates a new websocket and calls
renderer.lib/activate-session! on it.

That sits and waits (for half a second) for the browser to send a
message over the newly created websocket.
