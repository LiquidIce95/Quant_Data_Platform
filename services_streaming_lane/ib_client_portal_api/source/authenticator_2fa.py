#!/usr/bin/env python3
import sys
import os
import time
import requests
import pyotp
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By

from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.common.action_chains import ActionChains
from selenium.webdriver.common.keys import Keys

def generate_totp_code(totp_secret: str, interval: int = 30, settle_seconds: int = 5) -> str:
	# Force UTC each call so container TZ never affects codes
	os.environ["TZ"] = "UTC"
	time.tzset()
	

	secret = totp_secret.strip()
	
	totp = pyotp.TOTP(secret.replace(" ", "").upper(), interval=interval, digits=6)

	now = time.time()
	remaining = interval - (now % interval)
	if remaining <= settle_seconds:
		time.sleep(remaining + settle_seconds)
	else:
		time.sleep(settle_seconds)

	return totp.now()


def authenticate(username,password,requestpath):
	LOGIN_URL = "https://client-portal.client-portal-api:5000"
	LOGIN_URL2 = "https://client-portal.client-portal-api.svc:5000"
	LOGIN_URL_DEV = "https://localhost:5000"
	STATUS_URL = "https://client-portal.client-portal-api:5000/v1/api/iserver/auth/status"
	STATUS_URL_DEV = "https://localhost:5000/v1/api/iserver/auth/status"
	STATUS_URL_2 = "https://client-portal.client-portal-api.svc:5000/v1/api/iserver/auth/status"

	login_url_to_use = LOGIN_URL
	status_url_to_use = STATUS_URL

	if requestpath == "1":
		login_url_to_use = LOGIN_URL_DEV
		status_url_to_use = STATUS_URL_DEV
	elif requestpath == "2":
		login_url_to_use = LOGIN_URL2
		status_url_to_use = STATUS_URL_2

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
		
		time.sleep(1.5)
		## your section for selecting mobile authenticator starts here
		select_el = driver.find_element(By.CSS_SELECTOR, "select.form-control.xyz-multipleselect")
		select_el.click()
		time.sleep(1.5)
		opt = driver.find_element(By.XPATH, "//select[contains(@class,'xyz-multipleselect')]/option[contains(., 'Mobile Authenticator')]")
		opt.click()
		time.sleep(1.5)
		## your seciton for the implementation ends here

		submit_btn2 = WebDriverWait(driver, 20).until(
			EC.element_to_be_clickable((
				(By.XPATH,
				"/html/body/section/div/div/div[2]/div[2]/div[4]/form/div[3]/button")
			))
		)
		two_fa_code = generate_totp_code()

		## your section for implementing the entering of the two_fa_code which is correct and does not need any formatting, then pressing the login button like above in the already implemented code , start here
		code_field = driver.find_element(By.ID, "xyz-field-silver-response")
		code_field.clear()
		code_field.send_keys(two_fa_code)
		time.sleep(1.5)
		
		
		submit_btn2 = driver.find_element(By.XPATH, "/html/body/section/div/div/div[2]/div[2]/div[4]/form/div[3]/button")

		driver.execute_script("arguments[0].scrollIntoView(true);",submit_btn2)

		submit_btn2 = driver.find_element(By.XPATH, "/html/body/section/div/div/div[2]/div[2]/div[4]/form/div[3]/button")

		driver.execute_script("arguments[0].scrollIntoView(true);",submit_btn2)
		submit_btn2.click()

		print("[auth] submitted 2FA and credentials", flush=True)
		time.sleep(3)
		
		if "/sso/Dispatcher" in driver.current_url:
			# check message
			els = driver.find_elements(By.CSS_SELECTOR, "pre")
		if els and "Client login succeeds" in els[0].text:
			authenticated_page = True
			print("[auth] Portal page: Client login succeeds", flush=True)
		else: 
			print("[auth] did not reach final page")
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
	#authenticate("liquidice95","KronosOregeonUracil95","1")
    print(generate_totp_code())