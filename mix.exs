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
      preferred_cli_env: [testy: :test]
    ]
  end

  def application do
    [
      extra_applications: [:logger],
      mod: {FuzzDist.Application, []}
    ]
  end

  defp deps do
    [
      {:credo, "~> 1.6", only: :dev, runtime: false},
      {:dialyxir, "~> 1.1", only: :dev, runtime: false},
      {:ex_doc, "~> 0.28", only: :dev, runtime: false}
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
      ]
    ]
  end
end
