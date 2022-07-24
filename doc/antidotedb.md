# `fuzz-dist`'ing AntidoteDB

## Configuration

- git AntidoteDB/antidote master
  - default config (plus `sync_log true` when nemesis is process kill)
  - Erlang 24
- topologies
  - intra dc -> 1 * dc1n5 (single data center with 5 nodes)
  - inter dc -> 5 * dc1n1 (5 data centers with a single node)
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
    <td>~ 10 reads and 10 writes (random sequence) / sec / key</td>
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
    <td>low rate of reads<br />
        no writes</td>
    <td>10s<td>
  </tr>
  <tr>
    <th>final reads</th>
    <td>1 read / node / key</td>
    <td>immediate<td>
  </tr>
</table>

----

## g-set

Generator adds a sequential integer to a single set.

## pn-counter

Generators try different counter strategies:
- random values
- grow-only
- swinging between p's and n's

Uses largish unique random values to help in checking the results.
E.g. when calculating all of the possible eventually states to evaluate a read, larger values produce a sparser set of possible ranges than +/-1.

----

## Faults

### Types

- none
- partition
- kill
- pause
- targets: [:one, :all, :majority, :minority, :minority_third, :majorities_ring :primaries]

Clock and time faults are not being tested.
(Need real VMs.)

### Duration

Currently testing with `NetTickTime` / 2 (e.g. 30s)

Would like to test at just less than `:nodedown`:

0 <= fault_duration <= `NetTickTime` - 1 (e.g. 59s)


----

## Verification

Uses Jepsen's [set-full](https://jepsen-io.github.io/jepsen/jepsen.checker.html#var-set-full) and an enhanced (fuzz_dist) [pn-counter](https://nurturenature.github.io/jepsen.fuzz_dist/fuzz-dist.tests.pn-counter.html) model/checker for verification.

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
    <td>yes</td>
  </tr>
 <tr>
    <th>Inter DC</th>
    <td>yes</td>
    <td>yes</td>
    <td>yes</td>
    <td>yes</td>
  </tr>
<table>

----

Intra, e.g. 1 * dcn5, networking is **significantly** more resilient than inter, e.g. 5 * dcn1.

## Observations

See initial GitHub issues:
- [pn-counter can lose :ok'd increments in a no fault environment](https://github.com/AntidoteDB/antidote/issues/492)
- [pn-counters are susceptible to partitioning](https://github.com/AntidoteDB/antidote/issues/493)
- [Inter DC partitioning can disrupt replication](https://github.com/AntidoteDB/antidote/issues/489)

----

### No Faults

#### Intra DC
- no anomalies observed
- g-set: ~ 10+% write operations abort
- pn-counter: ~ 2-3/1000 write operations abort

#### Inter DC

g-set:
- all op's return `:ok`
- but not all writes replicated

pn-counter:
- all op's return `:ok`
- but pn-counter can produce:
  - impossible read values
  - invalid final read values
- most common failure is value read from node appears to have lost a previous :ok'd write for the same node
- less frequently the lost write appears to have come from a previously replicated op on another node

----

### Partitions

#### Intra DC
- no anomalies observed
- increased client write timeouts post partition, in healed state
- can crash `stable_meta_data_server`

#### Inter DC

g-set:
- all op's return `:ok`
- but most tests fail most of the time
  - not all writes are replicated


pc-counter:
- all op's return `:ok`
- but most tests fail most of the time
  - invalid final read values
  - impossible read values
  - non-monotonic reads for grow-only 

----

### Process Pause

#### Intra DC

- no anomalies observed
- increase in timeouts post pause, in healed state

#### Inter DC

g-set:
- not all `:ok` writes replicated

pn-counter:
- not all `:ok` writes replicated

----

### Process Kill

`sync_log true`

Clients will occasionally return `:error` for long periods, at times the remainder of the test, even after being healed.

g-set:
  - partial replication of writes
  - increase in timeouts post kill, in healed state

pn-counter:
  - impossible read values
  - invalid final reads
  - non-monotonic reads for grow-only

----

## Usage

Current docker compose is problematic, so a [workaround](https://github.com/nurturenature/jepsen-docker-workaround) has been developed.

Jepsen's LXC [environment](https://github.com/jepsen-io/jepsen#lxc) is the best.

Also see previously mentioned Antidote GitHub issues for steps to reproduce.