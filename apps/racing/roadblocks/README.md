## Goal

Flesh out the basic ideas behind frereth and [dis]prove the fundamental
concepts.

## Notes about this file

Most of this README was generated automatically by creating a new
shadow-cljs project.

Big chunks of the pieces about using shadow-cljs have been copied nearly
verbatim from the Shadow CLJS User Guide.

I think that's OK, but I'm not positive.

## Installation

### npm

This adds the package to your devDependencies:

`npm install --save-dev shadow-cljs`

`npm install -g shadow-cljs`

### yarn

`yarn add --dev shadow-cljs`
`yarn global add shadow-cljs`

### As a library

TODO:

Could also just add `[thheller/shadow-cljs "2.8.83"]` (or whatever
version is recent) to any other clojure JVM tool, such as `lein`,
`boot`, or `deps.edn`.

That will let me combine the server components. It looks like fulcro may
have set things up the way I want.

TODO: look into the sft folder to see how they set up deps.edn.

## Shadow CLJS usage

If you install it "globally," you can use the `shadow-cljs` command directly.

### Common Commands

Compile a build once and exit:

`$ shadow-cljs compile app`

Compile and watch:

`$ shadow-cljs watch app`

Connect to the REPL for the build (available while watch is running):

`$ shadow-cljs cljs-repl app`

Connect to standalone node REPL:

`$ shadow-cljs node-repl`

Run a release build for production use:

`$ shadow-cljs release app`

Try to get help with release issues caused by :advanced compilation
mode:

`$ shadow-cljs check app
$ shadow-cljs release app --debug`

### Server Mode

Run this in a stand-alone terminal so you don't have to wait for the
clojure/JVM to start up constantly.

Automatically gets started by commands like `watch`.

Need to restart it when you update `shadow-cljs.edn`

`$ shadow-cljs server`

Optionally:

`$ shadow-cljs cljs-repl` puts you into a REPL that allows you to
control the server directly from clojure.


You can also run the server in the background:

`$ shadow-cljs start
$ shadow-cljs restart
$ shadow-cljs stop`

Once any server is running, every other command will use that and run
much faster.


### Build/REPL and Hot Code Reload

#### Specific Build Targets

Mostly, you'll want to run builds for specific target.

##### Short Version

`$ shadow-cljs clj-repl`

`[2:0]~shadow.user=> (shadow/watch :app)

Read on for more generic details that probably don't belong in here.

##### CLI

`$ shadow-cljs watch ${build-id}`

Then, in a different terminal:

`$ shadow-cljs cljs-repl ${build-id}`

Or connect over CIDER, which is way out of scope.

##### Integrated REPL

`$ shadow-cljs clj-repl`

This allows you to control the shadow-cljs server and run all the other
build commands directly. You can upgrade it to a cljs REPL any time you
want/need:

`...
[2:0]~shadow.user=> (shadow/watch :browser)
...
:watching
[2:0]~shadow.user=> (shadow/repl :browser)
[2:1]~cljs.user=>
`

Enter `:repl/quit` to switch back to the clj REPL. The `watch` will
continue.

It's possible to run multiple `watch` workers in paralell. Connect and
disconnect to cljs REPLs as needed.

The `shadow.cljs.devtools.api` ns contains functions that more-or-less
map directly to the CLI. By default, it is aliased as `shadow`.

`;; shadow-cljs watch worker
(shadow/watch :foo)`

`;; shadow-cljs watch foo --verbose
(shadow/watch :foo {:verbose true})`

`;; shadow-cljs browser-repl
(shadow/browser-repl)`

#### node.js, raw

You may get some use out of opening a minimal Node REPL:

`shadow-cljs node-repl`

Byte-code does not get updated when your source files change. There is
no hot code reload. But you have access to all your code.

#### Browser, raw

It may be more useful to set up code for this project running in a
browser:

`shadow-cljs browser-repl`

That has most of the same capabilities and limitations as the Node REPL,
but at least it's running in a browser.

## Available Scripts

In the project directory, you can run:

### `npm start`

Runs the app in development mode.<br>
Open [http://localhost:3000](http://localhost:3000) to view it in the browser.
The page will reload if you make edits.

Builds use [Shadow CLJS](https://github.com/thheller/shadow-cljs) for
maximum compatibility with NPM libraries. You'll need a
[Java SDK](https://adoptopenjdk.net/) (Version 8+, Hotspot) to use
it. <br>
You can
[import npm libraries](https://shadow-cljs.github.io/docs/UsersGuide.html#js-deps)
using Shadow CLJS. See the
[user manual](https://shadow-cljs.github.io/docs/UsersGuide.html) for
more information.

### `npm run cards`

Runs the interactive live development enviroment.<br>
You can use it to design, test, and think about parts of your app in isolation.

This environment uses [Devcards](https://github.com/bhauman/devcards)
and
[React Testing Library](https://testing-library.com/docs/react-testing-library/intro).

### `npm run build`

Builds the app for production to the `public` folder.<br>
It correctly bundles all code and optimizes the build for the best performance.

Your app is ready to be deployed!

## Other useful scripts

### `null` and `npm run e2e`

You can use `null` to run tests a single time, and `npm run e2e` to run
the end-to-end test app.

(Q: Can you? I don't have `null` available)

`npm test` launches tests in interactive watch mode.<br>

See the ClojureScript
[testing page](https://clojurescript.org/tools/testing) for more
information. E2E tests use [Taiko](https://github.com/getgauge/taiko)
to interact with a headless browser.

### `npm run lint` and `npm run format`

`npm run lint` checks the code for known bad code patterns using
[clj-kondo](https://github.com/borkdude/clj-kondo).

`npm run format` will format your code in a consistent manner using
[zprint-clj](https://github.com/clj-commons/zprint-clj).

### `npm run report`

Make a report of what files contribute to your app size.

Consider
[code-splitting](https://code.thheller.com/blog/shadow-cljs/2019/03/03/code-splitting-clojurescript.html)
or using smaller libraries to make your app load faster.

### `npm run server`

Starts a Shadow CLJS background server.<br>
This will speed up starting time for other commands that use Shadow CLJS.

## Useful resources

Clojurians Slack http://clojurians.net/.

CLJS FAQ (for JavaScript developers) https://clojurescript.org/guides/faq-js.

Official CLJS API https://cljs.github.io/api/.

Quick reference https://cljs.info/cheatsheet/.

Offline searchable docs https://devdocs.io/.

VSCode plugin https://github.com/BetterThanTomorrow/calva.
