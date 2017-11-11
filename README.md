Overview
========

This is my personal pie-in-the-sky project that started with the
question "What would personal computing look like if it were designed from
the ground up for modern hardware, instead of built around limitations
that were state-of-the-art 40 years ago?"

I'm trying to question my assumptions at every layer of the stack, from
networking and file systems up through the front-end APIs we could be
using to build user interfaces that aren't based upon metaphors from
that same era.

It's probably silly and pointless. But I think the question's fascinating.

Inspirations
============

Somewhere around 2009, I started reading about the
experience of using Lisp machines and ran across Smalltalk.

Frereth started modestly, with plans to build an environment along
those lines.

Then I read Neal Stephenson's _Snowcrash_.

I was really still reeling from reading that when I ran across
[Alan Kay's Alice](https://www.alice.org/about/our-history/) project.

That led me to some of his other amazing ideas, maybe especially
the DynaBook.

Then I got involved with some pre-teen gamers, and I thought it would
be awesome to build a platform that makes it easy for them to build
their own games.


This is just Glue
=================

Most of what's in this folder is just cruft left over from early experiments.

The config/ folder contains a bunch of ansible scripts that I generally follow
in order to set up an environment for development. And maybe someday for running,
though I'm leaning more toward something like Docker containers these days.

Meat
====

I'm not sure how much sense it makes to split the pieces up like this. Or
whether I'm ignoring a fundamental question that needs to be asked about
whether the client/server architecture really makes sense.

[Frereth CP](https://github.com/jimrthy/frereth-cp) is the part I've been
working on most heavily lately. It's the networking piece that ties
everything else together. Although I have my doubts about the sensibility
of the basic idea.

[Frereth Common](https://github.com/jimrthy/frereth-common) is a foundation
layer for pieces that are shared by the others. It's my attempt at keeping
the whole thing DRY.

[Frereth Server](https://github.com/jimrthy/frereth-server) is the part
that I see as the bottom-end layer where the work happens, but end-users
never think about. This is the realm of databases and user authorization.

[Frereth Web Renderer](https://github.com/jimrthy/frereth-web) is the part
that's responsible for the actual user experience. I'm torn about whether
it makes sense for this to live inside a web server/browser pair. I started
with a native implementation
([Frereth Native Renderer]([https://github.com/jimrthy/frereth-renderer)),
decided I was spending too much time reinventing
many of the wheels that simply are not going to change, and decided to
go with the web version as a starting point.

And yet...one of my main goals is to avoid anything that resembles the
DOM. So we'll see.

[Frereth Client](https://github.com/jimrthy/frereth-client) is the part
that I intend to run almost all the actual code. These days, it's really
shaping up to be a library layer that runs "client-side" logic between
the renderer and the server.

I'm dubious about how it fits into the ecosystem, but
[Frereth Apps](https://github.com/jimrthy/frereth-app) is the place where
I think about a baseline for building apps to run on it. Along with
apps that seem like vaguely interesting prototypes to see how well the
concepts work. Although I'm also dabbling with apps in different branches
of this repo.

I'm strongly considering pulling the others into here so there's just a
central repo. I'm reluctant to do so because I think of this stack more
like a prototype for a communications protocol than anything else.

Status
======

Slightly more substantial than vaporware.


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
