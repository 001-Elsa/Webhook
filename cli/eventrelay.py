#!/usr/bin/env python3
"""Small dependency-free EventRelay operations CLI."""
import argparse
import json
import os
import urllib.request


def request(method, path, body=None):
    base = os.environ.get("EVENTRELAY_URL", "http://localhost:8080")
    headers = {
        "X-App-Id": os.environ["EVENTRELAY_APP_ID"],
        "X-Api-Key": os.environ["EVENTRELAY_API_KEY"],
        "Content-Type": "application/json",
    }
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(
            base + path, data=data, headers=headers, method=method), timeout=10) as response:
        return json.load(response)


parser = argparse.ArgumentParser(prog="eventrelay")
sub = parser.add_subparsers(dest="command", required=True)
sub.add_parser("deliveries")
replay = sub.add_parser("replay")
replay.add_argument("--dry-run", action="store_true")
replay.add_argument("--max", type=int, default=1000)
diagnose = sub.add_parser("diagnose")
diagnose.add_argument("delivery_id", type=int)
args = parser.parse_args()

if args.command == "deliveries":
    result = request("GET", "/api/deliveries")
elif args.command == "replay":
    result = request("POST", "/api/replay-jobs",
                     {"dryRun": args.dry_run, "maxDeliveries": args.max})
else:
    result = request("POST", f"/api/deliveries/{args.delivery_id}/diagnosis")
print(json.dumps(result, indent=2, ensure_ascii=False))
