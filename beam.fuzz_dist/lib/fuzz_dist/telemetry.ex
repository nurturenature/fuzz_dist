defmodule FuzzDist.Telemetry do
  @moduledoc """
    Telemetry integration.

    `FuzzDist` executes the following events:

    * `[:fuzz_dist, :beam]` - Executed on receipt of BEAM monitored message/signal.

      #### Metadata:

        * `:event` - The BEAM event

    TODO: add Prometheus collector.
  """

  use GenServer

  require Logger

  def start_link(opts) do
    {:ok, _pid} = GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @impl true
  def init(_args) do
    :ok = :net_kernel.monitor_nodes(true)
    monitor_ref = :erlang.monitor(:time_offset, :clock_service)

    :ok = :telemetry.attach(:fuzz_dist, [:fuzz_dist, :beam], &log_handler/4, nil)

    {:ok, %{monitor_ref: monitor_ref}}
  end

  @impl true
  def handle_info({node_state, _node} = message, state)
      when node_state == :nodeup or node_state == :nodedown do
    event(:beam, %{}, %{event: message})

    {:noreply, state}
  end

  # TODO:
  @impl true
  def handle_info({'CHANGE', monitor_ref, _type, _item, _new_time_offset} = message, state)
      when monitor_ref == state.monitor_ref do
    event(:beam, %{}, %{event: message})

    {:noreply, state}
  end

  @doc false
  # Used for reporting generic events
  def event(event, measurements, meta) do
    :telemetry.execute([:fuzz_dist, event], measurements, meta)
  end

  @doc false
  # emits a `start` telemetry event and returns the the start time
  def start(event, meta \\ %{}, extra_measurements \\ %{}) do
    start_time = System.monotonic_time()

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

    :telemetry.execute([:fuzz_dist, event, :exception], measurements, meta)
  end

  defp log_handler(event, measurements, meta, _config) do
    Logger.debug("Telemetry: [#{inspect(event)}, #{inspect(measurements)}, #{inspect(meta)}]")
  end
end
