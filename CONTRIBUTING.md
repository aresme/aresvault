# Contributing to AresVault

Thank you for considering contributing to AresVault! This document outlines the process and guidelines for contributing.

## 🤝 How to Contribute

### Reporting Bugs

Before creating bug reports, please check existing issues to avoid duplicates. When creating a bug report, include:

- Clear and descriptive title
- Steps to reproduce the behavior
- Expected vs actual behavior
- Device information (model, Android version, XOS version if applicable)
- Screenshots if applicable
- Relevant log output (`adb logcat -s AresVault`)

### Suggesting Enhancements

Enhancement suggestions are tracked as GitHub issues. When creating an enhancement suggestion, include:

- Clear and descriptive title
- Detailed description of the proposed feature
- Why this enhancement would be useful
- Possible implementation approaches if you have ideas

### Pull Requests

1. **Fork the repository** and create your branch from `main`
2. **Follow the existing code style**: Kotlin coding conventions, Android best practices
3. **Write clear commit messages**: Use conventional commits format
   - `feat:` for new features
   - `fix:` for bug fixes
   - `docs:` for documentation changes
   - `refactor:` for code refactoring
   - `test:` for test additions/changes
4. **Add tests** for new functionality
5. **Update documentation** if you change functionality
6. **Ensure all tests pass**: Run `./gradlew test` and instrumented tests
7. **Keep PRs focused**: One feature/fix per PR

## 🏗️ Development Setup

See [BUILDING.md](BUILDING.md) for detailed build instructions.

### Quick Start

```bash
# Clone your fork
git clone https://github.com/YOUR-USERNAME/aresvault.git
cd aresvault

# Set up local.properties
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# Build and run tests
./gradlew test
./gradlew :app:installDebug
```

## 📋 Code Guidelines

### Kotlin Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Keep functions small and focused
- Document public APIs with KDoc comments
- Use `sealed class` for state representation
- Prefer immutable data structures

### Android Specific

- Use Jetpack Compose for UI components
- Follow Material 3 design guidelines
- Use Hilt for dependency injection
- Keep ViewModels thin, business logic in repositories
- No network calls (this is a local-only app)

### Security

- Never log sensitive data (passwords, keys, tokens)
- Use `CharArray`/`ByteArray` for passwords when possible
- Clear sensitive data from memory after use
- Follow existing cryptographic patterns
- Review [SECURITY.md](SECURITY.md) before modifying security-critical code

## 🧪 Testing

- Write unit tests for business logic (`:core`, `:data` modules)
- Write instrumented tests for Android-specific features (`:app` module)
- Aim for meaningful coverage, not just high percentages
- Test security-critical paths thoroughly
- Run tests before submitting PR:

```bash
./gradlew test                                  # Unit tests
./gradlew :data:connectedDebugAndroidTest       # Data layer tests
./gradlew :app:connectedStagingAndroidTest      # E2E tests
```

## 🔍 Code Review Process

1. All PRs require review before merging
2. Address review comments promptly
3. Keep discussions focused and professional
4. CI checks must pass (build, tests, lint)
5. Maintainers will merge approved PRs

## 📜 License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.

## ❓ Questions?

- Check existing documentation: README.md, SECURITY.md, BUILDING.md
- Search existing issues
- Open a new issue with the "question" label

## 🙏 Thank You!

Your contributions help make AresVault better for everyone. We appreciate your time and effort!
