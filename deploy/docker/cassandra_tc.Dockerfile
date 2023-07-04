FROM cassandra:latest
LABEL maintainer="Pedro Ákos Costa <pah.costa@campus.fct.unl.pt>, Pedro Fouto <p.fouto@campus.fct.unl.pt>"

RUN set -eux; \
	apt-get update; \
    apt-get install -y --no-install-recommends \
      bc \
        iproute2 \
        nload;

ENTRYPOINT ["/tc/setupTc.sh"]
