# Protecting `main`

The `main` branch currently has no protection rule. A repository maintainer must apply the following settings once in GitHub:

1. Open **Settings → Rules → Rulesets** (or **Settings → Branches → Add branch protection rule**).
2. Target the branch pattern `main`.
3. Require a pull request before merging. For a solo-maintained project, one approval may be left optional; do not require an unavailable reviewer.
4. Require the status check **`Test, lint and build APK / AAB`** from the `Build SaliBussiness APK` workflow.
5. Require conversation resolution; block force-pushes and branch deletion.
6. Do not allow bypass for routine pushes. Keep an administrator emergency bypass only if operationally necessary.

The CI workflow intentionally gives the build job read-only repository permissions and grants `contents: write` only to the tag-only release publisher.
