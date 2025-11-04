#!/usr/bin/env python3
import sys
import time
import requests
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By


def main():
    LOGIN_URL = "https://client-portal.client-portal-api:5000"
    LOGIN_URL2 = "https://client-portal.client-portal-api.svc:5000"
    LOGIN_URL_DEV = "https://localhost:5000"
    STATUS_URL = "https://client-portal.client-portal-api:5000/v1/api/iserver/auth/status"
    STATUS_URL_DEV = "https://localhost:5000/v1/api/iserver/auth/status"
    STATUS_URL_2 = "https://client-portal.client-portal-api.svc:5000/v1/api/iserver/auth/status"

    login_url_to_use = LOGIN_URL
    status_url_to_use = STATUS_URL

    if len(sys.argv) != 4:
        print("Usage: app.py <USERNAME> <PASSWORD> <PATH_FLAG>", flush=True)
        sys.exit(1)

    username, password, requestpath = sys.argv[1], sys.argv[2], sys.argv[3]

    if requestpath == "1":
        login_url_to_use = LOGIN_URL_DEV
        status_url_to_use = STATUS_URL_DEV
    elif requestpath == "2":
        login_url_to_use = LOGIN_URL2
        status_url_to_use = STATUS_URL_2

    print(f"[auth] Launching Chromium (headless) → {login_url_to_use}", flush=True)
    chrome_opts = Options()
    chrome_opts.add_argument("--headless=new")
    chrome_opts.add_argument("--no-sandbox")
    chrome_opts.add_argument("--disable-dev-shm-usage")
    chrome_opts.add_argument("--ignore-certificate-errors")

    driver = webdriver.Chrome(options=chrome_opts)

    try:
        driver.get(login_url_to_use)
        time.sleep(2)

        user_field = driver.find_element(By.ID, "xyz-field-username")
        pass_field = driver.find_element(By.ID, "xyz-field-password")
        submit_btn = driver.find_element(By.CSS_SELECTOR, "button[type=submit]")

        user_field.send_keys(username)
        pass_field.send_keys(password)
        submit_btn.click()

        print("[auth] Credentials submitted. Approve 2FA on your phone…", flush=True)
        time.sleep(30)

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
    main()
