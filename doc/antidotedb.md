# `fuzz-dist`'ing AntidoteDB

## Configuration

- git AntidoteDB/antidote master
  - default config
  - Erlang 24
- topologies
  - 5 * dc1n1
  - 1 * dc1n5
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

0 <= fault_duration <= `NetTickTime` - 1

(Currently testing with `NetTickTime` / 2.)

----

## Verification

Uses Jepsen's [set-full](https://jepsen-io.github.io/jepsen/jepsen.checker.html#var-set-full) and [pn-counter](https://github.com/jepsen-io/maelstrom/blob/main/doc/04-crdts/02-counters.md) model/checkers for verification.

----

## Anomalies
<table>
  <tr>
    <th colspan=6 style="text-align:center;">Anomalies Observed/Reproducible</th>
  </tr>
  <tr>
    <td></td>
    <td></td>
    <th>no faults</th>
    <th>partition</th>
    <th>pause</th>
    <th>kill</th>
  </tr>
  <tr>
    <th rowspan=2>Intra DC</th>
    <th>observed</th>
    <td>none</td>
    <td>none</td>
    <td>none</td>
    <td>yes</td>
  </tr>
  <tr>
    <th>reproducible</th>
    <td></td>
    <td></td>
    <td></td>
    <td>yes</td>
  </tr>
 <tr>
    <th rowspan=2>Inter DC</th>
    <th>observed</th>
    <td>none</td>
    <td>yes</td>
    <td>yes</td>
    <td>yes</td>
  </tr>
  <tr>
    <th>reproducible</th>
    <td></td>
    <td>yes</td>
    <td>yes</td>
    <td>yes</td>
  </tr>
<table>

----

## Observations (***Very*** Preliminary)

With no faults, no anomalies have been observed. No composition, ordering, timing, or distribution of transactions has had an impact.

Intra, e.g. 1 * dcn5, networking is **significantly** more resilient than inter, e.g. 5 * dcn1. No pattern of partitioning was able to introduce an observed anomaly.

Inter-dc partitioning
  - can be no client errors, timeouts, etc, all `:ok`
    - but invalid results
  - other times cluster appears to loose complete cohesion

`:antidote_crdt_set_aw` and `:antidote_crdt_counter_pn` datatypes appear to behave similarly re no faults, partition and pause. `:antidote_crdt_counter_pn` is more resilent to process kills, the clients `:error` and the counter remains valid.

### g-set

- client adds periodically time out, `:timeout 1_000` but doesn't affect valid outcome
  - even w/o faults
  
### pn-counter

- intra-dc partitioning
  - lots of client timeouts, but valid results
  - can crash stable_meta_data_server

### Work In Progress

- add `:interactive` transactions
- add aborting transactions
- better client error handling
  - discern between known/unknown failures
  - descriptive logging
