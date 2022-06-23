defmodule FuzzDist.Jepsen.JepSir do
  @moduledoc """
  Generic Jepsen client for Elixir.

  Jepsen creates operations on the Control node driven by property test `:generator`s.

  The operations are executed on the target node's client as an Elixir `@behavior`.

  JepSir is strict in following a happy path.
  Unexpected or malformed messages crash or `raise`
  to invalidate the client in Jepsen.

  Function names and `@spec`s are the same as the Jepsen client protocol.
  """
  use GenServer

  require Logger

  alias FuzzDist.{Jepsen, Telemetry}

  @type jepsen_return :: %{
          :type => :fail | :info | :ok,
          optional(:value) => term(),
          optional(:error) => :aborted | :timeout | String.t()
        }

  @type antidote_return ::
          :ok | {:ok, term()} | {:error, :aborted} | {:error, :timeout} | {:error, String.t()}

  @impl true
  def init(_args) do
    # blocking, crash, raise in init/1, is intentional
    antidote_conn =
      case :antidotec_pb_socket.start_link(String.to_charlist("127.0.0.1"), 8087) do
        {:ok, antidote_conn} -> antidote_conn
        {:error, error} -> raise "Antidote connection fail! #{inspect(error)}"
      end

    Telemetry.event(:client, %{}, %{antidote_conn: antidote_conn})

    {:ok, %{antidote_conn: antidote_conn}}
  end

  @impl true
  def handle_call(message, _from, %{antidote_conn: antidote_conn} = state) do
    %{mod: mod, fun: fun, args: args} = Jason.decode!(message, keys: :atoms)
    mod = Macro.camelize(mod)

    resp =
      case {mod, fun, args} do
        {"GSet", "add", %{value: value}} ->
          Jepsen.GSet.add(antidote_conn, value)

        {"GSet", "read", _} ->
          Jepsen.GSet.read(antidote_conn)

        {"PnCounter", "increment", %{value: [key, value]}} ->
          Jepsen.PNCounter.increment(antidote_conn, key, value)

        {"PnCounter", "decrement", %{value: [key, value]}} ->
          Jepsen.PNCounter.decrement(antidote_conn, key, value)

        {"PnCounter", "read", %{value: [key, _value]}} ->
          Jepsen.PNCounter.read(antidote_conn, key)

        {"Db", "setup_primary", %{topology: topology, nodes: nodes}} ->
          Jepsen.Db.setup_primary(topology, nodes)

        op ->
          Logger.error("JepSir :not_implemented #{inspect(op)}")

          %{type: :fail, error: :not_implemented}
      end

    resp = Jason.encode!(resp, maps: :strict)

    {:reply, {:ok, resp}, state}
  end
end
