FROM debian:buster
MAINTAINER jake@apache.org

ENV LEIN_ROOT true

#
# Jepsen dependencies
#
RUN apt-get -y -q update && \
    apt-get install -qy openjdk-11-jdk-headless \
    libjna-java \
    vim \
    emacs \
    git \
    htop \
    screen \
    pssh \
    curl \
    wget \
    gnuplot \
    graphviz \
    dos2unix

RUN wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    mv lein /usr/bin && \
    chmod +x /usr/bin/lein && \
    lein self-install

# without --dev flag up.sh copies jepsen to these subfolders
# with --dev flag they are empty until mounted
COPY jepsen/jepsen /jepsen/jepsen/
RUN if [ -f /jepsen/jepsen/project.clj ]; then cd /jepsen/jepsen && lein install; fi
COPY jepsen /jepsen/

ADD ./bashrc /root/.bashrc
ADD ./init.sh /init.sh
RUN dos2unix /init.sh /root/.bashrc \
    && chmod +x /init.sh

# locale of UTF-8 vs default latin1
RUN apt-get -qy update && \
    apt-get -qy install \
    locales && \
    sed -i '/en_US.UTF-8/s/^# //g' /etc/locale.gen && \
    locale-gen
ENV LANG=en_US.UTF-8 \
    LANGUAGE=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8

# Install Elixir/Erlang from ESL repository
RUN apt-get -qy update && \
    apt-get -qy install \
    gnupg
RUN wget https://packages.erlang-solutions.com/erlang-solutions_2.0_all.deb && \
    dpkg -i erlang-solutions_2.0_all.deb && \
    apt-get -qy update && \
    apt-get -qy install \
    erlang \
    elixir && \
    mix local.hex --force --if-missing && \
    mix local.rebar --force --if-missing && \
    rm erlang-solutions_2.0_all.deb

CMD /init.sh
