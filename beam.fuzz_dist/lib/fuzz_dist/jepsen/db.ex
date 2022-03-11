defmodule FuzzDist.Jepsen.Db do
  @moduledoc """
  Implements Jepsen DB/Primary protocol.

  Called once after all db nodes started.
  """

  require Logger

  def setup_primary(antidote_conn, nodes) do
    # TODO: create 1 dc per 1 node,
    # FORNOW: dc1n_all
    # error to create a dc1n1 on dc1n1?
    long_names =
      Enum.map(nodes, fn <<"n", num::binary>> ->
        "antidote" <> "@" <> "192.168.122.10" <> num
      end)

    Logger.debug("DB setup_primaries(#{inspect(long_names)}) on #{inspect(antidote_conn)}")

    nodes_to_conns =
      Enum.reduce(
        long_names,
        %{},
        fn antidote_node, acc ->
          [_name, host] = String.split(antidote_node, "@")

          {:ok, antidote_conn} = :antidotec_pb_socket.start_link(String.to_charlist(host), 8087)

          Map.put(acc, antidote_node, antidote_conn)
        end
      )

    dcs =
      Enum.map(
        long_names,
        fn antidote_node ->
          :ok = :antidotec_pb_management.create_dc(nodes_to_conns[antidote_node], [antidote_node])
          antidote_node
        end
      )

    cluster =
      Enum.map(
        dcs,
        fn dc ->
          {:ok, descriptor} =
            :antidotec_pb_management.get_connection_descriptor(nodes_to_conns[dc])

          descriptor
        end
      )

    Enum.each(
      dcs,
      fn dc ->
        :ok = :antidotec_pb_management.connect_to_dcs(nodes_to_conns[dc], cluster)
      end
    )

    Enum.each(
      Map.to_list(nodes_to_conns),
      fn {_node, conn} ->
        :ok = :antidotec_pb_socket.stop(conn)
      end
    )

    Logger.debug("Db.setup_primary :ok")

    :ok
  end
end
