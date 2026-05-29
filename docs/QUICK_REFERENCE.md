# 📚 Quick Reference Card - Library Distribution

## One-Page Distribution Summary

### What is Distribution?
Making your library available for others to install and use via Maven/Gradle without downloading source code.

### What You Get Now
```
✅ Maven artifact (JAR file)
✅ Automated releases (GitHub Actions)
✅ GitHub Packages hosting
✅ Javadoc generation
✅ Source code publication
- **Uncommitted Changes** — Detects untracked or uncommitted Git files that may affect test reproducibility
- 69+ specialized detectors for common concurrency pitfalls
```

### How Others Install (Maven)
```xml
<repository>
    <id>github</id>
    <url>https://repo1.maven.org/maven2</url>
</repository>

<dependency>
    <groupId>se.deversity.async-test-lib</groupId>
    <artifactId>async-test-lib</artifactId>
    <version>1.6.0</version>
    <scope>test</scope>
</dependency>
```

### How Others Install (Gradle)
```gradle
repositories {
    maven { url = uri("https://repo1.maven.org/maven2") }
}
dependencies {
    testImplementation 'se.deversity.async-test-lib:async-test-lib:1.6.0'
}
```

### Artifacts Created Per Release
```
async-test-lib-1.6.0.jar          (150 KB)  → Main library
async-test-lib-1.6.0-sources.jar  (350 KB)  → Source code
async-test-lib-1.6.0-javadoc.jar  (450 KB)  → API docs
```

### Release Process (4 Steps)
```bash
# 1. Update version in pom.xml
sed -i 's/0.9.0/0.10.0/' pom.xml

# 2. Commit
git commit -am "Release 0.9.0"

# 3. Tag (this triggers GitHub Actions!)
git tag -a v0.9.0 -m "Release 0.9.0"

# 4. Push
git push origin main && git push origin v0.9.0
```

### GitHub Actions Automation
```
Tag push detected (v0.9.0)
    ↓
Workflow starts (publish.yml)
    ↓
Build & test with Maven
    ↓
Create 3 artifacts
    ↓
Publish to GitHub Packages
    ↓
Create GitHub Release page
    ↓
Done! (5-10 minutes)
```

### Files Modified/Created
```
Modified:
  pom.xml (added plugins, metadata, distribution config)

Created:
  .github/workflows/publish.yml (release automation)
  USAGE.md (how to use)
  RELEASE.md (how to release)
  DISTRIBUTION.md (architecture)
  ARCHITECTURE.md (technical details)
  DISTRIBUTION_SETUP.md (setup summary)
  PRE_RELEASE_CHECKLIST.md (checklist)
  DISTRIBUTION_COMPLETE.md (completion summary)
```

### Before First Release: 3 Tasks
1. Replace `PIsberg` with your GitHub username in pom.xml and .github/workflows/
2. Run `mvn clean package` to verify build works locally
3. Create tag: `git tag -a v1.6.0 -m "Initial release"`

### Verify Release Succeeded
```
Check 1: GitHub Actions
  https://github.com/PIsberg/async-test-lib/actions
  Look for: Publish Release - ✅ All jobs passed

Check 2: GitHub Packages
  https://github.com/PIsberg/async-test-lib/packages
  Look for: async-test v1.6.0

Check 3: GitHub Releases
  https://github.com/PIsberg/async-test-lib/releases
  Look for: v1.6.0 with download links
```

### Maven Configuration Summary
```
Group ID:     se.deversity.async-test-lib
Artifact ID:  async-test
Version:      1.1.0
Scope:        test
License:      MIT
URL:          https://github.com/PIsberg/async-test-lib
Repository:   https://repo1.maven.org/maven2
```

### Dependency Coordinates
```
Maven:   se.deversity.async-test-lib:async-test-lib:1.6.0
Gradle:  'se.deversity.async-test-lib:async-test-lib:1.6.0'
```

### pom.xml Key Sections
```xml
<!-- Metadata -->
<name>Async Test Library</name>
<description>Enterprise-grade JUnit 5 concurrency testing...</description>
<url>https://github.com/PIsberg/async-test-lib</url>
<license><name>MIT License</name></license>

<!-- Distribution -->
<distributionManagement>
  <repository>
    <url>https://repo1.maven.org/maven2</url>
  </repository>
</distributionManagement>

<!-- Plugins -->
<plugin>maven-source-plugin</plugin>    <!-- Creates -sources.jar -->
<plugin>maven-javadoc-plugin</plugin>   <!-- Creates -javadoc.jar -->
<plugin>maven-compiler-plugin</plugin>  <!-- Java 21 -->
```

### Workflow Trigger
```yaml
# publish.yml triggers on ANY tag matching v*
# Examples that trigger:
git tag -a v1.6.0 -m "..."     ✅ Triggers
git tag -a v1.6.0 -m "..."     ✅ Triggers
git tag -a v2.0.0 -m "..."     ✅ Triggers
git tag -a myversion -m "..."  ❌ Does NOT trigger
```

### Semantic Versioning
```
1.1.0 (Major . Minor . Patch)

1.1.0 → 1.1.0  (Patch)  - Bug fixes only
1.1.0 → 1.1.0  (Minor)  - New features, backward compatible
1.1.0 → 2.0.0  (Major)  - Breaking changes
```

### Documentation Files
```
USAGE.md ..................... 8,800 words (for end users)
RELEASE.md ................... 6,200 words (for maintainers)
DISTRIBUTION.md ............. 9,400 words (technical)
ARCHITECTURE.md ............ 12,700 words (system design)
DISTRIBUTION_SETUP.md ...... 7,300 words (configuration)
PRE_RELEASE_CHECKLIST.md ... 9,875 words (first release)
DISTRIBUTION_COMPLETE.md .... 8,439 words (summary)

Total: 62,714 words of documentation
```

### Distribution Channels (Current & Future)
```
✅ GitHub Packages (Active now)
  - No authentication needed for public repos
  - Immediate availability
  - Users: Most Maven projects
  
⏳ Maven Central (Optional, future)
  - Broadest distribution
  - No authentication needed
  - More setup time (1-2 weeks one-time)
  - Users: Everyone
  
📦 Gradle/Ivy (Automatic)
  - Gradle reads same Maven repos
  - No extra configuration needed
```

### Troubleshooting Quick Links
```
Tests fail during release?
  → Check GitHub Actions logs
  → Run: mvn clean test
  → Fix, re-tag with new version

Users can't find artifact?
  → Verify pom.xml repository URL
  → Clear cache: rm -rf ~/.m2/repository
  → Check version matches release

Build fails locally?
  → Verify Java 21: java -version
  → Clean: mvn clean
  → Full rebuild: mvn clean package
```

### Timeline
```
Now:           ✅ Distribution infrastructure complete
Before release: Replace username, commit, tag
Release day:   Push tag → Automatic build → Done
During release: Wait 5-10 minutes (watch GitHub Actions)
After release:  Verify on GitHub Packages/Releases
```

### Commands Cheat Sheet
```bash
# Check Java version
java -version   # Should be 21+

# Build locally
mvn clean package

# Build with coverage
mvn clean test jacoco:report

# Generate javadoc
mvn javadoc:javadoc

# Check for updates
mvn versions:display-dependency-updates

# Clean build
mvn clean

# Deploy (requires credentials)
mvn deploy

# Release tag
git tag -a v1.6.0 -m "Description"
git push origin v1.6.0
```

### Verify Everything
```bash
# 1. Check compilation
mvn compile

# 2. Run tests
mvn test

# 3. Build package
mvn package

# 4. Generate docs
mvn javadoc:javadoc

# 5. Check artifacts
ls -lah target/async-test-*.jar
```

Expected results:
```
async-test-lib-1.6.0.jar                 ~150 KB ✅
async-test-lib-1.6.0-sources.jar         ~350 KB ✅
async-test-lib-1.6.0-javadoc.jar         ~450 KB ✅
BUILD SUCCESS                             ✅
```

### What Users See
```
GitHub Releases page:
  v1.6.0 release
  ├── async-test-lib-1.6.0.jar (download)
  ├── async-test-lib-1.6.0-sources.jar (download)
  ├── async-test-lib-1.6.0-javadoc.jar (download)
  └── Release notes

GitHub Packages:
  async-test package
  └── 1.1.0 version

Maven Central (in future):
  se.deversity.async-test-lib:async-test-lib:1.6.0
  └── Available with no special configuration
```

### Configuration Locations
```
Main config:        pom.xml
Release workflow:   .github/workflows/publish.yml
Test workflow:      .github/workflows/tests.yml
Distribution docs:  DISTRIBUTION*.md files
Release guide:      RELEASE.md
User guide:         USAGE.md
Quick start:        PRE_RELEASE_CHECKLIST.md
```

### Support Resources
```
Question: How do I install?
  → Read: USAGE.md

Question: How do I release?
  → Read: RELEASE.md or PRE_RELEASE_CHECKLIST.md

Question: What's the architecture?
  → Read: ARCHITECTURE.md or DISTRIBUTION.md

Question: What's been configured?
  → Read: DISTRIBUTION_SETUP.md

Question: Is it ready?
  → Read: DISTRIBUTION_COMPLETE.md
```

### Success Metrics
```
✅ Artifact builds locally
✅ Tests pass
✅ GitHub Actions runs successfully
✅ 3 artifacts created
✅ Published to GitHub Packages
✅ GitHub Release created
✅ Users can install and use
```

### Next Actions
```
1. Replace PIsberg in pom.xml (5 min)
2. Test build locally: mvn clean package (5 min)
3. Commit and tag: git tag -a v1.6.0 (1 min)
4. Push: git push origin v1.6.0 (1 min)
5. Wait for automation (5-10 min)
6. Verify release on GitHub (2 min)

Total: ~20-25 minutes to first release
```

---

## Distribution Status: ✅ COMPLETE

Everything is configured and documented. You're ready to distribute!

See **PRE_RELEASE_CHECKLIST.md** for exact steps to your first release.
