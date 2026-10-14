"""Test the runtime image using only temporary Docker resources and offline data."""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


def docker(*args, check=True):
    return subprocess.run(
        ["docker", *args], check=check, text=True, capture_output=True, timeout=120
    ).stdout.strip()


def request(base, path, payload=None, method=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        base + path, data=data, method=method, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=5) as response:
        body = response.read()
        return json.loads(body) if body else None


def wait_for(description, condition):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            if condition():
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(1)
    raise TimeoutError(description)


def main(image):
    name = "job-monitor-smoke-" + uuid.uuid4().hex
    volume = name + "-data"
    try:
        assert docker("run", "--rm", "--entrypoint", "id", image, "-u") == "10001"
        docker(
            "run", "--rm", "--init", "--shm-size=1g", "--network=none",
            "--entrypoint", "java", image, "--enable-native-access=ALL-UNNAMED",
            "-cp", "/app/lib/*", "com.microsoft.playwright.CLI", "screenshot",
            "--browser=chromium", "--viewport-size=640,480",
            "data:text/html,<h1>Job Monitor container smoke</h1>", "/tmp/smoke.png",
        )
        docker("volume", "create", volume)
        docker(
            "run", "--detach", "--init", "--name", name,
            "--publish", "127.0.0.1::8080", "--mount", f"type=volume,src={volume},dst=/data",
            image,
        )
        address = docker("port", name, "8080/tcp")
        assert address.startswith("127.0.0.1:")
        base = "http://" + address

        def ready():
            request(base, "/api/v1/search-queries")
            return True

        wait_for("Application did not start", ready)
        query = request(base, "/api/v1/search-queries", {
            "name": "Container smoke", "queryText": '(Java OR "Kotlin") AND NOT Senior',
            "enabled": False, "intervalMinutes": 60, "source": "FAKE",
        })
        query_path = "/api/v1/search-queries/" + query["id"]
        request(base, query_path + "/run", method="POST")

        def collected():
            runs = request(base, "/api/v1/collection-runs")["content"]
            if runs and runs[0]["status"] == "FAILED":
                raise RuntimeError("Container collection failed")
            return bool(runs and runs[0]["status"] == "SUCCESS")

        wait_for("FAKE collection did not finish", collected)
        vacancies = request(base, "/api/v1/vacancies")
        assert vacancies["totalElements"] == 2
        vacancy_path = "/api/v1/vacancies/" + vacancies["content"][0]["id"]
        request(base, vacancy_path + "/status", {"status": "SAVED"}, "PATCH")
        docker("exec", name, "test", "-s", "/data/job-monitor.db")
        docker("restart", "--time=30", name)
        # Docker may assign a new ephemeral host port when restarting the container.
        address = docker("port", name, "8080/tcp")
        assert address.startswith("127.0.0.1:")
        base = "http://" + address
        wait_for("Application did not restart", ready)
        assert request(base, query_path)["queryText"] == query["queryText"]
        assert request(base, vacancy_path)["status"] == "SAVED"
        assert request(base, "/api/v1/vacancies")["totalElements"] == 2
        wait_for(
            "Container health check did not pass",
            lambda: docker("inspect", "--format", "{{.State.Health.Status}}", name) == "healthy",
        )
        print("Container smoke passed: non-root, Chromium, collection, SQLite persistence, health.")
    except subprocess.CalledProcessError as error:
        print(error.stdout, error.stderr, file=sys.stderr)
        raise
    finally:
        if sys.exc_info()[0] is not None:
            print(docker("logs", name, check=False), file=sys.stderr)
        docker("rm", "--force", "--volumes", name, check=False)
        docker("volume", "rm", volume, check=False)


if __name__ == "__main__":
    main(sys.argv[1])
