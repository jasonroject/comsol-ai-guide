"""Compile and run the COMSOL Java example in two independent batch processes.

python run_smoke.py --comsol-bin <Multiphysics/bin/win64> --out <NEW directory>
No Python third-party dependencies. Requires COMSOL and relevant license.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import traceback


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--comsol-bin", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--cores", type=int, default=1)
    p.add_argument("--timeout", type=int, default=180, help="Seconds per subprocess")
    a = p.parse_args()
    if a.cores < 1 or a.timeout < 1:
        p.error("cores and timeout must be positive")
    binaries = Path(a.comsol_bin).resolve()
    for exe in ("comsolcompile.exe", "comsolbatch.exe"):
        if not (binaries / exe).is_file():
            p.error(f"Missing executable: {binaries / exe}")
    out = Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    for sub in ("code", "logs", "tmp_build", "tmp_verify", "recovery_build", "recovery_verify"):
        (out / sub).mkdir()
    source = Path(__file__).with_name("SmokeConduction.java")
    java = out / "code" / source.name
    shutil.copy2(source, java)
    report = {"status": "started", "source_sha256": hashlib.sha256(java.read_bytes()).hexdigest(),
              "comsol_bin": str(binaries), "cores": a.cores, "processes": []}
    started = time.monotonic()
    env = os.environ.copy()
    env["PATH"] = str(binaries) + os.pathsep + env.get("PATH", "")
    env["COMSOL_GUIDE_OUT"] = str(out)

    def command(label, argv, mode="build"):
        env["COMSOL_GUIDE_MODE"] = mode
        entry = {"label": label, "command": [str(x) for x in argv]}
        report["processes"].append(entry)
        print(f"{label}: starting", flush=True)
        with (out / "logs" / f"{label}_stdout.log").open("wb") as log:
            child = subprocess.Popen(entry["command"], cwd=out, env=env, stdout=log,
                                     stderr=subprocess.STDOUT,
                                     creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            entry["pid"] = child.pid
            try:
                entry["exit_code"] = child.wait(timeout=a.timeout)
            except subprocess.TimeoutExpired:
                # Only this child is terminated; shared COMSOL processes are untouched.
                child.kill()
                child.wait()
                entry["timed_out"] = True
                entry["descendant_cleanup"] = "Check task ownership before any additional cleanup"
                raise RuntimeError(f"{label} exceeded {a.timeout}s; inspect logs and task-owned descendants")
        if entry["exit_code"] != 0:
            raise RuntimeError(f"{label} exit={entry['exit_code']}; see logs")
        print(f"{label}: finished", flush=True)

    try:
        command("compile", [binaries / "comsolcompile.exe", java])
        compiled = java.with_suffix(".class")
        if not compiled.is_file() or compiled.stat().st_mtime < java.stat().st_mtime:
            raise RuntimeError("Missing or stale Java class")
        for mode in ("build", "verify"):
            command(mode, [binaries / "comsolbatch.exe", "-np", str(a.cores),
                           "-inputfile", compiled, "-nosave", "-tmpdir", out / f"tmp_{mode}",
                           "-recoverydir", out / f"recovery_{mode}",
                           "-batchlog", out / "logs" / f"{mode}_batch.log"], mode)
            log = (out / "logs" / f"{mode}_stdout.log").read_text(errors="replace")
            if f"COMSOL_GUIDE_OK={mode}" not in log:
                raise RuntimeError(f"{mode}: missing completion marker")
        report["checks"] = {}
        for phase in ("first", "reopened_cached", "reopened_recomputed"):
            data = json.loads((out / f"{phase}.json").read_text())
            if data.get("passed") is not True:
                raise RuntimeError(f"Failed phase: {phase}")
            report["checks"][phase] = data
        if not (out / "smoke.mph").is_file() or not (out / "smoke.mph").stat().st_size:
            raise RuntimeError("Missing saved MPH model")
        report["status"] = "passed"
    except Exception:
        report["status"] = "failed"
        report["traceback"] = traceback.format_exc()
    finally:
        report["elapsed_seconds"] = time.monotonic() - started
        (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"status": report["status"], "report": str(out / "report.json"),
                      "checks": report.get("checks"), "error": report.get("traceback")},
                     ensure_ascii=False, indent=2), flush=True)
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
