from __future__ import annotations

import subprocess



def apply_command(command : list[str], check_string:str) -> str:
	"""
	Docstring for apply_command
	
	:param command: like ["terraform", "apply", "-auto-approve"] commands and flags seperated
	:type command: list[str]
	:param check_string: Description a substring that if contained in the console output, proves that the command ran successfully
	:type check_string: str
	:return: returns a string indicating the command was successfull and the command it ran
	:rtype: str
	"""
	p: subprocess.CompletedProcess[str] = subprocess.run(
		command,
		capture_output=True,
		text=True,
		check=False,
	)

	out: str = (p.stdout or "") + (p.stderr or "")
	assert len(out) > 0

	ok: bool = (p.returncode == 0) and (check_string in out)

	if ok:
		return f"Command ran successful:\n${command}\n\n{out}"

	return f"Command failed:\n$ terraform apply -auto-approve\n\n{out}"


def terraform_apply()->str:
	"""
	Docstring for terraform_apply
	Needs to be executed first to create the nodes for the test cluster
	"""
	comm=["terraform", "apply", "-auto-approve"]
	check = "Apply complete! Resources:"
	return apply_command(comm,check)


def terraform_destroy()->str:
	"""
	Docstring for terraform_apply
	Needs to be executed first to create the nodes for the test cluster
	"""
	comm=["terraform", "destroy", "-auto-approve"]
	check = "Apply complete! Resources:"
	return apply_command(comm,check)

def kubectl_get_nodes()->str:
	comm=["kubectl","get","nodes"]
	check = "control-plane-server-1"
	return apply_command(comm,check)

def check_workers_present()->str:
	output= kubectl_get_nodes()
	if (
		"test-worker-1            Ready" in output and 
		"test-worker-2            Ready" in output and 
		"test-worker-3            Ready" in output and 
		"test-worker-4            Ready" in output
	) :
		"all worker nodes present"
	else :
		"output"


def deregister_workers()->str:
	comms = [["kubectl","delete","node",f"test-worker-${n}"] for n in range(4)]

	for comm in comms:
		if "Command ran successful" not in apply_command(comm,"deleted") : return "a problem occured while deregistreing worker nodes"
	return "Command ran successful"

def create_cluster_test()->bool:
	"""
	Docstring for create_cluster_test
	Creates the test cluster and checks if worker nodes are correctly registered
	:return: Description
	:rtype: bool
	"""
	assert("Command ran successful" in terraform_apply())
	assert("Command ran successful" in check_workers_present())

def tear_down_cluster_test()->bool:
	"""
	Docstring for tear_down_cluster_test
	deletes nodes (and pods) from kubernetes cluster, uses terraform to destroy the nodes
	:return: Description
	:rtype: bool
	"""
	assert("Command ran successful" in deregister_workers())
	assert("Command ran successful" in terraform_destroy())


if __name__ == "__main__":
	pass