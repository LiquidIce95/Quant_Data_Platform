# Interactive Brokers Web API – Architecture Notes & Final Design Decision

## 1. Background
Every **Interactive Brokers (IBKR) retail account** comes with:
- **One live trading account**
- **One paper trading account**
- **One set of paper trading credentials** (a completely separate login/user)
- **It is possible to set up multiple general purpose users per live account that can access the live and paper trading account**
- **It is not possible to set up multiple paper trading account**
- **Because of the previous point, it's not possible to set up multiple paper trading credentials/users per live account**

Each user can only maintain **one active brokerage session** at a time.  
This means the same IBKR user (paper or general purpose) cannot be logged into two Web API sessions simultaneously — running **two pods with the same credentials** will always fail. Hence we need a unique user per pod.

The official IBKR docs for the web api state, that if one wants to use the paper trading account, we have to use the dedicated paper trading login, not a general purpose user.

---

## 2. The Initial Goal
We wanted to avoid opening a second funded account and instead reuse the same paper trading account with multiple users.  
The idea was to:
- Log in with **regular user credentials** (not the dedicated paper credentials)
- Use **TOTP (third-party authenticator)** for 2FA to access the **paper environment**
- Test if multiple general users could simultaneously log in to the paper system

It was crucial to note that **paper trading credentials** are **not** the same as general user credentials.  
Paper credentials represent a **separate, dedicated paper-only user**, which is independent from any general user who can log into both live and paper via the web portal. Per live account we can only have one such paper user / paper credentials.

---

## 3. TOTP Experiment Results
Using TOTP worked perfectly when logging into the **official IBKR Client Portal** (the web interface).  
It even worked against the **Client Portal Web API** in the sense that:
- The automation reached the final “**Client login succeeded**” page, proving that credentials and 2FA were correct.

However, the API response from  
`/v1/api/iserver/auth/status`  
still returned: {"authenticated":false}
even though the login was visibly successful.  
This confirmed that while TOTP works for the portal, it does **not** establish an authenticated session for the **Client Portal Web API**.

So It's possible to completely automate the 2FA login process in this way, by securely storing the 2FA/TOTP secret in something like Azure Key Vault and then pulling it when we need to create the 2FA code, then using selenium to pass in the credentials and the 2FA code into the login page for the web api (running at localhost) but it does not yield an authenticated session, even though we reach the final page saying "client login succeeded".

---

## 4. Shared Gateway (Service) Test
We also tested running one **Client Portal Gateway** instance as a shared service in the cluster.  
However, this failed consistently — every pod checking  
`/iserver/auth/status`  
received `authenticated=false`, likely due to **DNS and localhost binding**.  
External confirmation from developer forums and IBKR documentation indicates that **the gateway must always run on the same host (localhost) as the client**.

Also this would not be a desired architecture because the client portal gateway would represent a single point of failure, defeating the whole purpose of a distributed setting.

---

## 5. Final Architecture Decision
Running the **Client Portal Gateway** as a **sidecar container** in each pod (same node as the processing engine) is the **only feasible and reliable setup**.

- Each pod runs and manages its own **gateway sidecar** bound to `localhost:5000`
- Each gateway logs in using its own **paper trading credentials**
- No 2FA is required for paper credentials and the paper account user does not have any permissions for the live account which makes it secure
- Sessions remain fully authenticated
- L2 market data from the Web API is already well-structured, avoiding custom message parsing and complex state handling (recall that when using the legacy api, socket api from ib we need to track the order book per symbol and emit changed rows ourselves which led to the need of sharing states between the processor and the connection manager)

Running live accounts is **insecure** (Hackers would gain access to real money) and **impractical** for automation (manual 2FA process with ib key, cannot be automated).  
Using general users for paper logins with TOTP also fails to create valid Web API sessions.  
Therefore, the **only robust solution** is to use **two separate funded accounts**, each with its own paper trading credentials.

---

## 6. Practical & Financial Considerations
- Each IBKR account requires **at least $500 equity** to access real-time data.  
- Two accounts → ~$1,000 total “frozen” capital.
- Market data costs: **$32/month** for full real-time futures with L2 depth.

This $1,000 can be treated as **emergency or backup money**. It remains liquid and safe inside the brokerage account, similar to idle savings in a bank. Whether that backup money is lying around in a bank account or here, does not make any difference and everyonne should keep a few thousand dollars as backup.

The trade-off is minor compared to the architectural benefits:
- Fault tolerance across pods
- Full load balancing and sharding support
- True end-to-end distribution (from IB connector through ClickHouse)
- Stable, secure, and API-compliant setup with minimal operational overhead

---

## 7. Conclusion
This final solution:
- Uses **two funded accounts** → **two paper credentials**
- Runs **two pods**, each with its own **gateway sidecar**
- Eliminates the need for insecure live logins or unreliable TOTP hacks
- Enables a **fully distributed**, **fault-tolerant**, **production-ready** streaming architecture

In short:  
A small financial commitment (~$1,000 idle capital + $32/month data) delivers a clean, scalable, and maintainable end-to-end data pipeline.

**Final verdict:** This is the perfect solution — secure, inexpensive, fully distributed, and architecturally elegant.
