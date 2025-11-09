FROM debian:12.6-slim

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

ENV VENV_PATH=/opt/venv
RUN python3 -m venv "${VENV_PATH}"
ENV PATH="${VENV_PATH}/bin:${PATH}"

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --upgrade pip \
 && pip install --no-cache-dir -r /tmp/requirements.txt

WORKDIR /app
COPY authenticator_2fa.py /app/authenticator_2fa.py
COPY app2.py /authenticator_no_2fa/authenticator_no_2fa.py
CMD ["bash","-lc","sleep infinity"]
