defmodule FuzzDist.Antidote.GSet do
  @moduledoc """
  Antidote impl of a Jepsen g-set.
  """

  @behaviour FuzzDist.Jepsen.GSet

  require Logger

  @g_set_obj {"test_g_set", :antidote_crdt_set_aw, "test_bucket"}

  @impl true
  def read(antidote_conn) do
    {:ok, static_trans} = :antidotec_pb.start_transaction(antidote_conn, :ignore, static: true)

    case :antidotec_pb.read_objects(antidote_conn, [@g_set_obj], static_trans) do
      {:ok, [g_set]} ->
        g_set_value = :antidotec_set.value(g_set)

        return_value =
          Enum.map(
            g_set_value,
            &:erlang.binary_to_term/1
          )

        {:ok, return_value}

      {:error, error} ->
        {:error, error}
    end
  end

  @impl true
  # TODO: oh dialyzer, sigh...
  @dialyzer {:nowarn_function, add: 2}
  def add(antidote_conn, g_set_value) do
    {:ok, static_trans} = :antidotec_pb.start_transaction(antidote_conn, :ignore, static: true)

    updated_g_set = :antidotec_set.add(:erlang.term_to_binary(g_set_value), :antidotec_set.new())
    updated_ops = :antidotec_set.to_ops(@g_set_obj, updated_g_set)

    case :antidotec_pb.update_objects(antidote_conn, updated_ops, static_trans) do
      :ok -> :ok
      {:error, error} -> {:error, error}
    end
  end
end
