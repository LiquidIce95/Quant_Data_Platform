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
CMD ["bash", "-l"]

# Run our runtime init AFTER the container is created (post-start equivalent)
ENTRYPOINT ["/usr/local/bin/ibkr-entrypoint.sh"]
