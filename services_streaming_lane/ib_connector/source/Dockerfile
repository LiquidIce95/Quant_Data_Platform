FROM debian:12.6-slim

USER root

# Install necessary dependencies
RUN apt-get update && apt-get install -y \
	git \
	openssh-client \
	bash \
	lsof \
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
	  libnss3 libasound2 libxshmfence1 fonts-liberation \
 && rm -rf /var/lib/apt/lists/*

# install sbt and scala

# Install sbt 1.11.6 explicitly
RUN curl -L -o /tmp/sbt.tgz https://github.com/sbt/sbt/releases/download/v1.11.6/sbt-1.11.6.tgz \
 && mkdir -p /usr/local/sbt \
 && tar -xzf /tmp/sbt.tgz -C /usr/local/sbt --strip-components=1 \
 && ln -s /usr/local/sbt/bin/sbt /usr/local/bin/sbt \
 && rm /tmp/sbt.tgz

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
