FROM eclipse-temurin:8-alpine
LABEL maintainer="Pedro Ákos Costa <pah.costa@campus.fct.unl.pt>, Pedro Fouto <p.fouto@campus.fct.unl.pt>"

RUN apk add --no-cache \
                bind-tools \
                iproute2 \
                nload \
                bc

ENTRYPOINT ["/tc/setupTc.sh"]
