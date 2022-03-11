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

    :ok = :antidotec_pb_management.create_dc(antidote_conn, long_names)
    {:ok, _descriptor} = :antidotec_pb_management.get_connection_descriptor(antidote_conn)
    # :ok = :antidotec_pb_management.connect_to_dcs(antidote_conn, [descriptor])

    Logger.debug("Db.setup_primary :ok")

    :ok
  end
end
