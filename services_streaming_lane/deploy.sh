#!/usr/bin/env bash
set -euo pipefail

# ========= Settings (override via env if needed) =========
CLUSTER_NAME="${CLUSTER_NAME:-kind}"

# ========= Docker Hub ============
DOCKERHUB_USER="commodore95"
DOCKERHUB_REPO="commodore95/quant_data_platform_repository"
DOCKERHUB_TOKEN_KV_NAME="ibkr-secrets"
DOCKERHUB_TOKEN_SECRET_NAME="docker-hub-token"
DOCKERHUB_PULL_SECRET_NAME="dockerhub-pull"

# IB namespace (legacy kept separate)
NAMESPACE_IB_LEGACY="${NAMESPACE_IB_LEGACY:-ib-connector-legacy}"
NAMESPACE_IB="${NAMESPACE_IB:-ib-connector}"

# Kafka / Strimzi
NAMESPACE_KAFKA="${NAMESPACE_KAFKA:-kafka}"
KAFKA_NAME="${KAFKA_NAME:-dev-kafka}"
STRIMZI_URL="https://strimzi.io/install/latest?namespace=${NAMESPACE_KAFKA}"
BOOTSTRAP_LOCAL_PORT="${BOOTSTRAP_LOCAL_PORT:-9092}"

# Spark (K8s)
NAMESPACE_SPARK="${NAMESPACE_SPARK:-spark}"
SPARK_SA="${SPARK_SA:-spark-sa}"
SPARK_VERSION="${SPARK_VERSION:-3.5.7}"
SPARK_IMAGE_TAG="spark-our-own-apache-spark-kb8"
APP_IMAGE_TAG="${APP_IMAGE_TAG:-${SPARK_IMAGE_TAG}-app}"
SPARK_IMAGE_ADDRESS="${DOCKERHUB_REPO}:${APP_IMAGE_TAG}"
SPARK_APP_CLASS="${SPARK_APP_CLASS:-com.yourorg.spark.ReadTickLastPrint}"

# ClickHouse (disjoint namespace)
NAMESPACE_CLICKHOUSE="${NAMESPACE_CLICKHOUSE:-clickhouse}"
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
CLICKHOUSE_INFRA_DIR="$CLICKHOUSE_DIR/infra_dev"

# Kafka manifests (now under infra_dev)
NS_FILE="$KAFKA_DIR/infra_dev/00-namespace.yml"
KAFKA_FILE="$KAFKA_DIR/infra_dev/10-kafka-cluster.yml"
TOPICS_FILE="$KAFKA_DIR/infra_dev/20-topics.yml"

# Spark infra files (infra_dev)
SPARK_NS_FILE="$SPARK_DIR/infra_dev/00-namespace.yml"
SPARK_RBAC_FILE="$SPARK_DIR/infra_dev/10-rbac.yml"
SPARK_DRIVER_POD_TMPL="$SPARK_DIR/infra_dev/20-driver-pod-template.yml"
SPARK_EXEC_POD_TMPL="$SPARK_DIR/infra_dev/21-executor-pod-template.yml"
SPARK_DEFAULTS_FILE="$SPARK_DIR/infra_dev/30-spark-defaults.conf"

# ========= ClickHouse infra files (infra_dev) =========
CLICKHOUSE_NS_FILE="$CLICKHOUSE_INFRA_DIR/00-namespace.yml"
CLICKHOUSE_POD_FILE="$CLICKHOUSE_INFRA_DIR/10-clickhouse-pod.yml"
CLICKHOUSE_SVC_FILE="$CLICKHOUSE_INFRA_DIR/20-clickhouse-svc.yml"

# ========= IB Connector (legacy) =========
IB_NS_FILE_LEGACY="$ROOT/services_streaming_lane/ib_connector_legacy/infra_dev/00-namespace.yml"
IB_POD_FILE_LEGACY="$ROOT/services_streaming_lane/ib_connector_legacy/infra_dev/10-ib-connector-pod.yml"

# ========= Client Portal =========
CLIENT_PORTAL_NS="${CLIENT_PORTAL_NS:-client-portal-api}"
CLIENT_PORTAL_DIR="$ROOT/services_streaming_lane/ib_client_portal_api"
CLIENT_PORTAL_SRC_DIR="$CLIENT_PORTAL_DIR/source"
CLIENT_PORTAL_IMG="client-portal:1.0.0"

CLIENT_PORTAL_NS_FILE="$CLIENT_PORTAL_DIR/infra_dev/00-namespace.yml"
CLIENT_PORTAL_DEPLOY_FILE="$CLIENT_PORTAL_DIR/infra_dev/30-client-portal-deployment.yml"
CLIENT_PORTAL_SVC_FILE="$CLIENT_PORTAL_DIR/infra_dev/40-client-portal-service.yml"

# ========= IB Connector (current project) =========
IB_DIR="$ROOT/services_streaming_lane/ib_connector"
IB_SRC_DIR="$IB_DIR/source"
IB_NS_FILE="$IB_DIR/infra_dev/00-namespace.yml"
IB_RBAC_FILE="$IB_DIR/infra_dev/05-ib-connector-rbac.yml"
IB_POD_FILE="$IB_DIR/infra_dev/10-ib-connector-pod.yml"
IB_IMG="ib-connector:dev"

# ========= Avro Schema Registry =========
NAMESPACE_AVRO="${NAMESPACE_AVRO:-avro-schema-registry}"

AVRO_REG_DIR="$ROOT/services_streaming_lane/avro_schema_registry"
AVRO_REG_INFRA_DIR="$AVRO_REG_DIR/infra_dev"
AVRO_SCHEMA_DIR="$AVRO_REG_DIR/category_schemas"

AVRO_REG_NS_FILE="$AVRO_REG_INFRA_DIR/01-namespace.yml"
AVRO_REG_CFG_FILE="$AVRO_REG_INFRA_DIR/02-configmap-schema-registry.yml"
AVRO_REG_DEPLOY_FILE="$AVRO_REG_INFRA_DIR/03-deployment-schema-registry.yml"
AVRO_REG_SVC_FILE="$AVRO_REG_INFRA_DIR/04-service-schema-registry.yml"
AVRO_REG_KUSTOM_FILE="$AVRO_REG_INFRA_DIR/kustomization.yml"

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



create_dockerhub_pull_secret() {
	need az
	need kubectl

	local NS="${1:-}"
	[[ -n "${NS}" ]] || { echo "ERROR: missing namespace argument" >&2; return 1; }

	local TOKEN
	TOKEN="$(az keyvault secret show --vault-name "${DOCKERHUB_TOKEN_KV_NAME}" --name "${DOCKERHUB_TOKEN_SECRET_NAME}" --query value -o tsv)"

	kubectl -n "${NS}" create secret docker-registry "${DOCKERHUB_PULL_SECRET_NAME}" \
		--docker-server="https://index.docker.io/v1/" \
		--docker-username="${DOCKERHUB_USER}" \
		--docker-password="${TOKEN}" \
		--docker-email="unused@example.com" \
		--dry-run=client -o yaml | kubectl apply -f -
}

get_dockerhub_token() {
	need az
	az keyvault secret show --vault-name "${DOCKERHUB_TOKEN_KV_NAME}" --name "${DOCKERHUB_TOKEN_SECRET_NAME}" --query value -o tsv
}

push_to_dockerhub() {
	need docker

	local LOCAL_IMAGE="${1:-}"
	[[ -n "${LOCAL_IMAGE}" ]] || { echo "ERROR: missing image argument" >&2; return 1; }

	local TOKEN
	TOKEN="$(get_dockerhub_token)"

	docker login -u "${DOCKERHUB_USER}" -p "${TOKEN}"

	local TAG_SAFE
	TAG_SAFE="${LOCAL_IMAGE//\//-}"
	TAG_SAFE="${TAG_SAFE//:/-}"

	local REMOTE_IMAGE="${DOCKERHUB_REPO}:${TAG_SAFE}"

	docker tag "${LOCAL_IMAGE}" "${REMOTE_IMAGE}"
	docker push "${REMOTE_IMAGE}"

	printf '%s\n' "${REMOTE_IMAGE}"
}


set_deployment_env() {
	local env="${1:-}"
	case "$env" in
		test|dev|prod) ;;
		*) echo "ERROR: invalid env '$env' (expected: test|dev|prod)" >&2; return 1 ;;
	esac

	export DEPLOYMENT_ENV="$env"

	local comps=("KAFKA" "SPARK" "CLICKHOUSE" "IB" "AVRO" "DOCKRE")
	local comp file_var file ns_name ns_name_var

	for comp in "${comps[@]}"; do
		file_var="${comp}_NS_FILE"
		file="${!file_var:-}"
		[[ -n "$file" ]] || { echo "ERROR: ${file_var} not set" >&2; return 1; }
		[[ -f "$file" ]] || { echo "ERROR: file not found: $file" >&2; return 1; }

		ns_name="$(awk 'BEGIN{m=0} {k=tolower($1)} k=="metadata:"{m=1;next} m && tolower($1)=="name:"{print $2;exit}' "$file")"
		[[ -n "$ns_name" ]] || { echo "ERROR: could not parse metadata.name from $file" >&2; return 1; }

		ns_name_var="${comp}_NS_NAME"
		printf -v "$ns_name_var" '%s' "$ns_name"
		export "$ns_name_var"
	done
}


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
	echo "== SchemaRegistry =="; kubectl -n "$NAMESPACE_AVRO" get pods -o wide || true
	echo "== SchemaRegistry Svc =="; kubectl -n "$NAMESPACE_AVRO" get svc "$AVRO_REG_SVC_NAME" -o wide || true
}

down() { kind delete cluster --name "${CLUSTER_NAME}" || true; }

# ========= Kafka / Strimzi =========
install_strimzi() {
	have "$NS_FILE"; have "$KAFKA_FILE"
	kubectl apply -f "$NS_FILE"
	create_dockerhub_pull_secret "${NAMESPACE_KAFKA}"

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
	local NS="${NAMESPACE_KAFKA}"
	local BOOTSTRAP="${KAFKA_NAME}-kafka-bootstrap.${NS}:9092"

	kubectl -n "${NS}" run -it --rm kcat-tail-derivatives-tick-market-data \
		--image=edenhill/kcat:1.7.1 --restart=Never -- \
		-b "${BOOTSTRAP}" -t derivatives_tick_market_data -C -o -10 -e -q
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

# ========= Avro Schema Registry =========
preload_avro_registry_image() {
	need docker; need kind
	echo "[schema-registry] Pre-pulling image on host …"
	docker pull confluentinc/cp-schema-registry:7.6.1
	echo "[schema-registry] Loading image into docker hub '${CLUSTER_NAME}' …"
	push_to_dockerhub "confluentinc/cp-schema-registry:7.6.1" >/dev/null
}


deploy_avro_registry_schema() {
	need kubectl
	preload_avro_registry_image

	have "$AVRO_REG_NS_FILE"
	have "$AVRO_REG_CFG_FILE"
	have "$AVRO_REG_DEPLOY_FILE"
	have "$AVRO_REG_SVC_FILE"

	kubectl apply -f "$AVRO_REG_NS_FILE"
	create_dockerhub_pull_secret "${NAMESPACE_AVRO}"

	kubectl apply -n "$NAMESPACE_AVRO" -f "$AVRO_REG_CFG_FILE"
	kubectl apply -n "$NAMESPACE_AVRO" -f "$AVRO_REG_DEPLOY_FILE"
	kubectl apply -n "$NAMESPACE_AVRO" -f "$AVRO_REG_SVC_FILE"

	kubectl -n "$NAMESPACE_AVRO" rollout status deployment/schema-registry --timeout=400s || true
	kubectl -n "$NAMESPACE_AVRO" get pods -l app=schema-registry -o wide || true
	kubectl -n "$NAMESPACE_AVRO" get svc "$AVRO_REG_SVC_NAME" -o wide || true
}


register_avro_schemas() {
	need kubectl
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

		echo "[schema-registry] Registered ${SUBJECT} from $(basename "$FILE") (compat=${AVRO_REG_COMPATIBILITY})"
	}

	register_one_schema "$AVRO_SCHEMA_TICK" "derivatives_tick_market_data"
	register_one_schema "$AVRO_SCHEMA_L2" "derivatives_l2_market_data"
	register_one_schema "$AVRO_SCHEMA_IND" "indicators"
	register_one_schema "$AVRO_SCHEMA_ECO" "numeric_economic_data"

	kill "$PF_PID" >/dev/null 2>&1 || true
}

# ========= IB Connector (LEGACY) =========
deploy_ib_connector_legacy() {
	need docker; need kind; need kubectl; need envsubst
	have "$IB_DIR_LEGACY/Dockerfile"
	have "$IB_POD_FILE_LEGACY"
	have "$IB_NS_FILE_LEGACY"

	echo "[ib-connector-legacy] Applying namespace …"
	kubectl apply -f "$IB_NS_FILE_LEGACY"
	create_dockerhub_pull_secret "${NAMESPACE_IB_LEGACY}"


	echo "[ib-connector-legacy] Building image ib-connector-legacy:dev from ${IB_DIR_LEGACY} …"
	docker build -t ib-connector-legacy:dev "$IB_DIR_LEGACY"

	echo "[ib-connector-legacy] Loading image into kind cluster '${CLUSTER_NAME}' …"
	push_to_dockerhub "ib-connector-legacy:dev" >/dev/null

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
sync_ibkr_secrets_from_keyvault() {
	need az
	need kubectl

	local KV_NAME="ibkr-secrets"
	local NS="${NAMESPACE_IB}"
	local K8S_SECRET_NAME="ibkr-secret"

	local USERNAME_1
	local PASSWORD_1

	USERNAME_1="$(az keyvault secret show --vault-name "${KV_NAME}" --name "ibkr-username-1" --query value -o tsv)"
	PASSWORD_1="$(az keyvault secret show --vault-name "${KV_NAME}" --name "ibkr-password-1" --query value -o tsv)"

	kubectl -n "${NS}" create secret generic "${K8S_SECRET_NAME}" \
		--from-literal="IBKR_USERNAME_1=${USERNAME_1}" \
		--from-literal="IBKR_PASSWORD_1=${PASSWORD_1}" \
		--dry-run=client -o yaml | kubectl apply -f -
}

deploy_ib_connector() {
	# Ensure you ran az login and it was successfull!
	need docker; need kind; need kubectl; need envsubst

	have "$IB_NS_FILE"
	have "$IB_RBAC_FILE"
	have "$IB_POD_FILE"
	have "$IB_SRC_DIR/Dockerfile"

	# Ensure cert artifacts exist so the image can bake them in
	have "$IB_DIR/infra_dev/ibkr_truststore.jks"
	have "$IB_DIR/infra_dev/ibkr_client_portal.pem"

	echo "[ib-connector] Applying namespace …"
	kubectl apply -f "$IB_NS_FILE"
	create_dockerhub_pull_secret "${NAMESPACE_IB}"


	echo "syncing the secrets from the key vault"
	sync_ibkr_secrets_from_keyvault

	echo "[ib-connector] Building image ib-connector:dev from ${IB_SRC_DIR} …"
	
	docker build \
		-f "$IB_SRC_DIR/Dockerfile" \
		-t ib-connector:dev \
		"$ROOT/services_streaming_lane"

	echo "[ib-connector] Loading image into kind cluster '${CLUSTER_NAME}' …"
	push_to_dockerhub "ib-connector:dev" >/dev/null

	echo "[ib-connector] Applying RBAC manifest …"
	export NAMESPACE_IB NAMESPACE_KAFKA KAFKA_NAME LBL_KEY LBL_VAL_KAFKA
	envsubst < "$IB_RBAC_FILE" | kubectl apply -f -

	echo "[ib-connector] Applying pod manifest …"
	export NAMESPACE_IB NAMESPACE_KAFKA KAFKA_NAME LBL_KEY LBL_VAL_KAFKA
	envsubst < "$IB_POD_FILE" | kubectl apply -f -

	echo "[ib-connector] Waiting for pod/ib-connector Ready …"
	kubectl -n "$NAMESPACE_IB" wait --for=condition=Ready pod/ib-connector --timeout=180s || true

	echo "[ib-connector] Pods:"
	kubectl -n "$NAMESPACE_IB" get pods -o wide || true
}

ib_connector_play() {
	need kubectl
	echo "[ib-connector] Running 'runMain src.main.scala.Boilerplate.play' inside the ib-connector pod …"
	kubectl -n "${NAMESPACE_IB}" exec -it ib-connector -c ib-connector -- \
		bash -lc '
			cd /work
			sbt -batch "runMain src.main.scala.Boilerplate.play"
		'
}

ib_connector_run() {
	need kubectl
	echo "[ib-connector] Running 'runMain src.main.scala.IbConnector' inside the ib-connector pod …"
	kubectl -n "${NAMESPACE_IB}" exec -it ib-connector -c ib-connector -- \
		bash -lc '
			cd /work
			sbt -batch "runMain src.main.scala.IbConnector"
		'
}

# ========= Spark: base runtime image =========
make_spark_submit_kubeconfig() {
	need kubectl

	local NS="${1:?missing namespace}"
	local SA="${2:?missing serviceaccount}"
	local OUT="${3:?missing output kubeconfig path}"

	local SERVER
	local CA
	local TOKEN
	local CTX

	SERVER="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')"
	CA="$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')"
	TOKEN="$(kubectl -n "${NS}" create token "${SA}")"
	CTX="spark-submit@k8s"

	cat > "${OUT}" <<EOF
apiVersion: v1
kind: Config
clusters:
- name: k8s
  cluster:
    server: ${SERVER}
    certificate-authority-data: ${CA}
users:
- name: spark-submit
  user:
    token: ${TOKEN}
contexts:
- name: ${CTX}
  context:
    cluster: k8s
    user: spark-submit
    namespace: ${NS}
current-context: ${CTX}
EOF

	printf '%s\n' "${CTX}"
}

build_base_spark_image() {
	need docker; need kind
	have "$SPARK_HOME/bin/docker-image-tool.sh"
	(cd "$SPARK_HOME" && ./bin/docker-image-tool.sh -t "$SPARK_IMAGE_TAG" build)
	SPARK_REMOTE_BASE_IMAGE="$(push_to_dockerhub "${SPARK_IMAGE_TAG}")"
	export SPARK_REMOTE_BASE_IMAGE
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
	( cd "$ROOT" && docker build -t "${APP_IMAGE_TAG}" -f- . <<DOCKERFILE
FROM ${DOCKERHUB_REPO}:${SPARK_IMAGE_TAG}
COPY services_streaming_lane/app.jar ${APP_JAR_PATH_IN_IMAGE}
DOCKERFILE
	)
	SPARK_REMOTE_APP_IMAGE="$(push_to_dockerhub "${APP_IMAGE_TAG}")"
	export SPARK_REMOTE_APP_IMAGE
}

deploy_spark() {
	need kubectl
	have "$SPARK_NS_FILE"; have "$SPARK_RBAC_FILE"; have "$SPARK_DEFAULTS_FILE"
	kubectl apply -f "$SPARK_NS_FILE"
	create_dockerhub_pull_secret "${NAMESPACE_SPARK}"

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

	local SUBMIT_KUBECONFIG
	local SUBMIT_CTX
	SUBMIT_KUBECONFIG="$(mktemp -t spark-submit-kubeconfig.XXXXXX)"
	SUBMIT_CTX="$(make_spark_submit_kubeconfig "${NAMESPACE_SPARK}" "${SPARK_SA}" "${SUBMIT_KUBECONFIG}")"

	echo "[spark] Submitting YOUR app from image spark:${APP_IMAGE_TAG} ..."
	"${SPARK_HOME}/bin/spark-submit" \
		--master "k8s://${K8S_SERVER}" \
		--deploy-mode cluster \
		--name spark-app \
		--class "${SPARK_APP_CLASS}" \
		--conf "spark.kubernetes.namespace=${NAMESPACE_SPARK}" \
		--conf "spark.kubernetes.authenticate.submission.kubeconfigFile=${SUBMIT_KUBECONFIG}" \
		--conf "spark.kubernetes.authenticate.submission.context=${SUBMIT_CTX}" \
		--conf "spark.kubernetes.authenticate.driver.serviceAccountName=${SPARK_SA}" \
		--conf "spark.kubernetes.container.image=${SPARK_IMAGE_ADDRESS}" \
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

	local SUBMIT_KUBECONFIG
	local SUBMIT_CTX
	SUBMIT_KUBECONFIG="$(mktemp -t spark-submit-kubeconfig.XXXXXX)"
	SUBMIT_CTX="$(make_spark_submit_kubeconfig "${NAMESPACE_SPARK}" "${SPARK_SA}" "${SUBMIT_KUBECONFIG}")"

	echo "[spark] Minimal submit (examples jar) using spark:${SPARK_IMAGE_TAG} ..."
	"${SPARK_HOME}/bin/spark-submit" \
		--master "k8s://${K8S_SERVER}" \
		--deploy-mode cluster \
		--name spark-pi \
		--class org.apache.spark.examples.SparkPi \
		--conf "spark.kubernetes.namespace=${NAMESPACE_SPARK}" \
		--conf "spark.kubernetes.authenticate.submission.kubeconfigFile=${SUBMIT_KUBECONFIG}" \
		--conf "spark.kubernetes.authenticate.submission.context=${SUBMIT_CTX}" \
		--conf "spark.kubernetes.authenticate.driver.serviceAccountName=${SPARK_SA}" \
		--conf "spark.kubernetes.container.image=${SPARK_IMAGE_ADDRESS}" \
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

# ========= ClickHouse =========
build_clickhouse_image() {
	need docker
	have "$CLICKHOUSE_DIR/Dockerfile"
	have "$CLICKHOUSE_DIR/create_data_model.sql"
	(cd "$CLICKHOUSE_DIR" && docker build -t "${CLICKHOUSE_IMAGE_TAG}" .)
}

deploy_clickhouse() {
	need docker; need kind; need kubectl; need envsubst

	have "${CLICKHOUSE_NS_FILE}"
	kubectl apply -f "${CLICKHOUSE_NS_FILE}"
	create_dockerhub_pull_secret "${NAMESPACE_CLICKHOUSE}"
	build_clickhouse_image
	push_to_dockerhub "${CLICKHOUSE_IMAGE_TAG}" >/dev/null

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
	local NS="${NAMESPACE_CLICKHOUSE}"
	local POD
	POD="$(kubectl -n "${NS}" get pods -l app=clickhouse -o jsonpath='{.items[0].metadata.name}')"

	local Q='SELECT * FROM realtime_store.derivatives_tick_market_data ORDER BY tick_time DESC LIMIT 10;'

	kubectl -n "${NS}" exec -it "${POD}" -- \
		clickhouse-client --multiquery --query "${Q}"
}

check_clickhouse_tables() {
	need kubectl
	local NS="${NAMESPACE_CLICKHOUSE}"
	local POD
	POD="$(kubectl -n "${NS}" get pods -l app=clickhouse -o jsonpath='{.items[0].metadata.name}')"

	kubectl -n "${NS}" exec -it "${POD}" -- \
		clickhouse-client --multiquery --query "
SHOW DATABASES;
SHOW TABLES FROM realtime_store;
SHOW CREATE TABLE realtime_store.derivatives_tick_market_data;
"
}


azure_authenticate_first() {
	need kubectl

	# pick the first ib-connector pod in the namespace
	local POD
	POD="$(kubectl -n "${NAMESPACE_IB}" get pods -o jsonpath='{.items[0].metadata.name}')"

	if [[ -z "${POD:-}" ]]; then
		echo "[azure_authenticate] No ib-connector pod found in namespace ${NAMESPACE_IB}" >&2
		exit 1
	fi

	echo "[azure_authenticate] Using pod ${POD} in namespace ${NAMESPACE_IB}"
	echo "[azure_authenticate] Running 'az login --use-device-code' inside the pod."
	echo "[azure_authenticate] Follow the URL and enter the device code in your host browser."

	kubectl -n "${NAMESPACE_IB}" exec -it "${POD}" -- bash -lc '
		set -euo pipefail
		if ! command -v az >/dev/null 2>&1; then
			echo "[azure] Azure CLI not found, installing …"
			curl -sL https://aka.ms/InstallAzureCLIDeb | bash
		fi
		az login --use-device-code
	'
}

destroy_kafka_namespace(){
	NS="kafka"
	CLUSTER="dev-kafka"

	# 1) Delete Strimzi resources (topics/users first, then cluster)
	kubectl -n "${NS}" delete kafkatopic --all
	kubectl -n "${NS}" delete kafkauser --all
	kubectl -n "${NS}" delete kafka "${CLUSTER}"

	# 2) Wait until they are really gone
	kubectl -n "${NS}" wait --for=delete kafka/"${CLUSTER}" --timeout=10m || true

	# 3) (Optional, if you want a truly fresh start) delete storage
	kubectl -n "${NS}" delete pvc --all

	# 4) Delete namespace last
	kubectl delete ns "${NS}"

}

destroy_non_kafka_namespaces() {
	kubectl delete namespace spark
	kubectl delete namespace clickhouse
	kubectl delete namespace avro-schema-registry
	kubectl delete namespace ib-connector

}

usage() {
	cat <<EOF
Usage: $0 <command>

Cluster:
  create_cluster_dev          Create kind cluster and label nodes (kafka/spark)
  down                        Delete kind cluster
  status                      Show nodes/pods/services/topics
  destroy_kafka_namespace	  Deletes kafka namespace and all of its ressources
  destroy_non_kafka_namespaces Deletes the remaining namespaces and all of their ressources

Kafka:
  deploy_kafka                Install Strimzi, deploy Kafka & topics
  pf                          Port-forward Kafka bootstrap to localhost:${BOOTSTRAP_LOCAL_PORT}
  peek_topic_ticklast         Tail last 10 messages from 'ticklast'
  peek_topic_l2_data          Tail last 10 messages from 'l2-data'

Avro Schema Registry:
  deploy_avro_registry_schema Deploy Schema Registry into the avro-schema-registry namespace
  register_avro_schemas       Register all .avsc files into Schema Registry (compat=${AVRO_REG_COMPATIBILITY})

IB Connector (legacy):
  deploy_ib_connector_legacy  Build image and deploy ib-connector-legacy pod
  simulate_stream_legacy      [id] [intervalMs] [maxTicks]

IB Connector (current):
  deploy_ib_connector         Build image, load to kind, (re)create truststore Secret, apply pod
  azure_authenticate_first	  Runs az login on the first pod in the ib connector namespace
  ib_connector_play           Exec into pod and run 'runMain play'
  ib_connector_run			  Run the produciton version of the ib connector

Spark:
  deploy_spark                Apply spark infra, build base image, build app.jar, bake overlay image
  start_spark_sim             Submit YOUR baked app (local:///opt/spark/app/app.jar)
  start_spark_sim2            Minimal tutorial-style SparkPi using examples JAR
  peek_spark                  Shows logs from the driver pod

ClickHouse:
  deploy_clickhouse           Build clickhouse:dev image, load to kind, deploy pod + service in clickhouse namespace
  peek_clickhouse_market_trades  Show 10 latest rows from quant.market_trades


EOF
}

# ========= Main =========
need kind
need kubectl

cmd="${1:-help}"
case "$cmd" in
	create_cluster_dev) create_cluster_dev ;;
	destroy_non_kafka_namespaces) destroy_non_kafka_namespaces;;
	destroy_kafka_namespace) destroy_kafka_namespace;;
	deploy_kafka) deploy_kafka ;;
	deploy_avro_registry_schema) deploy_avro_registry_schema ;;
	register_avro_schemas) register_avro_schemas ;;
	deploy_ib_connector_legacy) deploy_ib_connector_legacy ;;
	simulate_stream_legacy) shift; simulate_stream_legacy "$@";;
	deploy_ib_connector) deploy_ib_connector ;;
	ib_connector_play) ib_connector_play ;;
	ib_connector_run) ib_connector_run;;
	deploy_spark) deploy_spark ;;
	start_spark_sim) start_spark_sim ;;
	start_spark_sim2) start_spark_sim2 ;;
	deploy_clickhouse) deploy_clickhouse ;;
	peek_clickhouse_market_trades) peek_clickhouse_market_trades ;;
	check_clickhouse_tables) check_clickhouse_tables ;;
	peek_topic_ticklast) peek_topic_ticklast ;;
	peek_topic_l2_data) peek_topic_l2_data ;;
	peek_spark) peek_spark ;;
	status) status ;;
	down) down ;;
	azure_authenticate_first) azure_authenticate_first;;
	label_workers) label_workers;;
	help|*) usage ;;
esac
