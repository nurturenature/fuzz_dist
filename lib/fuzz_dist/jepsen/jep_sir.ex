defmodule FuzzDist.Jepsen.JepSir do
  @moduledoc """
  Generic Jepsen client for Elixir as a `:cowboy` websocket.

  Jepsen creates operations on the Control node driven by property test `:generator`s.

  The operations are executed on the target node's client as an Elixir `@behavior`.

  JepSir is strict in following a happy path.
  Unexpected or malformed messages crash or `raise`
  to invalidate the client in Jepsen.
  """
  @behaviour :cowboy_websocket

  require Logger

  @impl true
  def init(req, opts) do
    # no auth, upgrade to ws
    Logger.debug("JepSir http connected: #{inspect({req, opts})}")

    {:cowboy_websocket, req, opts}
  end

  @impl true
  def websocket_init(state) do
    # ws connected and in a new process
    Logger.debug("JepSir ws connected: #{inspect(state)}")

    {[], state}
  end

  @impl true
  def websocket_handle({:text, message}, state) do
    Logger.debug("JepSir handle: #{inspect(message)}")

    %{mod: mod, fun: fun, args: args} = Jason.decode!(message, keys: :atoms)
    mod = Macro.camelize(mod)

    resp =
      case {mod, fun, args} do
        {"GSet", "read", _} -> %{type: :fail, return: :not_implemented}
        _ -> %{type: :fail, return: :not_implemented}
      end

    resp = Jason.encode!(resp, maps: :strict)

    {[{:text, resp}], state}
  end

  @impl true
  def websocket_handle(in_frame, state) do
    # unexpected message = framework violation = invalidates test
    Logger.error("JepSir unexpected handle: #{inspect({in_frame, state})}")

    raise "JepSir unexpected message!"

    {in_frame, state}
  end

  @impl true
  def websocket_info(info, state) do
    Logger.warning("JepSir unexpected info: #{inspect(info)}")

    {[], state}
  end

  @impl true
  def terminate(reason, partial_req, state) do
    Logger.debug("JepSir terminate: #{inspect({reason, partial_req, state})}")
    :ok
  end
end
