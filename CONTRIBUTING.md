# Contributing to Shelf

For bugs, open an issue with the Android version, device model, steps to reproduce, and what you expected to happen. Avoid including personal photos or stories unless you are comfortable sharing them.

For larger changes, open an issue first to discuss the approach. Keep pull requests focused on one improvement.

## Development workflow

1. Follow the build instructions in the [README](README.md).
2. Make your change using the existing Kotlin and native Android patterns.
3. Run `./gradlew assembleDebug lintDebug` and the relevant instrumentation tests.
4. Describe the change and how you tested it in your pull request.

Keep interactions accessible and preserve local storage behavior. Prefer Android platform features and existing dependencies over introducing new libraries.
