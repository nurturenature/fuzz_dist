defmodule FuzzDist.Jepsen.GSet do
  @moduledoc """
  A generic Elixir @behaviour for a Jepsen g-set.
  """

  alias FuzzDist.{Antidote, Telemetry}
  alias FuzzDist.Jepsen.JepSir

  @callback add(antidote_conn :: pid(), value :: integer()) :: JepSir.antidote_return()

  @callback read(antidote_conn :: pid()) :: JepSir.antidote_return()

  @spec add(pid, integer()) :: JepSir.jepsen_return()
  def add(antidote_conn, value) do
    start_time = Telemetry.start(:g_set_add, %{value: value})

    reply =
      case Antidote.GSet.add(antidote_conn, value) do
        :ok ->
          %{type: :ok}

        {:error, :aborted} ->
          %{type: :fail, error: :aborted}

        {:error, :timeout} ->
          %{type: :info, error: :timeout}

        {:error, {:unknown, err_msg}} ->
          {err_msg, _rest} = String.split_at(err_msg, 64)
          err_msg = err_msg <> "..."
          %{type: :info, error: err_msg}
      end

    Telemetry.stop(:g_set_add, start_time, reply)

    reply
  end

  @spec read(pid) :: JepSir.jepsen_return()
  def read(antidote_conn) do
    start_time = Telemetry.start(:g_set_read)

    reply =
      case Antidote.GSet.read(antidote_conn) do
        {:ok, value} ->
          %{type: :ok, value: value}

        {:error, :timeout} ->
          %{type: :info, error: :timeout}

        {:error, {:unknown, err_msg}} ->
          {err_msg, _rest} = String.split_at(err_msg, 64)
          err_msg = err_msg <> "..."
          %{type: :info, error: err_msg}
      end

    Telemetry.stop(:g_set_read, start_time, reply)

    reply
  end
end
