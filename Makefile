build-docker-env:
	docker build -t build-env-antidote .

build-antidote: antidote
	docker run --rm -it -v $(PWD):$(PWD) -w $(PWD) --name build build-env-antidote \
			bash -c "make antidote-rel"

antidote:
	git clone https://github.com/AntidoteDB/antidote.git

antidote-rel:
	(cd antidote && cp ${HOME}/bin/rebar3 ./ && make all rel)

start-jepsen-docker-env: jepsen
	cd docker && bin/up.sh

jepsen:
	git clone https://github.com/jepsen-io/jepsen
