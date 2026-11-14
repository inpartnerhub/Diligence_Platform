#!/usr/bin/env python3
"""Demo mode (branch `demo`, local stack): (re)creates project DEMO-001 with data at every stage for every role.

Everything goes through the real API, signed in as the demo users of `make demo-setup` (password + OTP through
Keycloak's own login pages), so the platform's rules apply exactly as in the UI: information barriers, four-eyes
approval, status transitions, audit log. Documents are small generated PDFs with fictional content.

    make demo-seed            # needs: make up, the backend running, make keycloak-bootstrap, make demo-setup

Standard library only. Reads .env from the repository root.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import html
import http.cookiejar
import json
import os
import re
import secrets
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CODE = "DEMO-001"


def load_env() -> dict[str, str]:
    env = {}
    for line in (ROOT / ".env").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip()
    return env


ENV = load_env()
KEYCLOAK = "http://localhost:8180/realms/diligence/protocol/openid-connect"
API = f"http://localhost:{ENV.get('BACKEND_PORT', '8080')}/api/v1"
REDIRECT = "http://localhost:3000/auth/callback"


# ---------------------------------------------------------------------------------------------------------- sign-in
class _AllCookies(http.cookiejar.DefaultCookiePolicy):
    """Keycloak marks its cookies Secure even on the plain-HTTP local stack."""

    def return_ok_secure(self, cookie, request):
        return True

    def set_ok(self, cookie, request):
        return True


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def _totp(secret: str) -> str:
    # Keycloak keys the HMAC with the stored secret's own bytes (see tests/security/zap-login.py).
    counter = struct.pack(">Q", int(time.time()) // 30)
    digest = hmac.new(secret.encode(), counter, hashlib.sha1).digest()
    offset = digest[-1] & 15
    return "%06d" % ((struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000)


def _form_action(page: str, form_id: str) -> str | None:
    tag = re.search(rf'<form[^>]*\bid="{form_id}"[^>]*>', page)
    action = tag and re.search(r'\baction="([^"]+)"', tag.group(0))
    return html.unescape(action.group(1)) if action else None


def sign_in(username: str) -> str:
    """Access token for a demo user: Authorization Code + PKCE through Keycloak's login and OTP forms."""
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(_AllCookies())),
                                         _NoRedirect)

    def call(url, data=None):
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        try:
            with opener.open(urllib.request.Request(url, data=body)) as response:
                return response.status, response.read().decode(), None
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode(errors="ignore"), error.headers.get("Location")

    verifier = base64.urlsafe_b64encode(os.urandom(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    query = urllib.parse.urlencode({"client_id": "diligence-web", "response_type": "code", "scope": "openid",
                                    "redirect_uri": REDIRECT, "code_challenge": challenge,
                                    "code_challenge_method": "S256", "state": "demo", "prompt": "login"})
    _, page, _ = call(f"{KEYCLOAK}/auth?{query}")
    status, page, location = call(_form_action(page, "kc-form-login"),
                                  {"username": username, "password": ENV["DEMO_PASSWORD"], "credentialId": ""})
    for attempt in range(2):
        if location or not _form_action(page, "kc-otp-login-form"):
            break
        if attempt:  # the same code was already used in this 30-second window
            time.sleep(31 - time.time() % 30)
        status, page, location = call(_form_action(page, "kc-otp-login-form"), {"otp": _totp(ENV["DEMO_TOTP_SECRET"])})
    if not location or "code=" not in location:
        sys.exit(f"Sign-in as {username} failed (status {status}). Run make demo-setup first.")
    code = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)["code"][0]
    token_form = urllib.parse.urlencode({"grant_type": "authorization_code", "client_id": "diligence-web",
                                         "client_secret": ENV["OIDC_CLIENT_SECRET"], "code": code,
                                         "redirect_uri": REDIRECT, "code_verifier": verifier}).encode()
    with urllib.request.urlopen(urllib.request.Request(f"{KEYCLOAK}/token", data=token_form)) as response:
        return json.loads(response.read())["access_token"]


# --------------------------------------------------------------------------------------------------------- API calls
class Api:
    def __init__(self, username: str):
        self.username = username
        self.token = sign_in(username)
        self.user_id = json.loads(base64.urlsafe_b64decode(self.token.split(".")[1] + "=="))["sub"]

    def call(self, method: str, path: str, body=None, raw: bytes | None = None, content_type: str | None = None):
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        elif content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(f"{API}{path}", data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request) as response:
                text = response.read().decode()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as error:
            sys.exit(f"{self.username}: {method} {path} → {error.code} {error.read().decode(errors='ignore')[:400]}")

    def upload(self, project: str, folder: str, name: str, content: bytes):
        boundary = secrets.token_hex(16)
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
                f"Content-Type: application/pdf\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
        return self.call("POST", f"/projects/{project}/folders/{folder}/documents", raw=body,
                         content_type=f"multipart/form-data; boundary={boundary}")


# ------------------------------------------------------------------------------------------------------------ helpers
def psql(sql: str) -> str:
    result = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", ENV["POSTGRES_USER"],
                             "-d", ENV["POSTGRES_DB"], "-v", "ON_ERROR_STOP=1", "-Atq", "-c", sql],
                            cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        sys.exit(f"psql failed: {result.stderr}")
    return result.stdout.strip()


def pdf(title: str, lines: list[str]) -> bytes:
    """A one-page PDF with Helvetica text (fictional demo content)."""
    def esc(text: str) -> str:
        return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    stream = ["BT", "/F1 16 Tf", "60 780 Td", f"({esc(title)}) Tj", "/F1 11 Tf", "0 -28 Td"]
    for line in lines:
        stream += [f"({esc(line)}) Tj", "0 -16 Td"]
    stream += ["ET"]
    content = "\n".join(stream).encode("latin-1", "replace")
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> "
               b"/Contents 4 0 R >>",
               b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % number + obj + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for offset in offsets:
        out += b"%010d 00000 n \n" % offset
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref)
    return bytes(out)


def days(n: int) -> str:
    return (date.today() + timedelta(days=n)).isoformat()


# --------------------------------------------------------------------------------------------------------- the seed
DOCUMENTS = {
    "LEGAL": [
        ("Akta Pendirian dan Perubahan.pdf", "Akta Pendirian PT Target Sejahtera", [
            "Akta No. 12 tanggal 3 Maret 2015, Notaris fiktif untuk demo.",
            "Perubahan terakhir: Akta No. 7 tanggal 10 Januari 2025 (perubahan direksi).",
            "Pemegang saham: PT Induk Sejahtera 90%, Koperasi Karyawan 10%."]),
        ("Perjanjian Kredit Bank Nusantara.pdf", "Perjanjian Kredit Investasi", [
            "Plafon kredit Rp 250.000.000.000, jangka waktu 7 tahun.",
            "Pasal 14.2 Change of Control: perubahan pemegang saham pengendali tanpa",
            "persetujuan tertulis bank merupakan cidera janji dan seluruh utang jatuh tempo.",
            "Jaminan: tanah dan bangunan pabrik Cikarang, fidusia mesin produksi."]),
        ("Nomor Induk Berusaha.pdf", "Nomor Induk Berusaha (NIB)", [
            "KBLI 10792: industri makanan ringan.",
            "Kegiatan ekspor dan perdagangan daring belum tercantum dalam NIB."]),
    ],
    "FINANCIAL": [
        ("Laporan Keuangan Audit 2025.pdf", "Laporan Keuangan Konsolidasian 2025 (audit)", [
            "Pendapatan Rp 412 miliar, laba bersih Rp 38 miliar.",
            "Piutang usaha Rp 96 miliar; 61% dari satu pelanggan (PT Ritel Raya).",
            "Opini auditor: wajar tanpa modifikasian."]),
        ("Daftar Utang dan Piutang.pdf", "Daftar Utang dan Piutang per 30 September 2026", [
            "Daftar ini belum dirinci per pelanggan dan per umur piutang."]),
    ],
    "TAX": [
        ("SPT Tahunan PPh Badan 2025.pdf", "SPT Tahunan PPh Badan 2025", [
            "Penghasilan kena pajak Rp 51 miliar, PPh terutang Rp 11,2 miliar.",
            "Kompensasi kerugian fiskal: tidak ada."]),
        ("Surat Ketetapan Pajak Kurang Bayar 2023.pdf", "SKPKB PPh Badan Tahun Pajak 2023", [
            "Kurang bayar pokok Rp 6,4 miliar ditambah sanksi administrasi.",
            "Koreksi utama: biaya promosi yang tidak didukung daftar nominatif."]),
    ],
}


def main() -> None:
    print("Signing in as the demo users…")
    admin, partner, pm, analyst = Api("demo.admin"), Api("demo.partner"), Api("demo.pm"), Api("demo.analyst")
    observer, client, target = Api("demo.observer"), Api("demo.client"), Api("demo.target")
    for api in (admin, partner, pm, analyst, observer, client, target):
        api.call("GET", "/me")  # just-in-time account rows

    client_org = psql("SELECT id FROM adm.organization WHERE type = 'CLIENT' ORDER BY created_at LIMIT 1")
    target_org = psql("SELECT id FROM adm.organization WHERE type = 'TARGET' ORDER BY created_at LIMIT 1")
    if not client_org or not target_org:
        sys.exit("No client/target organization. Create them in Administrasi → Organisasi first.")
    # External accounts belong to the project's organizations (#155); invitations would set this.
    psql(f"UPDATE adm.user_account SET organization_id = '{client_org}' WHERE id = '{client.user_id}'")
    psql(f"UPDATE adm.user_account SET organization_id = '{target_org}' WHERE id = '{target.user_id}'")

    # Earlier demo projects (DEMO-001 and leftovers of interrupted runs): renamed, closed and without the demo users,
    # so every role sees exactly one demo project.
    demo_users = ", ".join(f"'{api.user_id}'" for api in (admin, partner, pm, analyst, observer, client, target))
    for old in filter(None, psql(f"SELECT id FROM prj.project WHERE code LIKE '{CODE}%'").splitlines()):
        print(f"Retiring an earlier demo project ({old})…")
        psql(f"UPDATE prj.project SET code = 'DEMO-OLD-{old[:8]}', stage = 'CLOSED' WHERE id = '{old}'")
        psql(f"DELETE FROM prj.project_member WHERE project_id = '{old}' AND user_id IN ({demo_users})")

    print(f"Creating project {CODE}…")
    project = pm.call("POST", "/projects", {
        "code": CODE, "name": "Akuisisi PT Target Sejahtera", "clientOrganizationId": client_org,
        "targetOrganizationId": target_org, "startDate": days(-30), "endDate": days(45),
        "workstreams": ["LEGAL", "FINANCIAL", "TAX"]})
    pid = project["id"]
    ws = {w["type"]: w["id"] for w in pm.call("GET", f"/projects/{pid}/workstreams")}

    for api, role, stream in ((partner, "PARTNER", None), (analyst, "ANALYST", None), (observer, "OBSERVER", None),
                              (client, "CLIENT", None), (target, "TARGET", None)):
        pm.call("POST", f"/projects/{pid}/members", {"userId": api.user_id, "role": role,
                                                       **({"workstreamId": stream} if stream else {})})

    print("Data room: folders and documents…")
    folders = {}
    for name, stream in (("01 Legal", "LEGAL"), ("02 Keuangan", "FINANCIAL"), ("03 Pajak", "TAX")):
        folder = pm.call("POST", f"/projects/{pid}/folders", {"name": name, "workstreamId": ws[stream]})
        folders[stream] = folder["id"]
        pm.call("PUT", f"/projects/{pid}/folders/{folder['id']}/access",
                {"grants": [{"type": "ROLE", "permission": "UPLOAD"}]})
    internal = pm.call("POST", f"/projects/{pid}/folders", {"name": "99 Catatan internal tim"})

    documents = {}
    for stream, files in DOCUMENTS.items():
        for file_name, title, lines in files:
            uploaded = target.upload(pid, folders[stream], file_name, pdf(title, lines))
            documents[file_name] = uploaded.get("id") or uploaded.get("documentId") or uploaded["document"]["id"]
    analyst.upload(pid, internal["id"], "Memo kerja analis.pdf",
                   pdf("Memo kerja (internal)", ["Catatan kerja tim; tidak dibagikan ke pihak target."]))

    print("Request list…")
    items = {}
    for number, stream, category, description, priority, due, wave in (
            ("LEG-001", "LEGAL", "Korporasi", "Akta pendirian dan seluruh perubahannya", "HIGH", 7, 1),
            ("LEG-002", "LEGAL", "Perjanjian", "Perjanjian kredit dan perjanjian material lainnya", "HIGH", 7, 1),
            ("LEG-003", "LEGAL", "Perizinan", "NIB dan izin usaha yang berlaku", "MEDIUM", -2, 1),
            ("LEG-004", "LEGAL", "Litigasi", "Daftar perkara dan sengketa yang sedang berjalan", "MEDIUM", 21, 2),
            ("FIN-001", "FINANCIAL", "Laporan keuangan", "Laporan keuangan audit 3 tahun terakhir", "HIGH", 7, 1),
            ("FIN-002", "FINANCIAL", "Modal kerja", "Daftar utang dan piutang per pelanggan dan umur", "MEDIUM", 5, 1),
            ("FIN-003", "FINANCIAL", "Proyeksi", "Proyeksi keuangan 5 tahun dan asumsinya", "LOW", 28, 2),
            ("TAX-001", "TAX", "PPh Badan", "SPT Tahunan PPh Badan 3 tahun terakhir", "HIGH", 7, 1),
            ("TAX-002", "TAX", "Pemeriksaan", "Seluruh SKP dan korespondensi pemeriksaan pajak", "HIGH", 3, 1)):
        item = pm.call("POST", f"/projects/{pid}/requests", {
            "workstreamId": ws[stream], "number": number, "category": category, "description": description,
            "priority": priority, "dueDate": days(due), "wave": wave})
        items[number] = item["id"]
    pm.call("POST", f"/projects/{pid}/requests/release", {"wave": 1})

    for number, file_name in (("LEG-001", "Akta Pendirian dan Perubahan.pdf"),
                              ("LEG-002", "Perjanjian Kredit Bank Nusantara.pdf"),
                              ("FIN-001", "Laporan Keuangan Audit 2025.pdf"),
                              ("FIN-002", "Daftar Utang dan Piutang.pdf"),
                              ("TAX-001", "SPT Tahunan PPh Badan 2025.pdf"),
                              ("TAX-002", "Surat Ketetapan Pajak Kurang Bayar 2023.pdf")):
        target.call("POST", f"/projects/{pid}/requests/{items[number]}/documents",
                    {"documentId": documents[file_name], "comment": "Terlampir."})
        target.call("POST", f"/projects/{pid}/requests/{items[number]}/status", {"status": "RECEIVED"})
    analyst.call("POST", f"/projects/{pid}/requests/{items['FIN-002']}/status",
                 {"status": "REJECTED", "reason": "Mohon dirinci per pelanggan dan per umur piutang (0-30, 31-90, >90 hari)."})
    analyst.call("POST", f"/projects/{pid}/requests/{items['LEG-001']}/status", {"status": "CLOSED"})

    print("Findings…")
    findings = {}
    for key, stream, title, category, description, recommendation, impact, likelihood, source, money in (
            ("coc", "LEGAL", "Klausul change of control pada perjanjian kredit", "Kontrak",
             "Pasal 14.2 perjanjian kredit dengan Bank Nusantara menjadikan perubahan pengendali tanpa persetujuan bank "
             "sebagai cidera janji; seluruh fasilitas Rp 250 miliar dapat dipercepat jatuh temponya.",
             "Jadikan persetujuan tertulis bank sebagai syarat pendahuluan penandatanganan (CP) dalam SPA.",
             4, 4, "Perjanjian Kredit Bank Nusantara.pdf", 250_000_000_000),
            ("tax", "TAX", "Potensi kurang bayar PPh Badan tahun 2023", "Pajak",
             "SKPKB 2023 menetapkan kurang bayar Rp 6,4 miliar ditambah sanksi; koreksi serupa berpotensi berulang "
             "untuk tahun 2024 dan 2025.",
             "Mintakan indemnitas pajak spesifik dari penjual dan escrow sebesar estimasi eksposur.",
             4, 3, "Surat Ketetapan Pajak Kurang Bayar 2023.pdf", 6_400_000_000),
            ("ar", "FINANCIAL", "Konsentrasi piutang pada satu pelanggan", "Keuangan",
             "61% piutang usaha berasal dari PT Ritel Raya; keterlambatan pembayaran pelanggan ini berdampak langsung "
             "pada modal kerja.", "Lakukan analisis umur piutang dan konfirmasi saldo dengan pelanggan utama.",
             3, 3, "Laporan Keuangan Audit 2025.pdf", None),
            ("nib", "LEGAL", "NIB belum mencakup kegiatan ekspor dan penjualan daring", "Perizinan",
             "Kegiatan ekspor dan perdagangan daring yang sudah berjalan belum tercantum dalam KBLI pada NIB.",
             "Perbarui NIB sebelum penutupan transaksi.", 2, 3, "Nomor Induk Berusaha.pdf", None),
            ("cash", "FINANCIAL", "Selisih rekonsiliasi kas antar rekening", "Keuangan",
             "Draf: selisih rekonsiliasi kas belum dijelaskan oleh tim keuangan target.", "Menunggu klarifikasi.",
             2, 2, "Laporan Keuangan Audit 2025.pdf", None)):
        body = {"workstreamId": ws[stream], "title": title, "category": category, "description": description,
                "recommendation": recommendation, "impact": impact, "likelihood": likelihood}
        if money:
            body.update(monetaryImpact=money, currency="IDR")
        finding = analyst.call("POST", f"/projects/{pid}/findings", body)
        findings[key] = finding["id"]
        analyst.call("POST", f"/projects/{pid}/findings/{finding['id']}/documents", {"documentId": documents[source]})
    for key in ("coc", "tax", "ar", "nib"):
        analyst.call("POST", f"/projects/{pid}/findings/{findings[key]}/status", {"status": "IN_REVIEW"})
    for key in ("coc", "tax", "ar"):
        pm.call("POST", f"/projects/{pid}/findings/{findings[key]}/status", {"status": "APPROVED"})
    for key in ("coc", "tax"):
        partner.call("POST", f"/projects/{pid}/findings/{findings[key]}/status", {"status": "RELEASED"})

    print("Q&A…")
    q1 = analyst.call("POST", f"/projects/{pid}/questions", {
        "body": "Apakah sudah ada persetujuan tertulis Bank Nusantara atas rencana perubahan pemegang saham pengendali?",
        "workstreamId": ws["LEGAL"], "documentId": documents["Perjanjian Kredit Bank Nusantara.pdf"],
        "findingId": findings["coc"], "dueDate": days(5), "submit": True})
    q2 = analyst.call("POST", f"/projects/{pid}/questions", {
        "body": "Apakah atas SKPKB 2023 sudah diajukan keberatan? Mohon lampirkan surat keberatan bila ada.",
        "workstreamId": ws["TAX"], "documentId": documents["Surat Ketetapan Pajak Kurang Bayar 2023.pdf"],
        "dueDate": days(4), "submit": True})
    analyst.call("POST", f"/projects/{pid}/questions", {
        "body": "Mohon penjelasan selisih rekonsiliasi kas antara rekening operasional dan rekening penampungan.",
        "workstreamId": ws["FINANCIAL"], "dueDate": days(7), "submit": True})
    for question in (q1, q2):
        pm.call("POST", f"/projects/{pid}/questions/{question['id']}/status", {"status": "SENT"})
    target.call("PUT", f"/projects/{pid}/questions/{q1['id']}/answer", {
        "body": "Permohonan persetujuan sudah dikirim ke bank pada 1 November 2026; jawaban bank diharapkan dalam 30 hari."})
    pm.call("POST", f"/projects/{pid}/questions/{q1['id']}/status", {"status": "CLOSED"})

    print("Report…")
    report = partner.call("POST", f"/projects/{pid}/reports", {
        "title": "Laporan Due Diligence — Temuan Utama", "language": "id",
        "findingIds": [findings["coc"], findings["tax"]]})
    partner.call("POST", f"/projects/{pid}/reports/{report['id']}/submit", {"note": "Siap direview."})
    pm.call("POST", f"/projects/{pid}/reports/{report['id']}/approve", {"note": "Sudah direview."})
    partner.call("POST", f"/projects/{pid}/reports/{report['id']}/release", None)

    pm.call("PATCH", f"/projects/{pid}", {"stage": "CLARIFICATION"})
    print(f"Done: project {CODE} ({pid}). Open http://localhost:3000/demo and pick a role.")


if __name__ == "__main__":
    main()
