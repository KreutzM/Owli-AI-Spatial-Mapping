from __future__ import annotations

import unittest

from tools.post_merge_handoff import (
    ADS_WORKFLOW,
    ARTIFACT_NAME,
    MARKER,
    QUALITY_WORKFLOW,
    REPO_PACK_WORKFLOW,
    Trigger,
    publish_handoff,
    resolve_trigger,
    upsert_comment,
)

SHA = "b9262d8c5cae0c4329f108ed2729ee1d3ca99626"


def workflow_run(name: str, run_id: int, *, status: str = "completed", conclusion: str = "success"):
    return {
        "id": run_id,
        "name": name,
        "head_sha": SHA,
        "head_branch": "main",
        "status": status,
        "conclusion": conclusion,
        "html_url": f"https://github.test/actions/runs/{run_id}",
    }


class FakeApi:
    def __init__(self) -> None:
        self.pull = {"number": 4, "merged_at": "2026-08-05T17:35:05Z", "merge_commit_sha": SHA}
        self.quality = workflow_run(QUALITY_WORKFLOW, 31030711486)
        self.repo_pack = workflow_run(REPO_PACK_WORKFLOW, 31030815248)
        self.ads = workflow_run(ADS_WORKFLOW, 31030706756)
        self.artifact = {
            "id": 8940563493,
            "name": ARTIFACT_NAME,
            "digest": "sha256:50f0bdb1cb01748d3fe1e9aef253f0d5d997fe33eb9e1ce8c9a67c5cea2eec74",
            "expired": False,
        }
        self.comments = []
        self.created = []
        self.updated = []
        self.deleted = []
        self.run_snapshots = [[self.quality, self.repo_pack, self.ads]]

    def list_commit_pulls(self, sha):
        self.assert_sha(sha)
        return [self.pull]

    def list_workflow_runs(self, sha):
        self.assert_sha(sha)
        if len(self.run_snapshots) > 1:
            return self.run_snapshots.pop(0)
        return self.run_snapshots[0]

    def get_workflow_run(self, run_id):
        if run_id != self.repo_pack["id"]:
            raise AssertionError(f"unexpected run id {run_id}")
        return self.repo_pack

    def list_run_artifacts(self, run_id):
        if run_id != self.repo_pack["id"]:
            raise AssertionError(f"unexpected artifact run id {run_id}")
        return [self.artifact]

    def list_issue_comments(self, issue_number):
        if issue_number != self.pull["number"]:
            raise AssertionError(f"unexpected PR {issue_number}")
        return list(self.comments)

    def create_issue_comment(self, issue_number, body):
        comment = {"id": 9001, "body": body}
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

    @staticmethod
    def assert_sha(sha):
        if sha != SHA:
            raise AssertionError(f"unexpected SHA {sha}")


class PostMergeHandoffTests(unittest.TestCase):
    def test_successful_exact_sha_handoff_rendering(self):
        api = FakeApi()
        result = publish_handoff(
            api,
            Trigger(SHA, api.repo_pack["id"]),
            ads_attempts=1,
            ads_delay_seconds=0,
            sleeper=lambda _: None,
        )

        self.assertEqual((), result.failures)
        self.assertEqual("created", result.comment_action)
        body = api.created[0][1]
        self.assertIn(MARKER, body)
        self.assertIn(f"Merge commit: `{SHA}`", body)
        self.assertIn("Quality: success", body)
        self.assertIn("Quality run ID: 31030711486", body)
        self.assertIn("Automatic Dependency Submission: success", body)
        self.assertIn("WebAgent Repo Pack run ID: 31030815248", body)
        self.assertIn("Artifact ID: 8940563493", body)
        self.assertIn(api.artifact["digest"], body)

    def test_ads_absent_has_precise_disposition_without_failing_handoff(self):
        api = FakeApi()
        api.run_snapshots = [[api.quality, api.repo_pack]]

        result = publish_handoff(
            api,
            Trigger(SHA, api.repo_pack["id"]),
            ads_attempts=2,
            ads_delay_seconds=0,
            sleeper=lambda _: None,
        )

        self.assertEqual((), result.failures)
        body = api.created[0][1]
        self.assertIn(
            "Automatic Dependency Submission: unavailable (no exact-SHA run visible after 2 attempts) — n/a",
            body,
        )
        self.assertIn("Automatic Dependency Submission run ID: n/a", body)

    def test_existing_marked_comment_is_updated_and_unrelated_comment_is_unchanged(self):
        api = FakeApi()
        unrelated = {"id": 11, "body": "keep me"}
        marked = {"id": 12, "body": f"{MARKER}\nold"}
        api.comments = [unrelated.copy(), marked.copy()]

        comment_id, action = upsert_comment(api, 4, f"{MARKER}\nnew")

        self.assertEqual((12, "updated"), (comment_id, action))
        self.assertEqual([], api.created)
        self.assertEqual([(12, f"{MARKER}\nnew")], api.updated)
        self.assertEqual("keep me", next(c["body"] for c in api.comments if c["id"] == 11))
        self.assertEqual(1, sum(MARKER in c["body"] for c in api.comments))

    def test_duplicate_marked_comments_are_collapsed(self):
        api = FakeApi()
        api.comments = [
            {"id": 20, "body": f"{MARKER}\nfirst"},
            {"id": 21, "body": "unrelated"},
            {"id": 22, "body": f"{MARKER}\nsecond"},
        ]

        upsert_comment(api, 4, f"{MARKER}\nreplacement")

        self.assertEqual([22], api.deleted)
        self.assertEqual("unrelated", next(c["body"] for c in api.comments if c["id"] == 21))
        self.assertEqual(1, sum(MARKER in c["body"] for c in api.comments))

    def test_failed_quality_is_rendered_and_marks_handoff_failed(self):
        api = FakeApi()
        api.quality = workflow_run(QUALITY_WORKFLOW, 31030711486, conclusion="failure")
        api.run_snapshots = [[api.quality, api.repo_pack, api.ads]]

        result = publish_handoff(
            api,
            Trigger(SHA, api.repo_pack["id"]),
            ads_attempts=1,
            ads_delay_seconds=0,
            sleeper=lambda _: None,
        )

        self.assertIn("Quality did not complete successfully", result.failures)
        self.assertIn("Quality: failure", api.created[0][1])

    def test_missing_merged_pull_fails_without_commenting(self):
        api = FakeApi()
        api.pull = {"number": 4, "merged_at": None, "merge_commit_sha": SHA}

        with self.assertRaisesRegex(RuntimeError, "expected exactly one merged pull request"):
            publish_handoff(
                api,
                Trigger(SHA, api.repo_pack["id"]),
                ads_attempts=1,
                ads_delay_seconds=0,
                sleeper=lambda _: None,
            )

        self.assertEqual([], api.created)
        self.assertEqual([], api.updated)

    def test_failed_or_non_main_repo_pack_event_is_skipped(self):
        repository = {"full_name": "KreutzM/Owli-AI-Spatial-Mapping"}
        failed = {
            "repository": repository,
            "workflow_run": {
                "id": 1,
                "name": REPO_PACK_WORKFLOW,
                "head_sha": SHA,
                "head_branch": "main",
                "head_repository": repository,
                "conclusion": "failure",
            }
        }
        non_main = {
            "repository": repository,
            "workflow_run": {
                "id": 2,
                "name": REPO_PACK_WORKFLOW,
                "head_sha": SHA,
                "head_branch": "feature",
                "head_repository": repository,
                "conclusion": "success",
            }
        }

        self.assertIsNone(resolve_trigger("workflow_run", failed))
        self.assertIsNone(resolve_trigger("workflow_run", non_main))

    def test_fork_main_repo_pack_event_is_skipped(self):
        event = {
            "repository": {"full_name": "KreutzM/Owli-AI-Spatial-Mapping"},
            "workflow_run": {
                "id": 3,
                "name": REPO_PACK_WORKFLOW,
                "head_sha": SHA,
                "head_branch": "main",
                "head_repository": {"full_name": "someone/fork"},
                "conclusion": "success",
            },
        }

        self.assertIsNone(resolve_trigger("workflow_run", event))


if __name__ == "__main__":
    unittest.main()
