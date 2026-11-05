#!/usr/bin/env python3
"""Fails the DAST job on high-risk ZAP alerts or on rules configured FAIL (issue #67).

Usage: zap-gate.py <report.json>... ; ZAP exit codes of the scans in ZAP_EXIT_CODES ("baseline=0 api=1").
Prints a summary per risk level for the job log and the step summary.
"""
import json
import os
import sys

RISK = {"0": "Informational", "1": "Low", "2": "Medium", "3": "High"}
high, lines = [], []
for path in sys.argv[1:]:
    if not os.path.exists(path):
        lines.append(f"- {path}: missing (the scan did not finish)")
        high.append(f"{path}: no report")
        continue
    with open(path) as report:
        data = json.load(report)
    counts = {level: 0 for level in RISK.values()}
    for site in data.get("site", []):
        for alert in site.get("alerts", []):
            level = RISK.get(str(alert.get("riskcode")), "Informational")
            counts[level] += 1
            if level == "High":
                high.append(f"{alert.get('pluginid')} {alert.get('alert')} ({site.get('@name')})")
    lines.append(f"- {os.path.basename(path)}: " + ", ".join(f"{n} {k}" for k, n in counts.items()))

failed_rules = [entry for entry in os.environ.get("ZAP_EXIT_CODES", "").split()
                if entry.endswith("=1") or entry.endswith("=3")]
summary = "## OWASP ZAP\n" + "\n".join(lines) + "\n"
if high:
    summary += "\n**High-risk alerts:**\n" + "\n".join(f"- {h}" for h in high) + "\n"
if failed_rules:
    summary += f"\n**Rules configured FAIL were hit or the scan errored:** {' '.join(failed_rules)}\n"
print(summary)
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
        out.write(summary)
sys.exit(1 if high or failed_rules else 0)
