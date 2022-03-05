defmodule FuzzDist.Jepsen.Client do
  @moduledoc """
  Generic Jepsen client as a Websocket.
  """
  @behaviour :cowboy_websocket

  require Logger

  @impl true
  def init(req, opts) do
    # no auth, upgrade to ws
    Logger.debug("Client http connected: #{inspect({req, opts})}")

    {:cowboy_websocket, req, opts}
  end

  @impl true
  def websocket_init(state) do
    # ws connected and in a new process
    Logger.debug("Client ws connected: #{inspect(state)}")

    {[], state}
  end

  @impl true
  def websocket_handle({:text, message}, state) do
    Logger.debug("Client message: #{inspect(message)}")

    {[{:text, message}], state}
  end

  @impl true
  def websocket_handle(in_frame, state) do
    # unexpected message = framework violation = invalidates test
    raise "Unexpected message!"

    {in_frame, state}
  end

  @impl true
  def websocket_info(info, state) do
    Logger.warning("Unexpected: #{inspect(info)}")

    {[], state}
  end

  @impl true
  def terminate(_reason, _partial_req, _state) do
    :ok
  end
end
