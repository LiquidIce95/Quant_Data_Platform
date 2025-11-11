#!/usr/bin/env python3
import sys
import time
import requests
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from azure.identity import DefaultAzureCredential
from azure.keyvault.secrets import SecretClient


LOGIN_URL_DEV = "https://localhost:5000"
STATUS_URL_DEV = "https://localhost:5000/v1/api/iserver/auth/status"
KEYVAULT_NAME = "ibkr-secrets"
KEYVAULT_URL = f"https://{KEYVAULT_NAME}.vault.azure.net"


def build_secret_client() -> SecretClient:
	"""
	Build a SecretClient using DefaultAzureCredential.
	"""
	credential = DefaultAzureCredential()
	client = SecretClient(vault_url=KEYVAULT_URL, credential=credential)
	return client


def get_ibkr_credentials(user_id: int) -> tuple[str, str]:
	"""
	Fetch IBKR username and password for the given user_id from Key Vault.

	Secrets:
	  ibkr-username-{user_id}
	  ibkr-password-{user_id}
	"""
	client = build_secret_client()

	username_secret_name = f"ibkr-username-{user_id}"
	password_secret_name = f"ibkr-password-{user_id}"

	username = client.get_secret(username_secret_name).value
	password = client.get_secret(password_secret_name).value

	return username, password


def authenticate(userId:int):

	login_url_to_use = LOGIN_URL_DEV
	status_url_to_use = STATUS_URL_DEV


	username, password = get_ibkr_credentials(userId)


	print(f"[auth] Launching Chromium (headless) → {login_url_to_use}", flush=True)
	chrome_opts = Options()
	chrome_opts.add_argument("--headless=new")
	chrome_opts.add_argument("--window-size=1920,1080")
	chrome_opts.add_argument("--force-device-scale-factor=1")
	chrome_opts.add_argument("--high-dpi-support=1")
	chrome_opts.add_argument("--no-sandbox")
	chrome_opts.add_argument("--disable-dev-shm-usage")
	chrome_opts.add_argument("--ignore-certificate-errors")

	driver = webdriver.Chrome(options=chrome_opts)

	# immediately after creating the driver
	driver.set_window_rect(width=1920, height=1080)
	driver.execute_cdp_cmd("Emulation.setDeviceMetricsOverride", {
		"mobile": False, "width": 1920, "height": 1080, "deviceScaleFactor": 1
	})
	driver.execute_cdp_cmd("Emulation.setPageScaleFactor", {"pageScaleFactor": 1})

	try:
		driver.get(login_url_to_use)
		time.sleep(1.5)

		## your section, you can only add code here , toggle paper trading on 
		toggle_label = driver.find_element(By.CSS_SELECTOR, 'label[for="toggle1"]')
		toggle_label.click()
		## your implementation for toggling on paper trading ends here
		time.sleep(1.5)

		user_field = driver.find_element(By.ID, "xyz-field-username")
		pass_field = driver.find_element(By.ID, "xyz-field-password")
		submit_btn = driver.find_element(By.CSS_SELECTOR, "button[type=submit]")

		user_field.send_keys(username)
		pass_field.send_keys(password)
		submit_btn.click()

		print("[auth] Credentials submitted. Approve 2FA on your phone…", flush=True)
		time.sleep(5)

		sess = requests.Session()
		
		print(f"[auth] Checking {status_url_to_use} …", flush=True)
		resp = sess.get(status_url_to_use, verify=False)
		print(f"[auth] HTTP {resp.status_code}")
		print(resp.text)
	except Exception as e:
		print(f"[auth] ERROR: {e}", flush=True)
		sys.exit(2)
	finally:
		driver.quit()


if __name__ == "__main__":
	assert len(sys.argv)>=2
	userId = sys.argv[1]
	authenticate(userId)
