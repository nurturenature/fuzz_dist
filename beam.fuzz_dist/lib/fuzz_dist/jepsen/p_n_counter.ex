defmodule FuzzDist.Jepsen.PNCounter do
  @moduledoc """
  A generic Elixir @behaviour for a Jepsen pn-counter.
  """

  alias FuzzDist.{Antidote, Telemetry}
  alias FuzzDist.Jepsen.JepSir

  @callback increment(antidote_conn :: pid(), value :: integer()) :: JepSir.antidote_return()
  @callback decrement(antidote_conn :: pid(), value :: integer()) :: JepSir.antidote_return()
  @callback read(antidote_conn :: pid()) :: JepSir.antidote_return()

  @spec increment(pid, integer) :: JepSir.jepsen_return()
  def increment(antidote_conn, value) do
    start_time = Telemetry.start(:pn_counter_add, %{value: value})

    reply =
      case Antidote.PNCounter.increment(antidote_conn, value) do
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

    Telemetry.stop(:pn_counter_add, start_time, reply)

    reply
  end

  @spec decrement(pid, integer) :: JepSir.jepsen_return()
  def decrement(antidote_conn, value) do
    start_time = Telemetry.start(:pn_counter_add, %{value: value})

    reply =
      case Antidote.PNCounter.decrement(antidote_conn, value) do
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

    Telemetry.stop(:pn_counter_add, start_time, reply)

    reply
  end

  @spec read(pid) :: JepSir.jepsen_return()
  def read(antidote_conn) do
    start_time = Telemetry.start(:pn_counter_read)

    reply =
      case Antidote.PNCounter.read(antidote_conn) do
        {:ok, value} ->
          %{type: :ok, value: value}

        {:error, :timeout} ->
          %{type: :info, error: :timeout}

        {:error, {:unknown, err_msg}} ->
          {err_msg, _rest} = String.split_at(err_msg, 64)
          err_msg = err_msg <> "..."
          %{type: :info, error: err_msg}
      end

    Telemetry.stop(:pn_counter_read, start_time, reply)

    reply
  end
end
