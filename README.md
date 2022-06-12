# fuzz_dist

A framework and customization of Jepsen for BEAM applications.

Generative property testing of...

- actual application in a real cluster
- real environmental faults

... And validate the results with a model/checker.

----

## Using Jepsen

> "We should do a Jepsen test!"
> 
> -- <cite>Every Developer ever...</cite>

Using Jepsen meaningfully, like all dist-sys endeavors, is a meaningful commitment.

`fuzz_dist` is an effort to bring Jepsen and BEAM applications together in a more approachable way.

- generic'ish Elixir client
  - test operations as Elixir `@behavior`s vs Clojure `(fn [])`
  - common user connects via websocket to nearest local node which is geo clustered architecture 
- build/test/run environment pre-configured for BEAM
- more efficient/effective fault injection

----

## BEAM Aware

> “Know the rules well, so you can break them effectively.”
> 
> -- <cite>Dalai Lama XIV</cite>

Distributed Erlang is resilient. Applications can use these primitives to implement powerful algorithms.

Is the application's implementation standing firmly on and aligned with the BEAM?

`fuzz_dist` directs Jepsen's `nemesis` generators to fuzz at distributed Erlang boundary values, triggers.

Fuzz near
- partition duration ~ `NetTick(Time/Intensity)/HeartBeat`
- network faults ~ `GenServer.call(:timeout)`
- `:nodedown/up`, `{:error, :timeout}`
- ...

The BEAM was built to be observed. Combine these observations with Jepsen fault and application telemetry to directly link anomalies to metrics/spans/logs.

- `erlang:monitor(:nodes, :time/clock_services, ...)`
- Jepsen operations,injected faults ->
  - `Telemetry.execute(:trouble, :start)`
- ...

----

## Current Development, Usage

> "Always be suspicious of success."
> 
> -- <cite>Leslie Lamport</cite>

Please see the current AntidoteDB [tests](doc/antidotedb.md),
and an [environment](https://github.com/nurturenature/jepsen-docker-workaround) to run them.

----

## .next()

### Tests
- new types of faults
- more generative workflows
- improve analysis reports


### Client
- add `:interactive` transactions
  - ability to abort
- more comprehensive Telemetry

### Environment
- more ergonomic
- easier to package/deploy

---

## Jepsen Tests Are Living Tests

Fuzzing distributed systems productively, i.e. actually improving documentation/code, increasing understanding/confidence, in a sustainable way is both an art and a science.

The Jepsen community has lead the way in showing us how to do it effectively, ethically, and in a way that's truly fun.

Many thanks to @Aphyr and https://jepsen.io for https://github.com/jepsen-io/jepsen.
