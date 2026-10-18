# Playback and release incident runbook

## Triage and containment

1. Record the installed channel/version/build, device/API/ABI, source, reproduction
   steps and whether the failure affects a local file, one provider or every source.
2. Check [recent workflow runs](https://github.com/NatuneGroup/ArchiveTune/actions)
   and [published releases](https://github.com/NatuneGroup/ArchiveTune/releases).
   Match the installed build to the actual commit. Do not substitute an older green run.
3. For a bad candidate, stop promotion/publication and keep Stable unchanged.
   Do not rotate the signing key, publish another Canary, disable integrity checks,
   downgrade users' version codes or rewrite branch history as a recovery shortcut.
4. Open/link an issue with sanitized evidence, affected version/source and severity.
   A green APK build or automatically closed issue is not incident resolution.

## Gather safe evidence

Use the app's diagnostic export and capture the failure time, resolver tier/status,
queue transition, decoder and cache state. Preserve the original privately; publish
only redacted extracts. Never upload cookies, authorization headers, provider URLs,
account identifiers, private media, databases or signing material.

- A single-provider failure: inspect its login validity, bounded timeout, rate-limit
  and cooldown state before changing source order or retry policy.
- A YouTube login failure: follow [source-login boundaries](../source-logins.md).
  Do not stage credentials in the global playback client or clear unrelated cookies.
- A second/third-track stall: capture transition diagnostics and test ordinary
  playback separately from crossfade/automix, Cast and output-route changes.
- A local-file failure: use an owned synthetic fixture; distinguish decoder, seek,
  permission and source-selection failures from remote-provider errors.
- Listen Together: distinguish JSON/protobuf host restrictions and stale connection
  generations; never bypass server hosting restrictions.

## Reproduce, fix and verify

Use [Android QA](../android-qa.md) and the pinned toolchain on an authorized host.
Add a focused regression only when it exercises the failing behavior. Obtain the
separate exact-candidate build/lint and unit-test gates; repeat the actual interaction.
Preserve caller cancellation, playback position, personal credentials and source policy.

If verification is blocked by a suspended host, missing toolchain or unavailable
provider/account, record the blocker. Do not label the fix verified or close the incident.

## Release impact and recovery

Follow the [release procedure](../claude/RELEASES.md). After publication, verify the
successful publish run, tag/commit, APK variants/signature and the matching
`nightly.json` or `stable.json` update-feed asset. Then reproduce the original symptom
on that installed build. Compare fresh sanitized reports and issue activity with
the previous release; there is no claim of an existing production telemetry dashboard.

For recovery, prefer a reviewed forward fix with a newer version code and the
historical signing identity. Pause promotion while uncertain. Restoring an earlier
implementation still requires migration review, a new build and separate release gates.

Record the cause, fix commit, verification evidence, affected releases and prevention
work in the issue. Update this runbook only when the incident exposed a missing step.
