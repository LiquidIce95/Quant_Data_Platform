A cluster secret is a secret that needs to be shared between cluster compoennts, not the cloud environemnt, for example a clickhouse user for sprk

create the environmental variables on the K3s then anytime you create the namespace where the secret is needed, run

```bash
kubectl -n "$NAMESPACE" create secret generic "$SECRET_NAME" \
	--from-literal=password="$CH_PASSWORD"

```