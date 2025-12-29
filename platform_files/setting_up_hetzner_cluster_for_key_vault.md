
For local development use 'az login'

First we need to enable OIDC on the K3s 

```bash
sudo mkdir -p /etc/rancher/k3s

sudo tee /etc/rancher/k3s/config.yaml >/dev/null <<'EOF'
kube-apiserver-arg:
  - service-account-issuer=https://kubernetes.default.svc
  - service-account-signing-key-file=/var/lib/rancher/k3s/server/tls/service.key
  - service-account-key-file=/var/lib/rancher/k3s/server/tls/service.key
EOF

sudo systemctl restart k3s
 kubectl get --raw /.well-known/openid-configuration
```

Install webhook for identity management

```bash
kubectl apply -f https://github.com/Azure/azure-workload-identity/releases/latest/download/azure-wi-webhook.yaml
kubectl get pods -n azure-workload-identity-system
```

Install Secrets Store CSI Driver + Azure provider

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes-sigs/secrets-store-csi-driver/v1.5.5/deploy/rbac-secretproviderclass.yaml
kubectl apply -f https://raw.githubusercontent.com/kubernetes-sigs/secrets-store-csi-driver/v1.5.5/deploy/csidriver.yaml
kubectl apply -f https://raw.githubusercontent.com/kubernetes-sigs/secrets-store-csi-driver/v1.5.5/deploy/secrets-store.csi.x-k8s.io_secretproviderclasses.yaml

kubectl apply -f https://raw.githubusercontent.com/Azure/secrets-store-csi-driver-provider-azure/v1.7.2/deployment/provider-azure-installer.yaml

kubectl get pods -n kube-system | egrep 'secrets-store|provider-azure'
kubectl get csidrivers | grep secrets-store.csi.k8s.io

```

in the following you must replace <KEYVAULTNAME> with the correct name of the keyvault you want to set up permissions for. Obvsioulsy make sure the key vault does exist and holds the secrets you are interested in

```bash
KV_NAME="<KEYVAULTNAME>"
APP_NAME="k3s-ib-connector"
NAMESPACE="ib"
SERVICE_ACCOUNT="ib-connector-sa"

TENANT_ID="$(az account show --query tenantId -o tsv)"

APP_ID="$(az ad app create --display-name "$APP_NAME" --query appId -o tsv)"
az ad sp create --id "$APP_ID" >/dev/null

KV_ID="$(az keyvault show --name "$KV_NAME" --query id -o tsv)"

az role assignment create \
	--assignee "$APP_ID" \
	--role "Key Vault Secrets User" \
	--scope "$KV_ID"

cat >/tmp/fed.json <<EOF
{
	"name": "k3s-${NAMESPACE}-${SERVICE_ACCOUNT}",
	"issuer": "https://kubernetes.default.svc",
	"subject": "system:serviceaccount:${NAMESPACE}:${SERVICE_ACCOUNT}",
	"audiences": ["api://AzureADTokenExchange"]
}
EOF

az ad app federated-credential create \
	--id "$APP_ID" \
	--parameters @/tmp/fed.json

echo "$APP_ID"
echo "$TENANT_ID"

```