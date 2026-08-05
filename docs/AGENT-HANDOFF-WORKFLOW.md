# Agent Handoff Workflow

- A tracking issue holds the overall MVP goal and status.
- Each small implementation cycle has one child issue and one PR.
- Planner defines goal, scope, non-goals, acceptance criteria and reviewer focus.
- Builder works from current main, creates a thematic branch, implements only the child issue, runs checks, opens the PR and posts `Builder Handoff / Run Review` as a top-level PR comment.
- Reviewer reads the issue, PR, handoff, diff and CI; records `APPROVE`, `REQUEST_CHANGES`, or `COMMENT` in GitHub.
- Maintainer/orchestrator merges only after explicit approval and exact-head/CI verification.
- The tracking issue receives concise status updates, not long logs.
