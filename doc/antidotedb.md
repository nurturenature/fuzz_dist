# `fuzz-dist`'ing AntidoteDB

## Configuration

- git AntidoteDB/antidote
  - default config
- topologies
  - 5 * dc1n1
  - 1 * dc1n5
- Erlang client
  - grow-only set CRDT data type
  - `static: :true` transactions

----

## Workload

<table>
  <tr>
    <th>Phase</th>
    <th>Activity</th>
    <th>Faults</th>
    <th>Duration</th>
  </tr>
  <tr>
    <td>preamble</td>
    <td>~ 1 add / node / sec</td>
    <td></td>
    <td>5s<td>
  </tr>
  <tr>
    <td>workload</td>
    <td>~ 10 add/read (random mix) / sec</td>
    <td>random</td>
    <td>60s<td>
  </tr>
  <tr>
    <td>heal</td>
    <td>resolve any faults</td>
    <td></td>
    <td><td>
  </tr>
  <tr>
    <td>final adds</td>
    <td>~ 1 add / sec</td>
    <td></td>
    <td>10s<td>
  </tr>
  <tr>
    <td>quiesce</td>
    <td></td>
    <td></td>
    <td>10s<td>
  </tr>
  <tr>
    <td>final reads</td>
    <td>1 read /node</td>
    <td></td>
    <td><td>
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

Uses Jepsen's [set-full](https://jepsen-io.github.io/jepsen/jepsen.checker.html#var-set-full) model/checker for verification.

----

## Anomalies Observed/Reproducible
<table>
  <tr>
    <th colspan=2></th>
    <th colspan=4 style="text-align:center;">Faults</th>
  </tr>
  <tr>
    <th></th>
    <th></th>
    <th>none</th>
    <th>partition</th>
    <th>pause</th>
    <th>kill</th>
  </tr>
  <tr>
    <th rowspan=2>Intra DC</th>
    <th>observed</th>
    <td>false</td>
    <td>true</td>
    <td>true</td>
    <td>true</td>
  </tr>
  <tr>
    <th>reproducible</th>
    <td></td>
    <td>false</td>
    <td>true</td>
    <td>true</td>
  </tr>
 <tr>
    <th rowspan=2>Inter DC</th>
    <th>observed</th>
    <td>true</td>
    <td>true</td>
    <td>true</td>
    <td>true</td>
  </tr>
  <tr>
    <th>reproducible</th>
    <td>false</td>
    <td>true</td>
    <td>true</td>
    <td>true</td>
  </tr>
<table>
