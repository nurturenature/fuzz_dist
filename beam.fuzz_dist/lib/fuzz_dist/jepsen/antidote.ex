defmodule FuzzDist.Jepsen.Antidote do
  @moduledoc """
  An Elixir client for AntidoteDB.
  Implements operations that are generated by Jepsen during the running of a test.

  - uses Erlang client
  - `static: true` transactions
    - docs say static transactions don't need to be closed or managed

  Function names and `@spec`s are the same as the Jepsen client protocol.

  Explicit match for all Antidote returns.
  Unexpected values should crash the client to indicated unknown, Jepsen's :info, result.
  """

  @behaviour FuzzDist.Jepsen.JepSir

  require Logger

  @g_set_obj {"test_g_set", :antidote_crdt_set_aw, "test_bucket"}
  @pn_counter_obj {"test_pn_counter", :antidote_crdt_counter_pn, "test_bucket"}

  @impl true
  def g_set_read(antidote_conn) do
    {:ok, static_trans} = :antidotec_pb.start_transaction(antidote_conn, :ignore, static: true)

    {:ok, [g_set]} = :antidotec_pb.read_objects(antidote_conn, [@g_set_obj], static_trans)
    g_set_value = :antidotec_set.value(g_set)

    return_value =
      Enum.map(
        g_set_value,
        &:erlang.binary_to_term/1
      )

    {:ok, return_value}
  end

  @impl true
  # TODO: oh dialyzer, sigh...
  @dialyzer {:nowarn_function, g_set_add: 2}
  def g_set_add(antidote_conn, g_set_value) do
    {:ok, static_trans} = :antidotec_pb.start_transaction(antidote_conn, :ignore, static: true)

    updated_g_set = :antidotec_set.add(:erlang.term_to_binary(g_set_value), :antidotec_set.new())
    updated_ops = :antidotec_set.to_ops(@g_set_obj, updated_g_set)

    :ok = :antidotec_pb.update_objects(antidote_conn, updated_ops, static_trans)
  end

  @impl true
  def pn_counter_read(antidote_conn) do
    {:ok, static_trans} = :antidotec_pb.start_transaction(antidote_conn, :ignore, static: true)

    case :antidotec_pb.read_objects(antidote_conn, [@pn_counter_obj], static_trans) do
      {:ok, [pn_counter]} -> {:ok, :antidotec_counter.value(pn_counter)}
      {:error, :timeout} -> {:error, :timeout}
    end
  end

  @impl true
  # TODO: oh dialyzer, sigh...
  @dialyzer {:nowarn_function, pn_counter_add: 2}
  def pn_counter_add(antidote_conn, pn_counter_value) do
    {:ok, static_trans} = :antidotec_pb.start_transaction(antidote_conn, :ignore, static: true)

    updated_pn_counter = :antidotec_counter.increment(pn_counter_value, :antidotec_counter.new())
    updated_ops = :antidotec_counter.to_ops(@pn_counter_obj, updated_pn_counter)

    case :antidotec_pb.update_objects(antidote_conn, updated_ops, static_trans) do
      :ok -> :ok
      {:error, :aborted} -> {:error, :aborted}
      {:error, :timeout} -> {:error, :timeout}
      {:error, {:unknown, err_msg}} -> {:error, {:unknown, err_msg}}
    end
  end

  @impl true
  def setup_primary(:nodes, nodes) when is_list(nodes) and length(nodes) != 0 do
    [_name, host] = String.split(hd(nodes), "@")
    {:ok, antidote_conn} = :antidotec_pb_socket.start_link(String.to_charlist(host), 8087)

    :ok = :antidotec_pb_management.create_dc(antidote_conn, nodes)

    {:ok, descriptor} = :antidotec_pb_management.get_connection_descriptor(antidote_conn)

    :ok = :antidotec_pb_management.connect_to_dcs(antidote_conn, [descriptor])

    :ok = :antidotec_pb_socket.stop(antidote_conn)
  end

  @impl true
  def setup_primary(:dcs, nodes) when is_list(nodes) and length(nodes) != 0 do
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
