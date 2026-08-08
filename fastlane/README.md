fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android deploy

```sh
[bundle exec] fastlane android deploy
```

Upload the current release AAB to production. Pre-req: the app listing must already exist in Play Console (created manually — the Play Developer API can't create a brand-new app listing) and Data Safety + content rating questionnaires must be completed there first.

### android promote_to_open_testing

```sh
[bundle exec] fastlane android promote_to_open_testing
```

Push current AAB to Open Testing (beta) track. Pre-req: Play Console > Testing > Open testing must be enabled for this app.

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
