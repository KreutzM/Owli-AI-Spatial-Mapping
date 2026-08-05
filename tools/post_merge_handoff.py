#!/usr/bin/env python3
"""Publish an idempotent post-merge verification comment on the merged PR."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import time
from typing import Any, Callable, Iterable
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

MARKER = "<!-- post-merge-handoff -->"
ACTIONS_BOT_LOGIN = "github-actions[bot]"
ACTIONS_BOT_TYPE = "Bot"
QUALITY_WORKFLOW = "Quality"
REPO_PACK_WORKFLOW = "WebAgent Repo Pack"
ADS_WORKFLOW = "Automatic Dependency Submission (Gradle)"
ARTIFACT_NAME = "webagent-repo-pack"
SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
RUN_ID_PATTERN = re.compile(r"^[1-9][0-9]*$")
DIGEST_PATTERN = re.compile(r"^sha256:[0-9a-fA-F]{64}$")


@dataclass(frozen=True)
class Trigger:
    merge_sha: str
    repo_pack_run_id: int


@dataclass(frozen=True)
class PublishedHandoff:
    pull_number: int
    comment_id: int
    comment_action: str
    failures: tuple[str, ...]


class GitHubApi:
    def __init__(self, repository: str, token: str, api_url: str = "https://api.github.com") -> None:
        if "/" not in repository:
            raise ValueError("repository must use owner/name form")
        if not token:
            raise ValueError("github.token is required")
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")

    def _request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        request = Request(
            f"{self.api_url}{path}",
            data=body,
            method=method,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "owli-post-merge-handoff",
            },
        )
        try:
            with urlopen(request, timeout=30) as response:
                raw = response.read()
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"GitHub API {method} {path} failed: {error.code} {detail}") from error
        return None if not raw else json.loads(raw.decode("utf-8"))

    def _paginate(
        self,
        path: str,
        *,
        query: dict[str, str] | None = None,
        key: str | None = None,
    ) -> list[dict[str, Any]]:
        collected: list[dict[str, Any]] = []
        page = 1
        while True:
            params = dict(query or {})
            params.update({"per_page": "100", "page": str(page)})
            response = self._request("GET", f"{path}?{urlencode(params)}")
            items = response if key is None else response.get(key, [])
            if not isinstance(items, list):
                raise RuntimeError(f"GitHub API pagination payload for {path} is malformed")
            collected.extend(items)
            if len(items) < 100:
                return collected
            page += 1

    def list_commit_pulls(self, sha: str) -> list[dict[str, Any]]:
        return self._paginate(f"/repos/{self.repository}/commits/{quote(sha)}/pulls")

    def list_workflow_runs(self, sha: str) -> list[dict[str, Any]]:
        return self._paginate(
            f"/repos/{self.repository}/actions/runs",
            query={"head_sha": sha},
            key="workflow_runs",
        )

    def get_workflow_run(self, run_id: int) -> dict[str, Any]:
        response = self._request("GET", f"/repos/{self.repository}/actions/runs/{run_id}")
        if not isinstance(response, dict):
            raise RuntimeError("GitHub workflow-run response is malformed")
        return response

    def list_run_artifacts(self, run_id: int) -> list[dict[str, Any]]:
        return self._paginate(
            f"/repos/{self.repository}/actions/runs/{run_id}/artifacts",
            key="artifacts",
        )

    def list_issue_comments(self, issue_number: int) -> list[dict[str, Any]]:
        return self._paginate(f"/repos/{self.repository}/issues/{issue_number}/comments")

    def create_issue_comment(self, issue_number: int, body: str) -> dict[str, Any]:
        response = self._request(
            "POST",
            f"/repos/{self.repository}/issues/{issue_number}/comments",
            {"body": body},
        )
        if not isinstance(response, dict):
            raise RuntimeError("GitHub create-comment response is malformed")
        return response

    def update_issue_comment(self, comment_id: int, body: str) -> dict[str, Any]:
        response = self._request(
            "PATCH",
            f"/repos/{self.repository}/issues/comments/{comment_id}",
            {"body": body},
        )
        if not isinstance(response, dict):
            raise RuntimeError("GitHub update-comment response is malformed")
        return response

    def delete_issue_comment(self, comment_id: int) -> None:
        self._request("DELETE", f"/repos/{self.repository}/issues/comments/{comment_id}")


def validate_sha(value: str) -> str:
    normalized = value.strip().lower()
    if not SHA_PATTERN.fullmatch(normalized):
        raise ValueError("merge SHA must be exactly 40 hexadecimal characters")
    return normalized


def parse_run_id(value: Any) -> int:
    normalized = str(value or "").strip()
    if not RUN_ID_PATTERN.fullmatch(normalized):
        raise ValueError("repo_pack_run_id is required and must be a positive integer")
    return int(normalized)


def _repository_name(run: dict[str, Any], key: str) -> str:
    repository = run.get(key) or {}
    return str(repository.get("full_name") or "") if isinstance(repository, dict) else ""


def validate_repo_pack_run(
    run: dict[str, Any] | None,
    repository: str,
    *,
    expected_sha: str | None = None,
) -> list[str]:
    if run is None:
        return ["WebAgent Repo Pack run is missing"]

    failures: list[str] = []
    if run.get("name") != REPO_PACK_WORKFLOW:
        failures.append(f"workflow name must be exactly {REPO_PACK_WORKFLOW}")
    if _repository_name(run, "repository") != repository:
        failures.append("repo-pack run repository does not match the current repository")
    if _repository_name(run, "head_repository") != repository:
        failures.append("repo-pack run head repository does not match the current repository")
    if run.get("head_branch") != "main":
        failures.append("repo-pack run head_branch must be main")
    if run.get("status") != "completed":
        failures.append("repo-pack run status must be completed")
    if run.get("conclusion") != "success":
        failures.append("repo-pack run conclusion must be success")

    try:
        run_sha = validate_sha(str(run.get("head_sha") or ""))
    except ValueError:
        run_sha = ""
        failures.append("repo-pack run head_sha is not a valid full commit SHA")
    if expected_sha is not None and run_sha and run_sha != expected_sha:
        failures.append("repo-pack run does not match the exact merge SHA")

    try:
        parse_run_id(run.get("id"))
    except ValueError:
        failures.append("repo-pack run id is not a positive integer")
    return failures


def resolve_trigger(
    api: Any,
    event_name: str,
    event: dict[str, Any],
    dispatch_run_id: str = "",
) -> Trigger | None:
    if event_name == "workflow_run":
        run = event.get("workflow_run") or {}
        if not isinstance(run, dict):
            return None
        failures = validate_repo_pack_run(run, api.repository)
        if failures:
            return None
        return Trigger(validate_sha(str(run["head_sha"])), parse_run_id(run["id"]))

    if event_name == "workflow_dispatch":
        run_id = parse_run_id(dispatch_run_id)
        run = api.get_workflow_run(run_id)
        failures = validate_repo_pack_run(run, api.repository)
        if failures:
            raise RuntimeError(
                f"invalid recovery repo-pack run {run_id}: " + "; ".join(failures)
            )
        return Trigger(validate_sha(str(run["head_sha"])), run_id)

    raise ValueError(f"unsupported event: {event_name}")


def select_run(
    runs: Iterable[dict[str, Any]],
    workflow_name: str,
    sha: str,
) -> dict[str, Any] | None:
    matches = [
        run
        for run in runs
        if run.get("name") == workflow_name
        and run.get("head_sha") == sha
        and run.get("head_branch") == "main"
    ]
    return max(matches, key=lambda run: int(run.get("id", 0)), default=None)


def run_status(run: dict[str, Any] | None, missing_reason: str) -> tuple[str, str, str]:
    if run is None:
        return f"unavailable ({missing_reason})", "n/a", "n/a"
    status = str(run.get("conclusion") or run.get("status") or "unknown")
    url = str(run.get("html_url") or "n/a")
    run_id = str(run.get("id") or "n/a")
    return status, url, run_id


def wait_for_ads(
    api: Any,
    sha: str,
    *,
    attempts: int,
    delay_seconds: float,
    sleeper: Callable[[float], None] = time.sleep,
) -> tuple[dict[str, Any] | None, str]:
    attempts = max(1, attempts)
    latest: dict[str, Any] | None = None
    for attempt in range(1, attempts + 1):
        latest = select_run(api.list_workflow_runs(sha), ADS_WORKFLOW, sha)
        if latest is not None and latest.get("status") == "completed":
            return latest, ""
        if attempt < attempts:
            sleeper(delay_seconds)
    if latest is None:
        return None, f"no exact-SHA run visible after {attempts} attempts"
    return latest, f"still {latest.get('status', 'unknown')} after {attempts} attempts"


def find_merged_pull(api: Any, sha: str) -> dict[str, Any]:
    matches = [
        pull
        for pull in api.list_commit_pulls(sha)
        if pull.get("merged_at")
        and pull.get("merge_commit_sha") == sha
        and (pull.get("base") or {}).get("ref") == "main"
    ]
    if len(matches) != 1:
        raise RuntimeError(
            "expected exactly one pull request merged into main with "
            f"merge_commit_sha={sha}; found {len(matches)}"
        )
    return matches[0]


def find_artifact(
    api: Any,
    repo_pack_run: dict[str, Any] | None,
) -> tuple[dict[str, Any] | None, list[str]]:
    if repo_pack_run is None or not repo_pack_run.get("id"):
        return None, ["webagent-repo-pack artifact cannot be resolved without a repo-pack run"]
    artifacts = [
        artifact
        for artifact in api.list_run_artifacts(int(repo_pack_run["id"]))
        if artifact.get("name") == ARTIFACT_NAME
    ]
    if len(artifacts) != 1:
        return None, [
            f"expected exactly one {ARTIFACT_NAME} artifact on repo-pack run; found {len(artifacts)}"
        ]

    artifact = artifacts[0]
    failures: list[str] = []
    digest = str(artifact.get("digest") or "")
    if not DIGEST_PATTERN.fullmatch(digest):
        failures.append("artifact digest must be exactly sha256:<64 hexadecimal characters>")
    if artifact.get("expired"):
        failures.append("webagent-repo-pack artifact is expired")
    return artifact, failures


def render_comment(
    *,
    sha: str,
    pull_number: int,
    quality: dict[str, Any] | None,
    ads: dict[str, Any] | None,
    ads_reason: str,
    repo_pack: dict[str, Any] | None,
    artifact: dict[str, Any] | None,
) -> str:
    quality_status, quality_url, quality_id = run_status(
        quality, "no exact-SHA Quality run found"
    )
    ads_status, ads_url, ads_id = run_status(
        ads, ads_reason or "no exact-SHA Automatic Dependency Submission run found"
    )
    if ads is not None and ads_reason:
        ads_status = f"{ads_status} ({ads_reason})"
    repo_status, repo_url, repo_id = run_status(
        repo_pack, "no exact-SHA WebAgent Repo Pack run found"
    )
    artifact_id = str(artifact.get("id")) if artifact else "unavailable"
    artifact_digest = str(artifact.get("digest") or "unavailable") if artifact else "unavailable"

    return (
        f"{MARKER}\n"
        "POST-MERGE HANDOFF\n\n"
        "Schema: `owli.post-merge-handoff/v1`\n"
        f"Pull request: #{pull_number}\n"
        f"Merge commit: `{sha}`\n"
        f"Quality: {quality_status} — {quality_url}\n"
        f"Quality run ID: {quality_id}\n"
        f"Automatic Dependency Submission: {ads_status} — {ads_url}\n"
        f"Automatic Dependency Submission run ID: {ads_id}\n"
        f"WebAgent Repo Pack: {repo_status} — {repo_url}\n"
        f"WebAgent Repo Pack run ID: {repo_id}\n"
        f"Artifact: `{ARTIFACT_NAME}`\n"
        f"Artifact ID: {artifact_id}\n"
        f"Artifact digest: {artifact_digest}\n"
    )


def is_managed_handoff_comment(comment: dict[str, Any]) -> bool:
    body = str(comment.get("body") or "")
    user = comment.get("user") or {}
    if not isinstance(user, dict):
        return False
    return (
        body.startswith(MARKER)
        and user.get("login") == ACTIONS_BOT_LOGIN
        and user.get("type") == ACTIONS_BOT_TYPE
    )


def upsert_comment(api: Any, pull_number: int, body: str) -> tuple[int, str]:
    comments = api.list_issue_comments(pull_number)
    managed = sorted(
        [comment for comment in comments if is_managed_handoff_comment(comment)],
        key=lambda comment: int(comment.get("id", 0)),
    )
    if not managed:
        created = api.create_issue_comment(pull_number, body)
        return int(created["id"]), "created"

    keeper = managed[0]
    api.update_issue_comment(int(keeper["id"]), body)
    for duplicate in managed[1:]:
        api.delete_issue_comment(int(duplicate["id"]))
    return int(keeper["id"]), "updated"


def publish_handoff(
    api: Any,
    trigger: Trigger,
    *,
    ads_attempts: int = 6,
    ads_delay_seconds: float = 10.0,
    sleeper: Callable[[float], None] = time.sleep,
) -> PublishedHandoff:
    sha = trigger.merge_sha
    repo_pack = api.get_workflow_run(trigger.repo_pack_run_id)
    repo_pack_failures = validate_repo_pack_run(
        repo_pack,
        api.repository,
        expected_sha=sha,
    )
    if repo_pack_failures:
        raise RuntimeError(
            f"invalid trigger repo-pack run {trigger.repo_pack_run_id}: "
            + "; ".join(repo_pack_failures)
        )

    pull = find_merged_pull(api, sha)
    pull_number = int(pull["number"])
    initial_runs = api.list_workflow_runs(sha)
    quality = select_run(initial_runs, QUALITY_WORKFLOW, sha)
    ads, ads_reason = wait_for_ads(
        api,
        sha,
        attempts=ads_attempts,
        delay_seconds=ads_delay_seconds,
        sleeper=sleeper,
    )
    artifact, artifact_failures = find_artifact(api, repo_pack)

    failures: list[str] = []
    if quality is None:
        failures.append("Quality exact-SHA run is missing")
    elif quality.get("status") != "completed" or quality.get("conclusion") != "success":
        failures.append("Quality did not complete successfully")
    failures.extend(artifact_failures)

    body = render_comment(
        sha=sha,
        pull_number=pull_number,
        quality=quality,
        ads=ads,
        ads_reason=ads_reason,
        repo_pack=repo_pack,
        artifact=artifact,
    )
    comment_id, action = upsert_comment(api, pull_number, body)
    return PublishedHandoff(pull_number, comment_id, action, tuple(failures))


def load_event(path: str) -> dict[str, Any]:
    with Path(path).open(encoding="utf-8") as handle:
        event = json.load(handle)
    if not isinstance(event, dict):
        raise ValueError("GitHub event payload must be a JSON object")
    return event


def main() -> None:
    api = GitHubApi(
        repository=os.environ.get("GITHUB_REPOSITORY", ""),
        token=os.environ.get("GITHUB_TOKEN", ""),
        api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
    )
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    event_path = os.environ.get("GITHUB_EVENT_PATH", "")
    event = load_event(event_path) if event_path else {}
    trigger = resolve_trigger(
        api,
        event_name,
        event,
        os.environ.get("HANDOFF_REPO_PACK_RUN_ID", ""),
    )
    if trigger is None:
        print("Post-merge handoff skipped: repo-pack run was not successful on main")
        return

    result = publish_handoff(
        api,
        trigger,
        ads_attempts=int(os.environ.get("HANDOFF_ADS_ATTEMPTS", "6")),
        ads_delay_seconds=float(os.environ.get("HANDOFF_ADS_DELAY_SECONDS", "10")),
    )
    print(
        f"Post-merge handoff {result.comment_action} on PR #{result.pull_number} "
        f"as comment {result.comment_id}"
    )
    if result.failures:
        raise SystemExit("Post-merge handoff validation failed: " + "; ".join(result.failures))


if __name__ == "__main__":
    main()
