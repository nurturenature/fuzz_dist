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

Using Jepsen is not a casual undertaking.
After over a decade of Jepsen providing immense value, there's still only one Kyle Kingsbury and selective industry adoption.

`fuzz_dist` is an exploratory effort to bring Jepsen and BEAM applications together in a more approachable way.

- generic'ish Elixir client
  - write test operations as Elixir `@behavior`s vs Clojure functions
- build/test/run environment pre-configured for BEAM
- more effecient/effective fault injection

---

## BEAM Aware

> “Know the rules well, so you can break them effectively.”
> 
> -- <cite>Dalai Lama XIV</cite>

Distributed Erlang is resilient. Applications can use these primitive to implement powerful algorithms.

`fuzz_dist` tunes Jepsen's `Nemesis` generators to fuzz at the distributed Erlang boundary values, triggers.  e.g.

- partition durations
  - fuzz near HEARTBEAT to trigger `:NODEUP`, `:NODEDOWN`
- network faults
  - fuzz near `GenServer.call(:timeout)` to trigger timeouts, retries

Is the application's implementation standing firmly on and aligned with the BEAM?

---

## Current Development

> "Always be suspicious of success."
> 
> -- <cite>Leslie Lamport</cite>

The current state of `fuzz_dist` is very much at the
```elixir
:hello
  |> world()
```
stage. The simple tests and configurations are helping to develop and evaluate the idea of a more approachable Jepsen testing of BEAM applications. They are not real tests of AntidoteDB.

---

### Jepsen Tests Are Living Tests


Many thanks to @aphyr and https://jepsen.io for https://github.com/jepsen-io/jepsen.

---

### For Next

Elixir client emits telemerty events linking Jepsen test operations (and failures!). Go from a Jepsen anomoly report to your apps telemetry/spans/logs.
