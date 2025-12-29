
#!/usr/bin/env bash
set -euo pipefail

# Assumptions (ONE-TIME, already done and correct):
# - You run this with a kubectl context pointing to the Hetzner K3s cluster (usually via SSH tunnel).
# - Azure auth is already available in the shell running this script:
#   ACR_LOGIN_SERVER, SP_APP_ID, SP_PASSWORD, CH_PASSWORD
# - K3s nodes are already registered by Terraform (this script just runs terraform apply and verifies nodes exist).
# - Cluster is already configured for:
#   - ACR pulls via per-namespace imagePullSecret "acr-pull"
#   - KeyVault pulls via Workload Identity + CSI driver (IB connector manifests reference this)
# - All manifests are in infra_prod and are tested; this script only injects namespace + image refs via envsubst.

# ========= Environment prefix (HARD separation) =========
ENVIRONMENT_PREFIX="test"

# ========= Deterministic image naming =========
IMAGE_TAG="${IMAGE_TAG:-dev}"

# ========= Namespaces =========
NAMESPACE_IB_LEGACY="${ENVIRONMENT_PREFIX}-ib-connector-legacy"
NAMESPACE_IB="${ENVIRONMENT_PREFIX}-ib-connector"
NAMESPACE_KAFKA="${ENVIRONMENT_PREFIX}-kafka"
NAMESPACE_SPARK="${ENVIRONMENT_PREFIX}-spark"
NAMESPACE_CLICKHOUSE="${ENVIRONMENT_PREFIX}-clickhouse"
NAMESPACE_AVRO="${ENVIRONMENT_PREFIX}-avro-schema-registry"

# ========= Kafka / Strimzi =========
KAFKA_NAME="${ENVIRONMENT_PREFIX}-kafka"
STRIMZI_URL="https://strimzi.io/install/latest?namespace=${NAMESPACE_KAFKA}"
BOOTSTRAP_LOCAL_PORT="${BOOTSTRAP_LOCAL_PORT:-9092}"

# ========= Spark (K8s) =========
SPARK_SA="${SPARK_SA:-spark-sa}"
SPARK_VERSION="${SPARK_VERSION:-3.5.7}"
SPARK_IMAGE_TAG_LOCAL="our-own-apache-spark-kb8"
APP_IMAGE_TAG_LOCAL="${SPARK_IMAGE_TAG_LOCAL}-app"
SPARK_APP_CLASS="${SPARK_APP_CLASS:-com.yourorg.spark.ReadTickLastPrint}"

# ========= Secrets =========
ACR_PULL_SECRET_NAME="acr-pull"
CH_PASSWORD_SECRET_NAME="clickhouse-password"

# ========= Images (ACR) =========
IMG_IB_CONNECTOR="${ACR_LOGIN_SERVER}/${ENVIRONMENT_PREFIX}-ib-connector:${IMAGE_TAG}"
IMG_IB_LEGACY="${ACR_LOGIN_SERVER}/${ENVIRONMENT_PREFIX}-ib-connector-legacy:${IMAGE_TAG}"
IMG_CLICKHOUSE="${ACR_LOGIN_SERVER}/${ENVIRONMENT_PREFIX}-clickhouse:${IMAGE_TAG}"
IMG_SPARK_BASE="${ACR_LOGIN_SERVER}/${ENVIRONMENT_PREFIX}-spark-base:${IMAGE_TAG}"
IMG_SPARK_APP="${ACR_LOGIN_SERVER}/${ENVIRONMENT_PREFIX}-spark-app:${IMAGE_TAG}"

# ========= Repo paths =========
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

KAFKA_DIR="$ROOT/services_streaming_lane/kafka_message_broker"
IB_DIR_LEGACY="$ROOT/services_streaming_lane/ib_connector_legacy/source"
SPARK_DIR="$ROOT/services_streaming_lane/spark_processor"
SPARK_HOME="$ROOT/services_streaming_lane/spark-${SPARK_VERSION}-bin-hadoop3"

CLICKHOUSE_DIR="$ROOT/services_streaming_lane/click_house"
CLICKHOUSE_INFRA_DIR="$CLICKHOUSE_DIR/infra_prod"

# ========= Kafka manifests (infra_prod) =========
NS_FILE="$KAFKA_DIR/infra_prod/00-namespace.yml"
KAFKA_FILE="$KAFKA_DIR/infra_prod/10-kafka-cluster.yml"
TOPICS_FILE="$KAFKA_DIR/infra_prod/20-topics.yml"

# ========= Spark infra files (infra_prod) =========
SPARK_NS_FILE="$SPARK_DIR/infra_prod/00-namespace.yml"
SPARK_RBAC_FILE="$SPARK_DIR/infra_prod/10-rbac.yml"
SPARK_DRIVER_POD_TMPL="$SPARK_DIR/infra_prod/20-driver-pod-template.yml"
SPARK_EXEC_POD_TMPL="$SPARK_DIR/infra_prod/21-executor-pod-template.yml"
SPARK_DEFAULTS_FILE="$SPARK_DIR/infra_prod/30-spark-defaults.conf"

# ========= ClickHouse infra files (infra_prod) =========
CLICKHOUSE_NS_FILE="$CLICKHOUSE_INFRA_DIR/00-namespace.yml"
CLICKHOUSE_POD_FILE="$CLICKHOUSE_INFRA_DIR/10-clickhouse-pod.yml"
CLICKHOUSE_SVC_FILE="$CLICKHOUSE_INFRA_DIR/20-clickhouse-svc.yml"

# ========= IB Connector (legacy) infra (infra_prod) =========
IB_NS_FILE_LEGACY="$ROOT/services_streaming_lane/ib_connector_legacy/infra_prod/00-namespace.yml"
IB_POD_FILE_LEGACY="$ROOT/services_streaming_lane/ib_connector_legacy/infra_prod/10-ib-connector-pod.yml"

# ========= IB Connector (current) infra (infra_prod) =========
IB_DIR="$ROOT/services_streaming_lane/ib_connector"
IB_SRC_DIR="$IB_DIR/source"
IB_NS_FILE="$IB_DIR/infra_prod/00-namespace.yml"
IB_RBAC_FILE="$IB_DIR/infra_prod/05-ib-connector-rbac.yml"
IB_POD_FILE="$IB_DIR/infra_prod/10-ib-connector-pod.yml"

# ========= Avro Schema Registry (infra_prod) =========
AVRO_REG_DIR="$ROOT/services_streaming_lane/avro_schema_registry"
AVRO_REG_INFRA_DIR="$AVRO_REG_DIR/infra_prod"
AVRO_SCHEMA_DIR="$AVRO_REG_DIR/category_schemas"

AVRO_REG_NS_FILE="$AVRO_REG_INFRA_DIR/01-namespace.yml"
AVRO_REG_CFG_FILE="$AVRO_REG_INFRA_DIR/02-configmap-schema-registry.yml"
AVRO_REG_DEPLOY_FILE="$AVRO_REG_INFRA_DIR/03-deployment-schema-registry.yml"
AVRO_REG_SVC_FILE="$AVRO_REG_INFRA_DIR/04-service-schema-registry.yml"

AVRO_REG_SVC_NAME="${AVRO_REG_SVC_NAME:-schema-registry}"
AVRO_REG_LOCAL_PORT="${AVRO_REG_LOCAL_PORT:-8081}"
AVRO_REG_COMPATIBILITY="${AVRO_REG_COMPATIBILITY:-BACKWARD}"

AVRO_SCHEMA_TICK="$AVRO_SCHEMA_DIR/derivatives_tick_market_data.avsc"
AVRO_SCHEMA_L2="$AVRO_SCHEMA_DIR/derivatives_l2_market_data.avsc"
AVRO_SCHEMA_IND="$AVRO_SCHEMA_DIR/indicators.avsc"
AVRO_SCHEMA_ECO="$AVRO_SCHEMA_DIR/numeric_economic_data.avsc"

# ========= JAR paths (ABSOLUTE, NO find/symlink) =========
JAR_DEST="$ROOT/services_streaming_lane/app.jar"
SBT_ASSEMBLY_ABS="${SPARK_DIR}/source/target/scala-2.12/spark-processor-assembly-0.1.0-SNAPSHOT.jar"
APP_JAR_PATH_IN_IMAGE="/opt/spark/app/app.jar"


# ========= Helpers =========
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1"; exit 1; }; }
have() { [[ -f "$1" ]] || { echo "Required file not found: $1"; exit 1; }; }

require_env() {
	[[ -n "${ACR_LOGIN_SERVER:-}" ]] || { echo "ERROR: missing ACR_LOGIN_SERVER"; exit 1; }
	[[ -n "${SP_APP_ID:-}" ]] || { echo "ERROR: missing SP_APP_ID"; exit 1; }
	[[ -n "${SP_PASSWORD:-}" ]] || { echo "ERROR: missing SP_PASSWORD"; exit 1; }
	[[ -n "${CH_PASSWORD:-}" ]] || { echo "ERROR: missing CH_PASSWORD"; exit 1; }
}

# ========= Namespace secrets =========
apply_acr_pull_secret() {
	local ns="$1"
	kubectl -n "$ns" create secret docker-registry "${ACR_PULL_SECRET_NAME}" \
		--docker-server="$ACR_LOGIN_SERVER" \
		--docker-username="$SP_APP_ID" \
		--docker-password="$SP_PASSWORD" \
		--docker-email=unused@example.com \
		--dry-run=client -o yaml | kubectl apply -f -
}

apply_ch_password_secret() {
	local ns="$1"
	kubectl -n "$ns" create secret generic "${CH_PASSWORD_SECRET_NAME}" \
		--from-literal=password="$CH_PASSWORD" \
		--dry-run=client -o yaml | kubectl apply -f -
}

# ========= Cluster creation (Terraform) =========
create_cluster_test() {
	need terraform

	local TF_DIR="$ROOT/services_streaming_lane/test_env"
	[[ -d "$TF_DIR" ]] || { echo "Missing terraform dir: $TF_DIR"; exit 1; }

	(
		cd "$TF_DIR"
		terraform init
		terraform apply -auto-approve
	)

	kubectl get nodes -o wide

	local CNT
	CNT="$(kubectl get nodes --no-headers | awk '{print $1}' | grep -E "^${ENVIRONMENT_PREFIX}-worker" | wc -l | tr -d ' ')"
	if [[ "$CNT" == "0" ]]; then
		echo "ERROR: no nodes found with prefix '${ENVIRONMENT_PREFIX}-worker'." >&2
		exit 1
	fi
}

# ========= Kafka / Strimzi =========
install_strimzi() {
	have "$NS_FILE"; have "$KAFKA_FILE"
	export NAMESPACE_KAFKA
	envsubst < "$NS_FILE" | kubectl apply -f -

	apply_acr_pull_secret "$NAMESPACE_KAFKA"
	kubectl apply -n "$NAMESPACE_KAFKA" -f "$STRIMZI_URL"
	kubectl -n "$NAMESPACE_KAFKA" rollout status deployment/strimzi-cluster-operator
}

apply_kafka_cluster() {
	export KAFKA_NAME NAMESPACE_KAFKA
	envsubst < "$KAFKA_FILE" | kubectl apply -n "$NAMESPACE_KAFKA" -f -
	kubectl -n "$NAMESPACE_KAFKA" wait --for=condition=Ready "kafka/${KAFKA_NAME}" --timeout=300s
}

apply_topics() {
	have "$TOPICS_FILE"
	export KAFKA_NAME NAMESPACE_KAFKA
	envsubst < "$TOPICS_FILE" | kubectl apply -n "$NAMESPACE_KAFKA" -f -
}

deploy_kafka() { install_strimzi; apply_kafka_cluster; apply_topics; }

port_forward() {
	kubectl -n "$NAMESPACE_KAFKA" port-forward "svc/${KAFKA_NAME}-kafka-bootstrap" "${BOOTSTRAP_LOCAL_PORT}:9092"
}

peek_topic_ticklast() {
	local NS="${NAMESPACE_KAFKA}"
	local BOOTSTRAP="${KAFKA_NAME}-kafka-bootstrap.${NS}:9092"

	kubectl -n "${NS}" run -it --rm kcat-tail-derivatives-tick-market-data \
		--image=edenhill/kcat:1.7.1 --restart=Never -- \
		-b "${BOOTSTRAP}" -t derivatives_tick_market_data -C -o -10 -e -q
}

peek_topic_l2_data() {
	local BOOTSTRAP="${KAFKA_NAME}-kafka-bootstrap.${NAMESPACE_KAFKA}:9092"
	kubectl -n "${NAMESPACE_KAFKA}" run -it --rm kcat-tail-l2 \
		--image=edenhill/kcat:1.7.1 --restart=Never -- \
		-b "${BOOTSTRAP}" -t l2-data -C -o -10 -e -q
}

# ========= Avro Schema Registry =========
deploy_avro_registry_schema() {
	have "$AVRO_REG_NS_FILE"
	have "$AVRO_REG_CFG_FILE"
	have "$AVRO_REG_DEPLOY_FILE"
	have "$AVRO_REG_SVC_FILE"

	export NAMESPACE_AVRO
	envsubst < "$AVRO_REG_NS_FILE" | kubectl apply -f -
	apply_acr_pull_secret "$NAMESPACE_AVRO"

	export NAMESPACE_AVRO NAMESPACE_KAFKA KAFKA_NAME
	envsubst < "$AVRO_REG_CFG_FILE" | kubectl apply -n "$NAMESPACE_AVRO" -f -
	envsubst < "$AVRO_REG_DEPLOY_FILE" | kubectl apply -n "$NAMESPACE_AVRO" -f -
	envsubst < "$AVRO_REG_SVC_FILE" | kubectl apply -n "$NAMESPACE_AVRO" -f -

	kubectl -n "$NAMESPACE_AVRO" rollout status deployment/schema-registry --timeout=400s || true
	kubectl -n "$NAMESPACE_AVRO" get pods -l app=schema-registry -o wide || true
	kubectl -n "$NAMESPACE_AVRO" get svc "$AVRO_REG_SVC_NAME" -o wide || true
}

register_avro_schemas() {
	need curl
	need jq

	have "$AVRO_SCHEMA_TICK"
	have "$AVRO_SCHEMA_L2"
	have "$AVRO_SCHEMA_IND"
	have "$AVRO_SCHEMA_ECO"

	local PF_PID=""
	local REG_URL="http://127.0.0.1:${AVRO_REG_LOCAL_PORT}"

	kubectl -n "$NAMESPACE_AVRO" port-forward "svc/${AVRO_REG_SVC_NAME}" "${AVRO_REG_LOCAL_PORT}:8081" >/dev/null 2>&1 &
	PF_PID="$!"

	( sleep 1 )

	if ! curl -fsS "${REG_URL}/subjects" >/dev/null 2>&1; then
		kill "$PF_PID" >/dev/null 2>&1 || true
		echo "[schema-registry] Registry not reachable via port-forward on ${REG_URL}" >&2
		exit 1
	fi

	register_one_schema() {
		local FILE="$1"
		local TOPIC="$2"
		local SUBJECT="${TOPIC}-value"
		local PAYLOAD
		PAYLOAD="$(jq -Rs '{schema: .}' < "$FILE")"

		curl -fsS -X PUT \
			-H "Content-Type: application/vnd.schemaregistry.v1+json" \
			--data "{\"compatibility\":\"${AVRO_REG_COMPATIBILITY}\"}" \
			"${REG_URL}/config/${SUBJECT}" >/dev/null

		curl -fsS -X POST \
			-H "Content-Type: application/vnd.schemaregistry.v1+json" \
			--data "$PAYLOAD" \
			"${REG_URL}/subjects/${SUBJECT}/versions" >/dev/null
	}

	register_one_schema "$AVRO_SCHEMA_TICK" "derivatives_tick_market_data"
	register_one_schema "$AVRO_SCHEMA_L2" "derivatives_l2_market_data"
	register_one_schema "$AVRO_SCHEMA_IND" "indicators"
	register_one_schema "$AVRO_SCHEMA_ECO" "numeric_economic_data"

	kill "$PF_PID" >/dev/null 2>&1 || true
}

# ========= IB Connector (LEGACY) =========
build_push_ib_connector_legacy() {
	need docker
	have "$IB_DIR_LEGACY/Dockerfile"
	docker build -t "$IMG_IB_LEGACY" "$IB_DIR_LEGACY"
	docker push "$IMG_IB_LEGACY"
}

deploy_ib_connector_legacy() {
	need envsubst
	have "$IB_NS_FILE_LEGACY"
	have "$IB_POD_FILE_LEGACY"

	export NAMESPACE_IB_LEGACY
	envsubst < "$IB_NS_FILE_LEGACY" | kubectl apply -f -
	apply_acr_pull_secret "$NAMESPACE_IB_LEGACY"

	export NAMESPACE_IB_LEGACY NAMESPACE_KAFKA KAFKA_NAME IMG_IB_LEGACY
	envsubst < "$IB_POD_FILE_LEGACY" | kubectl apply -f -

	kubectl -n "$NAMESPACE_IB_LEGACY" wait --for=condition=Ready pod/ib-connector-legacy --timeout=180s || true
	kubectl -n "$NAMESPACE_IB_LEGACY" get pods -o wide || true
}

# ========= IB Connector (CURRENT) =========
build_push_ib_connector() {
	need docker
	have "$IB_SRC_DIR/Dockerfile"
	have "$IB_DIR/infra_prod/ibkr_truststore.jks"
	have "$IB_DIR/infra_prod/ibkr_client_portal.pem"

	docker build \
		-f "$IB_SRC_DIR/Dockerfile" \
		-t "$IMG_IB_CONNECTOR" \
		"$ROOT/services_streaming_lane"

	docker push "$IMG_IB_CONNECTOR"
}

deploy_ib_connector() {
	need envsubst
	have "$IB_NS_FILE"
	have "$IB_RBAC_FILE"
	have "$IB_POD_FILE"

	export NAMESPACE_IB
	envsubst < "$IB_NS_FILE" | kubectl apply -f -
	apply_acr_pull_secret "$NAMESPACE_IB"

	export NAMESPACE_IB NAMESPACE_KAFKA KAFKA_NAME
	envsubst < "$IB_RBAC_FILE" | kubectl apply -f -

	export NAMESPACE_IB NAMESPACE_KAFKA KAFKA_NAME IMG_IB_CONNECTOR
	envsubst < "$IB_POD_FILE" | kubectl apply -f -

	kubectl -n "$NAMESPACE_IB" wait --for=condition=Ready pod/ib-connector --timeout=180s || true
	kubectl -n "$NAMESPACE_IB" get pods -o wide || true
}

# ========= Spark (build + push deterministic ACR images) =========
build_base_spark_image() {
	need docker
	have "$SPARK_HOME/bin/docker-image-tool.sh"
	(cd "$SPARK_HOME" && sudo ./bin/docker-image-tool.sh -t "$SPARK_IMAGE_TAG_LOCAL" build)
	docker tag "spark:${SPARK_IMAGE_TAG_LOCAL}" "${IMG_SPARK_BASE}"
	docker push "${IMG_SPARK_BASE}"
}

build_fat_jar() {
	need docker
	local SRC_DIR="${SPARK_DIR%/}/source"
	have "${SRC_DIR}/build.sbt"

	docker run --rm \
		-v "${SRC_DIR}":/work \
		-v "$HOME/.ivy2":/root/.ivy2 \
		-v "$HOME/.sbt":/root/.sbt \
		-v "$HOME/.cache/coursier":/root/.cache/coursier \
		-w /work \
		docker.io/sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.11.6_2.12.20 \
		sbt -batch clean assembly

	have "${SBT_ASSEMBLY_ABS}"
	mkdir -p "$(dirname "${JAR_DEST}")"
	cp -f "${SBT_ASSEMBLY_ABS}" "${JAR_DEST}"
	chmod a+r "${JAR_DEST}"
}

build_app_image() {
	need docker
	have "${JAR_DEST}"

	( cd "$ROOT" && docker build -t "spark:${APP_IMAGE_TAG_LOCAL}" -f- . <<DOCKERFILE
FROM ${IMG_SPARK_BASE}
COPY services_streaming_lane/app.jar ${APP_JAR_PATH_IN_IMAGE}
DOCKERFILE
	)

	docker tag "spark:${APP_IMAGE_TAG_LOCAL}" "${IMG_SPARK_APP}"
	docker push "${IMG_SPARK_APP}"
}

deploy_spark() {
	need envsubst
	have "$SPARK_NS_FILE"; have "$SPARK_RBAC_FILE"; have "$SPARK_DEFAULTS_FILE"
	export NAMESPACE_SPARK
	envsubst < "$SPARK_NS_FILE" | kubectl apply -f -
	apply_acr_pull_secret "$NAMESPACE_SPARK"
	apply_ch_password_secret "$NAMESPACE_SPARK"

	kubectl apply -f "$SPARK_RBAC_FILE"

	build_base_spark_image
	build_fat_jar
	build_app_image
}

start_spark_sim() {
	local K8S_SERVER
	K8S_SERVER="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')"
	have "${JAR_DEST}"

	"${SPARK_HOME}/bin/spark-submit" \
		--master "k8s://${K8S_SERVER}" \
		--deploy-mode cluster \
		--name "${ENVIRONMENT_PREFIX}-spark-app" \
		--class "${SPARK_APP_CLASS}" \
		--conf "spark.kubernetes.namespace=${NAMESPACE_SPARK}" \
		--conf "spark.kubernetes.authenticate.driver.serviceAccountName=${SPARK_SA}" \
		--conf "spark.kubernetes.container.image=${IMG_SPARK_APP}" \
		--conf "spark.kubernetes.container.image.pullPolicy=IfNotPresent" \
		--conf "spark.kubernetes.driver.podTemplateFile=${SPARK_DRIVER_POD_TMPL}" \
		--conf "spark.kubernetes.executor.podTemplateFile=${SPARK_EXEC_POD_TMPL}" \
		--properties-file "${SPARK_DEFAULTS_FILE}" \
		"local://${APP_JAR_PATH_IN_IMAGE}"

	kubectl -n "${NAMESPACE_SPARK}" logs -f "$(kubectl -n "${NAMESPACE_SPARK}" get pods -l spark-role=driver -o name | tail -n1 | cut -d/ -f2)" || true
}

# ========= ClickHouse =========
build_push_clickhouse() {
	need docker
	have "$CLICKHOUSE_DIR/Dockerfile"
	have "$CLICKHOUSE_DIR/create_data_model.sql"
	(cd "$CLICKHOUSE_DIR" && docker build -t "${IMG_CLICKHOUSE}" .)
	docker push "${IMG_CLICKHOUSE}"
}

deploy_clickhouse() {
	need envsubst
	have "${CLICKHOUSE_NS_FILE}"
	have "${CLICKHOUSE_POD_FILE}"
	have "${CLICKHOUSE_SVC_FILE}"

	export NAMESPACE_CLICKHOUSE
	envsubst < "${CLICKHOUSE_NS_FILE}" | kubectl apply -f -
	apply_acr_pull_secret "$NAMESPACE_CLICKHOUSE"
	apply_ch_password_secret "$NAMESPACE_CLICKHOUSE"

	export NAMESPACE_CLICKHOUSE IMG_CLICKHOUSE
	envsubst < "${CLICKHOUSE_POD_FILE}" | kubectl apply -f -
	envsubst < "${CLICKHOUSE_SVC_FILE}" | kubectl apply -f -

	kubectl -n "${NAMESPACE_CLICKHOUSE}" wait --for=condition=Ready pod/clickhouse --timeout=240s || true
	kubectl -n "${NAMESPACE_CLICKHOUSE}" get pods -o wide || true
	kubectl -n "${NAMESPACE_CLICKHOUSE}" get svc clickhouse -o wide || true
}

# ========= Batch image build =========
build_push_images() {
	build_push_clickhouse
	build_push_ib_connector_legacy
	build_push_ib_connector
	deploy_spark
}

# ========= Misc =========
status() {
	echo "== Nodes =="; kubectl get nodes -o wide || true
	echo "== Kafka =="; kubectl -n "$NAMESPACE_KAFKA" get pods -o wide || true
	echo "== Spark =="; kubectl -n "$NAMESPACE_SPARK" get pods -o wide || true
	echo "== ClickHouse =="; kubectl -n "$NAMESPACE_CLICKHOUSE" get pods -o wide || true
	echo "== IB =="; kubectl -n "$NAMESPACE_IB" get pods -o wide || true
	echo "== IB Legacy =="; kubectl -n "$NAMESPACE_IB_LEGACY" get pods -o wide || true
	echo "== Avro =="; kubectl -n "$NAMESPACE_AVRO" get pods -o wide || true
}

usage() {
	cat <<EOF
Usage: $0 <command>

Cluster:
  create_cluster_test

Images:
  build_push_images
  build_push_clickhouse
  build_push_ib_connector_legacy
  build_push_ib_connector
  deploy_spark

Infra:
  deploy_kafka
  deploy_avro_registry_schema
  register_avro_schemas
  deploy_ib_connector_legacy
  deploy_ib_connector
  deploy_clickhouse

Spark run:
  start_spark_sim

Ops:
  status
  pf
  peek_topic_ticklast
  peek_topic_l2_data

EOF
}

# ========= Main =========
need kubectl
need envsubst
require_env

cmd="${1:-help}"
case "$cmd" in
	create_cluster_test) create_cluster_test ;;

	build_push_images) build_push_images ;;
	build_push_clickhouse) build_push_clickhouse ;;
	build_push_ib_connector_legacy) build_push_ib_connector_legacy ;;
	build_push_ib_connector) build_push_ib_connector ;;
	deploy_spark) deploy_spark ;;

	deploy_kafka) deploy_kafka ;;
	deploy_avro_registry_schema) deploy_avro_registry_schema ;;
	register_avro_schemas) register_avro_schemas ;;
	deploy_ib_connector_legacy) deploy_ib_connector_legacy ;;
	deploy_ib_connector) deploy_ib_connector ;;
	deploy_clickhouse) deploy_clickhouse ;;

	start_spark_sim) start_spark_sim ;;

	pf) port_forward ;;
	peek_topic_ticklast) peek_topic_ticklast ;;
	peek_topic_l2_data) peek_topic_l2_data ;;
	status) status ;;
	help|*) usage ;;
esac

