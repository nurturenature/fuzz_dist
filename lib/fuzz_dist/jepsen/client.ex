defmodule FuzzDist.Jepsen.Client do
  @moduledoc """
  Generic Jepsen client as a Websocket.
  """
  @behaviour :cowboy_websocket

  @impl true
  # no auth, upgrade to ws
  def init(req, opts), do: {:cowboy_websocket, req, opts}

  @impl true
  def websocket_init(state) do
    # ws connected and in a new process

    {[], state}
  end

  @impl true
  def websocket_handle({:text, message}, state) do
    {[{:text, message}], state}
  end

  @impl true
  def websocket_handle(in_frame, state) do
    # unexpected message = framework violation = invalidates test
    raise "Unexpected message!"

    {in_frame, state}
  end

  @impl true
  def websocket_info(_info, state) do
    {[], state}
  end

  @impl true
  def terminate(_reason, _partial_req, _state) do
    :ok
  end
end
