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

  # TODO update and rationalize return types for Antidote calls
  @callback g_set_add(antidote_conn :: pid(), value :: integer()) :: :ok
  @callback g_set_read(antidote_conn :: pid()) :: {:ok, list(integer())}

  @callback pn_counter_add(antidote_conn :: pid(), value :: integer()) :: :ok
  @callback pn_counter_read(antidote_conn :: pid()) :: {:ok, integer()}

  @callback setup_primary(topology :: atom(), nodes :: nonempty_list(node())) :: :ok

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
          start_time = Telemetry.start(:g_set_add, %{value: value})

          reply =
            case Jepsen.Antidote.g_set_add(antidote_conn, value) do
              :ok -> %{type: :ok}
              {:error, :aborted} -> %{type: :fail, error: :aborted}
              {:error, :timeout} -> %{type: :info, error: :timeout}
              {:error, {:unknown, err_msg}} -> %{type: :info, error: err_msg}
            end

          Telemetry.stop(:g_set_add, start_time, reply)

          reply

        {"GSet", "read", _} ->
          start_time = Telemetry.start(:g_set_read)

          reply =
            case Jepsen.Antidote.g_set_read(antidote_conn) do
              {:ok, value} -> %{type: :ok, value: value}
              {:error, :timeout} -> %{type: :info, error: :timeout}
              {:error, {:unknown, err_msg}} -> %{type: :info, error: err_msg}
            end

          Telemetry.stop(:g_set_read, start_time, reply)

          reply

        {"PnCounter", "add", %{value: value}} ->
          start_time = Telemetry.start(:pn_counter_add, %{value: value})

          reply =
            case Jepsen.Antidote.pn_counter_add(antidote_conn, value) do
              :ok -> %{type: :ok}
              {:error, :aborted} -> %{type: :fail, error: :aborted}
              {:error, :timeout} -> %{type: :info, error: :timeout}
              {:error, {:unknown, err_msg}} -> %{type: :info, error: err_msg}
            end

          Telemetry.stop(:pn_counter_add, start_time, reply)

          reply

        {"PnCounter", "read", _} ->
          start_time = Telemetry.start(:pn_counter_read)

          reply =
            case Jepsen.Antidote.pn_counter_read(antidote_conn) do
              {:ok, value} -> %{type: :ok, value: value}
              {:error, :timeout} -> %{type: :info, error: :timeout}
              {:error, {:unknown, err_msg}} -> %{type: :info, error: err_msg}
            end

          Telemetry.stop(:pn_counter_read, start_time, reply)

          reply

        {"Db", "setup_primary", %{topology: topology, nodes: nodes}} ->
          start_time = Telemetry.start(:setup_primary, %{topology: topology, nodes: nodes})

          :ok = Jepsen.Antidote.setup_primary(String.to_atom(topology), nodes)

          Telemetry.stop(:setup_primary, start_time)

          %{type: :ok}

        op ->
          Logger.error("JepSir :not_implemented #{inspect(op)}")

          %{type: :fail, error: :not_implemented}
      end

    resp = Jason.encode!(resp, maps: :strict)

    {:reply, {:ok, resp}, state}
  end
end
