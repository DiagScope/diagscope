# Releasing

A release is one workflow run. You decide the version and write the changelog; the `release` workflow does the rest.

## Do a release

1. Under `## Unreleased` in [CHANGELOG.md](../CHANGELOG.md), write what changes. The release is refused when that section
   is empty, and those lines become the release notes.
2. In *Actions → release → Run workflow*, enter the version (for example `0.1.7`). **Dry run** is on by default.
   Run it that way first when anything about the build or the credentials changed: it builds, runs the whole test
   suite, assembles the Central bundle with a throwaway key and checks it, and pushes and publishes nothing.
3. Run it again with **Dry run** off. If the `maven-central` environment has required reviewers, the job waits for one of
   them before it receives any secret; that approval is the human gate.
4. Done. The summary lists the links.

## What it does

| Step | Job | Where |
| --- | --- | --- |
| Bump the POMs, move `Unreleased` to the version, update the docs that quote it, commit `Release X.Y.Z` | prepare | `scripts/release/prepare.py` |
| Full build and tests; Central bundle check with a throwaway key | prepare | |
| Push the release commit to `main` | prepare | skipped on a dry run |
| Sign and publish every module to Maven Central (auto-publish on by default) | central | skipped when *publish-central* is off |
| Build the CLI jar, tag `vX.Y.Z` in this repository | github-release | |
| Create the GitHub release in `DiagScope/releases`, with the changelog section as notes | github-release | |
| Point the releases README at the new version | github-release | |
| Update the Homebrew formula and the tap README | tap repository | `update-formula` workflow |
| Update the version shown on the site | site repository | `bump-version` workflow |

## When something fails

Run the workflow again with the **same version**. It is built to be repeated:

- preparing a version the POMs already carry changes nothing, so the release commit is reused;
- a version already on Central is skipped, a GitHub release or tag that already exists is skipped;
- a failed Central validation publishes nothing, and the GitHub release does not start after a failed Central job.

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| *the CHANGELOG has nothing under Unreleased* | Nothing was written | Write the entries and push. |
| *is not newer than* | Version typo, or already released | Use the next version. |
| Central: *Could not find a public key* | The signing key is not on a keyserver | [PUBLISHING.md](PUBLISHING.md), step 4. |
| Central: *The secret ... is not set* | A secret is missing in `maven-central` | Add it. |
| Site step fails | The token cannot reach `diagscope.github.io` | Fix `HOMEBREW_TAP_TOKEN`, or run the `bump-version` workflow there with the `new-release` event by hand. The release itself is already out. |
| The release commit is on `main` but nothing else happened | The run stopped after *prepare* | Run again with the same version. |

## Secrets

| Secret | Where | Used for |
| --- | --- | --- |
| `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE` | environment `maven-central` | Publishing to Maven Central ([PUBLISHING.md](PUBLISHING.md)). |
| `RELEASES_PAT` | this repository | Creating the release and committing the README in `DiagScope/releases`. |
| `HOMEBREW_TAP_TOKEN` | this repository | Dispatching to `homebrew-tap` and `diagscope.github.io`; it needs write access to both. |

## What is not automated, on purpose

- **Deciding to release and what the version is.** A version on Central can never be changed or removed.
- **Writing the changelog.**
- **Rotating the signing key or the tokens.**

## Testing the machinery

`scripts/release/prepare.py` and `notes.py` are exercised on a copy of the repository by the `release-scripts` job of
`build.yml` on every push, and `actionlint` checks the workflows. The workflow as a whole is exercised by a dry run.
