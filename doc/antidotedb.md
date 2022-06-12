# `fuzz-dist`'ing AntidoteDB

## Configuration

- git AntidoteDB/antidote master
  - default config
  - Erlang 24
- topologies
  - 1 * dc1n5
  - 5 * dc1n1
- Erlang client
  - `:antidote_crdt_set_aw`, `:antidote_crdt_counter_pn` datatypes
  - `static: :true` transactions
  - `:timeout 1_000`

----

## Workload

<table>
  <tr>
    <th>Phase</th>
    <th>Activity</th>
    <th>Duration</th>
  </tr>
  <tr>
    <th rowspan=2>workload</th>
    <td>~ 10 read/write (random mix) / sec</td>
    <td>60s<td>
  </tr>
  <tr>
    <td>fault(s) (random)</td>
    <td>0 <= random <= 30s <td>
  </tr>
  <tr>
    <th>heal</th>
    <td>resolve any faults</td>
    <td>immediate<td>
  </tr>
  <tr>
    <th>quiesce</th>
    <td>none</td>
    <td>10s<td>
  </tr>
  <tr>
    <th>final reads</th>
    <td>1 read / node</td>
    <td>immediate<td>
  </tr>
</table>

----

## Faults

### Types

- partition
- kill
- pause

[:one, :all, :majority, :minority, :minority_third, :majorities_ring :primaries]

Clock and time faults are not being tested.
(Need real VMs.)

### Duration

Currently testing with `NetTickTime` / 2 (e.g. 30s)

Would like to test at just less than `:nodedown`:

0 <= fault_duration <= `NetTickTime` - 1 (e.g. 59s)


----

## Verification

Uses Jepsen's [set-full](https://jepsen-io.github.io/jepsen/jepsen.checker.html#var-set-full) and [pn-counter](https://github.com/jepsen-io/maelstrom/blob/main/doc/04-crdts/02-counters.md) model/checkers for verification.

----

## Anomalies
<table>
  <tr>
    <th colspan=5 style="text-align:center;">Anomalies Observed/Reproducible</th>
  </tr>
  <tr>
    <td></td>
    <th>no faults</th>
    <th>partition</th>
    <th>pause</th>
    <th>kill (w/sync_log)</th>
  </tr>
  <tr>
    <th>Intra DC</th>
    <td>none</td>
    <td>none</td>
    <td>none</td>
    <td>none</td>
  </tr>
 <tr>
    <th>Inter DC</th>
    <td>none</td>
    <td>yes</td>
    <td>yes</td>
    <td>yes</td>
  </tr>
<table>

----

## Observations

With no faults, no anomalies have been observed. No composition, ordering, timing, or distribution of transactions has had an impact.

Intra, e.g. 1 * dcn5, networking is **significantly** more resilient than inter, e.g. 5 * dcn1. No pattern of faults have been able to introduce an observed anomaly with intra dc nodes.

Inter-dc faults
  - can be invalid results, but:
    - all transactions return `:ok`
    - no client errors, timeouts, etc
  - other times cluster appears to loose complete cohesion, no further writes are replicated

### Non-Safety

Client writes occasionally return `:aborted` when not expected, e.g. no faults, but doesn't affect valid outcome.
  
### pn-counter

- intra-dc partitioning
  - lots of client timeouts, but valid results
  - can crash stable_meta_data_server
- process kills
  - clients can occasionally `:error` when healed, but the counter remains valid

----

## Work In Progress

- add `:interactive` transactions
- add application aborted transactions
- better client error handling
  - discern between known/unknown failures
  - more descriptive logging

----

## Usage

Current docker compose is problematic, so a [workaround](https://github.com/nurturenature/jepsen-docker-workaround) has been developed.

Jepsen's LXC [environment](https://github.com/jepsen-io/jepsen#lxc) is the best.
