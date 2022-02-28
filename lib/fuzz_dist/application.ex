defmodule FuzzDist.Application do
  @moduledoc false

  use Application

  @impl true
  def start(_type, _args) do
    topologies = [gossip: [strategy: Cluster.Strategy.Gossip]]

    children = [
      {Cluster.Supervisor, [topologies, [name: FuzzDist.ClusterSupervisor]]}
    ]

    opts = [strategy: :one_for_one, name: FuzzDist.Supervisor]

    Supervisor.start_link(children, opts)
  end
end
