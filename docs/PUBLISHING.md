# Publishing to Maven Central

The build is already configured. Everything below is account setup, done once.

**Ignore any guide that mentions a JIRA ticket, `oss.sonatype.org`,
`s01.oss.sonatype.org`, or "close and release a staging repository".** That was
OSSRH, which Sonatype retired in favour of the Central Portal. Most search
results still describe it, which is the single biggest source of confusion here.
`build.gradle.kts` already targets the Portal:

```kotlin
publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
```

## What the workflow needs

Four repository secrets. `publish.yml` maps them to the properties the
[vanniktech maven-publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/)
expects:

| GitHub secret | Gradle property | What it is |
|---|---|---|
| `MAVEN_CENTRAL_USERNAME` | `mavenCentralUsername` | Portal **user token** name — *not* your login |
| `MAVEN_CENTRAL_PASSWORD` | `mavenCentralPassword` | Portal user token password |
| `SIGNING_KEY` | `signingInMemoryKey` | ASCII-armoured **private** GPG key |
| `SIGNING_PASSWORD` | `signingInMemoryKeyPassword` | That key's passphrase |

---

## 1. Portal account and namespace

Sign in at <https://central.sonatype.com> (GitHub works), then
**Publish → Namespaces → Add Namespace** and enter `com.logduck`.

The Portal shows a verification code. Because this is a `com.` namespace, you
prove it by controlling the domain — add a DNS TXT record on `logduck.com`:

```
Type: TXT
Host: @            (the apex — logduck.com itself, not a subdomain)
Value: <the code the Portal shows>
```

`logduck.com` already carries TXT records for SPF and Microsoft 365, so this is
an addition, not a replacement — do not overwrite the existing ones.

Wait for propagation, then press **Verify Namespace**. Check it yourself first:

```bash
dig +short logduck.com TXT | grep -F "<the code>"
```

> The easier alternative is an `io.github.log-duck` namespace, verified by
> creating a throwaway public repo instead of touching DNS. It is not worth it
> here: the group id is baked into every consumer's build file, and
> `com.logduck` is the one worth owning.

## 2. User token

**Account → Generate User Token.** It returns a name/password pair, shown once,
wrapped in an XML block:

```xml
<server>
  <id>central</id>
  <username>...</username>
  <password>...</password>
</server>
```

That is a Maven `settings.xml` fragment. Gradle does not read it — only the two
values inside matter, and `<id>` is a Maven server name to ignore.

These are *not* your Portal login.

**Generating a new token does not revoke the old one.** The Portal keeps named
tokens side by side under *Active Tokens*, and each has to be revoked on its own
row. Rotating a credential is therefore two actions, not one — issue the new
pair, set the secrets, then go back and revoke the old. Assuming the first step
did the third leaves a live credential behind, which is exactly what happened
during this repository's setup.

Extract them with the shell rather than by hand. A trailing newline or a stray
space is preserved verbatim in a GitHub secret and comes back as a 401 that
looks exactly like a wrong token:

```bash
# paste the XML into /tmp/token.xml, then:
sed -n 's|.*<username>\(.*\)</username>.*|\1|p' /tmp/token.xml | tr -d '\n \t' \
  | gh secret set MAVEN_CENTRAL_USERNAME --repo Log-Duck/logduck-kotlin

sed -n 's|.*<password>\(.*\)</password>.*|\1|p' /tmp/token.xml | tr -d '\n \t' \
  | gh secret set MAVEN_CENTRAL_PASSWORD --repo Log-Duck/logduck-kotlin

rm /tmp/token.xml
```

## 3. GPG signing key

Maven Central rejects unsigned artifacts, and it checks the public key against a
keyserver — so publishing the public half is a required step, not housekeeping.

```bash
# Generate. RSA 4096, and give it a real expiry you will actually renew.
gpg --full-generate-key

# Find the key id (the long hex string after the slash).
gpg --list-secret-keys --keyid-format=long

# Publish the PUBLIC half. Central looks it up here.
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export the PRIVATE half for CI, armoured, to a file — never to a terminal
# you might screenshot or paste.
gpg --armor --export-secret-keys <KEY_ID> > /tmp/signing-key.asc
```

`--send-keys` publishes the **public** half only. It cannot upload a private key
even if asked; the private half goes to the `SIGNING_KEY` secret and nowhere
else.

Confirm it arrived before relying on it — a key that never propagated fails
Central's signature check long after the upload appears to have worked:

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys <KEY_ID>
```

Use `keyserver.ubuntu.com`. `keys.openpgp.org` strips user IDs until you confirm
by email, and Central's lookup can fail against a key with no UID.

## 4. Set the secrets

From stdin or a file, so nothing lands in shell history or a chat log:

```bash
gh secret set MAVEN_CENTRAL_USERNAME --repo Log-Duck/logduck-kotlin
gh secret set MAVEN_CENTRAL_PASSWORD --repo Log-Duck/logduck-kotlin
gh secret set SIGNING_PASSWORD       --repo Log-Duck/logduck-kotlin
gh secret set SIGNING_KEY            --repo Log-Duck/logduck-kotlin < /tmp/signing-key.asc

rm /tmp/signing-key.asc
```

`SIGNING_KEY` reads the whole armoured block, `BEGIN`/`END` lines and internal
newlines included — those matter, so do not strip whitespace from this one the
way you should for the token values.

Verify all four are present — a missing one fails late, after the build:

```bash
gh secret list --repo Log-Duck/logduck-kotlin
```

## 5. Publish

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow refuses to run if the tag disagrees with `version` in
`build.gradle.kts`, if that disagrees with `SDK_VERSION` in `LogDuckClient.kt`
(it rides in the User-Agent), or if the version already exists on Central.

**A published version can never be replaced or removed, only superseded.**

Artifacts appear at `repo1.maven.org` within about 15 minutes and become
searchable later; the search index lagging is normal and not a failed publish:

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  https://repo1.maven.org/maven2/com/logduck/logduck/1.0.0/
```

## Considering a dry run first

`automaticRelease = true` means a validated upload goes straight to the world.
For a first-ever publish you may prefer to see the deployment in the Portal
before it becomes permanent:

```kotlin
publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
```

The upload then waits in **Publish → Deployments** as `VALIDATED`, and you press
**Publish** yourself. Set it back to `true` afterwards so later releases stay
hands-off.

## When it fails

| Symptom | Cause |
|---|---|
| `401 Unauthorized` | Portal login used instead of a user token, or the token in the secret was revoked |
| `Namespace not allowed` | Namespace unverified, or the DNS TXT record has not propagated |
| `PGP signature validation failed` | Public key never sent to a keyserver, or sent only to `keys.openpgp.org` without a confirmed UID |
| `signingInMemoryKey` errors | Usually the passphrase, not the key — confirm `SIGNING_PASSWORD` matches this key |
| Build fails before publishing | Not a credentials problem; `./gradlew build` reproduces it locally |
