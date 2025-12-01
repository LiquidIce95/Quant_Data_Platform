import subprocess


DEV_DIR: str = "/usr/local/airflow/services_streaming_lane"


def run_dev_command(command: str) -> None:
	"""Run a single dev.sh command inside the services_streaming_lane directory."""
	result: subprocess.CompletedProcess[bytes] = subprocess.run(
		["bash", "dev.sh", command],
		cwd=DEV_DIR,
	)
	assert result.returncode == 0


def test_create_and_deploy() -> None:
	"""Create kind cluster and deploy all components for the dev pipeline."""
	run_dev_command("create_cluster_dev")
	run_dev_command("deploy_kafka")
	run_dev_command("deploy_ib_connector")
	run_dev_command("deploy_spark")
	run_dev_command("deploy_clickhouse")
