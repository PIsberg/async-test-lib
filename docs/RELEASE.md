# Release Process

## Automated Release via GitHub Actions

The project uses GitHub Actions to automatically publish releases when you push a version tag.

### How to Release

#### 1. Update Version in pom.xml

Before releasing, update the version in `pom.xml`:

```xml
<version>1.1.0</version>  <!-- Change from 1.1.0 -->
```

Follow semantic versioning:
- MAJOR.MINOR.PATCH (e.g., 1.1.0, 1.1.0, 2.0.0)

#### 2. Commit and Tag

```bash
# Commit version change
git add pom.xml
git commit -m "Release version 1.1.0"

# Create annotated tag (required for release)
git tag -a v1.1.0 -m "Release version 1.1.0"

# Push both commits and tags
git push origin main
git push origin v1.1.0
```

#### 3. Automatic Publishing

The GitHub Actions workflow (`.github/workflows/publish.yml`) will:
1. Detect the new tag
2. Build and test the project
3. Publish the JAR to GitHub Packages
4. Create a GitHub Release with:
   - async-test-1.1.0.jar (main library)
   - async-test-1.1.0-sources.jar (source code)
   - async-test-1.1.0-javadoc.jar (API documentation)

#### 4. Verify the Release

Check GitHub Releases: https://github.com/yourusername/async-test-lib/releases

You should see:
- Release notes with version and installation instructions
- Three downloadable artifacts
- Link to Maven package repository

## Manual Release (If Needed)

If automated release fails, you can publish manually:

### Prerequisites

```bash
# Set GitHub credentials for Maven
export GITHUB_ACTOR=yourusername
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx
```

### Build and Deploy

```bash
# Build the project
mvn clean verify

# Deploy to GitHub Packages
mvn deploy

# Create release notes
# Then manually create release on GitHub
```

## Distribution Channels

### GitHub Packages (Primary)

Configured in `pom.xml` under `<distributionManagement>`.

Users add to their `pom.xml`:
```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/yourusername/async-test-lib</url>
</repository>

<dependency>
    <groupId>com.github.asynctest</groupId>
    <artifactId>async-test</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Maven Central (Future)

To publish to Maven Central:

1. Register at https://central.sonatype.com/
2. Create JIRA ticket with groupId approval
3. Configure `pom.xml` with Maven Central distributor
4. Sign artifacts with GPG
5. Update GitHub Actions to publish to Central

### JCenter / Bintray (Legacy)

These services are being sunset. Focus on Maven Central and GitHub Packages.

## Artifact Contents

### async-test-1.1.0.jar

- Compiled library classes
- All 20+ detectors
- JUnit 5 extension
- Manifest with version info

### async-test-1.1.0-sources.jar

- Complete Java source code
- Includes JavaDoc comments
- Easy IDE integration (download sources)

### async-test-1.1.0-javadoc.jar

- Full API documentation
- Parameter descriptions
- Usage examples
- Integration with IDE tooltips

## Version Numbering

Semantic Versioning (https://semver.org/):

- **MAJOR** (1.x.x): Breaking API changes
  - Example: Changing @AsyncTest parameter names
  
- **MINOR** (x.1.x): New features, backward compatible
  - Example: Adding new detector parameter
  
- **PATCH** (x.x.1): Bug fixes only
  - Example: Fixing false positive in race detection

## Release Checklist

Before pushing a release tag:

- [ ] Update `pom.xml` version
- [ ] Run full test suite: `mvn clean test`
- [ ] Run code coverage: `mvn jacoco:report`
- [ ] Verify coverage >= 60%
- [ ] Update CHANGELOG.md with new features
- [ ] Update README.md if needed
- [ ] Check for deprecation warnings
- [ ] Test manual installation:
  ```bash
  mvn clean install
  # Then use in another project
  ```
- [ ] Create release notes (included in tag message)
- [ ] Git commit and tag
- [ ] Push commits and tag
- [ ] Verify GitHub Actions publish succeeds
- [ ] Download and test artifacts locally

## Maintaining the Project

### Regular Updates

1. **Monthly**: Update dependencies
   ```bash
   mvn versions:display-dependency-updates
   mvn versions:display-plugin-updates
   ```

2. **Quarterly**: Review and update JUnit versions as they release

3. **Annually**: Review and update build plugins

### Monitoring

- Watch GitHub issues for bug reports
- Monitor test results in Actions
- Track code coverage trends
- Keep dependencies up-to-date

## Example Release Session

```bash
# 1. Verify all tests pass
mvn clean test

# 2. Update version
sed -i 's/<version>1.1.0<\/version>/<version>1.1.0<\/version>/' pom.xml

# 3. Commit version
git add pom.xml
git commit -m "Prepare release 1.1.0"

# 4. Tag release
git tag -a v1.1.0 -m "Release 1.1.0 - Bug fixes for deadlock detection"

# 5. Push to trigger CI/CD
git push origin main
git push origin v1.1.0

# 6. Monitor build at: https://github.com/yourusername/async-test-lib/actions

# 7. Check release at: https://github.com/yourusername/async-test-lib/releases
```

## Rollback

If a release has critical issues:

```bash
# Remove the tag
git tag -d v1.1.0
git push origin :refs/tags/v1.1.0

# Delete the release on GitHub (manually)
# Re-run the automated process with fixes

# Or create a patch release
# v1.1.0 -> v1.0.2
```

## FAQ

**Q: How do I publish to Maven Central?**
A: See "Maven Central (Future)" section above. Requires SONATYPE account and additional configuration.

**Q: Can I publish snapshots for testing?**
A: Yes, update version to `1.1.0-SNAPSHOT` and run `mvn deploy`. Snapshots go to separate repository.

**Q: What if the GitHub Actions workflow fails?**
A: Check the workflow run logs. Common issues:
- Compilation errors (fix and re-tag)
- Test failures (fix and re-tag)
- Network issues (retry)

**Q: How do I deprecate an old version?**
A: Create a new release with deprecation notice. Users should update. No automated mechanism needed.

**Q: Can I have multiple active versions?**
A: Yes! Maintain multiple branches (main for latest, release-1.0.x for 1.0 line). Release from each branch.
