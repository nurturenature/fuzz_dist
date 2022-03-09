defmodule FuzzDist.Jepsen.Db do
  @moduledoc """
  Implements Jepsen DB/Primary protocol.

  Called once after all db nodes started.
  """

  require Logger

  def setup_primaries(nodes) do
    Logger.debug("DB setup_primaries: #{inspect(nodes)}")

    %{type: :fail, return: :not_implemented}
  end
end
