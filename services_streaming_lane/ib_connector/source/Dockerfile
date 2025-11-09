FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.11.6_2.13.16

USER root

# Install necessary dependencies
RUN apt-get update && apt-get install -y \
	git \
	openssh-client \
	bash \
	&& rm -rf /var/lib/apt/lists/*

WORKDIR /work
COPY ./ /work
RUN mkdir -p /trust

# ensure entrypoint is in the image (place this file next to devcontainer.json)
COPY ./ibkr_client_portal.pem /trust/ibkr_client_portal.pem 
COPY ./ibkr_truststore.jks /trust/ibkr_truststore.jks
COPY ./entrypoint.sh /usr/local/bin/ibkr-entrypoint.sh
RUN chmod +x /usr/local/bin/ibkr-entrypoint.sh

# Default command; ENTRYPOINT will hand off to this

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl bash tini openjdk-17-jre-headless \
 && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY ./ /home
ENV JAVA_TOOL_OPTIONS="-Dvertx.cacheDirBase=/opt/clientportal.gw/resources/.vertx"

# Python + Chromium + chromedriver
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      python3 python3-venv python3-pip \
      chromium chromium-driver \
 && rm -rf /var/lib/apt/lists/*

ENV VENV_PATH=/opt/venv
RUN python3 -m venv "${VENV_PATH}"
ENV PATH="${VENV_PATH}/bin:${PATH}"

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --upgrade pip \
 && pip install --no-cache-dir -r /tmp/requirements.txt

COPY authenticator_2fa.py /work/authenticator_2fa.py
COPY authenticator_no_2fa.py /work/authenticator_no_2fa.py
CMD ["bash","-lc","sleep infinity"]

# Run our runtime init AFTER the container is created (post-start equivalent)
ENTRYPOINT ["/usr/local/bin/ibkr-entrypoint.sh"]
