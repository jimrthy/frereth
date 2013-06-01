Overview
========

Put the various pieces of frereth into play.

This is really just me "throwing a rope over the gorge" at this
point. (Yes, I'm totally stealing the idea from 0MQ).

This is just Glue
=================

Everything interesting should happen in other projects.

Running
=======

It'd be nice to make something simple to automate this.

Until then:

Configure the various pieces to listen/use free ports to communicate. There'll
be some sort of configuration file in each directory. They client needs to
ports specified: one for the renderer, the other for the stand-alone server.

Open 3 terminals. In each:
1. Start the frereth stand-alone local server.
2. Start the frereth client.
3. Start the frereth renderer.

After that, magic should happen.