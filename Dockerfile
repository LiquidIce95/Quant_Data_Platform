FROM apache/airflow:2.10.2-python3.10

USER root

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        wget \
        gnupg \
        software-properties-common \
        curl \
        ca-certificates && \
    wget -O- https://apt.releases.hashicorp.com/gpg | \
        gpg --dearmor > /usr/share/keyrings/hashicorp-archive-keyring.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" > /etc/apt/sources.list.d/hashicorp.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends terraform && \
    curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash && \
    curl -Lo /usr/local/bin/kind https://kind.sigs.k8s.io/dl/v0.23.0/kind-linux-amd64 && \
    chmod +x /usr/local/bin/kind && \
    apt-get clean && rm -rf /var/lib/apt/lists/*


WORKDIR /usr/local/airflow

COPY . .

ENV AIRFLOW_HOME=/usr/local/airflow

USER airflow


RUN pip install --no-cache-dir -r requirements.txt

