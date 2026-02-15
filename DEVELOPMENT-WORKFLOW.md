# Synthesis Development Workflow

**CRITICAL**: Always use PR workflow. Never push directly to `main`.

---

## 🔄 Standard Development Workflow

### 1. Create Feature Branch

```bash
# From main branch
git checkout main
git pull origin main

# Create feature branch
git checkout -b feature/your-feature-name
```

### 2. Make Changes & Test Locally

```bash
# Build and install for testing
./bin/test-local.sh

# Or skip tests for faster iteration
./bin/test-local.sh --skip-tests
```

The `test-local.sh` script:
- ✅ Builds the current branch
- ✅ Installs JARs to `~/.synthesis/lib/`
- ✅ Updates `current.jar` symlink
- ✅ Verifies version
- ✅ Shows what you're testing

**Always use this script** to ensure you're testing the latest code.

### 3. Commit Changes

```bash
git add -A
git commit -m "feat: Your feature description

Detailed explanation of changes.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

### 4. Push Feature Branch

```bash
git push -u origin feature/your-feature-name
```

### 5. Create Pull Request

```bash
gh pr create --title "feat: Your feature" --body "Description..."
```

Or use the GitHub UI.

### 6. Review & Merge

- Review PR on GitHub
- Run tests (CI)
- Merge via GitHub (not command line)

### 7. Clean Up

```bash
git checkout main
git pull origin main
git branch -d feature/your-feature-name
```

---

## 🚫 What NOT to Do

### ❌ NEVER Push Directly to Main

```bash
# ❌ DON'T DO THIS
git checkout main
git commit -m "changes"
git push origin main
```

**Why?**
- Bypasses code review
- No CI validation before merge
- Can break main branch
- Loses PR documentation

### ✅ Always Create a PR

```bash
# ✅ DO THIS INSTEAD
git checkout -b feature/my-changes
git commit -m "changes"
git push -u origin feature/my-changes
gh pr create
```

---

## 🧪 Testing Workflow

### Quick Test Cycle

```bash
# 1. Make code changes
vim src/main/java/...

# 2. Build and install (skip tests for speed)
./bin/test-local.sh --skip-tests

# 3. Test manually
synthesis status
synthesis list
# etc.

# 4. Repeat until working
```

### Full Test Cycle

```bash
# 1. Make code changes
vim src/main/java/...

# 2. Build and install with tests
./bin/test-local.sh

# 3. Test manually
synthesis status
# etc.

# 4. Commit when tests pass
git commit -m "feat: ..."
```

### Verify You're Testing Latest

```bash
# Check installed version
synthesis --version

# Check built version
grep -m 1 '<version>' pom.xml

# Should match (e.g., both 1.2.2-SNAPSHOT)
```

If they don't match, run `./bin/test-local.sh` again.

---

## 📦 Release Workflow

### 1. Prepare Release

```bash
git checkout main
git pull origin main

# Update version (remove -SNAPSHOT)
vim pom.xml  # 1.2.2-SNAPSHOT -> 1.2.2
git commit -m "release: Synthesis v1.2.2"
git tag v1.2.2
```

### 2. Deploy to Maven

```bash
# Build at release version
mvn clean package -DskipTests

# Deploy to Cantara Maven repository
git checkout v1.2.2
mvn clean deploy -DskipTests
```

### 3. Bump to Next SNAPSHOT

```bash
git checkout main
vim pom.xml  # 1.2.2 -> 1.2.3-SNAPSHOT
git commit -m "chore: Bump version to 1.2.3-SNAPSHOT"
git push origin main
git push origin v1.2.2
```

### 4. Create GitHub Release (Optional)

```bash
gh release create v1.2.2 \
  --title "Release v1.2.2" \
  --notes-file RELEASE-NOTES-2026-02-15.md \
  target/synthesis-1.2.2.jar
```

---

## 🔧 Common Tasks

### Switching Between Branches

```bash
# Always use test-local.sh after switching
git checkout feature/branch-a
./bin/test-local.sh --skip-tests

git checkout feature/branch-b
./bin/test-local.sh --skip-tests
```

### Cleaning Up Old Branches

```bash
# List merged branches
git branch --merged main

# Delete local branch
git branch -d feature/old-feature

# Delete remote branch
git push origin --delete feature/old-feature
```

### Undoing a Direct Push to Main

```bash
# If you accidentally pushed to main:

# 1. Reset main locally
git checkout main
git reset --hard HEAD~1

# 2. Force push (only if safe!)
git push origin main --force-with-lease

# 3. Create proper feature branch
git checkout -b feature/fix
git cherry-pick <commit-sha>
git push -u origin feature/fix

# 4. Create PR
gh pr create
```

---

## 📋 Checklist for Every Change

Before pushing:
- [ ] Created feature branch (not on main)
- [ ] Ran `./bin/test-local.sh` to verify latest code
- [ ] Tested manually
- [ ] Committed with descriptive message
- [ ] Pushed feature branch (not main)
- [ ] Created PR with description
- [ ] PR reviewed and merged via GitHub

---

## 🎯 Key Principles

1. **Always use feature branches**
2. **Always create PRs** (never push to main)
3. **Always use `./bin/test-local.sh`** to ensure latest code
4. **Always test locally** before pushing
5. **Always document** in PR descriptions

---

**Remember**: The extra 30 seconds to create a PR prevents hours of debugging and maintains project quality! 🚀
