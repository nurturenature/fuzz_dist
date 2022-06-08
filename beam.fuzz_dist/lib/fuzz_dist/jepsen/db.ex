defmodule FuzzDist.Jepsen.Db do
  @moduledoc """
  A generic Elixir @behaviour for a Jepsen Db.
  """

  alias FuzzDist.{Antidote, Telemetry}
  alias FuzzDist.Jepsen.JepSir

  @callback setup_primary(topology :: String.t(), nodes :: nonempty_list(node())) :: :ok

  @spec setup_primary(String.t(), nonempty_list(node())) :: JepSir.jepsen_return()
  def setup_primary(topology, nodes) do
    start_time = Telemetry.start(:setup_primary, %{topology: topology, nodes: nodes})

    :ok = Antidote.Db.setup_primary(topology, nodes)

    Telemetry.stop(:setup_primary, start_time)

    %{type: :ok}
  end
end
