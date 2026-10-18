# ADR-0010: Publish to Maven Central under `io.github.diagscope`

## Status

Accepted. The build, the signing and the bundle are implemented and checked in CI; the first publication needs the
owner's account, namespace verification, token and signing key ([PUBLISHING.md](../PUBLISHING.md)).

## Context

The Maven plugin ([ADR-0008](ADR-0008-maven-plugin.md)) cannot reach anyone else until Maven can download it. Until
now it installed only from source. Maven Central is where Maven resolves plugins without extra repository configuration,
and it is the only option that needs nothing from the user.

Central accepts a namespace only when the publisher proves it owns it. The project used `dev.diagscope`, which needs
ownership of the domain `diagscope.dev`. That domain did not resolve when checked, so the owner chose the namespace
that the GitHub organization proves on its own.

## Decision

1. **The groupId becomes `io.github.diagscope`.** It is verified through the `DiagScope` GitHub organization. Java
   packages are unchanged (`dev.diagscope.*`): they are code, not coordinates, and renaming them would touch every
   source file for no benefit. Nothing had been published under the old
   groupId.
2. **Publish every module but the test fixtures.** The plugin needs only itself, the CLI jar and the parent POM, which a
   clean-repository run confirmed. The other modules are listed in the CLI's POM, so someone depending on the CLI
   directly would fail to resolve without them. The cost is that they become a public surface to keep compatible.
3. **A `release` profile, not the default build.** Source and javadoc jars, signing and the upload exist only under
   `-Prelease`, so contributors and CI's ordinary `verify` need no key and no credentials.
4. **Manual, gated publishing.** The `publish-central` workflow runs by hand, in an environment that can require a
   reviewer, and does not auto-publish by default: the validated deployment waits in the Portal for a human. Central
   never allows a version to be changed or deleted, so the irreversible step is a deliberate one.
5. **CI assembles the bundle on every push** with a throwaway key and checks it
   (`scripts/ci/check-central-bundle.sh`), so what Central rejects for reasons within our control fails early.
6. **The first Central version is whatever is released next.** It is built from a release commit, not from a branch.

## Consequences

- Anyone can add the plugin to a POM with no repository configuration once the first version is published.
- Each version adds about 78 MB to Central (the shaded CLI jar), for ever.
- The coordinates changed before any publication, so only local installs of the unpublished plugin are affected.
- A rejected or mistaken publication is fixed by publishing the next version, never by editing one.
- The POM names the `DiagScope` organization as the developer; it can be replaced by named people.

## Not decided here

- Whether to also publish the CLI as a standalone download from Central (it is already in the bundle as an artifact),
  or keep the GitHub release as the CLI's canonical home.
- A Maven Central badge and a pluginGroup entry so that `mvn diagscope:scan` resolves without the full coordinates.
