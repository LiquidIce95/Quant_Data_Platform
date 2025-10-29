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

# Non-root runtime user
RUN useradd -r -u 10001 -m appuser

# Copy the unzipped IB Client Portal Gateway distribution
# Ensure this folder exists in the build context:
# services_streaming_lane/ib_client_portal_api/source/clientportal.gw/...
COPY clientportal.gw /opt/clientportal.gw

# Entrypoint wrapper (calls ./bin/run.sh root/conf.yaml)
COPY entrypoint.sh /entrypoint.sh

# Prepare writable dirs and fix ownership for non-root user
RUN chmod +x /entrypoint.sh \
 && mkdir -p /opt/clientportal.gw/logs \
              /opt/clientportal.gw/resources \
              /opt/clientportal.gw/resources/.vertx \
              /home/appuser/.vertx \
 && chown -R appuser:appuser /opt/clientportal.gw /home/appuser \
 && chmod -R u+rwX /opt/clientportal.gw

# Keep Vert.x file cache in a writable directory
ENV JAVA_TOOL_OPTIONS="-Dvertx.cacheDirBase=/opt/clientportal.gw/resources/.vertx"

# Default ports (HTTP UI + optional WS)
EXPOSE 5000 5001

USER appuser
WORKDIR /opt/clientportal.gw

# Basic healthcheck (TCP)
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=5 \
  CMD bash -lc 'exec 3<>/dev/tcp/127.0.0.1/5000 && exit 0 || exit 1'

ENTRYPOINT ["/usr/bin/tini","--"]
CMD ["/entrypoint.sh"]
