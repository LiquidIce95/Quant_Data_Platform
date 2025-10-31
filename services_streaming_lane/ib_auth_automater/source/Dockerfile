FROM python:3.12-slim

# OS deps: Chromium, Chromedriver, and runtime libs for headless
RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium chromium-driver \
    ca-certificates curl bash \
    libnss3 libasound2 libxshmfence1 fonts-liberation \
 && rm -rf /var/lib/apt/lists/*

# Use a venv so we don't hit Debian's PEP 668 guard
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:${PATH}"

# Python dependencies
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --upgrade pip \
 && pip install --no-cache-dir -r /tmp/requirements.txt

WORKDIR /app
COPY app.py /app/app.py

# Your app already points to /usr/bin/chromedriver and sets --no-sandbox
ENTRYPOINT ["python", "/app/app.py"]
