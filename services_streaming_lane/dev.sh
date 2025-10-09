#!/usr/bin/env bash
set -euo pipefail

# ========= Settings (override via env if needed) =========
CLUSTER_NAME="${CLUSTER_NAME:-kind}"

# Kafka / Strimzi
NAMESPACE_KAFKA="${NAMESPACE_KAFKA:-kafka}"
KAFKA_NAME="${KAFKA_NAME:-dev-kafka}"
STRIMZI_URL="${STRIMZI_URL:-https://strimzi.io/install/latest?namespace=${NAMESPACE_KAFKA}}"
BOOTSTRAP_LOCAL_PORT="${BOOTSTRAP_LOCAL_PORT:-9092}"

# Spark (K8s)
NAMESPACE_SPARK="${NAMESPACE_SPARK:-spark}"
SPARK_SA="${SPARK_SA:-spark-sa}"
SPARK_VERSION="${SPARK_VERSION:-4.0.1}"
SPARK_IMAGE_TAG="${SPARK_IMAGE_TAG:-our-own-apache-spark-kb8}"        # built by docker-image-tool.sh
APP_IMAGE_TAG="${APP_IMAGE_TAG:-${SPARK_IMAGE_TAG}-app}"              # overlay image w/ app.jar
SPARK_APP_CLASS="${SPARK_APP_CLASS:-com.yourorg.spark.ReadTicklastPrint}"

# Node labels
LBL_KEY="streamlane/role"
LBL_VAL_KAFKA="kafka"
LBL_VAL_SPARK="spark"

# ========= Repo paths =========
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

KAFKA_DIR="$ROOT/services_streaming_lane/kafka_message_broker"
IB_DIR="$ROOT/services_streaming_lane/ib_connector/source"
SPARK_DIR="$ROOT/services_streaming_lane/spark_processor"
SPARK_HOME="$ROOT/services_streaming_lane/spark-4.0.1-bin-hadoop3"

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

# IB infra
IB_POD_FILE="$ROOT/services_streaming_lane/ib_connector/infra/10-ib-connector-pod.yml"

# ========= JAR paths (ABSOLUTE, NO find/symlink) =========
# NOTE: We copy whatever sbt assembled into a FIXED host path:
JAR_DEST="$ROOT/services_streaming_lane/app.jar"
# If you keep the sbt output name stable, set it here (ABSOLUTE). Adjust the scala version / artifact as needed.
SBT_ASSEMBLY_ABS="${SPARK_DIR}/source/target/scala-2.13/spark-processor-assembly-0.1.0-SNAPSHOT.jar"
# Path INSIDE the container image (NOT under work-dir to avoid Spark copy clash)
APP_JAR_PATH_IN_IMAGE="/opt/spark/app/app.jar"

# ========= Helpers =========
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1"; exit 1; }; }
have() { [[ -f "$1" ]] || { echo "Required file not found: $1"; exit 1; }; }

# ========= Cluster creation (kind) =========
create_cluster_dev() {
  need kind; need kubectl
  kind delete cluster || true
  kind create cluster --config kind-config.yml
  kubectl label nodes kind-worker  "${LBL_KEY}=${LBL_VAL_KAFKA}" --overwrite
  kubectl label nodes kind-worker2 "${LBL_KEY}=${LBL_VAL_SPARK}" --overwrite
  kubectl get nodes --show-labels
}

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

# ========= IB Connector =========
deploy_ib_connector() {
  need docker; need kind; need kubectl; need envsubst
  have "$IB_DIR/Dockerfile"; have "$IB_POD_FILE"
  docker build -t ib-connector:dev "$IB_DIR"
  kind load docker-image ib-connector:dev --name "$CLUSTER_NAME"
  export NAMESPACE_KAFKA KAFKA_NAME LBL_KEY LBL_VAL_KAFKA
  envsubst < "$IB_POD_FILE" | kubectl apply -f -
  kubectl -n "$NAMESPACE_KAFKA" wait --for=condition=Ready pod/ib-connector --timeout=120s || true
  kubectl -n "$NAMESPACE_KAFKA" get pods -o wide
}

simulate_stream() {
  need kubectl
  local sim_id="${1:-1}"
  local interval_ms="${2:-250}"
  local max_ticks="${3:-1000}"
  kubectl -n "${NAMESPACE_KAFKA}" exec -it ib-connector -- \
    bash -lc "cd /work && sbt -batch 'runMain src.main.scala.SimulateStreaming ${sim_id} ${interval_ms} ${max_ticks}'"
}

# ========= Spark: base runtime image =========
build_base_spark_image() {
  need docker; need kind
  have "$SPARK_HOME/bin/docker-image-tool.sh"
  (cd "$SPARK_HOME" && sudo ./bin/docker-image-tool.sh -t "$SPARK_IMAGE_TAG" build)
  kind load docker-image "spark:${SPARK_IMAGE_TAG}" --name "$CLUSTER_NAME"
}

# ========= Spark: build fat jar (copy to ABSOLUTE fixed path) =========
# No 'find', no symlink. We copy a KNOWN assembled file to JAR_DEST.
build_fat_jar() {
  need docker
  local SRC_DIR="${SPARK_DIR%/}/source"
  have "${SRC_DIR}/build.sbt"

  # Build inside sbt image; output lands at ${SBT_ASSEMBLY_ABS}
  docker run --rm \
    -v "${SRC_DIR}":/work \
    -v "$HOME/.ivy2":/root/.ivy2 \
    -v "$HOME/.sbt":/root/.sbt \
    -v "$HOME/.cache/coursier":/root/.cache/coursier \
    -w /work \
    docker.io/sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.11.6_2.13.16 \
    sbt -batch clean assembly

  have "${SBT_ASSEMBLY_ABS}"
  mkdir -p "$(dirname "${JAR_DEST}")"
  cp -f "${SBT_ASSEMBLY_ABS}" "${JAR_DEST}"
  chmod a+r "${JAR_DEST}"
  echo "[spark] JAR -> ${JAR_DEST}"
}

# ========= Spark: overlay image that bakes app.jar under /opt/spark/app =========
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

# ========= Spark: apply infra + build images + jar =========
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

# ========= Spark submit (MINIMAL, like tutorial: SparkPi from examples) =========
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

# ========= Misc =========
status() {
  echo "== Nodes =="; kubectl get nodes -o wide --show-labels || true
  echo "== Kafka Pods =="; kubectl -n "$NAMESPACE_KAFKA" get pods -o wide || true
  echo "== Services (kafka) =="; kubectl -n "$NAMESPACE_KAFKA" get svc || true
  echo "== Topics =="; kubectl -n "$NAMESPACE_KAFKA" get kafkatopic || true
  echo "== Spark Pods =="; kubectl -n "$NAMESPACE_SPARK" get pods -o wide || true
}

down() { kind delete cluster --name "${CLUSTER_NAME}" || true; }

usage() {
  cat <<EOF
Usage: $0 <command>

Cluster:
  create_cluster_dev    Create kind cluster and label nodes (kafka/spark)
  down                  Delete kind cluster
  status                Show nodes/pods/services/topics

Kafka:
  deploy_kafka          Install Strimzi, deploy Kafka & topics
  pf                    Port-forward Kafka bootstrap to localhost:${BOOTSTRAP_LOCAL_PORT}
  peek_topic_ticklast   Tail last 10 messages from 'ticklast'
  peek_topic_l2_data    Tail last 10 messages from 'l2-data'

IB Connector:
  deploy_ib_connector   Build image and deploy IB connector pod
  simulate_stream [id] [intervalMs] [maxTicks]

Spark:
  deploy_spark          Apply spark infra, build base image, build app.jar, bake overlay image
  start_spark_sim       Submit YOUR baked app (local:///opt/spark/app/app.jar)
  start_spark_sim2      Minimal tutorial-style SparkPi using examples JAR
EOF
}

# ========= Main =========
need kind
need kubectl

cmd="${1:-help}"
case "$cmd" in
  create_cluster_dev) create_cluster_dev ;;
  deploy_kafka) deploy_kafka ;;
  deploy_ib_connector) deploy_ib_connector ;;
  simulate_stream) shift; simulate_stream "$@";;
  deploy_spark) deploy_spark ;;
  start_spark_sim) start_spark_sim ;;
  start_spark_sim2) start_spark_sim2 ;;
  peek_topic_ticklast) peek_topic_ticklast ;;
  peek_topic_l2_data) peek_topic_l2_data ;;
  pf) port_forward ;;
  status) status ;;
  down) down ;;
  help|*) usage ;;
esac
