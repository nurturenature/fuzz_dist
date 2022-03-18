# fuzz_dist

A framework for and customization of Jepsen for BEAM applications.

Generative property testing of...

- actual application in a real cluster
- real environmental faults

... And validate the results with a model/checker.

---

## Using Jepsen

> "We should do a Jepsen test!"
> 
> -- <cite>Every Developer ever...</cite>

Using Jepsen meaningfully, like all dist-sys endeavors, is a meaningful commitment.

`fuzz_dist` is an effort to bring Jepsen and BEAM applications together in a more approachable way.

- generic'ish Elixir client
  - test operations as Elixir `@behavior`s vs Clojure `(fn )`
  - classic user connects via websocket to nearest local node which is geo clustered architecture 
- build/test/run environment pre-configured for BEAM
- more efficient/effective fault injection

---

## BEAM Aware

> “Know the rules well, so you can break them effectively.”
> 
> -- <cite>Dalai Lama XIV</cite>

Distributed Erlang is resilient. Applications can use these primitives to implement powerful algorithms.

Is the application's implementation standing firmly on and aligned with the BEAM?

`fuzz_dist` directs Jepsen's `nemesis` generators to fuzz at distributed Erlang boundary values, triggers.

Fuzz near
- partition duration ~ `NetTickTime`
  - -> `:nodedown`, `:nodeup`
- network faults ~ `GenServer.call(:timeout)`
  - -> `{:error, :timeout}`
- ...

The BEAM was built to be observed. Add these observations during fault injection to the application's OpenTelemetry. Link anomalies to metrics/spans/logs of both the application and the BEAM.

- `erlang:monitor(:nodes, :time/clock_services, ...)`
- Jepsen operations,injected faults ->
  - `Telemetry.execute(:trouble, :start)`
- ...

---

## Current Development

> "Always be suspicious of success."
> 
> -- <cite>Leslie Lamport</cite>

The current state of `fuzz_dist` is very much at the
```elixir
:hello
  |> world("!")
```
stage. The simple tests and configurations are helping to develop and evaluate the idea of a more approachable Jepsen testing of BEAM applications.

Current AntidoteDB test shows:
- setup/teardown
  - build/install
  - configure cluster (5 * dc1n1)
  - capture logs
- client
  - Elixir `@behavior` using Erlang client API
  - `static:` transactions
- operations generator
  - random mix of adds/reads across cluster
- nemesis (fault injection)
  - majority/minority partition, random membership
  - isolate an individual data center
- model/checker
  - grow-set
- basic stats/analysis

---

## .next()

### Tests
- make workflows generative, config property vaues/ranges
- improve analysis reports


### Client
- add `:interactive` transactions
  - and ability to abort
- more comprehensive Telemetry

### Environment
- more erogonmic
- easier to package/deploy

---

## Jepsen Tests Are Living Tests


Many thanks to @aphyr and https://jepsen.io for https://github.com/jepsen-io/jepsen.

---

