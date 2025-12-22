# Portfolio Infrastructure Bootstrap

This document records the exact steps taken to bootstrap the portfolio infrastructure using Hetzner Cloud, k3s, and Terraform Cloud.
The goal is pragmatic setup, reproducibility, and clarity.

## Accounts

### Hetzner Cloud
Account created at
https://console.hetzner.cloud/

Two-factor authentication enabled.
Default project created.

### Terraform Cloud
Account created at
https://portal.cloud.hashicorp.com/sign-in

Free tier used.
Purpose is remote Terraform state and execution.

## Hetzner Cloud Configuration

### Hetzner API Token
In the Hetzner Console, open the user menu in the top right and navigate to API Tokens.

A token named terraform-quant-data-platform was created.
The token was copied once.
The token is stored only in Terraform Cloud.

### Private Network
A private network was created in the Hetzner Console.

Name
k3s-private

Network zone
eu-central

CIDR
10.0.0.0/16

All servers are attached to this network.

### Firewall
A firewall was created and attached only to the control plane server.

Inbound rules allow access only from the developer public IP.

SSH access on TCP port 22.
Kubernetes API access on TCP port 6443.
ICMP allowed for diagnostics.

Worker nodes have no public IP and are not directly reachable.

## Control Plane Server

### Server Creation
A server named control-plane-server-1 was created with regular performance.

320 GB NVMe disk included.
Public IPv4 enabled.
Private network k3s-private attached.
Existing SSH public key used.

Backups disabled.
Volumes not configured.
Labels empty.
Cloud-init empty.

### Deletion Protection
Deletion protection enabled to prevent accidental deletion or rebuild.

## k3s Installation

### SSH Access
ssh root@<public-ip>

### Install k3s Server
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="server --node-ip 10.0.0.2" sh -

### Verify Installation
kubectl get nodes

The control plane node appears in Ready state.

### Store join secret in terraform backend
ssh into the control plane server, execute 

```bash
sudo cat /var/lib/rancher/k3s/server/node-token
```

Then store the secret as a sensitive variable in terraform cloud with the key 'k3s_token'

## Kubernetes Access from Local Machine

### Retrieve kubeconfig
cat /etc/rancher/k3s/k3s.yaml

### Local Configuration
The file was saved as ~/.kube/config on the local machine.

If kind was previously used, the kubeconfig must be merged so multiple contexts can coexist.

Direct public access fails TLS validation, therefore SSH port-forwarding is used.

### Access via SSH Tunnel
ssh -N -L 6443:127.0.0.1:6443 root@<public-ip>

In another terminal:
kubectl --context k3s-hetzner get nodes

Both kubectl and the Python Kubernetes client use the same kubeconfig.

## Terraform Cloud Setup

### Organization
Quant_Data_Platform

### Workspace
hetzner-test

Workflow type: CLI-driven
Execution mode: Remote

### Store Hetzner Token
Workspace variable created.

Key
HCLOUD_TOKEN

Value
Hetzner API token

Marked as sensitive.

## Terraform Installation

### Check Installation
terraform version

### Install Terraform on Ubuntu 24.04
sudo apt-get update
sudo apt-get install -y gnupg software-properties-common curl
curl -fsSL https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list
sudo apt-get update
sudo apt-get install -y terraform

## Terraform Cloud Verification

### Create Test Directory
mkdir -p ~/tf-test
cd ~/tf-test
nano main.tf

### main.tf Content
terraform {
  cloud {
    organization = "Quant_Data_Platform"
    workspaces {
      name = "hetzner-test"
    }
  }

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.49"
    }
  }
}

provider "hcloud" {}

### Terraform Login
terraform login

Will prompt you to create a token and store it, the token cannot be viewed in the UI afterwards but with the command 

```bash
cat ~/.terraform.d/credentials.tfrc.json
```

### Init and Plan
terraform init
terraform plan

The plan runs remotely in Terraform Cloud.
State is stored remotely.
Hetzner provider authentication works via the workspace variable.

## Development Model

### Terraform
Terraform can be run from the control plane VM or the local development machine.
Terraform Cloud handles state and remote execution.
The Hetzner API token is never stored locally.

