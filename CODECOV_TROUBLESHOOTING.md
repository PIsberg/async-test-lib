# Codecov "Unknown" Status - Troubleshooting Guide

## Issue
Codecov badge shows "unknown" status instead of coverage percentage.

## Root Causes Identified

### 1. **Network Blocked by step-security/harden-runner** (FIXED ✅)
The `step-security/harden-runner` action with `egress-policy: block` was preventing the Codecov action from downloading its CLI tool from `cli.codecov.io`.

**Error:**
```
curl: (7) Failed to connect to cli.codecov.io port 443 after 1 ms: Couldn't connect to server
```

**Fix Applied:** Added `cli.codecov.io:443` to the `allowed-endpoints` list in the harden-runner step.

### 2. **Coverage File Not Generated**
The JaCoCo plugin should generate `./target/site/jacoco/jacoco.xml` during `mvn clean install`, but this might fail if:
- Tests are skipped (`-DskipTests`)
- JaCoCo plugin isn't properly configured
- Build fails before test phase

**Fix Applied:** Added debug step to verify file exists before upload.

### 3. **Silent Upload Failures** 
With `fail_ci_if_error: false`, upload failures were silent.

**Fix Applied:** Changed to `fail_ci_if_error: true` to surface errors.

### 4. **Missing Codecov Token**
The `CODECOV_TOKEN` secret must be configured in GitHub repository settings.

**Setup Required:**
1. Go to https://app.codecov.io/gh/PIsberg/async-test-lib
2. Copy your upload token
3. Add to GitHub: Settings → Secrets and variables → Actions → `CODECOV_TOKEN`

### 5. **Fork PR Limitations**
GitHub Actions don't expose secrets to fork PRs. Codecov will show "unknown" for PRs from forks.

**Workaround:** 
- Contributors should push branches to the main repo
- Or use `pull_request_target` (requires security review)

### 6. **Repository Not Registered on Codecov**
The repository must be registered and activated on Codecov.

**Setup:**
1. Visit https://app.codecov.io
2. Sign in with GitHub
3. Enable coverage for `PIsberg/async-test-lib`

## Changes Made

### `.github/workflows/tests.yml`
1. ✅ Removed redundant `jacoco:report` step (already runs during `mvn install`)
2. ✅ Added debug step to verify coverage file exists
3. ✅ Changed `fail_ci_if_error: false` → `true` to surface errors
4. ✅ Added detailed error logging if file is missing

### `codecov.yml`
- Configuration is correct, no changes needed

## Next Steps

1. **Push this branch and create a PR**
2. **Check the workflow logs** for the "Check coverage file exists" step
3. **Verify CODECOV_TOKEN secret** is set in repository settings
4. **Check Codecov dashboard** at https://app.codecov.io/gh/PIsberg/async-test-lib

## Debugging Commands

If the issue persists, check:

```bash
# Locally verify JaCoCo generates the report
mvn clean install
ls -la target/site/jacoco/jacoco.xml

# Check file size (should be > 1KB)
wc -c target/site/jacoco/jacoco.xml

# View report in browser
open target/site/jacoco/index.html
```

## Expected Workflow Output

After pushing, the workflow should show:

```
✓ Coverage file found
12345 ./target/site/jacoco/jacoco.xml
[Codecov] Upload successful
```

If you see errors instead, they will now be visible in the workflow logs.
