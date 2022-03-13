# fuzz_dist

A framework for and customization of Jepsen for BEAM applications.

Generative property testing of:

- actual application in a real cluster
- real environmental faults
- formally validate results against model

---

## Using Jepsen

> "We should do a Jepsen test!"
> 
> -- <cite>Every Developer</cite>

Using Jepsen is not a casual undertaking.
After over a decade of Jepsen providing immense value, there's still only one Kyle Kingsbury.

`fuzz_dist` is an exploratory effort to bring Jepsen and BEAM applications together in a more approachable way.

- generic'ish Elixir client
  - write test operations as Elixir @behaviors vs Clojure
- build/test/run environment pre-configured for BEAM
- more effecient/effective fault injection property generation 


Elixir client emits telemerty events linking Jepsen test operations (and failures!). Go from a Jepsen anomoly report to your apps telemetry/spans/logs.

---

## BEAM Aware

> “Know the rules well, so you can break them effectively.”
> 
> -- <cite>Dalai Lama XIV</cite>

Tune `Nemesis` generators to fuzz at the distributed Erlang boundary values, triggers.

Distributed Erlang is resilient and provides the application the capabilitiess for it to be the same.

Is the application standing firmly and correctly on the BEAM?

- partition durations
  - fuzz near HEARTBEAT timeouts to trigger `:NODEUP`, `:NODEDOWN`
- network faults
  - fuzz near `GenServer.call(:timeout)` to trigger timeouts, retries

Focus on distribute Erlang values and behaviors that support the correct implementation of CURE.

---

## Fault Injection or just The Real World?

Organic free range faults

Jepsen's Nemesis is not generating chaos, it's using your application as designed.

---

- Jepsen tests are living tests

---

> "Always be suspicious of success."
> 
> -- <cite>Dalai Lama XIV</cite>

Many thanks to @aphyr and https://jepsen.io for https://github.com/jepsen-io/jepsen.