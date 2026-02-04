#!/usr/bin/env bash
set -euo pipefail

# ====== good to know commands =======
# kubectl delete pods --all -n spark

# ========= Settings (override via env if needed) =========
CLUSTER_NAME="${CLUSTER_NAME:-kind}"

# ========= Docker Hub ============
DOCKERHUB_USER="commodore95"
DOCKERHUB_REPO="commodore95/quant_data_platform_repository"
DOCKERHUB_TOKEN_KV_NAME="ibkr-secrets"
DOCKERHUB_TOKEN_SECRET_NAME="docker-hub-token"
DOCKERHUB_PULL_SECRET_NAME="dockerhub-pull"

# IB connector
NAMESPACE_IB="${NAMESPACE_IB:-testenv-ib-connector}"

# Kafka / Strimzi
NAMESPACE_KAFKA="${NAMESPACE_KAFKA:-testenv-kafka}"
KAFKA_NAME="${KAFKA_NAME:-test-kafka}"
STRIMZI_URL="https://strimzi.io/install/latest?namespace=${NAMESPACE_KAFKA}"
BOOTSTRAP_LOCAL_PORT="${BOOTSTRAP_LOCAL_PORT:-9092}"

# Spark (K8s)
NAMESPACE_SPARK="${NAMESPACE_SPARK:-testenv-spark}"
SPARK_SA="${SPARK_SA:-spark-sa}"
SPARK_VERSION="${SPARK_VERSION:-3.5.7}"
SPARK_IMAGE_TAG="spark-our-own-apache-spark-kb8-test"
APP_IMAGE_TAG="${APP_IMAGE_TAG:-${SPARK_IMAGE_TAG}-app}"
SPARK_IMAGE_ADDRESS="${DOCKERHUB_REPO}:${APP_IMAGE_TAG}"
SPARK_APP_CLASS="${SPARK_APP_CLASS:-com.yourorg.spark.ReadTickLastPrint}"

# ClickHouse (disjoint namespace)
NAMESPACE_CLICKHOUSE="${NAMESPACE_CLICKHOUSE:-testenv-clickhouse}"
CLICKHOUSE_IMAGE_TAG="${CLICKHOUSE_IMAGE_TAG:-clickhouse:test}"


# ========= Repo paths =========
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DEFAULT_DOPPLER_SECRETS_FILE="${SCRIPT_DIR}/default_secrets.yml"


KAFKA_DIR="$ROOT/services_streaming_lane/kafka_message_broker"
IB_DIR_LEGACY="$ROOT/services_streaming_lane/ib_connector_legacy/source"
SPARK_DIR="$ROOT/services_streaming_lane/spark_processor"
SPARK_HOME="$ROOT/services_streaming_lane/spark-${SPARK_VERSION}-bin-hadoop3"

CLICKHOUSE_DIR="$ROOT/services_streaming_lane/click_house"
CLICKHOUSE_INFRA_DIR="$CLICKHOUSE_DIR/infra_test"

# Kafka manifests (now under infra_dev)
NS_FILE="$KAFKA_DIR/infra_test/00-namespace.yml"
KAFKA_FILE="$KAFKA_DIR/infra_test/10-kafka-cluster.yml"
TOPICS_FILE="$KAFKA_DIR/infra_test/20-topics.yml"

# Spark infra files (infra_dev)
SPARK_NS_FILE="$SPARK_DIR/infra_test/00-namespace.yml"
SPARK_RBAC_FILE="$SPARK_DIR/infra_test/10-rbac.yml"
SPARK_DRIVER_POD_TMPL="$SPARK_DIR/infra_test/20-driver-pod-template.yml"
SPARK_EXEC_POD_TMPL="$SPARK_DIR/infra_test/21-executor-pod-template.yml"
SPARK_DEFAULTS_FILE="$SPARK_DIR/infra_test/30-spark-defaults.conf"

# ========= ClickHouse infra files (infra_dev) =========
CLICKHOUSE_NS_FILE="$CLICKHOUSE_INFRA_DIR/00-namespace.yml"
CLICKHOUSE_POD_FILE="$CLICKHOUSE_INFRA_DIR/10-clickhouse-pod.yml"
CLICKHOUSE_SVC_FILE="$CLICKHOUSE_INFRA_DIR/20-clickhouse-svc.yml"


# ========= IB Connector (current project) =========
IB_DIR="$ROOT/services_streaming_lane/ib_connector"
IB_INFRA_DIR="infra_test"
IB_SRC_DIR="$IB_DIR/source"
IB_NS_FILE="$IB_DIR/$IB_INFRA_DIR/00-namespace.yml"
IB_RBAC_FILE="$IB_DIR/$IB_INFRA_DIR/05-ib-connector-rbac.yml"
IB_POD_FILE="$IB_DIR/$IB_INFRA_DIR/10-ib-connector-pod.yml"
IB_SECRETS_FILE="${IB_DIR}/$IB_INFRA_DIR/20-secrets.yml"

# ========= Avro Schema Registry =========
NAMESPACE_AVRO="${NAMESPACE_AVRO:-testenv-avro-schema-registry}"

AVRO_REG_DIR="$ROOT/services_streaming_lane/avro_schema_registry"
AVRO_REG_INFRA_DIR="$AVRO_REG_DIR/infra_test"
AVRO_SCHEMA_DIR="$AVRO_REG_DIR/category_schemas"

AVRO_REG_NS_FILE="$AVRO_REG_INFRA_DIR/01-namespace.yml"
AVRO_REG_CFG_FILE="$AVRO_REG_INFRA_DIR/02-configmap-schema-registry.yml"
AVRO_REG_DEPLOY_FILE="$AVRO_REG_INFRA_DIR/03-deployment-schema-registry.yml"
AVRO_REG_SVC_FILE="$AVRO_REG_INFRA_DIR/04-service-schema-registry.yml"
AVRO_REG_KUSTOM_FILE="$AVRO_REG_INFRA_DIR/kustomization.yml"

AVRO_REG_SVC_NAME="${AVRO_REG_SVC_NAME:-test-schema-registry}"
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

label_workers() {
	# assumes that the names of the nodes appearing in 'kubectl get nodes' are of form test-worker-1 , dev-worker-1 or prod-worker-1
	# needs to be run like this :
	# source ./deploy.sh
	# set_deployment_env dev
	# label_workers
	# Now run this—it should succeed because it sees the variable in memory
    # Ensure DEPLOYMENT_ENV is set
    if [[ -z "${DEPLOYMENT_ENV:-}" ]]; then
        echo "ERROR: DEPLOYMENT_ENV is not set. Run set_deployment_env first." >&2
        return 1
    fi


    echo "Scanning nodes for environment: ${DEPLOYMENT_ENV}"

    # Filter nodes that start with the specific environment prefix (e.g., "dev-")
    local node_pattern="^${DEPLOYMENT_ENV}-"
    local nodes=$(kubectl get nodes --no-headers -o custom-columns=":metadata.name" | grep -E "$node_pattern")

    if [[ -z "$nodes" ]]; then
        echo "WARNING: No nodes found matching pattern '$node_pattern'"
        return 0
    fi

    for node in $nodes; do
        echo "Labelling node $node with env=${DEPLOYMENT_ENV}"
        kubectl label node "$node" env="${DEPLOYMENT_ENV}" --overwrite
    done
} 

apply_default_secrets() {
	# install_doppler and create_doppler_service_token need to run first
	need kubectl

	have "${DEFAULT_DOPPLER_SECRETS_FILE}"
	have "${IB_SECRETS_FILE}"

	kubectl apply -f "${DEFAULT_DOPPLER_SECRETS_FILE}"
}


# ========= Terraform Environment Management =========:
# these are less dynamic and more hardcoded to avoid accidental mixture of terraform states of the different environemnts
create_cluster_test() {
    local target_dir="$SCRIPT_DIR/test_env"
    
    if [[ ! -d "$target_dir" ]]; then
        echo "ERROR: Directory $target_dir not found." >&2
        return 1
    fi

    echo "[terraform] Starting cluster creation in $target_dir..."
    
    # We use a subshell ( ) to encapsulate the directory change and execution
    (
        cd "$target_dir"
        terraform init -input=false
        # This will block until the cloud provider confirms resources are up
        terraform apply -auto-approve -input=false
    )
    
    # Because of 'set -e' at the top, if terraform fails, the script exits.
    # If we reach this line, terraform finished successfully.
    echo "[terraform] Success: Cluster is provisioned and ready."
}

destroy_cluster_test() {
    local target_dir="$SCRIPT_DIR/test_env"
    
    if [[ ! -d "$target_dir" ]]; then
        echo "ERROR: Directory $target_dir not found." >&2
        return 1
    fi

    echo "[terraform] Starting cluster destruction in $target_dir..."
    
    (
        cd "$target_dir"
        # This will block until the cloud provider confirms resources are deleted
        terraform destroy -auto-approve -input=false
    )
    
    echo "[terraform] Success: Cluster resources have been torn down."
}


remove_workers_test(){
	kubectl get nodes -o name \
	| grep '^node/test' \
	| xargs -r kubectl delete

}

create_dockerhub_pull_secret() {
	need kubectl

	local NS="${1:-}"
	[[ -n "${NS}" ]] || { echo "ERROR: missing namespace argument" >&2; return 1; }

	local TOKEN
	TOKEN="$(kubectl -n default get secret "${DOCKERHUB_PULL_SECRET_NAME}" -o jsonpath='{.data.DOCKER_HUB_TOKEN}' | base64 -d)"

	kubectl -n "${NS}" create secret docker-registry "${DOCKERHUB_PULL_SECRET_NAME}" \
		--docker-server="https://index.docker.io/v1/" \
		--docker-username="${DOCKERHUB_USER}" \
		--docker-password="${TOKEN}" \
		--docker-email="unused@example.com" \
		--dry-run=client -o yaml | kubectl apply -f -
}

## if for some reason doppler is missing.. first install and then run create_doppler_service_token_secret
install_doppler() {
	need kubectl

	kubectl apply -f https://github.com/DopplerHQ/kubernetes-operator/releases/download/v1.7.1/recommended.yaml
	kubectl -n doppler-operator-system get deploy
	kubectl -n doppler-operator-system rollout status deploy/doppler-operator-controller-manager --timeout=300s
}

create_doppler_service_token_secret() {
	need kubectl

	local service_token="${1:?ERROR: missing Doppler serviceToken argument}"

	kubectl -n doppler-operator-system create secret generic doppler-token-secret \
		--from-literal="serviceToken=${service_token}" \
		--dry-run=client -o yaml | kubectl apply -f -
}

get_dockerhub_token_from_k8s_default() {
	need kubectl
	kubectl -n default get secret "${DOCKERHUB_PULL_SECRET_NAME}" -o jsonpath='{.data.DOCKER_HUB_TOKEN}' | base64 -d
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
	TOKEN="$(get_dockerhub_token_from_k8s_default)"

	docker login -u "${DOCKERHUB_USER}" -p "${TOKEN}"

	local TAG_SAFE
	TAG_SAFE="${LOCAL_IMAGE//\//-}"
	TAG_SAFE="${TAG_SAFE//:/-}"

	local REMOTE_IMAGE="${DOCKERHUB_REPO}:${TAG_SAFE}"

	docker tag "${LOCAL_IMAGE}" "${REMOTE_IMAGE}"
	docker push "${REMOTE_IMAGE}"

	printf '%s\n' "${REMOTE_IMAGE}"
}
# This function orchestrates the sequence in a single process
prepare_env() {
	# use thsi for labelling of the workers nodes
    local target_env="${1:-}"
    if [[ -z "$target_env" ]]; then
        echo "ERROR: prepare_env requires an environment (dev|test|prod)" >&2
        return 1
    fi

    # 1. Set the variable in memory
    set_deployment_env "$target_env"

    # 2. Label only the nodes matching that environment
    label_workers
	apply_default_secrets
}

set_deployment_env() {
	# needs to be run like this :
	# source ./deploy.sh
	# set_deployment_env dev
	# label_workers
    local env="${1:-}"
    
    case "$env" in
        test|dev|prod) ;;
        *) 
            echo "ERROR: invalid env '$env' (expected: test|dev|prod)" >&2
            return 1 
            ;;
    esac

    # Exporting ensures that child processes and subsequent 
    # function calls in the same script can see this.
    export DEPLOYMENT_ENV="$env"
    
    echo "Deployment environment set to: $DEPLOYMENT_ENV"
}


# ========= Helpers =========
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1"; exit 1; }; }
have() { [[ -f "$1" ]] || { echo "Required file not found: $1"; exit 1; }; }
ns_exists() { kubectl get ns "$1" >/dev/null 2>&1; }


# ========= Misc =========
status() {
	echo "status of TEST environment"
	echo "== Nodes =="; kubectl get nodes -o wide --show-labels || true
	echo "== Kafka Pods =="; kubectl -n "$NAMESPACE_KAFKA" get pods -o wide || true
	echo "== Services (kafka) =="; kubectl -n "$NAMESPACE_KAFKA" get svc || true
	echo "== Topics =="; kubectl -n "$NAMESPACE_KAFKA" get kafkatopic || true
	echo "== Spark Pods =="; kubectl -n "$NAMESPACE_SPARK" get pods -o wide || true
	echo "== ClickHouse Pods =="; kubectl -n "$NAMESPACE_CLICKHOUSE" get pods -o wide || true
	echo "== ClickHouse Svc =="; kubectl -n "$NAMESPACE_CLICKHOUSE" get svc test-clickhouse -o wide || true
	echo "== IbConnector =="; kubectl -n "$NAMESPACE_IB" get pods -o wide || true
	echo "== SchemaRegistry =="; kubectl -n "$NAMESPACE_AVRO" get pods -o wide || true
	echo "== SchemaRegistry Svc =="; kubectl -n "$NAMESPACE_AVRO" get svc "$AVRO_REG_SVC_NAME" -o wide || true
}


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

deploy_kafka() { 
	install_strimzi 
	apply_kafka_cluster 
	apply_topics 
	kubectl wait --for=condition=Ready pod --all -n "${NAMESPACE_KAFKA}" --timeout=120s

}

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


deploy_avro_schema_registry() {
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

	kubectl -n "$NAMESPACE_AVRO" rollout status deployment/test-schema-registry --timeout=400s || true
	kubectl -n "$NAMESPACE_AVRO" get pods -l app=test-schema-registry -o wide || true
	kubectl -n "$NAMESPACE_AVRO" get svc "$AVRO_REG_SVC_NAME" -o wide || true

	sleep 10

	register_avro_schemas

	kubectl wait --for=condition=Ready pod --all -n "${NAMESPACE_AVRO}" --timeout=60s
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

# ========= IB Connector (CURRENT) — stupid simple deploy =========

deploy_ib_connector() {
	# Ensure you ran az login and it was successfull!
	need docker; need kind; need kubectl; need envsubst

	have "$IB_NS_FILE"
	have "$IB_RBAC_FILE"
	have "$IB_POD_FILE"
	have "$IB_SRC_DIR/Dockerfile"

	# Ensure cert artifacts exist so the image can bake them in
	have "$IB_DIR/$IB_INFRA_DIR/ibkr_truststore.jks"
	have "$IB_DIR/$IB_INFRA_DIR/ibkr_client_portal.pem"

	echo "[ib-connector] Applying namespace …"
	kubectl apply -f "$IB_NS_FILE"
	create_dockerhub_pull_secret "${NAMESPACE_IB}"
	kubectl apply -f "${IB_SECRETS_FILE}"

	echo "[ib-connector] Building image ib-connector:test from ${IB_SRC_DIR} …"
	
	docker build \
		-f "$IB_SRC_DIR/Dockerfile" \
		-t ib-connector:test \
		"$ROOT/services_streaming_lane"

	echo "[ib-connector] Loading image into kind cluster '${CLUSTER_NAME}' …"
	push_to_dockerhub "ib-connector:test" >/dev/null

	echo "[ib-connector] Applying RBAC manifest …"
	export NAMESPACE_IB NAMESPACE_KAFKA KAFKA_NAME
	envsubst < "$IB_RBAC_FILE" | kubectl apply -f -

	echo "[ib-connector] Applying pod manifest …"
	export NAMESPACE_IB NAMESPACE_KAFKA KAFKA_NAME
	envsubst < "$IB_POD_FILE" | kubectl apply -f -

	echo "[ib-connector] Waiting for pod/ib-connector Ready …"
	kubectl -n "$NAMESPACE_IB" wait --for=condition=Ready pod/test-ib-connector --timeout=360s || true

	echo "[ib-connector] Pods:"
	kubectl -n "$NAMESPACE_IB" get pods -o wide || true
}

ib_connector_play() {
	need kubectl
	echo "[ib-connector] Running 'runMain src.main.scala.Boilerplate.play' inside the ib-connector pod …"
	kubectl -n "${NAMESPACE_IB}" exec -it test-ib-connector -c test-ib-connector -- \
		bash -lc '
			cd /work
			sbt -batch "runMain src.main.scala.Boilerplate.play"
		'
}

ib_connector_run() {
	need kubectl
	echo "[ib-connector] Running 'runMain src.main.scala.IbConnector' inside the ib-connector pod …"
	kubectl -n "${NAMESPACE_IB}" exec -it test-ib-connector -c test-ib-connector -- \
		bash -lc '
			cd /work
			sbt -batch "runMain src.main.scala.IbConnector"
		'
}

ib_connector_inspect() {
	# the last arg is the pod name 
	kubectl -n "${NAMESPACE_IB}" logs -f test-ib-connector
}

# ========= Spark: base runtime image =========

build_base_spark_image() {
	need docker; need kind
	have "$SPARK_HOME/bin/docker-image-tool.sh"
	(cd "$SPARK_HOME" && ./bin/docker-image-tool.sh -t "$SPARK_IMAGE_TAG" build)
	SPARK_REMOTE_BASE_IMAGE="$(push_to_dockerhub "spark:${SPARK_IMAGE_TAG}")"
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
FROM ${SPARK_REMOTE_BASE_IMAGE}
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
submit_spark_job() {

	kubectl -n "${NAMESPACE_SPARK}" create token "${SPARK_SA}" > /tmp/spark-sa.token

	SERVER="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')"
	CA="$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')"

cat > /tmp/spark-submit.kubeconfig <<EOF
apiVersion: v1
kind: Config
clusters:
- name: k3s
  cluster:
    server: ${SERVER}
    certificate-authority-data: ${CA}
users:
- name: spark-submit
  user:
    token: $(cat /tmp/spark-sa.token)
contexts:
- name: spark-submit@k3s
  context:
    cluster: k3s
    user: spark-submit
    namespace: ${NAMESPACE_SPARK}
current-context: spark-submit@k3s
EOF

	unset KUBECONFIG
	export KUBECONFIG="/tmp/spark-submit.kubeconfig"

	CTX="spark-submit@k3s"
	SERVER="$(kubectl config view --raw --kubeconfig "${KUBECONFIG}" --minify -o jsonpath='{.clusters[0].cluster.server}')"

	"${SPARK_HOME}/bin/spark-submit" \
		--master "k8s://${SERVER}" \
		--deploy-mode cluster \
		--name spark-app \
		--class "${SPARK_APP_CLASS}" \
		--conf "spark.kubernetes.namespace=${NAMESPACE_SPARK}" \
		--conf "spark.kubernetes.authenticate.submission.kubeconfigFile=${KUBECONFIG}" \
		--conf "spark.kubernetes.authenticate.submission.context=${CTX}" \
		--conf "spark.kubernetes.authenticate.driver.serviceAccountName=${SPARK_SA}" \
		--conf "spark.kubernetes.container.image=${SPARK_IMAGE_ADDRESS}" \
		--conf "spark.kubernetes.container.image.pullPolicy=IfNotPresent" \
		--conf "spark.kubernetes.driver.podTemplateFile=${SPARK_DRIVER_POD_TMPL}" \
		--conf "spark.kubernetes.executor.podTemplateFile=${SPARK_EXEC_POD_TMPL}" \
		--properties-file "${SPARK_DEFAULTS_FILE}" \
		"local://${APP_JAR_PATH_IN_IMAGE}" > /dev/null 2>&1 &
	# Wait for Spark to register the pod in the API
	echo "[spark] Sleeping 20s to allow Spark to create the driver pod..."
	sleep 20

	# Wait for all pods in the namespace to be Ready
	echo "[spark] Waiting for pods in namespace '${NAMESPACE_SPARK}' to be Ready..."
	kubectl wait --for=condition=Ready pod --all -n "${NAMESPACE_SPARK}" --timeout=180s
	kubectl wait --for=jsonpath='{.status.phase}'=Running pod --all -n "${NAMESPACE_SPARK}" --timeout=180s	

}

spark_insepct_driver() {
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
	export NAMESPACE_CLICKHOUSE
	envsubst < "${CLICKHOUSE_POD_FILE}" | kubectl apply -f -
	envsubst < "${CLICKHOUSE_SVC_FILE}" | kubectl apply -f -

	kubectl -n "${NAMESPACE_CLICKHOUSE}" wait --for=condition=Ready pod/test-clickhouse --timeout=240s || true
	kubectl -n "${NAMESPACE_CLICKHOUSE}" get pods -o wide
	kubectl -n "${NAMESPACE_CLICKHOUSE}" get svc test-clickhouse -o wide || true
}

peek_clickhouse_market_trades() {
	need kubectl
	local NS="${NAMESPACE_CLICKHOUSE}"
	local POD
	POD="$(kubectl -n "${NS}" get pods -l app=test-clickhouse -o jsonpath='{.items[0].metadata.name}')"

	local Q='SELECT * FROM test_realtime_store.derivatives_tick_market_data ORDER BY tick_time DESC LIMIT 10;'

	kubectl -n "${NS}" exec -it "${POD}" -- \
		clickhouse-client --multiquery --query "${Q}"
}

check_clickhouse_tables() {
	need kubectl
	local NS="${NAMESPACE_CLICKHOUSE}"
	local POD
	POD="$(kubectl -n "${NS}" get pods -l app=test-clickhouse -o jsonpath='{.items[0].metadata.name}')"

	kubectl -n "${NS}" exec -it "${POD}" -- \
		clickhouse-client --multiquery --query "
SHOW DATABASES;
SHOW TABLES FROM test_realtime_store;
SHOW CREATE TABLE test_realtime_store.derivatives_tick_market_data;
"
}

check_clickhouse_ingestion_tables() {
	need kubectl

	local NS="${NAMESPACE_CLICKHOUSE}"
	local POD
	local ROWS

	POD="$(kubectl -n "${NS}" get pods -l app=test-clickhouse -o jsonpath='{.items[0].metadata.name}')"

	ROWS="$(kubectl -n "${NS}" exec "${POD}" -- \
		clickhouse-client --query "SELECT count() FROM test_realtime_store.derivatives_tick_market_data;" | tr -d '\r\n')"

	test "${ROWS}" -gt 0

	echo "[clickhouse] OK: test_realtime_store.derivatives_tick_market_data has ${ROWS} rows"
}


destroy_kafka_namespace(){

	# 1) Delete Strimzi resources (topics/users first, then cluster)
	kubectl -n "${NAMESPACE_KAFKA}" delete kafkatopic --all
	kubectl -n "${NAMESPACE_KAFKA}" delete kafkauser --all
	kubectl -n "${NAMESPACE_KAFKA}" delete kafka "${KAFKA_NAME}"

	# 2) Wait until they are really gone
	kubectl -n "${NAMESPACE_KAFKA}" wait --for=delete kafka/"${KAFKA_NAME}" --timeout=10m || true

	# 3) (Optional, if you want a truly fresh start) delete storage
	kubectl -n "${NAMESPACE_KAFKA}" delete pvc --all

	# 4) Delete namespace last
	kubectl delete ns "${NAMESPACE_KAFKA}"

}

destroy_non_kafka_namespaces() {
	kubectl delete namespace $NAMESPACE_IB
	kubectl delete namespace $NAMESPACE_SPARK
	kubectl delete namespace $NAMESPACE_CLICKHOUSE
	kubectl delete namespace $NAMESPACE_AVRO

}

destroy_namespace_spark() {
	kubectl delete namespace $NAMESPACE_SPARK	
}

destroy_namespace_ib() {
	kubectl delete namespace $NAMESPACE_IB
}

destroy_namespace_avro() {
	kubectl delete namespace $NAMESPACE_AVRO
}

destroy_namespace_clickhouse(){
	kubectl delete namespace $NAMESPACE_CLICKHOUSE
}

usage() {
	cat <<EOF
Usage: $0 <command>
Doppler:
  install_doppler                     Install Doppler K8s operator (v1.7.1) in current context
  create_doppler_service_token_secret <serviceToken>  Create/replace doppler-token-secret in doppler-operator-system

Cluster:
  status                      Show nodes/pods/services/topics
  destroy_kafka_namespace	  Deletes kafka namespace and all of its ressources
  destroy_non_kafka_namespaces Deletes the remaining namespaces and all of their ressources
  remove_workers_test         Deletes the test workers from the k3s server 

Kafka:
  deploy_kafka                Install Strimzi, deploy Kafka & topics
  pf                          Port-forward Kafka bootstrap to localhost:${BOOTSTRAP_LOCAL_PORT}
  peek_topic_ticklast         Tail last 10 messages from 'ticklast'
  peek_topic_l2_data          Tail last 10 messages from 'l2-data'

Avro Schema Registry:
  deploy_avro_schema_registry Deploy Schema Registry into the avro-schema-registry namespace
  register_avro_schemas       Register all .avsc files into Schema Registry (compat=${AVRO_REG_COMPATIBILITY})

IB Connector (legacy):
  deploy_ib_connector_legacy  Build image and deploy ib-connector-legacy pod
  simulate_stream_legacy      [id] [intervalMs] [maxTicks]

IB Connector (current):
  deploy_ib_connector         Build image, load to kind, (re)create truststore Secret, apply pod
  ib_connector_play           Exec into pod and run 'runMain play'
  ib_connector_run			  Run the produciton version of the ib connector

Spark:
  deploy_spark                Apply spark infra, build base image, build app.jar, bake overlay image
  submit_spark_job             Submit YOUR baked app (local:///opt/spark/app/app.jar)
  start_spark_sim2            Minimal tutorial-style SparkPi using examples JAR
  peek_spark                  Shows logs from the driver pod

ClickHouse:
  deploy_clickhouse           Build clickhouse:dev image, load to kind, deploy pod + service in clickhouse namespace
  peek_clickhouse_market_trades  Show 10 latest rows from quant.market_trades
  check_clickhouse_ingestion_tables   Fail if realtime_store.derivatives_tick_market_data is empty

canonical use:
  install_doppler // one time 
  create_doppler_service_token_secret // one time
  create_cluster_test
  prepare_env test
  deploy_kafka
  deploy_avro_schema_registry
  deploy_clickhouse
  deploy_spark
  submit_spark_job
  deploy_ib_connector
  destroy_non_kafka_namespaces
  destroy_kafka_namespace
  destroy_cluster_test
  remove_workers_test

EOF
}

# ========= Main =========
need kind
need kubectl

cmd="${1:-help}"
case "$cmd" in
	install_doppler) install_doppler ;;
	create_doppler_service_token_secret) shift; create_doppler_service_token_secret "$@" ;;
	prepare_env) shift; prepare_env "$@";;
	create_cluster_test) create_cluster_test;;
	destroy_cluster_test) destroy_cluster_test;;
	remove_workers_test) remove_workers_test;;
	set_deployment_env) shift; set_deployment_env "$@";;
	destroy_non_kafka_namespaces) destroy_non_kafka_namespaces;;
	destroy_namespace_spark) destroy_namespace_spark;;
	destroy_namespace_ib) destroy_namespace_ib;;
	destroy_namespace_avro) destroy_namespace_avro;;
	destroy_namespace_clickhouse) destroy_namespace_clickhouse;;
	destroy_kafka_namespace) destroy_kafka_namespace;;
	deploy_kafka) deploy_kafka ;;
	deploy_avro_schema_registry) deploy_avro_schema_registry ;;
	deploy_ib_connector) deploy_ib_connector ;;
	ib_connector_play) ib_connector_play ;;
	ib_connector_run) ib_connector_run;;
	ib_connector_inspect) ib_connector_inspect;;
	deploy_spark) deploy_spark ;;
	submit_spark_job) submit_spark_job ;;
	spark_insepct_driver) spark_insepct_driver;;
	start_spark_sim2) start_spark_sim2 ;;
	deploy_clickhouse) deploy_clickhouse ;;
	peek_clickhouse_market_trades) peek_clickhouse_market_trades ;;
	check_clickhouse_tables) check_clickhouse_tables ;;
	check_clickhouse_ingestion_tables) check_clickhouse_ingestion_tables ;;
	peek_topic_ticklast) peek_topic_ticklast ;;
	peek_topic_l2_data) peek_topic_l2_data ;;
	peek_spark) peek_spark ;;
	status) status ;;
	label_workers) label_workers;;
	help|*) usage ;;
esac
