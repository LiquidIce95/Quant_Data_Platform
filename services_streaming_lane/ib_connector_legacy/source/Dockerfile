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
