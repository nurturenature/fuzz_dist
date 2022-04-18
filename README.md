# fuzz_dist

A framework and customization of Jepsen for BEAM applications.

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
  - test operations as Elixir `@behavior`s vs Clojure `(fn [])`
  - common user connects via websocket to nearest local node which is geo clustered architecture 
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
- partition duration ~ `NetTick(Time/Intensity)/HeartBeat`
- network faults ~ `GenServer.call(:timeout)`
- `:nodedown/up`, `{:error, :timeout}`
- ...

The BEAM was built to be observed. Combine these observations with Jepsen fault and application telemetry to directly link anomalies to metrics/spans/logs.

- `erlang:monitor(:nodes, :time/clock_services, ...)`
- Jepsen operations,injected faults ->
  - `Telemetry.execute(:trouble, :start)`
- ...

---

## Current Development

> "Always be suspicious of success."
> 
> -- <cite>Leslie Lamport</cite>

### Current AntidoteDB test:

- DB
  - build/install
  - configure cluster (5 * dc1n1)
  - capture logs
  
- client
  - Elixir `@behavior` using Erlang client API
  - `static:` transactions
  
- operations generator
  - random mix of add or read


- nemesis (fault injection)
  - partitions
    - majority/minority
    - majorities ring
    - bridge
  - isolate an individual data center

- model/checker
  - grow-set (Jepsen's internal `(set-full)`)

- basic stats/analysis

---

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


---

## Usage

`fuzz_dist` is designed to run in the same default environment that Jepsen
core develops/tests with: https://github.com/jepsen-io/jepsen#lxc

During local development, a directory structure of
```
$project/antidote   # with make rel
$project/fuzz_dist
```
is assumed and configured in `fuzz_dist/jepsen.fuzz_dist/util.clj`

This is an initial setup and a fuller solution is under development,
https://github.com/nurturenature/fuzz_dist/issues/38.

```bash
cd $project/fuzz_dist/jepsen.fuzz_dist
lein run test --workload demo

# has webserver to interact with test results
# http://localhost:8080
lein run serve
```
