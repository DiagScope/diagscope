# Publishing to Maven Central

The build, the signing and the upload are prepared and checked. What remains is what only the project owner can do:
prove the namespace, create a token and a signing key, and store them as secrets. This page lists those steps, then
the release procedure.

Decisions and their reasons are in [ADR-0010](decisions/ADR-0010-maven-central-publishing.md).

## What is published

Every module except `diagscope-test-fixtures`, under the namespace `io.github.diagscope`:

| Artifact | Why it is there |
| --- | --- |
| `diagscope-maven-plugin` | The reason for publishing: `mvn verify` runs the scan ([MAVEN_PLUGIN.md](MAVEN_PLUGIN.md)). |
| `diagscope-cli` | A dependency of the plugin. A shaded jar of about 78 MB, so each version adds that to Central. |
| `diagscope-parent` | The parent POM of all the others. |
| `diagscope-core`, `-jvmanalysis`, `-javaparser`, `-kotlinparser` | Listed as dependencies by the CLI's POM, so anyone depending on the CLI directly needs them to resolve. The plugin does not download them. |

Java package names stay `dev.diagscope.*`; only the Maven coordinates change namespace.

## One-time setup (you)

I could not do or test these from the build environment.

1. **Central Portal account.** Sign in at <https://central.sonatype.com>. Signing in with the GitHub account that owns
   the `DiagScope` organization is the simplest.
2. **Verify the namespace `io.github.diagscope`.** In the Portal: *Namespaces* → *Add Namespace*. The Portal shows a
   verification key. Create a **public** repository in the `DiagScope` GitHub organization whose name is that key, and
   press *Verify Namespace*. When it shows *Verified* the repository can be deleted.
3. **User token.** In the Portal, *Account* → *Generate User Token*. It gives a username and a password; they are not your
   login. Store them as GitHub secrets `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`.
4. **Signing key.** Central only accepts signed artifacts, and the public key must be on a public keyserver.

   ```bash
   gpg --full-generate-key                                  # RSA 4096; set a passphrase
   gpg --list-secret-keys --keyid-format long               # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID> # also keys.openpgp.org, which asks you to confirm by e-mail
   gpg --armor --export-secret-keys <KEYID>                 # the whole block goes into the secret below
   ```

   Secrets: `GPG_PRIVATE_KEY` (the exported block) and `GPG_PASSPHRASE`. Keep a backup of the private key somewhere
   safe: losing it means future versions are signed with a different key.
5. **Optional but recommended:** in *Settings → Environments*, open `maven-central` (created by the first run) and add a
   required reviewer, so the job cannot get the secrets without a human approving it. Put the four secrets there rather
   than at repository level.

The POM lists `DiagScope` (the organization) as the developer. Replace it with real names if you prefer; Central only
requires that a developer is present.

## Release procedure

1. Release as usual: bump the version, update the changelog, run the `release` workflow. A version on Central can never
   be changed or removed, so publish only what is already released and verified.
2. Run the **publish-central** workflow, with the release commit (or tag) as `ref` and `auto-publish` **off**.
   It builds with tests, signs, and uploads the bundle.
3. Open the deployment in the Portal (<https://central.sonatype.com/publishing/deployments>). It should be
   *validated*. Check the file list, then press *Publish*. (With `auto-publish` on, Central publishes by itself as soon
   as validation passes; use that once the procedure is proven.)
4. After a few minutes the artifacts are under
   <https://repo1.maven.org/maven2/io/github/diagscope/>. The search index follows later.
5. Confirm from a clean repository, as a user would:

   ```bash
   mvn -Dmaven.repo.local=/tmp/clean-repo io.github.diagscope:diagscope-maven-plugin:<version>:scan
   ```

6. Update [MAVEN_PLUGIN.md](MAVEN_PLUGIN.md) and the README of the tap, which say the plugin is not on Central yet.

## What CI already checks

The `central-bundle` job of `build.yml` runs the same build with a throwaway key and `-Dcentral.skipUpload=true`, then
`scripts/ci/check-central-bundle.sh` verifies that every artifact has a jar, a pom, sources, javadoc, signatures and
checksums, that the test fixtures are not in it, and that each POM carries a name, description, URL, license, developer
and SCM. A broken javadoc or a signing misconfiguration therefore fails on a push, not on release day. Locally:

```bash
mvn -Prelease -Dcentral.skipUpload=true -DskipTests deploy    # needs a gpg key; the bundle is in target/central-publishing
scripts/ci/check-central-bundle.sh target/central-publishing/central-bundle.zip
```

A bundle built this way was also consumed from a clean local Maven repository (as a `file:` repository): the plugin,
the CLI jar and the parent POM resolved, and the gate failed the build as designed.

## What the first run showed

The first publication of 0.1.6 went through `publish-central` and taught two things worth keeping:

- **The public key must be on a keyserver before the upload.** The first attempt reached the Portal, authenticated, and
  was rejected with one error repeated for every file: *Could not find a public key by the key fingerprint*. Everything
  else (namespace, POM metadata, javadoc and source jars, checksums) had passed. Publishing the key to
  `keyserver.ubuntu.com` was the whole fix. `gpg --send-keys` over the default HKP port can fail on a network that blocks
  it (`No route to host`); `hkps://keyserver.ubuntu.com` or pasting the exported public key into the keyserver's web form
  both work. Check with
  `https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x<fingerprint>` before running the workflow.
- **A failed deployment publishes nothing**, so re-running the workflow for the same commit after fixing the cause is
  safe. The failed deployment stays listed in the Portal and can be ignored or dropped there.

The second run validated and left the deployment waiting for the manual *Publish* (`autoPublish` off), as designed.

## Not tested

- What happens after the *Publish* click, until `repo1.maven.org` serves the files and a clean build resolves them. Do
  step 5 of the procedure and record the result here.
- `autoPublish` against the live service. The parameter name was read from the plugin's descriptor.
