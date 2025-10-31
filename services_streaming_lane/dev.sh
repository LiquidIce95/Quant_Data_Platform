#!/usr/bin/env bash
set -euo pipefail

# ========= Settings (override via env if needed) =========
CLUSTER_NAME="${CLUSTER_NAME:-kind}"

# IB namespace (legacy kept separate)
NAMESPACE_IB_LEGACY="${NAMESPACE_IB_LEGACY:-ib-connector-legacy}"
NAMESPACE_IB="${NAMESPACE_IB:-ib-connector}"

# Kafka / Strimzi
NAMESPACE_KAFKA="${NAMESPACE_KAFKA:-kafka}"
KAFKA_NAME="${KAFKA_NAME:-dev-kafka}"
STRIMZI_URL="${STRIMZI_URL:-https://strimzi.io/install/latest?namespace=${NAMESPACE_KAFKA}}"
BOOTSTRAP_LOCAL_PORT="${BOOTSTRAP_LOCAL_PORT:-9092}"

# Spark (K8s)
NAMESPACE_SPARK="${NAMESPACE_SPARK:-spark}"
SPARK_SA="${SPARK_SA:-spark-sa}"
SPARK_VERSION="${SPARK_VERSION:-3.5.7}"
SPARK_IMAGE_TAG="${SPARK_IMAGE_TAG:-our-own-apache-spark-kb8}"
APP_IMAGE_TAG="${APP_IMAGE_TAG:-${SPARK_IMAGE_TAG}-app}"
SPARK_APP_CLASS="${SPARK_APP_CLASS:-com.yourorg.spark.ReadTickLastPrint}"

# ClickHouse (runs in Spark ns by default)
NAMESPACE_CLICKHOUSE="${NAMESPACE_CLICKHOUSE:-${NAMESPACE_SPARK}}"
CLICKHOUSE_IMAGE_TAG="${CLICKHOUSE_IMAGE_TAG:-clickhouse:dev}"

# Node labels
LBL_KEY="streamlane/role"
LBL_VAL_KAFKA="kafka"
LBL_VAL_SPARK="spark"

# ========= Repo paths =========
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

KAFKA_DIR="$ROOT/services_streaming_lane/kafka_message_broker"
IB_DIR_LEGACY="$ROOT/services_streaming_lane/ib_connector_legacy/source"
SPARK_DIR="$ROOT/services_streaming_lane/spark_processor"
SPARK_HOME="$ROOT/services_streaming_lane/spark-${SPARK_VERSION}-bin-hadoop3"

CLICKHOUSE_DIR="$ROOT/services_streaming_lane/click_house"
CLICKHOUSE_INFRA_DIR="$CLICKHOUSE_DIR/infra"

# Kafka manifests
NS_FILE="$KAFKA_DIR/00-namespace.yml"
KAFKA_FILE="$KAFKA_DIR/10-kafka-cluster.yml"
TOPICS_FILE="$KAFKA_DIR/20-topics.yml"

# Spark infra files
SPARK_NS_FILE="$SPARK_DIR/infra/00-namespace.yml"
SPARK_RBAC_FILE="$SPARK_DIR/infra/10-rbac.yml"
SPARK_DRIVER_POD_TMPL="$SPARK_DIR/infra/20-driver-pod-template.yml"
SPARK_EXEC_POD_TMPL="$SPARK_DIR/infra/21-executor-pod-template.yml"
SPARK_DEFAULTS_FILE="$SPARK_DIR/infra/30-spark-defaults.conf"

# ========= IB Connector (legacy) =========
IB_NS_FILE_LEGACY="$ROOT/services_streaming_lane/ib_connector_legacy/infra/00-namespace.yml"
IB_POD_FILE_LEGACY="$ROOT/services_streaming_lane/ib_connector_legacy/infra/10-ib-connector-pod.yml"

# ========= Client Portal =========
CLIENT_PORTAL_NS="${CLIENT_PORTAL_NS:-client-portal-api}"
CLIENT_PORTAL_DIR="$ROOT/services_streaming_lane/ib_client_portal_api"
CLIENT_PORTAL_SRC_DIR="$CLIENT_PORTAL_DIR/source"
CLIENT_PORTAL_IMG="client-portal:1.0.0"

CLIENT_PORTAL_NS_FILE="$CLIENT_PORTAL_DIR/infra/00-namespace.yml"
CLIENT_PORTAL_DEPLOY_FILE="$CLIENT_PORTAL_DIR/infra/30-client-portal-deployment.yml"
CLIENT_PORTAL_SVC_FILE="$CLIENT_PORTAL_DIR/infra/40-client-portal-service.yml"

# ========= IB Connector (current project) =========
IB_DIR="$ROOT/services_streaming_lane/ib_connector"
IB_SRC_DIR="$IB_DIR/source"
IB_NS_FILE="$IB_DIR/infra/00-namespace.yml"
IB_POD_FILE="$IB_DIR/infra/10-ib-connector-pod.yml"
IB_IMG="ib-connector:dev"


# ========= JAR paths (ABSOLUTE, NO find/symlink) =========
JAR_DEST="$ROOT/services_streaming_lane/app.jar"
SBT_ASSEMBLY_ABS="${SPARK_DIR}/source/target/scala-2.12/spark-processor-assembly-0.1.0-SNAPSHOT.jar"
APP_JAR_PATH_IN_IMAGE="/opt/spark/app/app.jar"

# ========= Helpers =========
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1"; exit 1; }; }
have() { [[ -f "$1" ]] || { echo "Required file not found: $1"; exit 1; }; }
ns_exists() { kubectl get ns "$1" >/dev/null 2>&1; }

# ========= Cluster creation (kind) =========
create_cluster_dev() {
	need kind; need kubectl
	kind delete cluster || true
	kind create cluster --config kind-config.yml
	kubectl label nodes kind-worker  "${LBL_KEY}=${LBL_VAL_KAFKA}" --overwrite
	kubectl label nodes kind-worker2 "${LBL_KEY}=${LBL_VAL_SPARK}" --overwrite
	kubectl get nodes --show-labels
}

# ========= Misc =========
status() {
	echo "== Nodes =="; kubectl get nodes -o wide --show-labels || true
	echo "== Kafka Pods =="; kubectl -n "$NAMESPACE_KAFKA" get pods -o wide || true
	echo "== Services (kafka) =="; kubectl -n "$NAMESPACE_KAFKA" get svc || true
	echo "== Topics =="; kubectl -n "$NAMESPACE_KAFKA" get kafkatopic || true
	echo "== Spark Pods =="; kubectl -n "$NAMESPACE_SPARK" get pods -o wide || true
	echo "== ClickHouse Pods =="; kubectl -n "$NAMESPACE_CLICKHOUSE" get pods -o wide || true
	echo "== ClickHouse Svc =="; kubectl -n "$NAMESPACE_CLICKHOUSE" get svc clickhouse -o wide || true
	echo "== ClientPortalAPI =="; kubectl -n "$CLIENT_PORTAL_NS" get pods -o wide || true
	echo "== IbConnector =="; kubectl -n "$NAMESPACE_IB" get pods -o wide || true
	echo "== IbConnectorLegacy =="; kubectl -n "$NAMESPACE_IB_LEGACY" get pods -o wide || true
}

down() { kind delete cluster --name "${CLUSTER_NAME}" || true; }

# ========= Kafka / Strimzi =========
install_strimzi() {
	have "$NS_FILE"; have "$KAFKA_FILE"
	kubectl apply -f "$NS_FILE"
	kubectl apply -n "$NAMESPACE_KAFKA" -f "$STRIMZI_URL"
	kubectl -n "$NAMESPACE_KAFKA" rollout status deployment/strimzi-cluster-operator
}

apply_kafka_cluster() {
	kubectl apply -n "$NAMESPACE_KAFKA" -f "$KAFKA_FILE"
	kubectl -n "$NAMESPACE_KAFKA" wait --for=condition=Ready "kafka/${KAFKA_NAME}" --timeout=300s
}

apply_topics() {
	have "$TOPICS_FILE"
	kubectl apply -n "$NAMESPACE_KAFKA" -f "$TOPICS_FILE"
}

deploy_kafka() { install_strimzi; apply_kafka_cluster; apply_topics; }

peek_topic_ticklast() {
	need kubectl
	local BOOTSTRAP="${KAFKA_NAME}-kafka-bootstrap.${NAMESPACE_KAFKA}:9092"
	kubectl -n "${NAMESPACE_KAFKA}" run -it --rm kcat-tail-ticklast \
		--image=edenhill/kcat:1.7.1 --restart=Never -- \
		-b "${BOOTSTRAP}" -t ticklast -C -o -10 -e -q
}

peek_topic_l2_data() {
	need kubectl
	local BOOTSTRAP="${KAFKA_NAME}-kafka-bootstrap.${NAMESPACE_KAFKA}:9092"
	kubectl -n "${NAMESPACE_KAFKA}" run -it --rm kcat-tail-l2 \
		--image=edenhill/kcat:1.7.1 --restart=Never -- \
		-b "${BOOTSTRAP}" -t l2-data -C -o -10 -e -q
}

port_forward() {
	kubectl -n "$NAMESPACE_KAFKA" port-forward "svc/${KAFKA_NAME}-kafka-bootstrap" "${BOOTSTRAP_LOCAL_PORT}:9092"
}

# ========= IB Connector (LEGACY) =========
deploy_ib_connector_legacy() {
	need docker; need kind; need kubectl; need envsubst
	have "$IB_DIR_LEGACY/Dockerfile"
	have "$IB_POD_FILE_LEGACY"
	have "$IB_NS_FILE_LEGACY"

	echo "[ib-connector-legacy] Applying namespace …"
	kubectl apply -f "$IB_NS_FILE_LEGACY"

	echo "[ib-connector-legacy] Building image ib-connector-legacy:dev from ${IB_DIR_LEGACY} …"
	docker build -t ib-connector-legacy:dev "$IB_DIR_LEGACY"

	echo "[ib-connector-legacy] Loading image into kind cluster '${CLUSTER_NAME}' …"
	kind load docker-image ib-connector-legacy:dev --name "$CLUSTER_NAME"

	echo "[ib-connector-legacy] Applying pod manifest …"
	export NAMESPACE_IB_LEGACY NAMESPACE_KAFKA KAFKA_NAME LBL_KEY LBL_VAL_KAFKA
	envsubst < "$IB_POD_FILE_LEGACY" | kubectl apply -f -

	echo "[ib-connector-legacy] Waiting for pod/ib-connector-legacy Ready …"
	kubectl -n "$NAMESPACE_IB_LEGACY" wait --for=condition=Ready pod/ib-connector-legacy --timeout=180s || true

	echo "[ib-connector-legacy] Pods:"
	kubectl -n "$NAMESPACE_IB_LEGACY" get pods -o wide || true
}

simulate_stream_legacy() {
	need kubectl
	local sim_id="${1:-1}"
	local interval_ms="${2:-250}"
	local max_ticks="${3:-1000}"
	kubectl -n "${NAMESPACE_IB_LEGACY}" exec -it ib-connector-legacy -- \
		bash -lc "cd /work && sbt -batch 'runMain src.main.scala.SimulateStreaming ${sim_id} ${interval_ms} ${max_ticks}'"
}

# ========= IB Connector (CURRENT) — stupid simple deploy =========
deploy_ib_connector() {
	need docker; need kind; need kubectl; need envsubst

	have "$IB_NS_FILE"
	have "$IB_POD_FILE"
	have "$IB_SRC_DIR/Dockerfile"

	# Ensure cert artifacts exist so the image can bake them in
	have "$IB_DIR/infra/ibkr_truststore.jks"
	have "$IB_DIR/infra/ibkr_client_portal.pem"

	echo "[ib-connector] Applying namespace …"
	kubectl apply -f "$IB_NS_FILE"

	echo "[ib-connector] Building image ib-connector:dev from ${IB_SRC_DIR} …"
	docker build -t ib-connector:dev "$IB_SRC_DIR"

	echo "[ib-connector] Loading image into kind cluster '${CLUSTER_NAME}' …"
	kind load docker-image ib-connector:dev --name "$CLUSTER_NAME"

	echo "[ib-connector] Applying pod manifest …"
	export NAMESPACE_IB NAMESPACE_KAFKA KAFKA_NAME LBL_KEY LBL_VAL_KAFKA
	envsubst < "$IB_POD_FILE" | kubectl apply -f -

	echo "[ib-connector] Waiting for pod/ib-connector Ready …"
	kubectl -n "$NAMESPACE_IB" wait --for=condition=Ready pod/ib-connector --timeout=180s || true

	echo "[ib-connector] Pods:"
	kubectl -n "$NAMESPACE_IB" get pods -o wide || true
}


# ========= Spark: base runtime image =========
build_base_spark_image() {
	need docker; need kind
	have "$SPARK_HOME/bin/docker-image-tool.sh"
	(cd "$SPARK_HOME" && sudo ./bin/docker-image-tool.sh -t "$SPARK_IMAGE_TAG" build)
	kind load docker-image "spark:${SPARK_IMAGE_TAG}" --name "${CLUSTER_NAME}"
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
	echo "[spark] JAR -> ${JAR_DEST}"
}

build_app_image() {
	need docker; need kind
	have "${JAR_DEST}"
	( cd "$ROOT" && docker build -t "spark:${APP_IMAGE_TAG}" -f- . <<DOCKERFILE
FROM spark:${SPARK_IMAGE_TAG}
COPY services_streaming_lane/app.jar ${APP_JAR_PATH_IN_IMAGE}
DOCKERFILE
	)
	kind load docker-image "spark:${APP_IMAGE_TAG}" --name "${CLUSTER_NAME}"
}

deploy_spark() {
	need kubectl
	have "$SPARK_NS_FILE"; have "$SPARK_RBAC_FILE"; have "$SPARK_DEFAULTS_FILE"
	kubectl apply -f "$SPARK_NS_FILE"
	kubectl apply -f "$SPARK_RBAC_FILE"
	build_base_spark_image
	build_fat_jar
	build_app_image
	echo "[spark] Ready. Use './dev.sh start_spark_sim2' (examples) or './dev.sh start_spark_sim' (your app)."
}

# ========= Spark submit (YOUR app.jar baked in image) =========
start_spark_sim() {
	need kubectl
	local K8S_SERVER
	K8S_SERVER="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')"
	have "${JAR_DEST}"

	echo "[spark] Submitting YOUR app from image spark:${APP_IMAGE_TAG} ..."
	"${SPARK_HOME}/bin/spark-submit" \
		--master "k8s://${K8S_SERVER}" \
		--deploy-mode cluster \
		--name spark-app \
		--class "${SPARK_APP_CLASS}" \
		--conf "spark.kubernetes.namespace=${NAMESPACE_SPARK}" \
		--conf "spark.kubernetes.authenticate.driver.serviceAccountName=${SPARK_SA}" \
		--conf "spark.kubernetes.container.image=spark:${APP_IMAGE_TAG}" \
		--conf "spark.kubernetes.container.image.pullPolicy=IfNotPresent" \
		--conf "spark.kubernetes.driver.podTemplateFile=${SPARK_DRIVER_POD_TMPL}" \
		--conf "spark.kubernetes.executor.podTemplateFile=${SPARK_EXEC_POD_TMPL}" \
		--properties-file "${SPARK_DEFAULTS_FILE}" \
		"local://${APP_JAR_PATH_IN_IMAGE}"

	echo "[spark] Driver logs:"
	kubectl -n "${NAMESPACE_SPARK}" logs -f "$(kubectl -n "${NAMESPACE_SPARK}" get pods -l spark-role=driver -o name | tail -n1 | cut -d/ -f2)" || true
}

# ========= Spark submit (MINIMAL) =========
start_spark_sim2() {
	need kubectl
	local K8S_SERVER
	K8S_SERVER="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')"

	echo "[spark] Minimal submit (examples jar) using spark:${SPARK_IMAGE_TAG} ..."
	"${SPARK_HOME}/bin/spark-submit" \
		--master "k8s://${K8S_SERVER}" \
		--deploy-mode cluster \
		--name spark-pi \
		--class org.apache.spark.examples.SparkPi \
		--conf "spark.kubernetes.namespace=${NAMESPACE_SPARK}" \
		--conf "spark.kubernetes.authenticate.driver.serviceAccountName=${SPARK_SA}" \
		--conf "spark.kubernetes.container.image=spark:${SPARK_IMAGE_TAG}" \
		--conf "spark.kubernetes.container.image.pullPolicy=IfNotPresent" \
		--conf "spark.executor.instances=2" \
		"local:///opt/spark/examples/jars/app.jar" 1000

	echo "[spark] Driver logs:"
	kubectl -n "${NAMESPACE_SPARK}" logs -f "$(kubectl -n "${NAMESPACE_SPARK}" get pods -l spark-role=driver -o name | tail -n1 | cut -d/ -f2)" || true
}

peek_spark() {
	need kubectl
	echo "== Spark driver pod =="
	kubectl -n "${NAMESPACE_SPARK}" get pods -l spark-role=driver -o name | tail -n1 || true
	DRIVER_POD="$(kubectl -n "${NAMESPACE_SPARK}" get pods -l spark-role=driver -o name | tail -n1 | cut -d/ -f2 || true)"
	if [[ -n "${DRIVER_POD:-}" ]]; then
		echo "---- Driver logs (following) ----"
		kubectl -n "${NAMESPACE_SPARK}" logs -f "${DRIVER_POD}" || true
	else
		echo "[spark] No driver pod found."
	fi
}

forward_client_portal_port() {
	kubectl -n "${CLIENT_PORTAL_NS}" port-forward svc/client-portal 5000:5000
}

# ========= CLIENT PORTAL API =========
deploy_client_portal() {
	need docker; need kind; need kubectl
	have "$CLIENT_PORTAL_SRC_DIR/Dockerfile"
	have "$CLIENT_PORTAL_NS_FILE"
	have "$CLIENT_PORTAL_DEPLOY_FILE"
	have "$CLIENT_PORTAL_SVC_FILE"

	echo "[client-portal] Building image ${CLIENT_PORTAL_IMG} ..."
	docker build -t "${CLIENT_PORTAL_IMG}" "${CLIENT_PORTAL_SRC_DIR}"

	echo "[client-portal] Loading image into kind cluster '${CLUSTER_NAME}' ..."
	kind load docker-image "${CLIENT_PORTAL_IMG}" --name "${CLUSTER_NAME}"

	echo "[client-portal] Applying namespace / manifests ..."
	kubectl apply -f "${CLIENT_PORTAL_NS_FILE}"
	kubectl -n "${CLIENT_PORTAL_NS}" apply -f "${CLIENT_PORTAL_DEPLOY_FILE}"
	kubectl -n "${CLIENT_PORTAL_NS}" apply -f "${CLIENT_PORTAL_SVC_FILE}"

	echo "[client-portal] Pods:"
	kubectl -n "${CLIENT_PORTAL_NS}" get pods -o wide || true

	echo "[client-portal] Logs (Ctrl-C to stop):"
	kubectl -n "${CLIENT_PORTAL_NS}" logs -f deploy/client-portal || true
}

# ===== IB Connector helpers =========
ib_connector_play() {
	need kubectl
	echo "[ib-connector] Running 'runMain src.main.scala.Boilerplate.play' inside the ib-connector pod …"
	kubectl -n "${NAMESPACE_IB}" exec -it ib-connector -c ib-connector -- \
		bash -lc '
			cd /work
			sbt -batch "runMain src.main.scala.Boilerplate.play"
		'
}


# ========= ClickHouse =========
build_clickhouse_image() {
	need docker
	have "$CLICKHOUSE_DIR/Dockerfile"
	have "$CLICKHOUSE_DIR/create_data_model.sql"
	(cd "$CLICKHOUSE_DIR" && docker build -t "${CLICKHOUSE_IMAGE_TAG}" .)
}

deploy_clickhouse() {
	need docker; need kind; need kubectl; need envsubst
	build_clickhouse_image
	kind load docker-image "${CLICKHOUSE_IMAGE_TAG}" --name "${CLUSTER_NAME}"

	ns_exists "${NAMESPACE_CLICKHOUSE}" || kubectl create namespace "${NAMESPACE_CLICKHOUSE}"

	have "${CLICKHOUSE_POD_FILE}"
	have "${CLICKHOUSE_SVC_FILE}"
	export NAMESPACE_CLICKHOUSE LBL_KEY LBL_VAL_SPARK
	envsubst < "${CLICKHOUSE_POD_FILE}" | kubectl apply -f -
	envsubst < "${CLICKHOUSE_SVC_FILE}" | kubectl apply -f -

	kubectl -n "${NAMESPACE_CLICKHOUSE}" wait --for=condition=Ready pod/clickhouse --timeout=240s || true
	kubectl -n "${NAMESPACE_CLICKHOUSE}" get pods -o wide
	kubectl -n "${NAMESPACE_CLICKHOUSE}" get svc clickhouse -o wide || true
}

peek_clickhouse_market_trades() {
	need kubectl
	local Q="
    SELECT
      ingestion_time,
      event_time,
      trading_symbol,
      source,
      price,
      size,
      event_id
    FROM quant.market_trades
    ORDER BY event_time DESC
    LIMIT 10"
	kubectl -n "${NAMESPACE_CLICKHOUSE}" exec -it clickhouse -- \
		clickhouse-client --user spark --password sparkpass --multiquery --query "$Q"
}

build_auth_automater() {
	need docker; need kind
	local SRC_DIR="$ROOT/services_streaming_lane/ib_auth_automater/source"
	have "$SRC_DIR/Dockerfile"
	have "$SRC_DIR/app.py"

	echo "[auth-automater] Building image ib-auth-automater:dev from ${SRC_DIR} …"
	docker build -t ib-auth-automater:dev "$SRC_DIR"

	echo "[auth-automater] Loading image into kind cluster '${CLUSTER_NAME}' …"
	kind load docker-image ib-auth-automater:dev --name "$CLUSTER_NAME"
}

auth_ib() {
	need kubectl
	local user="${1:-}"; local pass="${2:-}"
	if [[ -z "$user" || -z "$pass" ]]; then
		echo "Usage: $0 auth_ib <USERNAME> <PASSWORD>"
		return 2
	fi

	echo "[auth-automater] Launching one-shot pod (no secrets stored)…"
	kubectl -n "${CLIENT_PORTAL_NS}" run ib-auth-automater \
	  --image=ib-auth-automater:dev \
	  --restart=Never --rm -it -- \
	  --url "https://client-portal.client-portal-api:5000" \
	  "$user" "$pass"
}



usage() {
	cat <<EOF
Usage: $0 <command>

Cluster:
  create_cluster_dev          Create kind cluster and label nodes (kafka/spark)
  down                        Delete kind cluster
  status                      Show nodes/pods/services/topics

Kafka:
  deploy_kafka                Install Strimzi, deploy Kafka & topics
  pf                          Port-forward Kafka bootstrap to localhost:${BOOTSTRAP_LOCAL_PORT}
  peek_topic_ticklast         Tail last 10 messages from 'ticklast'
  peek_topic_l2_data          Tail last 10 messages from 'l2-data'

IB Connector (legacy):
  deploy_ib_connector_legacy  Build image and deploy ib-connector-legacy pod
  simulate_stream_legacy      [id] [intervalMs] [maxTicks]

IB Connector (current):
  deploy_ib_connector         Build image, load to kind, (re)create truststore Secret, apply pod
  ib_connector_play           Exec into pod and run 'runMain play'

Spark:
  deploy_spark                Apply spark infra, build base image, build app.jar, bake overlay image
  start_spark_sim             Submit YOUR baked app (local:///opt/spark/app/app.jar)
  start_spark_sim2            Minimal tutorial-style SparkPi using examples JAR
  peek_spark                  Shows logs from the driver pod

ClickHouse:
  deploy_clickhouse           Build clickhouse:dev image, load to kind, deploy pod + service on spark node
  peek_clickhouse_market_trades  Show 10 latest rows from quant.market_trades

Client Portal API:
  deploy_client_portal        Build image, load to kind, apply manifests, and stream logs
  forward_client_portal_port  Port-forward svc/client-portal :5000 -> localhost:5000

EOF
}

# ========= Main =========
need kind
need kubectl

cmd="${1:-help}"
case "$cmd" in
	create_cluster_dev) create_cluster_dev ;;
	deploy_kafka) deploy_kafka ;;
	deploy_ib_connector_legacy) deploy_ib_connector_legacy ;;
	simulate_stream_legacy) shift; simulate_stream_legacy "$@";;
	deploy_ib_connector) deploy_ib_connector ;;
	ib_connector_play) ib_connector_play ;;
	deploy_spark) deploy_spark ;;
	start_spark_sim) start_spark_sim ;;
	start_spark_sim2) start_spark_sim2 ;;
	deploy_clickhouse) deploy_clickhouse ;;
	peek_clickhouse_market_trades) peek_clickhouse_market_trades ;;
	peek_topic_ticklast) peek_topic_ticklast ;;
	peek_topic_l2_data) peek_topic_l2_data ;;
	peek_spark) peek_spark ;;
	pf) port_forward ;;
	status) status ;;
	down) down ;;
	deploy_client_portal) deploy_client_portal ;;
	forward_client_portal_port) forward_client_portal_port ;;
	build_auth_automater) build_auth_automater ;;
	auth_ib) shift; auth_ib "$@";;
	help|*) usage ;;
esac
