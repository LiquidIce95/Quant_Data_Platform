# services_streaming_lane/ib_client_portal_api/source/Dockerfile

FROM debian:12.6-slim

# Minimal runtime deps + Java (required by Client Portal Gateway)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      bash \
      tini \
      openjdk-17-jre-headless \
 && rm -rf /var/lib/apt/lists/*

# (Optional but explicit)
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

COPY ./ /home

ENV JAVA_TOOL_OPTIONS="-Dvertx.cacheDirBase=/opt/clientportal.gw/resources/.vertx"

# Default ports (HTTP UI + optional WS)
EXPOSE 5000 5001
CMD ["bash","-lc","sleep infinity"]

