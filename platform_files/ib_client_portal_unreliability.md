```md
# IBKR Client Portal Gateway – Deployment Decision

# Summary
- The Interactive Brokers Client Portal Gateway is not production-grade in a containerized or Kubernetes environment
- Although the HTTP endpoint returns 200 OK, the gateway fails to establish a stable authenticated session
- As a result, the IB connector is intentionally limited to the test environment and not deployed to production

# Reproduction

# Authentication Check
- Run inside the container
- /opt/venv/bin/python authenticator_no_2fa.py 1

# Observed Result
- authenticated false
- connected false
- competing false
- HTTP status code is 200

# Equivalent Check
- curl -sk https://localhost:5000/v1/api/iserver/auth/status

# Key Observation
- HTTP layer is reachable
- Application layer authentication never completes
- HTTP 200 does not imply an authenticated or connected session

# Impact
- Gateway starts and accepts connections
- Initial streaming events may be emitted
- After approximately 60 to 90 seconds the gateway becomes unstable or terminates
- Downstream processes receive SIGTERM and restart
- Failures are non deterministic and not observable via Kubernetes health signals

# Root Cause
- IBKR Client Portal Gateway fails to complete authentication even after successful 2FA approval
- Session state remains unauthenticated and disconnected
- This is an upstream gateway defect
- Scala connector Kafka and ClickHouse pipeline are not the cause

# Decision
- IB connector is kept in the test environment only
- No production deployment
- CI remains green
- Early events reach Kafka
- Data flows into ClickHouse
- End to end plumbing is validated

# Rationale
- The gateway exhibits unreliable and opaque failure modes
- Operational risk is unacceptable for production use
- The component is documented as test only by design
```
