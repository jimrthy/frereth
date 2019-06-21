# Privileged Handlers

This is a note I jotted down about the defhandler macro in the clj
version of renderer.handlers. I don't want to lose it in the bowels of
source code.

Subscribed to current System's event-bus
These have access to things like the session atom
These probably belong in a generic frereth.apps.lib workspace.

It's tempting to write a macro to generate these and the
integrant methods.
That's doable but doesn't seems worth the effort.
Are there any other places involved in setting this up?
Or other places that need to do the same sort of thing?
(The
more there are, the more justification there is for fancier
macrology).

These seem dubious and extra-convoluted.
Having them as their own function makes total sense. But is it
really worth the extra convolution of keeping them isolated on an
event bus?

This approach (with macros that add arbitrary values into the
lexical scope) doesn't make sense here, but it probably does for
higher-level bits that the actual games/worlds use.
The automatic logging and exception handling around the handler
really is nice.
But, at this level, the handler should return a combination of
a) functions to apply to update the state of this session
b) messages to publish back to the browser side
Even that much abstraction may be overkill.
Q: Will this layer of the messaging protocol ever need more than
a req/rep interchange?
Keeping in mind that the Client can always send whichever messages
it wants.
Well...it can't, until the Renderer has Joined.
Which adds another step to this interchange.
Need to Fork the world, yes.
Also need to Join it to exchange messages.
Or come up with some concept of running daemons.
A database server like mysql is the first, most obvious example
that comes to mind.
It doesn't have to run in a privileged mode, even though it usually
does.
Daemons associated with system accounts are one thing.
tmux is a different matter.
Q: How does that work?
(Yes, this is for off-topic, but still important)

FIXME: Really shouldn't expose either session-id
or session-atom to the handler.
Except that access to the session-atom is a major
part of the point. These are allowed to manage the session-atom because
they're deliberately isolated and limited.

They really should be converted into plain functions rather than
event-bus listeners, but the basic idea still seems pretty decent.
