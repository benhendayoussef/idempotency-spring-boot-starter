# Releasing

How to publish a release to Maven Central. Only needed by maintainers.

Artifacts are published by the tag-triggered [`release.yml`](.github/workflows/release.yml)
workflow, which uploads and validates a deployment but deliberately does **not** auto-release it —
reviewing the deployment and clicking Publish stays a manual step.

## One-time setup

### 1. Sonatype Central Portal account

Sign in at [central.sonatype.com](https://central.sonatype.com/) with the GitHub account that owns
this repository. Signing in via GitHub automatically grants the matching `io.github.<username>`
namespace, which is why the project's `groupId` is `io.github.benhendayoussef` — no separate domain
verification is needed.

### 2. GPG signing key

Central rejects unsigned artifacts.

```bash
# Generate a key (RSA, 4096-bit) if you don't already have one
gpg --full-generate-key

# Publish the public half so Central can verify signatures
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export the private half in the ASCII-armored form the publish plugin expects
gpg --export-secret-keys --armor <KEY_ID>
```

The export prints a multi-line block starting with `-----BEGIN PGP PRIVATE KEY BLOCK-----`. Copy it
in full. **Never commit it anywhere.**

### 3. Central user token

Generate a user token on the Central Portal (Account → Generate User Token). This is *not* your
portal login password.

For publishing from a local machine, put credentials in `~/.gradle/gradle.properties` — never in
the repository:

```properties
mavenCentralUsername=<token username>
mavenCentralPassword=<token password>

signing.keyId=<KEY_ID>
signing.password=<key passphrase>
signing.secretKeyRingFile=/path/to/secring.gpg
```

Local `publishToMavenLocal` works without any of this: signing is only required when signing
credentials are actually present.

### 4. GitHub repository secrets

Settings → Secrets and variables → Actions. The names must match exactly what `release.yml` reads:

| Secret | Value |
|---|---|
| `ORG_GRADLE_PROJECT_mavenCentralUsername` | Central user token username |
| `ORG_GRADLE_PROJECT_mavenCentralPassword` | Central user token password |
| `ORG_GRADLE_PROJECT_signingInMemoryKey` | The full ASCII-armored private key block from step 2 |
| `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` | The key's passphrase |

## Validating the pipeline

Before the first real release, push a throwaway version to confirm the whole pipeline works:

```bash
git tag v0.0.1 && git push origin v0.0.1
```

Watch the Actions run, then check the deployment on the Central Portal:

- all four artifacts present (`idempotency-core`, `idempotency-spring-boot-starter`,
  `idempotency-store-redis`, `idempotency-store-jdbc`)
- each with a `.pom`, `.jar`, `-sources.jar`, `-javadoc.jar`, and `.asc` signature
- status is `VALIDATED`, not `FAILED`
- no errors about missing javadoc, missing signatures, or incomplete POM metadata

Central releases cannot be deleted once published, only superseded — so leave the smoke deployment
unpublished unless you actually want a `0.0.1` on Central permanently. Validation is visible
without publishing.

Locally, the same version override works for testing against `mavenLocal()`:

```bash
./gradlew -Pversion=0.0.1 publishToMavenLocal
```

## Cutting a release

1. Update `CHANGELOG.md` — move the unreleased section under the version being released.
2. Tag and push:
   ```bash
   git tag v0.1.0 && git push origin v0.1.0
   ```
3. Wait for the release workflow to finish.
4. Review the deployment on the Central Portal, then click **Publish**.
5. Verify from Maven Central (not `mavenLocal()`) in a scratch project: add
   `io.github.benhendayoussef:idempotency-spring-boot-starter:<version>` with only `mavenCentral()`
   in `repositories {}`, and confirm the README quickstart works against the published artifact.

The version is derived from the tag: `v0.1.0` publishes `0.1.0`. The build defaults to `0.1.0` when
no `-Pversion` is supplied.
