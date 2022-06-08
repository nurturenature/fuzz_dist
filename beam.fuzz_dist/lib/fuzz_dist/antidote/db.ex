defmodule FuzzDist.Antidote.Db do
  @moduledoc """
  Antidote impl of a Jepsen Db.

  Jepsen's setup-primary is used to configure and cluster Antidote.
  Antidote does not have a literal "primary".
  """

  @behaviour FuzzDist.Jepsen.Db

  @impl true
  def setup_primary("nodes", nodes) when is_list(nodes) and length(nodes) != 0 do
    # "nodes" configures a single data center with all nodes.
    # e.g. 1 * dcn5
    [_name, host] = String.split(hd(nodes), "@")
    {:ok, antidote_conn} = :antidotec_pb_socket.start_link(String.to_charlist(host), 8087)

    :ok = :antidotec_pb_management.create_dc(antidote_conn, nodes)

    {:ok, descriptor} = :antidotec_pb_management.get_connection_descriptor(antidote_conn)

    :ok = :antidotec_pb_management.connect_to_dcs(antidote_conn, [descriptor])

    :ok = :antidotec_pb_socket.stop(antidote_conn)
  end

  @impl true
  def setup_primary("dcs", nodes) when is_list(nodes) and length(nodes) != 0 do
    # "dcs" configures a data center for each node.
    # e.g. 5 * dcn1
    nodes_to_conns =
      Enum.reduce(nodes, %{}, fn antidote_node, acc ->
        [_name, host] = String.split(antidote_node, "@")

        {:ok, antidote_conn} = :antidotec_pb_socket.start_link(String.to_charlist(host), 8087)

        Map.put(acc, antidote_node, antidote_conn)
      end)

    dcs =
      Enum.map(nodes, fn antidote_node ->
        :ok = :antidotec_pb_management.create_dc(nodes_to_conns[antidote_node], [antidote_node])
        antidote_node
      end)

    cluster =
      Enum.map(dcs, fn dc ->
        {:ok, descriptor} = :antidotec_pb_management.get_connection_descriptor(nodes_to_conns[dc])

        descriptor
      end)

    Enum.each(dcs, fn dc ->
      :ok = :antidotec_pb_management.connect_to_dcs(nodes_to_conns[dc], cluster)
    end)

    Enum.each(Map.to_list(nodes_to_conns), fn {_node, conn} ->
      :ok = :antidotec_pb_socket.stop(conn)
    end)

    :ok
  end
end
