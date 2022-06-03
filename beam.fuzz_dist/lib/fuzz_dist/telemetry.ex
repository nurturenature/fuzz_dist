defmodule FuzzDist.Telemetry do
  @moduledoc """
  Telemetry integration.

  Match `:ok = :telemetry/all` as telemetry is expected as part of test.

  `FuzzDist` executes the following events:

  * `[:fuzz_dist, :beam]` - Executed on receipt of BEAM monitored message/signal.

    #### Measurements

      * `%{}`

    #### Metadata:

      * `:event` - The BEAM event

  * `[:fuzz_dist, :client]` - Executed on connection of Jepsen client ws connection

    #### Measurements

      * `%{}`

    #### Metadata:

      * `:antidote_conn` - AntidoteDB connection that client will use for all transactions

  * `[:fuzz_dist, :g_set_read, :start]` - Executed on receipt of Jepsen :read operation.

    #### Measurements

      * `:system_time` - The system time

    #### Metadata:

      * `%{}`

  * `[:fuzz_dist, :g_set_read, :stop]` - Executed on completion of read before return to Jepsen.

    #### Measurements

      * `:duration` - Duration of the read.

    #### Metadata:

      * :value - Value returned by read.

  * `[:fuzz_dist, :g_set_add, :start]` - Executed on receipt of Jepsen :add operation.

    #### Measurements

      * `:system_time` - The system time

    #### Metadata:

      * `value`: - The value to be added

  * `[:fuzz_dist, :g_set_add, :stop]` - Executed on completion of add before return to Jepsen.

    #### Measurements

      * `:duration` - Duration of the read.

    #### Metadata:

      * `%{}`

  * `[:fuzz_dist, :pn_counter_read, :start]` - Executed on receipt of Jepsen :read operation.

    #### Measurements

      * `:system_time` - The system time

    #### Metadata:

      * `%{}`

  * `[:fuzz_dist, :pn_counter_read, :stop]` - Executed on completion of read before return to Jepsen.

    #### Measurements

      * `:duration` - Duration of the read.

    #### Metadata:

      * reply returned by read, may contain keys:
      * :type  - :ok, :fail, :info.
      * :value - Value returned by read.
      * :error - Supplemental error information.

  * `[:fuzz_dist, :pn_counter_add, :start]` - Executed on receipt of Jepsen :add operation.

    #### Measurements

      * `:system_time` - The system time

    #### Metadata:

      * `value`: - The value to be added

  * `[:fuzz_dist, :pn_counter_add, :stop]` - Executed on completion of add before return to Jepsen.

    #### Measurements

      * `:duration` - Duration of the read.

    #### Metadata:

      * reply returned by add, may contain keys:
      * :type  - :ok, :fail, :info.
      * :error - Supplemental error information.

  * `[:fuzz_dist, :setup_primary, :start]` - Executed on receipt of Jepsen :setup_primary operation.

    #### Measurements

      * `:system_time` - The system time

    #### Metadata:

      * `nodes` - List of (long) node names

  * `[:fuzz_dist, :setup_primary, :stop]` - Executed on completion of `setup_primary/1` before return to Jepsen.

    #### Measurements

      * `:duration` - Duration of the cluster configuration

    #### Metadata:

      * `%{}`


  TODO: add Prometheus collector.
  """

  use GenServer

  require Logger

  def start_link(opts) do
    {:ok, _pid} = GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @impl true
  def init(_args) do
    # blocking, crash, intentional in init/1.

    :ok = :net_kernel.monitor_nodes(true)
    monitor_ref = :erlang.monitor(:time_offset, :clock_service)

    :ok =
      :telemetry.attach_many(
        :fuzz_dist,
        [
          [:fuzz_dist, :beam],
          [:fuzz_dist, :g_set_add, :start],
          [:fuzz_dist, :g_set_add, :stop],
          [:fuzz_dist, :g_set_read, :start],
          [:fuzz_dist, :g_set_read, :stop],
          [:fuzz_dist, :pn_counter_add, :start],
          [:fuzz_dist, :pn_counter_add, :stop],
          [:fuzz_dist, :pn_counter_read, :start],
          [:fuzz_dist, :pn_counter_read, :stop],
          [:fuzz_dist, :setup_primary, :start],
          [:fuzz_dist, :setup_primary, :stop]
        ],
        &FuzzDist.Telemetry.log_handler/4,
        nil
      )

    {:ok, %{monitor_ref: monitor_ref}}
  end

  @impl true
  def handle_info({node_state, _node} = message, state)
      when node_state == :nodeup or node_state == :nodedown do
    :ok = event(:beam, %{}, %{event: message})

    {:noreply, state}
  end

  @impl true
  def handle_info({'CHANGE', monitor_ref, _type, _item, _new_time_offset} = message, state)
      when monitor_ref == state.monitor_ref do
    :ok = event(:beam, %{}, %{event: message})

    {:noreply, state}
  end

  @doc false
  # Used for reporting generic events
  def event(event, measurements, meta) do
    :ok = :telemetry.execute([:fuzz_dist, event], measurements, meta)
  end

  @doc false
  # emits a `start` telemetry event and returns the the start time
  def start(event, meta \\ %{}, extra_measurements \\ %{}) do
    start_time = System.monotonic_time()

    :ok =
      :telemetry.execute(
        [:fuzz_dist, event, :start],
        Map.merge(extra_measurements, %{system_time: System.system_time()}),
        meta
      )

    start_time
  end

  @doc false
  # Emits a stop event.
  def stop(event, start_time, meta \\ %{}, extra_measurements \\ %{}) do
    end_time = System.monotonic_time()
    measurements = Map.merge(extra_measurements, %{duration: end_time - start_time})

    :ok =
      :telemetry.execute(
        [:fuzz_dist, event, :stop],
        measurements,
        meta
      )
  end

  @doc false
  def exception(event, start_time, kind, reason, stack, meta \\ %{}, extra_measurements \\ %{}) do
    end_time = System.monotonic_time()
    measurements = Map.merge(extra_measurements, %{duration: end_time - start_time})

    meta =
      meta
      |> Map.put(:kind, kind)
      |> Map.put(:error, reason)
      |> Map.put(:stacktrace, stack)

    :ok = :telemetry.execute([:fuzz_dist, event, :exception], measurements, meta)
  end

  def log_handler(event, measurements, meta, _config) do
    Logger.debug("Telemetry: [#{inspect(event)}, #{inspect(measurements)}, #{inspect(meta)}]")
  end
end
