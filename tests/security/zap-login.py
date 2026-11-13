#!/usr/bin/env python3
"""Signs a test user in for the authenticated ZAP scans (issue #67).

Prints two lines for $GITHUB_ENV:
  ZAP_COOKIE  the web app's session cookie, from a real sign-in through the BFF (/auth/login -> Keycloak -> callback)
  ZAP_BEARER  an access token for the API scan, from Authorization Code + PKCE with the public web client

Uses only the standard library. Environment: APP_URL, ZAP_USER, ZAP_PASSWORD, ZAP_TOTP_SECRET (every role requires
OTP, #161; e.g. the local PM with KEYCLOAK_DEV_TOTP_SECRET).
"""
import base64
import hashlib
import hmac
import html
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import time

APP = os.environ.get("APP_URL", "http://localhost:3000").rstrip("/")
USER = os.environ["ZAP_USER"]
PASSWORD = os.environ["ZAP_PASSWORD"]
TOTP_SECRET = os.environ.get("ZAP_TOTP_SECRET", "")

COOKIES: dict[str, dict[str, str]] = {}  # per host; keeps Secure cookies Keycloak sets on plain http in dev mode


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def request(url: str, data: bytes | None = None) -> tuple[int, dict, str]:
    host = urllib.parse.urlparse(url).netloc
    req = urllib.request.Request(url, data=data)
    jar = COOKIES.setdefault(host, {})
    if jar:
        req.add_header("Cookie", "; ".join(f"{k}={v}" for k, v in jar.items()))
    try:
        resp = OPENER.open(req)
        status, headers, body = resp.status, resp.headers, resp.read().decode()
    except urllib.error.HTTPError as err:
        status, headers, body = err.code, err.headers, err.read().decode(errors="replace")
    for cookie in headers.get_all("Set-Cookie") or []:
        name, _, rest = cookie.partition("=")
        jar[name.strip()] = rest.split(";", 1)[0]
    return status, headers, body


def follow(url: str, data: bytes | None = None, limit: int = 10) -> str:
    """Follows redirects until a page or a redirect to another origin's callback; returns the last URL."""
    for _ in range(limit):
        status, headers, body = request(url, data)
        data = None
        if status in (301, 302, 303, 307, 308):
            url = urllib.parse.urljoin(url, headers["Location"])
            continue
        return url if status < 400 else sys.exit(f"{url} answered {status}: {body[:300]}")
    sys.exit("too many redirects")


def totp(secret: str) -> str:
    """RFC 6238 (SHA-1, 6 digits, 30 s); Keycloak keys the HMAC with the stored secret's characters as they are."""
    counter = int(time.time() // 30).to_bytes(8, "big")
    digest = hmac.new(secret.encode(), counter, hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return f"{(int.from_bytes(digest[offset:offset + 4], 'big') & 0x7FFFFFFF) % 1_000_000:06d}"


def keycloak_login(auth_url: str) -> str:
    """Submits the Keycloak login form; returns the redirect back to the app (with ?code=)."""
    status, headers, page = request(auth_url)
    if status in (302, 303):  # SSO session already there
        return urllib.parse.urljoin(auth_url, headers["Location"])
    action = re.search(r'action="([^"]+)"', page)
    if not action:
        sys.exit("no Keycloak login form: " + page[:300])
    form = urllib.parse.urlencode({"username": USER, "password": PASSWORD}).encode()
    status, headers, page = request(html.unescape(action.group(1)), form)
    if status == 200 and 'name="otp"' in page and TOTP_SECRET:  # OTP form (#161)
        action = re.search(r'action="([^"]+)"', page)
        form = urllib.parse.urlencode({"otp": totp(TOTP_SECRET)}).encode()
        status, headers, page = request(html.unescape(action.group(1)), form)
    if status not in (302, 303):
        sys.exit("sign-in refused (wrong credentials, OTP or a required action?)")
    return urllib.parse.urljoin(auth_url, headers["Location"])


# 1. Session cookie through the BFF, exactly like a browser.
status, headers, _ = request(f"{APP}/auth/login")
callback = keycloak_login(urllib.parse.urljoin(APP, headers["Location"]))
follow(callback)
session = COOKIES.get(urllib.parse.urlparse(APP).netloc, {}).get("diligence_sid")
if not session:
    sys.exit("no diligence_sid cookie after the callback")

# 2. Access token for the API scan: PKCE with the public client, reusing the Keycloak SSO session from step 1.
login_url = urllib.parse.urlparse(urllib.parse.urljoin(APP, headers["Location"]))
query = urllib.parse.parse_qs(login_url.query)
issuer_auth = f"{login_url.scheme}://{login_url.netloc}{login_url.path}"
verifier = base64.urlsafe_b64encode(os.urandom(32)).rstrip(b"=").decode()
challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
params = {"client_id": query["client_id"][0], "response_type": "code", "scope": "openid",
          "redirect_uri": query["redirect_uri"][0], "code_challenge": challenge,
          "code_challenge_method": "S256", "state": "zap"}
back = keycloak_login(issuer_auth + "?" + urllib.parse.urlencode(params))
code = urllib.parse.parse_qs(urllib.parse.urlparse(back).query).get("code", [None])[0]
if not code:
    sys.exit("no authorization code for the API token")
token_url = issuer_auth.rsplit("/auth", 1)[0] + "/token"
form = urllib.parse.urlencode({"grant_type": "authorization_code", "client_id": params["client_id"], "code": code,
                               "redirect_uri": params["redirect_uri"], "code_verifier": verifier}).encode()
status, _, body = request(token_url, form)
if status != 200:
    sys.exit(f"token request failed: {status}")
token = json.loads(body)["access_token"]

print(f"ZAP_COOKIE=diligence_sid={session}")
print(f"ZAP_BEARER={token}")
