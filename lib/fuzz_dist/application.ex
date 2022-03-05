defmodule FuzzDist.Application do
  @moduledoc false

  use Application

  @impl true
  def start(_type, _args) do
    topologies = [gossip: [strategy: Cluster.Strategy.Gossip]]

    children = [
      {Cluster.Supervisor, [topologies, [name: FuzzDist.ClusterSupervisor]]},
      cowboy_childspec()
    ]

    opts = [strategy: :one_for_one, name: FuzzDist.ApplicationSupervisor]

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
         {"/fuzz_dist/jepsir", FuzzDist.Jepsen.JepSir, []}
       ]}
    ])
  end
end
