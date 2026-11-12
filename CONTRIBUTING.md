# Contributing to Secrets

Thanks for your interest! Secrets is part of the [c3kit](https://github.com/cleancoders/c3kit) family of libraries.

## Getting Started

1. Fork and clone the repo.
2. Make sure you have a JDK 21+ and the [Clojure CLI](https://clojure.org/guides/install_clojure) installed.
3. Run the test suite to confirm a green baseline:

```sh
clj -M:test:spec        # JVM specs (one-shot)
clj -M:test:spec -a     # JVM specs (auto-runner)
```

The specs use fake AWS SDK clients and never contact AWS. Keep it that way: no spec may need AWS credentials or network access.

## Workflow

**All pull requests must be linked to an open issue.** PRs without a linked issue will be closed without review. Open (or find) an issue first, get a thumbs-up from a maintainer that the change is wanted, then start work. This protects everyone's time — yours and ours.

- Open or find an issue describing the bug or proposed change. Wait for maintainer acknowledgement before starting work on anything non-trivial.
- Create a feature branch off `master`.
- **Use TDD.** Write a failing spec first, then the minimum code to make it pass, then refactor.
- Keep commits small and focused. Write descriptive commit messages.
- Update `CHANGES.md` with a one-line entry under a new or current version section.
- If you change the public API, update `README.md` and any relevant docstrings.
- Never put secret values in exception messages, `ex-data`, or logs.

## Code Style

- Idiomatic Clojure: prefer `->` / `->>` threading, keep functions small and focused.
- `!`-suffix for fns that throw; `->type` / `<-type` symmetry for converters.
- Don't column-align values in maps; use single spaces.

## Submitting a PR

1. Confirm your PR is linked to an open issue (use `Closes #N` in the description).
2. Ensure the specs pass and `clj-kondo` and `cljfmt check` are clean.
3. Open a PR against `master`.
4. Describe what changed and why.

## Deployment

Releases run in CI — see the README's "Deployment" section. Publishing from a local checkout is refused; `clj -T:build deploy` aborts outside GitHub Actions.

## Reporting Bugs / Requesting Features

Open an issue. Include:
- Secrets version and Clojure version
- A minimal reproduction
- Expected vs actual behavior
