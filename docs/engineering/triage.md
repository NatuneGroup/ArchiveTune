# Issue triage and error follow-up

The repository's issue forms are the intake path. Use the fork's actual installed
Stable/Nightly version and revision, not an unrelated upstream APK.

`issue_labels.yml` creates only missing labels from `.github/labels.json` and
adds `needs-triage` plus the form's affected-area label. It never changes existing
label definitions, closes issues, assigns priority from keywords or executes issue
text. Editing a triaged issue does not restore a removed triage label.

## Maintainer review

1. Reproduce on the reported revision, or document precisely why evidence is
   unavailable. Stable-only security/data-loss/crash reports remain valid intake;
   asking someone to install Nightly is not a prerequisite for private disclosure.
2. Confirm the affected area and choose one `priority:*` label based on impact.
   Remove `needs-triage` only after that review. Use `blocked` with a concrete
   missing dependency/evidence, not as a substitute for ownership.
3. For crashes/ANRs, use a sanitized exception type and first relevant application
   frame to find related issues. Do not copy credentials, account IDs, private
   URLs, databases or complete raw log archives into a public issue.
4. Link the fix PR, meaningful regression test, exact-head build/test results and
   release tag back to the issue. Follow the [incident runbook](incident-runbook.md)
   for user-facing outages or bad release artifacts.
5. Review `needs-triage`, `blocked` and high-priority issues weekly. Do not close
   old issues merely to improve a metric or label every issue "critical".

This is a human-reviewed error-to-fix path, not remote crash collection or product
analytics. The app does not upload diagnostics through this workflow. Activating
the workflow requires publishing it to the default branch; source files alone do
not prove that existing issues have been triaged.
