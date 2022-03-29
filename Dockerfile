FROM debian:buster

# Basic system stuff
RUN apt-get update
RUN apt-get install -y apt-transport-https

# Install packages
RUN apt-get -qy update && \
    apt-get -qy install \
             build-essential \
             libssl-dev \
             automake \
             autoconf \
             libncurses5-dev \
             git \
             curl

SHELL ["/bin/bash", "-c"]
# Install erlang
RUN mkdir -p $HOME/bin && \
    git clone https://github.com/asdf-vm/asdf.git ~/.asdf --branch v0.9.0 && \
    source $HOME/.asdf/asdf.sh && \
    asdf plugin add erlang
RUN source $HOME/.asdf/asdf.sh && \
    asdf install erlang 25.0-rc2 || true

ENV PATH="$HOME/bin:/root/.asdf/plugins/erlang/kerl-home/builds/asdf_25.0-rc2/release_25.0-rc2/bin:${PATH}"

# Install rebar3
RUN git clone https://github.com/erlang/rebar3.git && \
    (cd rebar3 && ./bootstrap && mv _build/prod/bin/rebar3 $HOME/bin)
