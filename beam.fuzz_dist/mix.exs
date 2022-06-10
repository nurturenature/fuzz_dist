defmodule FuzzDist.MixProject do
  use Mix.Project

  def project do
    [
      app: :fuzz_dist,
      version: "0.0.1-scrappy",
      elixir: "~> 1.13",
      start_permanent: Mix.env() == :prod,
      deps: deps(),
      aliases: aliases(),
      preferred_cli_env: [testy: :test, releasey: :prod],
      releases: releases(),
      test_coverage: [threshold: 0]
    ]
  end

  def application do
    [
      mod: {FuzzDist.Application, []},
      extra_applications: [:logger]
    ]
  end

  defp releases do
    [
      fuzz_dist: [
        include_executables_for: [:unix],
        applications: [runtime_tools: :permanent],
        cookie: "fuzz_dist"
      ]
    ]
  end

  defp deps do
    [
      {:antidote_pb_codec,
       git: "https://github.com/AntidoteDB/antidote.git",
       subdir: "apps/antidote_pb_codec",
       override: true},
      {:antidotec_pb,
       git: "https://github.com/AntidoteDB/antidote.git", subdir: "apps/antidotec_pb"},
      {:cowboy, "~> 2.9"},
      {:credo, "~> 1.6", only: :dev, runtime: false},
      {:dialyxir, "~> 1.1", only: :dev, runtime: false},
      {:ex_doc, "~> 0.28", only: :dev, runtime: false},
      {:jason, "~> 1.3"},
      {:libcluster, "~> 3.3"},
      {:telemetry, "~> 1.1"}
    ]
  end

  defp aliases do
    [
      setup: [
        "deps.clean --unused --unlock",
        "deps.get",
        "deps.compile"
      ],
      qa: [
        "format --check-formatted",
        "compile --warnings-as-errors",
        "credo --strict",
        "dialyzer",
        "docs"
      ],
      testy: [
        "test --warnings-as-errors --cover --export-coverage default",
        "test.coverage"
      ],
      releasey: [
        "deps.get",
        "release --overwrite"
      ]
    ]
  end
end
