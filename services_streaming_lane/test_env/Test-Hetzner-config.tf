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

variable "HCLOUD_TOKEN" {
	type      = string
	sensitive = true
}

variable "k3s_token" {
	type      = string
	sensitive = true
}

provider "hcloud" {
	token = var.HCLOUD_TOKEN
}

variable "server_type" {
	type    = string
	default = "cpx32"
}

data "hcloud_network" "k3s_private" {
	name = "k3s-private"
}

data "hcloud_ssh_key" "david_key" {
	name = "david_mx_linux_latest"
}

data "hcloud_firewall" "basic" {
	name = "basic_firewall_test_env_workers"
}

locals {
	workers = [
		{
			name     = "Test-worker-1"
			workload = "Test-ib-connector"
		},
		{
			name     = "Test-worker-2"
			workload = "Test-kafka"
		},
		{
			name     = "Test-worker-3"
			workload = "Test-spark"
		},
		{
			name     = "Test-worker-4"
			workload = "Test-clickhouse"
		},
	]
}

resource "hcloud_server" "test_worker" {
	count = length(local.workers)

	name        = local.workers[count.index].name
	image       = "ubuntu-24.04"
	server_type = var.server_type
	location    = "nbg1"

	ssh_keys = [
		data.hcloud_ssh_key.david_key.id
	]

	public_net {
		ipv4_enabled = true
		ipv6_enabled = true
	}

	network {
		network_id = data.hcloud_network.k3s_private.id
	}

	labels = {
		env      = "test"
		workload = local.workers[count.index].workload
	}

user_data = <<-EOF
#cloud-config
runcmd:
  - |
    while ! ip -4 addr show enp7s0 | grep -q 'inet '; do sleep 2; done
    IP=$(ip -4 addr show enp7s0 | awk '/inet / {print $2}' | cut -d/ -f1)
    curl -sfL https://get.k3s.io | \
      K3S_URL="https://10.0.0.2:6443" \
      K3S_TOKEN="${var.k3s_token}" \
      INSTALL_K3S_EXEC="agent --node-ip $IP" \
      K3S_NODE_LABEL="node-role.kubernetes.io/worker=true" \
      sh -
EOF

}

resource "hcloud_firewall_attachment" "test_worker_fw" {
	firewall_id = data.hcloud_firewall.basic.id
	server_ids  = [for s in hcloud_server.test_worker : s.id]
}

output "test_worker_public_ipv4" {
	value = {
		for i, w in local.workers :
		w.name => hcloud_server.test_worker[i].ipv4_address
	}
}

output "test_worker_private_ip" {
	value = {
		for i, w in local.workers :
		w.name => one([for n in hcloud_server.test_worker[i].network : n.ip])
	}
}
