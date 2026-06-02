"""
Demo script — simulates 3 attack/failure scenarios against a running LogGuard backend.
Usage: python demo_data.py [--url http://localhost:8080]

to run it :
python3 ai-engine/demo_data.py
"""
import requests
import time
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--url", default="http://localhost:8080")
args = parser.parse_args()

BASE = args.url
HEADERS = {"Content-Type": "application/json"}


def login() -> str:
    r = requests.post(f"{BASE}/api/auth/login", json={"username": "admin", "password": "admin123"})
    r.raise_for_status()
    token = r.json()["token"]
    print(f"[auth] logged in, token: {token[:30]}...")
    return token


def post_log(token: str, level: str, source: str, message: str, raw: str = None):
    headers = {**HEADERS, "Authorization": f"Bearer {token}"}
    payload = {"level": level, "source": source, "message": message}
    if raw:
        payload["rawPayload"] = raw
    r = requests.post(f"{BASE}/api/logs", json=payload, headers=headers)
    if r.status_code == 201:
        print(f"  [{source}] {level}: {message[:80]}")
    else:
        print(f"  ERROR {r.status_code}: {r.text[:120]}")
    time.sleep(0.05)


def scenario_brute_force(token: str):
    print("\n=== Scenario 1: Brute Force Attack ===")
    for i in range(15):
        post_log(token, "ERROR", "auth-service",
                 f"Authentication failed for user 'admin' from IP 192.168.1.{i % 10} — invalid credentials",
                 f'{{"event":"login_failed","attempt":{i+1},"ip":"192.168.1.{i % 10}"}}')
    post_log(token, "WARN", "auth-service",
             "Account 'admin' temporarily locked after repeated authentication failures")


def scenario_cascade_failure(token: str):
    print("\n=== Scenario 2: Cascading Failure ===")
    post_log(token, "ERROR", "database-service",
             "Connection pool exhausted — max connections reached (100/100)")
    post_log(token, "ERROR", "user-service",
             "Failed to fetch user profile: database-service timed out after 5000ms")
    post_log(token, "ERROR", "order-service",
             "Cannot validate user session: upstream user-service returned 503")
    post_log(token, "ERROR", "payment-service",
             "Order validation failed: order-service dependency unavailable")
    post_log(token, "FATAL", "api-gateway",
             "Critical upstream failures — circuit breaker OPEN for 4 services")
    post_log(token, "ERROR", "notification-service",
             "Failed to send alert emails: database-service connection refused")


def scenario_similar_flood(token: str):
    print("\n=== Scenario 3: Similar Message Flood (TF-IDF trigger) ===")
    templates = [
        "Cache miss for key user_session_{} — falling back to database lookup",
        "Redis cache lookup failed for key user_session_{} — database hit required",
        "Session cache entry user_session_{} not found — querying primary store",
        "Cache MISS on user_session_{}: redirecting request to database backend",
        "Unable to retrieve user_session_{} from cache layer — DB fallback initiated",
        "Key user_session_{} missing from Redis cache pool — database query triggered",
        "Cache eviction detected for user_session_{} — reloading from persistent store",
        "Stale cache entry user_session_{} purged — fresh database query in progress",
    ]
    for i, tpl in enumerate(templates):
        post_log(token, "WARN", "cache-service", tpl.format(1000 + i))


if __name__ == "__main__":
    token = login()
    scenario_brute_force(token)
    scenario_cascade_failure(token)
    scenario_similar_flood(token)
    print("\n[done] all scenarios sent.")
