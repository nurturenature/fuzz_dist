defmodule FuzzDist.Jepsen.JepWs do
  @moduledoc """
  Websocket endpoint for a `JepSir` client as a `:cowboy_websocket`.
  """

  @behaviour :cowboy_websocket

  require Logger

  alias FuzzDist.Jepsen

  @impl true
  def init(req, opts) do
    # no auth, upgrade to ws
    {:cowboy_websocket, req, opts}
  end

  @impl true
  def websocket_init(state) do
    # ws connected and in a new process

    # blocking, possible crash in init/1, is intentional
    case GenServer.start_link(Jepsen.JepSir, [], name: via_self()) do
      {:ok, _pid} -> :ok
      {:error, error} -> raise "JepSir client failed to start! #{error}"
    end

    {[], state}
  end

  @impl true
  def websocket_handle({:text, message}, state) do
    {:ok, resp} = GenServer.call(via_self(), message)

    {[{:text, resp}], state}
  end

  @impl true
  def websocket_info(info, state) do
    Logger.warning("JepWs unexpected info: #{inspect(info)}")

    {[], state}
  end

  defp via_self do
    {:via, Registry, {FuzzDist.Registry, self()}}
  end
end
