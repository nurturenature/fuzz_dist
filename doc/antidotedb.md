# `fuzz-dist`'ing AntidoteDB

## Configuration

- git AntidoteDB/antidote master
  - default config
- topologies
  - 5 * dc1n1
  - 1 * dc1n5
- Erlang client
  - `:antidote_crdt_set_aw`, `:antidote_crdt_counter_pn` datatypes
  - `static: :true` transactions

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
    <td>0 <= random <= 16s <td>
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

### Duration

`0 <= partition_duration < NETTICK_TIME`

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
    <td colspan=2></td>
    <th colspan=4 style="text-align:center;">Faults</th>
  </tr>
  <tr>
    <td></td>
    <td></td>
    <th>none</th>
    <th>partition</th>
    <th>pause</th>
    <th>kill</th>
  </tr>
  <tr>
    <th rowspan=2>Intra DC</th>
    <th>observed</th>
    <td>no</td>
    <td>yes</td>
    <td>yes</td>
    <td>yes</td>
  </tr>
  <tr>
    <th>reproducible</th>
    <td></td>
    <td>no</td>
    <td>yes</td>
    <td>yes</td>
  </tr>
 <tr>
    <th rowspan=2>Inter DC</th>
    <th>observed</th>
    <td>yes</td>
    <td>yes</td>
    <td>yes</td>
    <td>yes</td>
  </tr>
  <tr>
    <th>reproducible</th>
    <td>no</td>
    <td>yes</td>
    <td>yes</td>
    <td>yes</td>
  </tr>
<table>

----

## Observations (***Very*** Preliminary)

Under normal, e.g. no faults, operating conditions no anomolies have been observed.

### g-set

- one of initial client adds often times out, but doesn't affect valid outcome
  
### pn-counter

- intra-dc paritioning
  - lots of client timeouts
  - but valid results
- inter-dc partitioning
  - no client errors, timeouts, etc, all `:ok`
  - but invalid results
