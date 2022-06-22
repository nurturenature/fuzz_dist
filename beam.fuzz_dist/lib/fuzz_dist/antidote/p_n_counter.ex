defmodule FuzzDist.Antidote.PNCounter do
  @moduledoc """
  Antidote impl of a Jepsen pn-counter.
  """

  @behaviour FuzzDist.Jepsen.PNCounter

  require Logger

  @pn_counter_obj {"test_pn_counter", :antidote_crdt_counter_pn, "test_bucket"}

  @impl true
  def read(antidote_conn) do
    {:ok, static_trans} = :antidotec_pb.start_transaction(antidote_conn, :ignore, static: true)

    case :antidotec_pb.read_objects(antidote_conn, [@pn_counter_obj], static_trans) do
      {:ok, [pn_counter]} -> {:ok, :antidotec_counter.value(pn_counter)}
      {:error, error} -> {:error, error}
    end
  end

  @impl true
  # TODO: oh dialyzer, sigh...
  @dialyzer {:nowarn_function, increment: 2}
  def increment(antidote_conn, pn_counter_value) do
    {:ok, static_trans} = :antidotec_pb.start_transaction(antidote_conn, :ignore, static: true)

    updated_pn_counter = :antidotec_counter.increment(pn_counter_value, :antidotec_counter.new())
    updated_ops = :antidotec_counter.to_ops(@pn_counter_obj, updated_pn_counter)

    case :antidotec_pb.update_objects(antidote_conn, updated_ops, static_trans) do
      :ok -> :ok
      {:error, error} -> {:error, error}
    end
  end

  @impl true
  # TODO: oh dialyzer, sigh...
  @dialyzer {:nowarn_function, decrement: 2}
  def decrement(antidote_conn, pn_counter_value) do
    {:ok, static_trans} = :antidotec_pb.start_transaction(antidote_conn, :ignore, static: true)

    updated_pn_counter = :antidotec_counter.decrement(pn_counter_value, :antidotec_counter.new())
    updated_ops = :antidotec_counter.to_ops(@pn_counter_obj, updated_pn_counter)

    case :antidotec_pb.update_objects(antidote_conn, updated_ops, static_trans) do
      :ok -> :ok
      {:error, error} -> {:error, error}
    end
  end
end
