# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

This is a Gerrit plugin. It cannot be built standalone — it must live inside the Gerrit source tree at `gerrit/plugins/reviewtarget/`. The CI workflows in `.github/workflows/` check out Gerrit and move the plugin into place before building.

```
server/      — Java backend (REST endpoints, Guice modules, business logic)
ui/          — TypeScript/Lit frontend (plugin.ts entry point, dialog, API layer)
test-server/ — Java unit/integration tests (run via Bazel inside Gerrit tree)
BUILD        — Bazel build file; defines gerrit_plugin(), junit_tests(), ui bundle
GERRIT_VERSION — Pinned Gerrit release tag used by CI (e.g. v3.13.6)
```

## Build and test commands

All commands must be run from the **Gerrit root** (`/Users/martin/src/gerrit/`).

```sh
# Build the plugin JAR
bazel build //plugins/reviewtarget:reviewtarget

# Run Java tests
bazel test //plugins/reviewtarget:reviewtarget_tests --test_output=errors

# Run UI tests (requires Playwright/Chromium)
bazel test //plugins/reviewtarget/ui:web_test_runner

# Build the full Gerrit release WAR (includes plugin)
bazel build release
```

The Gerrit tree needs the plugin registered in `tools/bzl/plugins.bzl`:
```python
CUSTOM_PLUGINS = [
    "reviewtarget",
]
```

## Local test server

From the Gerrit root:
```sh
./update-test-server   # builds and re-initialises the dev server at ~/gerrit/
~/gerrit/bin/gerrit.sh start
~/gerrit/bin/gerrit.sh stop
```

After `gerrit init`, run `gerrit reindex` with the server running to get correct submit requirement evaluation — the offline init phase does not load plugins, so `has:selected_reviewtarget` evaluates as error until an online reindex.

## Architecture

The plugin manages "review branches": Gerrit changes whose commit message carries `Review-Target` and `Review-Files` footer lines. These footers specify which git ref (tag/branch) to review and which file patterns to include. The plugin computes which files would be added/updated/removed relative to the review target and can create new patchsets accordingly.

### REST API

Single endpoint `/changes/{id}/follow`:
- `GET` (`GetFollow.java`): returns current review target, review files, follow-branch version, and validity. Returns early (omitting `review_target`/`review_files`) when `valid_review_target` is false.
- `POST` (`PostFollow.java`): with `do_update: false` previews path changes; with `do_update: true` creates a new patchset. The two response types have different fields — GET returns target/files info, POST returns path change lists.

### Server classes

- `UpdateTree` — orchestrates all git operations: resolves refs, rebases when necessary, rewrites the commit tree. Central to both GET and POST.
- `UpdateUtil` — lower-level git helpers: reads/writes commit footers, resolves refs, creates patchsets.
- `ReviewFilter` — gitignore-style matching for `Review-Files` patterns.
- `FollowPreconditions` — permission and state checks shared by GET and POST.
- `HasReviewTargetOperand` — registers `has:selected_reviewtarget` as a Gerrit query predicate for use in submit requirements.

### UI

`ui/plugin.ts` is the entry point. Key state variables:
- `currentFollowInfo` — the `FollowInfo` from the most recent `SHOW_CHANGE` GET; used to initialise the dialog without a second GET.
- `knownFollowVersion` — the follow-branch version at page load; the polling publisher compares against this to detect branch advances.
- `lastManagedChangeId` — prevents `SHOW_REVISION_ACTIONS` from acting on a different change.

Flow: `SHOW_CHANGE` fires → GET → stores info → `applyActions()` adds the Select button. `SHOW_REVISION_ACTIONS` fires after every `gr-change-actions.reload()` and calls `applyActions()` again to restore the button (Gerrit resets plugin-added buttons on reload). The tap listener opens `SelectReviewTargetDialog` using the stored `currentFollowInfo`; the dialog's `willUpdate()` immediately calls `loadPaths()` which does a fresh POST with `do_update: false` to compute the current path diff.

**Dialog initialisation**: `initialize(info)` and property assignments (`plugin`, `change`, `popupApi`) happen synchronously before `appendContent()` connects the element; Lit batches them into one microtask update so all properties are set when `willUpdate()` fires.

## CI

`.github/workflows/build.yml` — runs on every branch push; builds, tests, uploads JAR artifact.  
`.github/workflows/release.yml` — runs on `v*` tags; same steps plus creates a GitHub Release.

Both workflows check out Gerrit at the version in `GERRIT_VERSION`, patch `tools/bzl/plugins.bzl` via Python, and build inside that tree. Only `modules/jgit` and `modules/java-prettify` submodules are initialised (other plugin submodules use Gerrit-internal URLs that are inaccessible externally).
