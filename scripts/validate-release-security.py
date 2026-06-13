#!/usr/bin/env python3
"""Validates security properties of .github/workflows/release.yml.

Checks that signing credentials are not exposed as plain env vars in the
Gradle build step, that secrets are re-masked, that a cleanup step exists,
and that concurrency is configured to prevent overlapping releases.

Also checks that the Play Store service-account secret is not exposed as a
plain env var on any Gradle step, and that the SA file is cleaned up.
"""

import sys
import yaml

WORKFLOW_PATH = ".github/workflows/release.yml"
SIGNING_SECRET_KEYS = {"KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"}
# The env var name used when the Play SA JSON secret is loaded into the step env.
PLAY_SA_ENV_KEY = "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"
# The temp file path the SA JSON is written to on disk.
PLAY_SA_FILE_PATH = "/tmp/gpp-sa.json"


def fail(msg: str) -> None:
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def find_steps(workflow: dict) -> list[dict]:
    steps = []
    for job in workflow.get("jobs", {}).values():
        steps.extend(job.get("steps", []))
    return steps


def main() -> None:
    with open(WORKFLOW_PATH) as f:
        workflow = yaml.safe_load(f)

    # 1. Workflow-level concurrency must be configured.
    concurrency = workflow.get("concurrency")
    if not concurrency:
        fail("release.yml is missing a top-level 'concurrency:' group — overlapping releases can corrupt GitHub Releases and Play edits.")
    if concurrency.get("cancel-in-progress", True) is True:
        fail("release.yml concurrency.cancel-in-progress must be false — cancelling an in-progress release can leave artifacts in an inconsistent state.")

    steps = find_steps(workflow)

    # 2. A masking step must emit ::add-mask:: for every signing secret.
    mask_steps = [
        s for s in steps
        if s.get("run") and "add-mask" in s.get("run", "")
    ]
    if not mask_steps:
        fail("No '::add-mask::' step found — signing secrets must be re-masked before any Gradle invocation.")
    for key in SIGNING_SECRET_KEYS:
        if not any(key in s.get("run", "") for s in mask_steps):
            fail(f"Signing secret {key!r} is not masked via '::add-mask::' in any step.")

    # 3. Signing secrets must NOT appear in env of ANY Gradle step (not just
    #    assembleRelease), and the Play SA secret must not be exposed either.
    gradle_steps = [
        s for s in steps
        if s.get("run") and "gradlew" in s.get("run", "")
    ]
    if not gradle_steps:
        fail("Could not locate any Gradle invocation step — check validate-release-security.py if the step name changed.")
    for gradle_step in gradle_steps:
        env = gradle_step.get("env") or {}
        leaked_signing = SIGNING_SECRET_KEYS & set(env.keys())
        if leaked_signing:
            fail(
                f"Signing secret(s) {leaked_signing} are set as plain env vars on a Gradle step. "
                "Write them to ~/.gradle/gradle.properties instead."
            )
        if PLAY_SA_ENV_KEY in env:
            fail(
                f"Play Store service-account secret '{PLAY_SA_ENV_KEY}' is set as a plain env var "
                "on a Gradle step. Write the JSON to a temp file (e.g. /tmp/gpp-sa.json) and pass "
                "GOOGLE_PLAY_SERVICE_ACCOUNT_FILE instead."
            )
        # 3b. No '${{ ... }}' interpolation directly in a Gradle step's run: —
        #     that is a command-injection sink in the job that holds signing
        #     secrets. Pass values via env: and reference shell variables instead.
        if "${{" in gradle_step.get("run", ""):
            fail(
                f"Gradle step {gradle_step.get('name', '<unnamed>')!r} interpolates '${{{{ ... }}}}' "
                "directly into its run: command. Pass the value via env: and reference it as a shell "
                "variable (e.g. \"$PLAY_TRACK\") to avoid a command-injection sink."
            )

    # 4. A cleanup step must run with `if: always()` and remove both the
    #    keystore and the Play SA file so secrets never persist on a reused runner.
    cleanup_steps = [
        s for s in steps
        if s.get("if") == "always()" and s.get("run") and "release.keystore" in s.get("run", "")
    ]
    if not cleanup_steps:
        fail("No cleanup step found with 'if: always()' that removes /tmp/release.keystore — secrets may persist on a reused runner.")
    for cleanup_step in cleanup_steps:
        if PLAY_SA_FILE_PATH not in cleanup_step.get("run", ""):
            fail(
                f"Cleanup step does not remove Play SA file '{PLAY_SA_FILE_PATH}' — "
                "the service-account credentials may persist on a reused runner."
            )

    # 5. Signing credentials must be written to ~/.gradle/gradle.properties.
    props_steps = [
        s for s in steps
        if s.get("run") and "gradle.properties" in s.get("run", "") and "compassduel.signing" in s.get("run", "")
    ]
    if not props_steps:
        fail("No step found that writes 'compassduel.signing.*' properties to gradle.properties — signing credentials must not travel as env vars.")

    print(f"OK: {WORKFLOW_PATH} passes all signing-security checks.")


if __name__ == "__main__":
    main()
