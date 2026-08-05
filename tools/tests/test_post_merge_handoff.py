from __future__ import annotations

import inspect
from pathlib import Path
import re
import unittest

from tools.post_merge_handoff import (
    ACTIONS_BOT_LOGIN,
    ACTIONS_BOT_TYPE,
    ADS_WORKFLOW,
    ARTIFACT_NAME,
    GitHubApi,
    MARKER,
    QUALITY_WORKFLOW,
    REPO_PACK_WORKFLOW,
    Trigger,
    find_artifact,
    is_managed_handoff_comment,
    publish_handoff,
    resolve_trigger,
    upsert_comment,
    wait_for_ads,
)

REPOSITORY = "KreutzM/Owli-AI-Spatial-Mapping"
SHA = "b9262d8c5cae0c4329f108ed2729ee1d3ca99626"
OTHER_SHA = "70d2a2ed810e457c7cf331fe74aae1e766429e74"
VALID_DIGEST = "sha256:50f0bdb1cb01748d3fe1e9aef253f0d5d997fe33eb9e1ce8c9a67c5cea2eec74"


def workflow_run(
    name: str,
    run_id: int,
    *,
    sha: str = SHA,
    status: str = "completed",
    conclusion: str = "success",
    branch: str = "main",
    repository: str = REPOSITORY,
    head_repository: str | None = None,
):
    return {
        "id": run_id,
        "name": name,
        "head_sha": sha,
        "head_branch": branch,
        "status": status,
        "conclusion": conclusion,
        "html_url": f"https://github.test/actions/runs/{run_id}",
        "repository": {"full_name": repository},
        "head_repository": {"full_name": head_repository or repository},
    }


def bot_comment(comment_id: int, body: str):
    return {
        "id": comment_id,
        "body": body,
        "user": {"login": ACTIONS_BOT_LOGIN, "type": ACTIONS_BOT_TYPE},
    }


def human_comment(comment_id: int, body: str):
    return {
        "id": comment_id,
        "body": body,
        "user": {"login": "KreutzM", "type": "User"},
    }


class FakeApi:
    def __init__(self) -> None:
        self.repository = REPOSITORY
        self.pull = {
            "number": 4,
            "merged_at": "2026-08-05T17:35:05Z",
            "merge_commit_sha": SHA,
            "base": {"ref": "main"},
        }
        self.pulls_by_sha = {SHA: [self.pull]}
        self.quality = workflow_run(QUALITY_WORKFLOW, 31030711486)
        self.repo_pack = workflow_run(REPO_PACK_WORKFLOW, 31030815248)
        self.ads = workflow_run(ADS_WORKFLOW, 31030706756)
        self.runs_by_id = {self.repo_pack["id"]: self.repo_pack}
        self.artifact = {
            "id": 8940563493,
            "name": ARTIFACT_NAME,
            "digest": VALID_DIGEST,
            "expired": False,
        }
        self.artifacts_by_run = {self.repo_pack["id"]: [self.artifact]}
        self.comments = []
        self.created = []
        self.updated = []
        self.deleted = []
        self.commit_pull_calls = []
        self.workflow_run_get_calls = []
        self.workflow_run_list_calls = []
        self.artifact_run_calls = []
        self.run_snapshots = [[self.quality, self.repo_pack, self.ads]]

    def list_commit_pulls(self, sha):
        self.commit_pull_calls.append(sha)
        return list(self.pulls_by_sha.get(sha, []))

    def list_workflow_runs(self, sha):
        self.workflow_run_list_calls.append(sha)
        index = min(len(self.workflow_run_list_calls) - 1, len(self.run_snapshots) - 1)
        return list(self.run_snapshots[index])

    def get_workflow_run(self, run_id):
        self.workflow_run_get_calls.append(run_id)
        if run_id not in self.runs_by_id:
            raise AssertionError(f"unexpected run id {run_id}")
        return self.runs_by_id[run_id]

    def list_run_artifacts(self, run_id):
        self.artifact_run_calls.append(run_id)
        return list(self.artifacts_by_run.get(run_id, []))

    def list_issue_comments(self, issue_number):
        if issue_number != self.pull["number"]:
            raise AssertionError(f"unexpected PR {issue_number}")
        return list(self.comments)

    def create_issue_comment(self, issue_number, body):
        comment = bot_comment(9001, body)
        self.created.append((issue_number, body))
        self.comments.append(comment)
        return comment

    def update_issue_comment(self, comment_id, body):
        self.updated.append((comment_id, body))
        for comment in self.comments:
            if comment["id"] == comment_id:
                comment["body"] = body
                return comment
        raise AssertionError(f"missing comment {comment_id}")

    def delete_issue_comment(self, comment_id):
        self.deleted.append(comment_id)
        self.comments = [comment for comment in self.comments if comment["id"] != comment_id]


class PagingApi(GitHubApi):
    def __init__(self) -> None:
        super().__init__(REPOSITORY, "token")
        self.requested_paths = []

    def _request(self, method, path, payload=None):
        self.requested_paths.append(path)
        if path.endswith("page=1"):
            return [human_comment(index, f"comment {index}") for index in range(1, 101)]
        if path.endswith("page=2"):
            return [human_comment(101, "comment 101")]
        raise AssertionError(f"unexpected pagination path {path}")


class PostMergeHandoffTests(unittest.TestCase):
    def publish(self, api: FakeApi, *, attempts: int = 1):
        return publish_handoff(
            api,
            Trigger(SHA, api.repo_pack["id"]),
            ads_attempts=attempts,
            ads_delay_seconds=0,
            sleeper=lambda _: None,
        )

    def test_workflow_dispatch_accepts_only_required_repo_pack_run_id(self):
        workflow = Path(".github/workflows/post-merge-handoff.yml").read_text()
        dispatch_block = workflow.split("  workflow_dispatch:\n", 1)[1].split("\npermissions:\n", 1)[0]
        input_names = re.findall(r"^      ([A-Za-z0-9_]+):$", dispatch_block, re.MULTILINE)

        self.assertEqual(["repo_pack_run_id"], input_names)
        self.assertIn("repo_pack_run_id:\n        description:", dispatch_block)
        self.assertIn("        required: true", dispatch_block)
        self.assertNotIn("merge_sha", workflow)
        self.assertNotIn("HANDOFF_TARGET_SHA", workflow)
        self.assertIn(
            "post-merge-handoff-${{ github.event.workflow_run.head_sha || inputs.repo_pack_run_id }}",
            workflow,
        )
        self.assertNotIn("dispatch_sha", inspect.signature(resolve_trigger).parameters)

    def test_dispatch_sha_is_derived_only_from_fetched_run(self):
        api = FakeApi()
        recovery = workflow_run(REPO_PACK_WORKFLOW, 777, sha=OTHER_SHA)
        api.runs_by_id = {777: recovery}

        trigger = resolve_trigger(api, "workflow_dispatch", {}, "777")

        self.assertEqual(Trigger(OTHER_SHA, 777), trigger)
        self.assertEqual([777], api.workflow_run_get_calls)
        self.assertEqual([], api.commit_pull_calls)

    def test_dispatch_requires_positive_repo_pack_run_id(self):
        api = FakeApi()
        for value in ("", "0", "-1", "abc", "1.5"):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "required and must be a positive integer"):
                    resolve_trigger(api, "workflow_dispatch", {}, value)
        self.assertEqual([], api.commit_pull_calls)

    def test_recovery_run_with_wrong_workflow_name_is_rejected_before_pr_lookup(self):
        api = FakeApi()
        api.repo_pack = workflow_run("Wrong Workflow", 777)
        api.runs_by_id = {777: api.repo_pack}

        with self.assertRaisesRegex(RuntimeError, "workflow name must be exactly WebAgent Repo Pack"):
            resolve_trigger(api, "workflow_dispatch", {}, "777")

        self.assertEqual([], api.commit_pull_calls)
        self.assertEqual([], api.created)

    def test_recovery_run_must_be_completed_successful_and_on_main(self):
        cases = [
            ("branch", {"branch": "feature"}, "head_branch must be main"),
            ("status", {"status": "in_progress"}, "status must be completed"),
            ("conclusion", {"conclusion": "failure"}, "conclusion must be success"),
        ]
        for name, overrides, message in cases:
            with self.subTest(name=name):
                api = FakeApi()
                run = workflow_run(REPO_PACK_WORKFLOW, 777, **overrides)
                api.runs_by_id = {777: run}
                with self.assertRaisesRegex(RuntimeError, message):
                    resolve_trigger(api, "workflow_dispatch", {}, "777")
                self.assertEqual([], api.commit_pull_calls)
                self.assertEqual([], api.created)

    def test_recovery_run_from_other_repository_is_rejected(self):
        api = FakeApi()
        run = workflow_run(REPO_PACK_WORKFLOW, 777, repository="someone/else")
        api.runs_by_id = {777: run}

        with self.assertRaisesRegex(RuntimeError, "repository does not match"):
            resolve_trigger(api, "workflow_dispatch", {}, "777")

        self.assertEqual([], api.commit_pull_calls)

    def test_run_from_another_commit_cannot_address_a_separately_chosen_pr(self):
        api = FakeApi()
        recovery = workflow_run(REPO_PACK_WORKFLOW, 777, sha=OTHER_SHA)
        api.runs_by_id = {777: recovery}
        api.repo_pack = recovery
        trigger = resolve_trigger(api, "workflow_dispatch", {}, "777")

        with self.assertRaisesRegex(RuntimeError, "merged into main"):
            publish_handoff(
                api,
                trigger,
                ads_attempts=1,
                ads_delay_seconds=0,
                sleeper=lambda _: None,
            )

        self.assertEqual([OTHER_SHA], api.commit_pull_calls)
        self.assertEqual([], api.created)
        self.assertEqual([], api.updated)

    def test_non_main_merged_pull_is_rejected_without_comment(self):
        api = FakeApi()
        api.pull["base"] = {"ref": "release"}

        with self.assertRaisesRegex(RuntimeError, "merged into main"):
            self.publish(api)

        self.assertEqual([], api.created)
        self.assertEqual([], api.updated)

    def test_ads_can_appear_on_a_later_retry(self):
        api = FakeApi()
        api.run_snapshots = [
            [api.quality, api.repo_pack],
            [api.quality, api.repo_pack, api.ads],
        ]
        sleeps = []

        ads, reason = wait_for_ads(
            api,
            SHA,
            attempts=2,
            delay_seconds=3,
            sleeper=sleeps.append,
        )

        self.assertEqual(api.ads, ads)
        self.assertEqual("", reason)
        self.assertEqual([3], sleeps)
        self.assertEqual([SHA, SHA], api.workflow_run_list_calls)

    def test_quality_run_with_other_sha_is_not_accepted(self):
        api = FakeApi()
        wrong_quality = workflow_run(QUALITY_WORKFLOW, 31030711486, sha=OTHER_SHA)
        api.run_snapshots = [[wrong_quality, api.repo_pack, api.ads]]

        result = self.publish(api)

        self.assertIn("Quality exact-SHA run is missing", result.failures)
        self.assertIn("Quality: unavailable (no exact-SHA Quality run found)", api.created[0][1])

    def test_missing_or_ambiguous_merged_pull_is_rejected_without_comment(self):
        for name, pulls in (
            ("missing", []),
            (
                "ambiguous",
                [
                    {
                        "number": 4,
                        "merged_at": "2026-08-05T17:35:05Z",
                        "merge_commit_sha": SHA,
                        "base": {"ref": "main"},
                    },
                    {
                        "number": 5,
                        "merged_at": "2026-08-05T17:36:05Z",
                        "merge_commit_sha": SHA,
                        "base": {"ref": "main"},
                    },
                ],
            ),
        ):
            with self.subTest(name=name):
                api = FakeApi()
                api.pulls_by_sha[SHA] = pulls
                with self.assertRaisesRegex(RuntimeError, "expected exactly one"):
                    self.publish(api)
                self.assertEqual([], api.created)
                self.assertEqual([], api.updated)

    def test_missing_expired_or_ambiguous_artifact_is_rejected(self):
        cases = {
            "missing": ([], "found 0"),
            "ambiguous": ([self._artifact(1), self._artifact(2)], "found 2"),
            "expired": ([self._artifact(1, expired=True)], "artifact is expired"),
        }
        for name, (artifacts, expected) in cases.items():
            with self.subTest(name=name):
                api = FakeApi()
                api.artifacts_by_run[api.repo_pack["id"]] = artifacts
                _, failures = find_artifact(api, api.repo_pack)
                self.assertTrue(any(expected in failure for failure in failures), failures)

    def test_artifact_lookup_is_bound_to_trigger_repo_pack_run(self):
        api = FakeApi()

        result = self.publish(api)

        self.assertEqual((), result.failures)
        self.assertEqual([api.repo_pack["id"]], api.artifact_run_calls)

    def test_invalid_artifact_digest_is_rejected(self):
        invalid_digests = {
            "missing": None,
            "wrong algorithm": "sha512:" + "a" * 64,
            "short": "sha256:" + "a" * 63,
            "long": "sha256:" + "a" * 65,
            "non hex": "sha256:" + "g" * 64,
        }
        for name, digest in invalid_digests.items():
            with self.subTest(name=name):
                api = FakeApi()
                api.artifact["digest"] = digest
                _, failures = find_artifact(api, api.repo_pack)
                self.assertEqual(
                    ["artifact digest must be exactly sha256:<64 hexadecimal characters>"],
                    failures,
                )

    def test_pagination_reads_more_than_one_page(self):
        api = PagingApi()

        comments = api.list_issue_comments(4)

        self.assertEqual(101, len(comments))
        self.assertEqual([1, 101], [comments[0]["id"], comments[-1]["id"]])
        self.assertEqual(2, len(api.requested_paths))
        self.assertIn("page=1", api.requested_paths[0])
        self.assertIn("page=2", api.requested_paths[1])

    def test_bot_owned_marker_comment_is_updated(self):
        api = FakeApi()
        api.comments = [bot_comment(12, f"{MARKER}\nold")]

        comment_id, action = upsert_comment(api, 4, f"{MARKER}\nnew")

        self.assertEqual((12, "updated"), (comment_id, action))
        self.assertEqual([(12, f"{MARKER}\nnew")], api.updated)
        self.assertEqual([], api.created)

    def test_duplicate_bot_owned_marker_comments_are_cleaned(self):
        api = FakeApi()
        unrelated = human_comment(21, "unrelated")
        api.comments = [
            bot_comment(20, f"{MARKER}\nfirst"),
            unrelated.copy(),
            bot_comment(22, f"{MARKER}\nsecond"),
        ]

        upsert_comment(api, 4, f"{MARKER}\nreplacement")

        self.assertEqual([22], api.deleted)
        self.assertEqual("unrelated", next(c["body"] for c in api.comments if c["id"] == 21))
        self.assertEqual(1, sum(is_managed_handoff_comment(c) for c in api.comments))

    def test_human_comment_beginning_with_marker_remains_unchanged(self):
        api = FakeApi()
        human = human_comment(30, f"{MARKER}\nI am discussing the handoff")
        api.comments = [human.copy()]

        comment_id, action = upsert_comment(api, 4, f"{MARKER}\nnew")

        self.assertEqual((9001, "created"), (comment_id, action))
        self.assertEqual(human, next(c for c in api.comments if c["id"] == 30))
        self.assertEqual([], api.updated)
        self.assertEqual([], api.deleted)

    def test_human_comment_quoting_marker_remains_unchanged(self):
        api = FakeApi()
        quoted = human_comment(31, f"Earlier handoff said:\n> {MARKER}\nPlease review")
        api.comments = [quoted.copy()]

        upsert_comment(api, 4, f"{MARKER}\nnew")

        self.assertEqual(quoted, next(c for c in api.comments if c["id"] == 31))
        self.assertEqual([], api.updated)
        self.assertEqual([], api.deleted)

    def test_without_bot_owned_marker_exactly_one_comment_is_created(self):
        api = FakeApi()
        api.comments = [
            human_comment(40, "unrelated"),
            human_comment(41, f"{MARKER}\nhuman marker"),
            bot_comment(42, f"Discussion before marker {MARKER}"),
        ]

        comment_id, action = upsert_comment(api, 4, f"{MARKER}\nnew")

        self.assertEqual((9001, "created"), (comment_id, action))
        self.assertEqual(1, len(api.created))
        self.assertEqual([], api.updated)
        self.assertEqual([], api.deleted)
        self.assertEqual(4, len(api.comments))

    def test_successful_exact_historical_references_are_rendered(self):
        api = FakeApi()

        result = self.publish(api)

        self.assertEqual((), result.failures)
        self.assertEqual("created", result.comment_action)
        body = api.created[0][1]
        self.assertTrue(body.startswith(MARKER))
        self.assertIn(f"Merge commit: `{SHA}`", body)
        self.assertIn("Quality: success", body)
        self.assertIn("Quality run ID: 31030711486", body)
        self.assertIn("Automatic Dependency Submission: success", body)
        self.assertIn("Automatic Dependency Submission run ID: 31030706756", body)
        self.assertIn("WebAgent Repo Pack run ID: 31030815248", body)
        self.assertIn("Artifact ID: 8940563493", body)
        self.assertIn(VALID_DIGEST, body)

    def test_ads_absent_after_bounded_retry_is_precisely_reported(self):
        api = FakeApi()
        api.run_snapshots = [[api.quality, api.repo_pack]]

        result = self.publish(api, attempts=2)

        self.assertEqual((), result.failures)
        body = api.created[0][1]
        self.assertIn(
            "Automatic Dependency Submission: unavailable (no exact-SHA run visible after 2 attempts) — n/a",
            body,
        )
        self.assertIn("Automatic Dependency Submission run ID: n/a", body)

    def test_unrelated_comments_remain_unchanged_during_publish(self):
        api = FakeApi()
        unrelated = human_comment(50, "keep me exactly")
        managed = bot_comment(51, f"{MARKER}\nold")
        api.comments = [unrelated.copy(), managed]

        result = self.publish(api)

        self.assertEqual("updated", result.comment_action)
        self.assertEqual(unrelated, next(c for c in api.comments if c["id"] == 50))

    def test_automatic_event_uses_validated_event_sha_and_run_id(self):
        api = FakeApi()
        event = {"workflow_run": api.repo_pack}

        trigger = resolve_trigger(api, "workflow_run", event)

        self.assertEqual(Trigger(SHA, api.repo_pack["id"]), trigger)
        self.assertEqual([], api.workflow_run_get_calls)

    def test_failed_non_main_or_fork_automatic_event_is_skipped(self):
        cases = [
            workflow_run(REPO_PACK_WORKFLOW, 1, conclusion="failure"),
            workflow_run(REPO_PACK_WORKFLOW, 2, branch="feature"),
            workflow_run(REPO_PACK_WORKFLOW, 3, head_repository="someone/fork"),
        ]
        for run in cases:
            with self.subTest(run_id=run["id"]):
                api = FakeApi()
                self.assertIsNone(resolve_trigger(api, "workflow_run", {"workflow_run": run}))
                self.assertEqual([], api.commit_pull_calls)

    def test_invalid_trigger_repo_pack_run_fails_before_pr_lookup(self):
        api = FakeApi()
        api.repo_pack = workflow_run(REPO_PACK_WORKFLOW, 31030815248, sha=OTHER_SHA)
        api.runs_by_id = {api.repo_pack["id"]: api.repo_pack}

        with self.assertRaisesRegex(RuntimeError, "does not match the exact merge SHA"):
            self.publish(api)

        self.assertEqual([], api.commit_pull_calls)
        self.assertEqual([], api.created)

    @staticmethod
    def _artifact(artifact_id: int, *, expired: bool = False):
        return {
            "id": artifact_id,
            "name": ARTIFACT_NAME,
            "digest": VALID_DIGEST,
            "expired": expired,
        }


if __name__ == "__main__":
    unittest.main()
