# Hardened components

Change them in place, never rewrite them.

The parts below were reviewed, attacked with probe tests, fixed, and covered by regression tests (2026-11). Each one
handles edge cases that aren't obvious from a quick read. Change them with small edits that keep their tests passing.
Don't replace one with a "cleaner" version: if a redesign is truly needed, port every edge case and its test first,
and say so in the change. When another part gets the same review-probe-fix-test treatment, add it here.

| File | What it handles (tests in `android/app/src/test/java/com/thesiegs/hearth/`) |
|---|---|
| TV `Allowance.java` (`timeUpGroup`, `heldApps`, `lockedNow`) | A lock holds every app however it's opened; a next lock whose time has come holds without Home Assistant (`HeldAppsTest`) |
| TV `LauncherAccessibilityService.java` `holdRunningApp` | The app in front is re-checked periodically with Home Assistant too, so apps already open, or relaunched past the hold cap, go home |
| TV `HaStatusReporter.java` + `hub.py` `_tell_failing_tv_checks` | The TV reports its accessibility service; 5 minutes off is told to the adults once, cleared when back (`test_qc_fixes`) |
| TV `HaAllowance.java` `staleCountdown` | With no word from Home Assistant for 10 minutes, each group's minutes are counted down by the time in front since its last word. HearthTube's time counts against daily time, and HearthTube's minutes come down with what other apps take of a group holding it (`HaStaleCountdownTest`, `SeamsStaleCountdownTest`) |
| TV `HaAllowance.java` `profilesFromDocument`, `carriedOver`, `valuesOf` | HearthTube isn't held by daily time when its `except` lists it. Past midnight a lock with no end and a next lock the TV started itself still hold (with when Home Assistant last spoke). A lifted bedtime's end is the next lock, ending at the schedule's wake time. HearthTube is locked exactly when other apps are (`lockedNow`). Minutes used come from the document's `time` (`SeamsDocumentTest`, `SeamsOfflineLocksTest`, `HaAllowanceCarryTest`, `LockInForceTest`) |
| TV `HaAllowance.java` `carriedOver` new day, `newDayGroups` | A new day with no word from Home Assistant starts each group from `tomorrow`'s minutes (an older Home Assistant: today's base minutes from `time`), counted down by staleCountdown from midnight; a group with none is held at once. Once the night's lock is over, `tomorrow`'s first lock (School time) holds, only one that names its end. Nothing known: no minutes, as before (`TvStateDocumentFixtureTest`) |
| TV `HaAllowance.java` `liftsEnded` | An allow or Unlimited ending with Home Assistant away (3 minutes past `until`, no word since), or a category bonus whose minutes the apps have used since the word, holds the group again with `minutes_after` plus the lift's time since the word (not counted by Home Assistant), so it's never held sooner than Home Assistant would. Its apps count in daily time again, and daily time gets the same time back. HearthTube's minutes follow (`TvStateDocumentFixtureTest`) |
| State document seam (TV and Home Assistant) | One fixture, `android/app/src/test/resources/contract_fixtures/tv_state_document.json` here, a byte-for-byte copy of Hearth's `ha_tests/contract_fixtures/tv_state_document.json`, at six moments (evening with tomorrow, bedtime, bedtime lifted, a pause, YouTube allowed by a bonus, Video allowed until 6 PM): Home Assistant must still send what it says (Hearth's `test_client_contract`) and the TV must read it as a family expects (`TvStateDocumentFixtureTest`), and its whole `document` must carry the top-level keys Hearth's `docs/contracts/hearth-tv.md` lists (`TvStateDocumentContractTest`). Change both repositories' copies and both tests together |

The Home Assistant integration's hardened parts (`counting.py`, `enforce.py`, `household.py`, the other side of the
seams above, and more) are listed in [Hearth's own table](https://github.com/theSiegs/Hearth/blob/master/docs/hardened-components.md).

Run `flutter test` and `android/gradlew :app:testDebugUnitTest` (in `android/`) before every push and read the result first.
