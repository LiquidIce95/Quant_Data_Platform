PORTFOLIO INFRASTRUCTURE BOOTSTRAP

This document records the exact steps taken to bootstrap the portfolio infrastructure using Hetzner Cloud, k3s, and Terraform Cloud.
The goal is pragmatic setup, reproducibility, and clarity.

==================================================
ACCOUNTS
==================================================

HETZNER CLOUD
Account created at
https://console.hetzner.cloud/

Two factor authentication enabled.
Default project created.

TERRAFORM CLOUD
Account created at
https://portal.cloud.hashicorp.com/sign-in

Free tier used.
Purpose is remote Terraform state and execution.

==================================================
HETZNER CLOUD CONFIGURATION
==================================================

HETZNER API TOKEN
In the Hetzner Console open the user menu in the top right and navigate to API Tokens.
A token named terraform-quant-data-platform was created.
The token was copied once.
The token is stored only in Terraform Cloud.

PRIVATE NETWORK
A private network was created in the Hetzner Console.

Name
k3s-private

Network zone
eu-central

CIDR
10.0.0.0/16

All servers are attached to this network.

FIREWALL
A firewall was created and attached only to the control plane server.

Inbound rules allow access only from the developer public IP.

TCP port 22 for SSH.
TCP port 6443 for Kubernetes API.
ICMP allowed for diagnostics.

Worker nodes have no public IP and are not directly reachable.

==================================================
CONTROL PLANE SERVER
==================================================

SERVER CREATION
A server named control-plane-server-1 was created with regular performance.

320 GB NVMe disk included.
Public IPv4 enabled.
Private network k3s-private attached.
Existing SSH public key used.

Backups disabled.
Volumes not configured.
Labels empty.
Cloud init empty.

DELETION PROTECTION
Deletion protection enabled to prevent accidental deletion or rebuild.

==================================================
K3S INSTALLATION
==================================================

SSH ACCESS
Command used to connect to the control plane server:

ssh root@<public-ip>

INSTALL K3S SERVER
The server is forced to advertise its private IP so all cluster traffic stays on the private network.

curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="server --node-ip 10.0.0.2" sh -

VERIFY INSTALLATION
kubectl get nodes

The control plane node appears in Ready state.

==================================================
KUBERNETES ACCESS FROM LOCAL MACHINE
==================================================

RETRIEVE KUBECONFIG
On the control plane server:

cat /etc/rancher/k3s/k3s.yaml

LOCAL CONFIGURATION
The file was saved as ~/.kube/config on the local machine.
The server address was changed from
https://127.0.0.1:6443
to
https://<control-plane-public-ip>:6443

TEST
kubectl get nodes

kubectl and the Python Kubernetes client both use this kubeconfig.

==================================================
TERRAFORM CLOUD SETUP
==================================================

ORGANIZATION
Organization created with name:
Quant_Data_Platform

WORKSPACE
Workspace created with name:
hetzner-test

Workflow type:
CLI driven

Execution mode:
Remote

STORE HETZNER TOKEN
Workspace variable created.

Key
HCLOUD_TOKEN

Value
Hetzner API token

Marked as sensitive.

==================================================
TERRAFORM INSTALLATION
==================================================

CHECK INSTALLATION
terraform version

INSTALL TERRAFORM ON UBUNTU 24.04

sudo apt-get update
sudo apt-get install -y gnupg software-properties-common curl
curl -fsSL https://apt.releases.hashicorp.com/gpg | sudo gpg --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list
sudo apt-get update
sudo apt-get install -y terraform

==================================================
TERRAFORM CLOUD VERIFICATION
==================================================

CREATE TEST DIRECTORY

mkdir -p ~/tf-test
cd ~/tf-test

nano main.tf

MAIN.TF CONTENT

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

TERRAFORM LOGIN

terraform login

INIT AND PLAN

terraform init
terraform plan

The plan runs remotely in Terraform Cloud.
State is stored remotely.
Hetzner provider authentication works via the workspace variable.

==================================================
DEVELOPMENT MODEL
==================================================

TERRAFORM
Terraform can be run from the control plane VM or the local development machine.
Terraform Cloud handles state and remote execution.
The Hetzner API token is never stored locally.

KUBERNETES
kubectl uses ~/.kube/config and communicates with the API server on port 6443.
The Python Kubernetes client uses the same kubeconfig.

==================================================
NEXT STEP
==================================================

Create Terraform module for worker nodes.
Use cloud init to install k3s agent with the control plane private IP and join token.
Verify workers with kubectl get nodes.

END OF DOCUMENT
