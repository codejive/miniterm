# Release Process

This project uses [JReleaser](https://jreleaser.org/) for automated releases to GitHub Releases and Maven Central.

## Creating a Release

1. **Ensure all changes are committed and pushed**

   ```bash
   git status  # Should be clean
   ```

2. **Trigger the release workflow**
   - Go to [Actions → Release](../../actions/workflows/release.yml)
   - Click **Run workflow**
   - Enter the release version (e.g., `1.0.0`)
   - Click **Run workflow**

3. **What the workflow does**
   - Updates all `pom.xml` versions to the release version
   - Builds and signs artifacts (JARs, sources, javadoc) for both modules
   - Deploys staging artifacts to local directories
   - Commits and pushes the version change
   - Creates a GitHub Release with auto-generated changelog
   - Publishes `miniterm`, `miniterm-ffm`, and `ansiparser` to Maven Central
   - Bumps versions to the next `-SNAPSHOT` and pushes

4. **Verify the release**
   - Check [GitHub Releases](../../releases)
   - Check [Maven Central](https://central.sonatype.com/search?q=g:org.codejive.miniterm) (may take ~30 minutes)

## Local Testing (Optional)

```bash
# Validate JReleaser configuration
./mvnw jreleaser:config

# Test build with release profile (produces staged artifacts)
./mvnw clean deploy jreleaser:assemble -Prelease

# Inspect staged artifacts (all modules land here)
ls target/staging-deploy/

# Do a dry-run of the full release
./mvnw jreleaser:full-release -Djreleaser.dry.run
```

## First-Time Setup

### 1. Generate GPG Keys

```bash
gpg --gen-key
gpg --list-secret-keys --keyid-format=long

# Export keys (replace KEY_ID with your key ID)
gpg --armor --export KEY_ID > public.key
gpg --armor --export-secret-keys KEY_ID > private.key

# Publish to key server
gpg --keyserver keyserver.ubuntu.com --send-keys KEY_ID
```

### 2. Register at Maven Central

1. Sign up at https://central.sonatype.com/
2. Verify namespace ownership for `org.codejive`
3. Generate a user token (username + password)

### 3. Configure GitHub Secrets

Add at [Settings → Secrets → Actions](../../settings/secrets/actions):

| Secret | Description |
|--------|-------------|
| `GPG_PUBLIC_KEY` | Contents of `public.key` |
| `GPG_SECRET_KEY` | Contents of `private.key` |
| `GPG_PASSPHRASE` | Passphrase used when generating the GPG key |
| `MAVENCENTRAL_USERNAME` | Maven Central token username |
| `MAVENCENTRAL_PASSWORD` | Maven Central token password |

`GITHUB_TOKEN` is provided automatically by GitHub Actions.

### 4. Optional: Protected Environment

For an extra approval gate, create a `jreleaser` environment under Settings → Environments, add the same secrets there, and uncomment the `environment: jreleaser` line in `.github/workflows/release.yml`.

## Version Management

- Development versions use `-SNAPSHOT` suffix (e.g., `1.0.0-SNAPSHOT`)
- The workflow automatically bumps to the next patch SNAPSHOT after each release
- Use [semantic versioning](https://semver.org/): `MAJOR.MINOR.PATCH`
- Use [conventional commits](https://www.conventionalcommits.org/) for automatic changelog entries (`feat:`, `fix:`, `docs:`, etc.)
