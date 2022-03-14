defmodule FuzzDist.Application do
  @moduledoc false

  use Application

  @impl true
  def start(_type, _args) do
    topologies = [gossip: [strategy: Cluster.Strategy.Gossip]]

    children = [
      {Cluster.Supervisor, [topologies, [name: FuzzDist.ClusterSupervisor]]},
      {Registry, name: FuzzDist.Registry, keys: :unique},
      cowboy_childspec()
    ]

    opts = [strategy: :rest_for_one, name: FuzzDist.ApplicationSupervisor]

    Supervisor.start_link(children, opts)
  end

  defp cowboy_childspec do
    %{
      id: FuzzDist.Cowboy,
      start: {
        :cowboy,
        :start_clear,
        [
          FuzzDist.Cowboy,
          [{:port, 8080}],
          %{env: %{dispatch: cowboy_dispatch()}}
        ]
      }
    }
  end

  defp cowboy_dispatch do
    :cowboy_router.compile([
      {:_,
       [
         {"/fuzz_dist/jep_ws", FuzzDist.Jepsen.JepWs, []}
       ]}
    ])
  end
end
