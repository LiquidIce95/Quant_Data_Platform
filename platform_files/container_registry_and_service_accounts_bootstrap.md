# Azure Container Registry (ACR) — Setup

This document describes the creation of an Azure Container Registry (ACR)
used by a k3s cluster running on Hetzner to pull application images.

Scope: **registry creation only**.

---

## 1. Create the Azure Container Registry

Create the registry via the Azure Portal.

**Required settings:**

- **Registry name:** `qdpregistry`
- **Resource group:** `streaming_lane`
- **Location:** `West Europe`
- **Pricing plan:** `Standard`
- **Domain name label scope:** `Subscription Reuse`
- **Role assignment permissions mode:** `RBAC Registry Permissions`
- **Public network access:** `Enabled`

No additional configuration is required in:
- Networking
- Encryption
- Identity
- Tags

---

## 2. Verify registry creation (CLI)

Execute these commands on the K3s server. By using the ssh tunnel ot the k3s server and the correct kubectl context, you can work on the cluster form you local machine, so these commands only need to be done once on the K3s server.

Ensure you are logged in:

```bash
az login
```

Now ensure you have the correct context / subscription

```bash
az account show
```

Next store its name 

```bash
ACR_NAME="qdpregistry"
```

## 3. create service account,store credentials
We need to create a servcie principal with AcrPull on the registry

make sure you stored ACR_NAME as in the previous step, now store the ID,Name and login server 

```bash
ACR_ID="$(az acr show --name "$ACR_NAME" --query id -o tsv)"
SP_NAME="qdp-acr-pull"
ACR_LOGIN_SERVER="$(az acr show --name "$ACR_NAME" --query loginServer -o tsv)"
```
Make sure that ECHO $ACR_LOGIN_SERVER looks like qdpregistry.*.azurecr.io where * can be some suffix

you may want to verify by running the command and then checking echo $ACR_ID

**IMPORTANT: you only do this ONCE on the K3s server, doing it multple times may break the authenctication**

Now we create the account
For security best practices, trust the process and dont echo these vars, can be seen in log traces.

```bash
SP_APP_ID="$(az ad sp create-for-rbac --name "$SP_NAME" --scopes "$ACR_ID" --role "AcrPull" --query appId -o tsv)"
SP_PASSWORD="$(az ad sp credential reset --id "$SP_APP_ID" --query password -o tsv)"
```


## 5. using the secrets in kuberentes

**Anytime** you create a namespace run the following commmands after its creation, with the correct namespace name of course
```bash
NAMESPACE="test"
kubectl -n "$NAMESPACE" create secret docker-registry acr-pull \
	--docker-server="$ACR_LOGIN_SERVER" \
	--docker-username="$SP_APP_ID" \
	--docker-password="$SP_PASSWORD" \
	--docker-email=unused@example.com \
	--dry-run=client -o yaml | kubectl apply -f -

```

You now have to put the following lines in the Pod/Deployement spec yml for each component, otherwise you cannod delpoy app images from the registry as pods

```yaml
imagePullSecrets:
  - name: acr-pull

```

# Setup for pods to pull secrets from Key Vault
Make sure you have created an Azure Key Vault, you need it for the ib_connector

