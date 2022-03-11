defmodule FuzzDist.Jepsen.JepSir do
  @moduledoc """
  Generic Jepsen client for Elixir.

  Jepsen creates operations on the Control node driven by property test `:generator`s.

  The operations are executed on the target node's client as an Elixir `@behavior`.

  JepSir is strict in following a happy path.
  Unexpected or malformed messages crash or `raise`
  to invalidate the client in Jepsen.
  """
  use GenServer

  require Logger

  alias FuzzDist.Jepsen

  @impl true
  def init(_args) do
    # blocking, possible crash in init/1, is intentional
    antidote_conn =
      case :antidotec_pb_socket.start_link(String.to_charlist("127.0.0.1"), 8087) do
        {:ok, antidote_conn} -> antidote_conn
        {:error, error} -> raise "Antidote connection fail! #{inspect(error)}"
      end

    Logger.debug("JepSir init w/antidote_conn: #{inspect(antidote_conn)}")

    {:ok, %{antidote_conn: antidote_conn}}
  end

  @impl true
  def handle_call(message, _from, %{antidote_conn: antidote_conn} = state) do
    Logger.debug("JepSir called: #{inspect(message)} on #{inspect(antidote_conn)}")

    %{mod: mod, fun: fun, args: args} = Jason.decode!(message, keys: :atoms)
    mod = Macro.camelize(mod)

    resp =
      case {mod, fun, args} do
        {"Db", "setup_primary", nodes} ->
          :ok = Jepsen.Db.setup_primary(antidote_conn, nodes)
          %{type: :ok}

        {"GSet", "read", _} ->
          %{type: :fail, return: :not_implemented}

        _ ->
          %{type: :fail, return: :not_implemented}
      end

    resp = Jason.encode!(resp, maps: :strict)

    Logger.debug("JepSir resp: #{inspect(resp)}")
    {:reply, {:ok, resp}, state}
  end
end
