# Path to 1.0.0

Written in English to match `docs/CODE_GUIDELINES.md`. Assumes the Phase 1
technical gate has already passed on real repositories under the
maintainer's own review — that is real, load-bearing progress. What
follows is what "1.0.0" should mean on top of that, given what the project
already commits to in `docs/ROADMAP.md` and `docs/PROJECT_OVERVIEW.md`.

The organizing question for everything below: **1.0.0 is a promise to
someone who isn't you.** Alpha asked "does this work." 1.0 asks "can a
stranger depend on this without you in the room." Every item here exists
because it closes a gap between those two questions.

---

## 0. The one honest thing to decide first

Right now, exactly one person has used this tool: its author. The Phase 1
"interest gate" in `PROJECT_OVERVIEW.md` explicitly asks for a *behavioral*
signal from someone else — a request to scan another service, not just a
passing technical result. That signal doesn't exist yet.

This doesn't block a 1.0.0 release by itself. It changes what the release
should say about itself. Two honest paths:

- **Get at least one external user or team to try it before tagging 1.0.**
  Section 3 below (Maven plugin, Gradle plugin, GitHub Action) is exactly
  what removes the friction that's currently stopping that from happening —
  right now, trying this tool means cloning a Java repository and running
  Maven by hand, which is a real barrier for someone who isn't already
  invested.
- **Or ship 1.0.0 solo-validated, and say so plainly** in the README and
  release notes — "field-validated by the author on N repositories;
  external adoption feedback not yet collected." That's consistent with
  the project's own stated principle that honest uncertainty beats a
  confident but misleading claim. What isn't consistent with that
  principle is a 1.0.0 that implies broader validation than actually
  happened.

Pick one. Everything else in this document works either way.

---

## 1. Define what 1.0.0 actually promises

Semantic versioning means something specific: after 1.0.0, breaking a
public contract requires a major version bump. Right now nothing states
which surfaces *are* that contract. Before tagging, write down — in
`README.md` or a new `docs/COMPATIBILITY.md` — that these are covered by
semver from 1.0.0 onward, and these are not:

**Covered (breaking changes require a major bump):**
- `result.json` schema — the field names and types documented as stable
  today. The current `schemaVersion` value is `"1.0-alpha.1"`; it should
  become a real, alpha-free version string as part of this release, with
  the additive-vs-breaking policy written down (new optional field = minor,
  anything else = major).
- CLI flags and exit codes as documented in `docs/CLI.md`.
- The `diagscope.yml` schema.
- Fingerprint stability — already has a real answer via
  `--baseline-migration OLD=NEW`, which is a genuinely strong asset most
  1.0 static-analysis tools don't have this early. Document it as a first-
  class upgrade story, not just a flag description: "upgrading DiagScope
  will not silently reopen your entire baseline; here's how to migrate a
  fingerprint that changed."

**Explicitly not covered (can change without a major bump):**
- The Markdown and HTML report layout — already stated as presentation-
  only in the report footer; keep that framing consistent in the docs.
- Internal rule detection heuristics, as long as severity/confidence
  meaning doesn't change.
- Anything under `diagscope-core` internals not exposed through the three
  contracts above.

Without this written down, "1.0.0" is just a number. With it, it's an
actual promise someone can build a CI gate on top of.

---

## 2. Rule catalog: be conservative about what ships enabled by default

There are roughly 26 rules now, added at very different points in the
project's life. The original six went through the full fixture-and-
validation discipline the project is built on. Some of the newer ones
(database/JDBC, async/resilience, MDC) may not have had the same real-
repository exposure the technical gate measured.

Before 1.0.0:

- Split the catalog into **stable / default-enabled** and **preview /
  opt-in** tiers in `RULES.md` and in `diagscope.yml`'s schema. A rule only
  moves to default-enabled once it's been part of a real-repository run
  with a maintainer verdict on its precision, the same standard the
  technical gate already applies to the tool as a whole.
- This is not about removing rules — it's about not letting "26 rules
  shipped" imply "26 rules independently proven," which the project's own
  non-goal ("broad rule count as a substitute for precision") already
  warns against. A 1.0.0 with 8 proven-default rules and 18 clearly-marked
  preview rules is more credible than 26 rules presented as equally solid.

---

## 3. Close the three real Phase 3A gaps

`docs/ROADMAP.md` should also be corrected before 1.0.0 — it currently
describes Phase 3A as "Next," but the CLI already ships `--fail-on`,
`--baseline` (with migration and pruning), `--changed-since`, and
`diagscope.yml`. That's most of Phase 3A, already real. Update the roadmap
to reflect it; a stale roadmap undermines trust in every other document
right when 1.0.0 is asking people to trust the project more, not less.

What's genuinely still missing, and matters most for adoption:

- **`diagscope-maven-plugin`** — bind a scan to the Maven lifecycle instead
  of a hand-written CI step.
- **A Gradle plugin equivalent.**
- **A published GitHub Action**, with a pull-request summary comment. This
  is very likely the single highest-leverage item on this entire list —
  it's what makes trying DiagScope a five-minute experience for someone
  who has never heard of it, which is exactly the missing ingredient from
  Section 0.

---

## 4. Distribution: make the jar installable, not just buildable

There is no tagged GitHub Release, no prebuilt artifact, no install path
that doesn't start with cloning the repository and running
`mvn clean install`. For 1.0.0:

- Tag `v1.0.0`, attach the built `diagscope-cli/target/diagscope.jar` (or
  an equivalent shaded artifact) to a GitHub Release.
- Publish `diagscope-core` (and whatever the Maven/Gradle plugins depend
  on) to a real Maven coordinate — GitHub Packages is the low-friction
  starting point; Maven Central is the eventual target once the group ID
  and release process are stable.
- Update `bin/diagscope` and the README so "install DiagScope" doesn't
  silently mean "clone this Java monorepo first."

---

## 5. Documentation written for someone who has never seen the code

Everything currently in `docs/` is excellent, but it's almost entirely
contributor- and maintainer-facing (ADRs, `PROJECT_MEMORY.md`,
`CODE_GUIDELINES.md`, `ALPHA_CONSOLIDATION.md`). A 1.0.0 user needs a short
path that assumes none of that context:

- install → first scan → read the HTML report → add `diagscope.yml` →
  suppress one false positive → wire `--fail-on` into CI.
- Check whether `docs/CLI.md` already covers this for the CLI-experienced
  reader; if so, the gap is a five-minute *landing* page above it, not a
  rewrite.
- One realistic example report from an actual real-repository scan (with
  identifying details scrubbed), not only the small `mixed-flow` fixture —
  so a first-time reader sees what this looks like at real-project scale,
  not toy scale.

---

## 6. Security posture matches what "trust this in your pipeline" requires

Carried over from the earlier review, more important now than at alpha,
because 1.0.0 is explicitly asking people to run this against their own
source and wire it into CI:

- Enable GitHub's private vulnerability reporting — `SECURITY.md` should
  stop pointing at a channel that isn't configured yet.
- Add `.github/dependabot.yml` for the Maven and GitHub Actions ecosystems.
- Enable CodeQL's default setup for the Java sources.
- Document and test the existing symlink-traversal protection in the
  source-discovery adapter explicitly, rather than leaving it as an
  unstated property of `Files.find`'s default behavior.

---

## 7. Prove it across platforms, not just the author's machine

The verification snapshot in `ALPHA_CONSOLIDATION.md` was run on macOS.
`Finding`'s path-normalization logic already anticipates Windows path
separators, but CI only exercises `ubuntu-latest`. Add a Windows leg (at
minimum for `diagscope-core` and `diagscope-cli`, where path and
fingerprint logic live) before 1.0.0 — "written for Windows" and "verified
on Windows" should match by the time this is a numbered release.

---

## 8. Capture real performance numbers, not just fixture numbers

The only recorded benchmark is three scans of the six-file `mixed-flow`
fixture (~0.25s). That's a tooling smoke test, correctly labeled as one —
but it's the only performance evidence that exists. Since real-repository
validation has already happened, record and publish scan time and peak
memory for those actual repositories (sizes, not identities, if they're
private) in `docs/PERFORMANCE.md`. A 1.0.0 user's first question is "how
long will this take on my codebase," and today there's no real answer.

---

## 9. Release mechanics

- Keep `CHANGELOG.md` current up through 1.0.0, in Keep-a-Changelog style
  if it isn't already — this is the artifact that makes every future
  version bump legible against the compatibility promise in Section 1.
- Decide and document the versioning cadence going forward (does a new
  rule ship as a patch, a minor, given it can change CI-visible output for
  someone with `--fail-on` set? This needs an explicit answer — "new
  default-enabled rule = minor version" is a reasonable default, but it
  has to be written down, not assumed.)
- Close the one remaining open item in `ALPHA_CONSOLIDATION.md`'s
  checklist — a pre-deletion snapshot or tag for the `diagscope-b`
  exploration's history — so no administrative loose end survives into a
  numbered release.

---

## 10. What 1.0.0 should explicitly still refuse

Worth restating, because "let's finally ship 1.0" is exactly the moment
scope tends to quietly grow. Everything `docs/ROADMAP.md` already lists as
a non-goal stays a non-goal at 1.0.0:

- no blocking CI enforcement without the baseline/policy model that
  already exists — good, that part is done, don't weaken it;
- no native-image packaging without a measured startup-time problem to
  justify it;
- no cross-service claims from guessed static topology;
- no autofix or code rewriting;
- no AI authority over which findings are real.

And, given Section 0 and the framing of this whole request: no new
language or framework support (Quarkus, Vert.x, or otherwise) before
1.0.0 ships. That's explicitly the next thing after this list, not part of
it.

---

## Summary — the shortest version

If only three things happen before tagging `v1.0.0`, they should be:
**(1)** write down what the version number actually promises (Section 1),
**(2)** ship the GitHub Action and one build-tool plugin so a first
external user can try this in minutes, not by cloning a monorepo
(Section 3), and **(3)** be honest, in the release itself, about how much
real-world validation this has actually had (Section 0). Everything else
in this document makes the release more credible; those three make it
honest.
