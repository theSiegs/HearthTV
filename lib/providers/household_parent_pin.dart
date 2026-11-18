import 'package:flauncher/flauncher_channel.dart';
import 'package:flauncher/providers/parent_pin_hash.dart';
import 'package:flauncher/providers/settings_service.dart';
import 'package:flauncher/quiet_failure.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// What one round of [HouseholdParentPin] does.
enum ParentPinStep {
  /// Nothing to do: this TV's and the household's agree.
  none,

  /// The household's Parent PIN becomes this TV's.
  take,

  /// The household removed its Parent PIN: this TV's goes too.
  remove,

  /// This TV's (or its removal) goes to the household: it was changed here, past the PIN.
  give,

  /// This TV had its own Parent PIN before it joined, and the household's differs: it's kept until an adult enters
  /// the household's here (Settings > Family > Parent PIN), so joining can't replace it unseen.
  ask,
}

/// The household's Parent PIN (docs/household-sync.md, "Parent PIN"): one for every TV, the newest change winning.
/// What the TVs tell each other is its **version** (ParentPinHash.isTag), never the PIN and no longer its hash: a TV
/// keeps a hash only of a PIN that was typed on it. A TV that follows a version it holds no hash of asks the other
/// TVs when the PIN is typed (the Java side: HaParentPins), and keeps what one of them confirms.
///
/// A TV that joins without a Parent PIN takes the household's. A TV with its own, different one is asked for the
/// household's before it takes it. A change made here (which needs the PIN here) goes out. What this TV last took or
/// gave is remembered, which is how a change here is told from one in the household.
///
/// A household whose entry an older build wrote still holds the hash: a TV takes it with its version (every TV works
/// out the same one from it), so nothing changes until a Parent PIN is next set, which writes a version in its place.
class HouseholdParentPin {
  /// device_: the TV's own, never in a backup. The version this TV last took from or gave to the household.
  static const String knownKey = "device_household_parent_pin_known";

  /// device_: the household's version this TV dropped because the TV that set it was removed before it was confirmed
  /// here: not followed again (a newer one is).
  static const String droppedKey = "device_household_parent_pin_dropped";

  /// What [SettingsService.householdParentPinOffered] holds for a household PIN an old build left unsalted.
  static const String legacyOffer = "legacy";

  final FLauncherChannel _channel;
  final SharedPreferences _prefs;
  final SettingsService _settings;

  HouseholdParentPin(this._channel, this._prefs, this._settings);

  /// The rule, pure: [household] is the household's version (null when it has none), [householdRemoved] when it had
  /// one and removed it; [local] this TV's; [known] what this TV last took or gave ("" for none; null: nothing yet
  /// since it joined).
  static ParentPinStep decide(
      {required String? household, required bool householdRemoved, required String? local, required String? known}) {
    if (known == null) {
      if (local == null) return household != null ? ParentPinStep.take : ParentPinStep.none;
      if (household == null) return ParentPinStep.give;
      return household == local ? ParentPinStep.none : ParentPinStep.ask;
    }
    // Changed here since: it goes out
    if ((local ?? "") != known) return ParentPinStep.give;
    if (household != null) return household == local ? ParentPinStep.none : ParentPinStep.take;
    if (householdRemoved) return local == null ? ParentPinStep.none : ParentPinStep.remove;
    // The household has none (a household older than this kind): this TV's is the first
    return local == null ? ParentPinStep.none : ParentPinStep.give;
  }

  /// One round: first Home Assistant's parent PINs; while the TV uses those, the household's isn't synced (each TV
  /// asks Home Assistant). Outside a household, forgets what it knew (a household joined later starts fresh).
  Future<void> tick() async {
    try {
      final fromHa = await _channel.getHaParentPins();
      if (fromHa != null) {
        await _settings.setHaParentPins(kept: fromHa["kept"] == true, managed: fromHa["managed"] == true);
      }
      if (_settings.haParentPinsKept) {
        await _settings.setHouseholdParentPinOffered(null);
        return;
      }
      final state = await _channel.getHouseholdParentPin();
      // Can't be read just now: nothing changes (what this TV last took or gave is how a change here is told)
      if (state == null) return;
      // The PIN this TV now follows is known here (typed and confirmed): the one put aside before it isn't needed
      if (fromHa != null && fromHa["ownKnown"] == true) await _settings.clearParentPinPrevious();
      if (state["state"] == "out") {
        await _prefs.remove(knownKey);
        await _prefs.remove(droppedKey);
        await _settings.setHouseholdParentPinOffered(null);
        // In no household any more, following a PIN that was only ever typed on other TVs: nobody is left to say
        // what it is, so it goes rather than lock this TV for good. (One typed here for it is kept by the Java side
        // and still works.)
        if (fromHa != null &&
            fromHa["ownKnown"] == false &&
            _settings.parentPinHash == null &&
            _settings.parentPinVersion != null) {
          await _fallBack(fromHa, null);
        }
        return;
      }
      await _migrateKnown();
      final live = state["state"] == "live";
      final entryVersion = live ? state["version"] : null;
      final entryHash = live ? state["hash"] : null;
      // An older build's entry: the hash itself, which this TV may keep (it is in the household's entries anyway)
      final hash = entryHash is String && ParentPinHash.pattern.hasMatch(entryHash) && !ParentPinHash.isTag(entryHash)
          ? entryHash
          : null;
      var household = hash != null
          ? ParentPinHash.tagOf(hash)
          : (entryVersion is String && ParentPinHash.isTag(entryVersion) ? entryVersion : null);
      var known = _prefs.getString(knownKey);
      // An old build's unsalted entry is the PIN for whoever holds it: it's worked out, and replaced
      final legacy = live ? state["legacy"] : null;
      final legacyPin = legacy is String ? ParentPinHash.pinOfLegacy(legacy) : null;
      if (legacyPin != null) {
        if (_settings.parentPinVersion == null) {
          await _settings.setParentPin(legacyPin);
        } else if (known == null && !_settings.verifyParentPin(legacyPin)) {
          // This TV had another PIN of its own when it joined: asked for the household's first, as ever
          await _settings.setHouseholdParentPinOffered(legacyOffer);
          return;
        }
        // This TV holds that PIN, salted: its version goes out in the old entry's place
        household = null;
      }
      var local = _settings.parentPinVersion;
      var dropped = _prefs.getString(droppedKey);
      // The TV that set the household's PIN was removed (or left) before this TV confirmed it: nobody may be left who
      // can say what it is, so this TV doesn't follow it and falls back as a TV removed from the household does. A
      // hash an older build left in the entry can still be checked here, so that one is followed.
      if (live && state["writerGone"] == true && fromHa != null && hash == null && household != null &&
          household != dropped) {
        final confirmed = household == local && (_settings.parentPinHash != null || fromHa["ownKnown"] == true);
        if (!confirmed) {
          if (household == local) {
            await _fallBack(fromHa, household);
            local = _settings.parentPinVersion;
            // Not a change made here: what it fell back to doesn't go out
            known = local ?? "";
            await _setKnown(local);
          }
          await _prefs.setString(droppedKey, household);
          dropped = household;
        }
      }
      if (dropped != null && household != dropped) {
        // The household moved on (a TV still in it set another): followed as usual
        await _prefs.remove(droppedKey);
      } else if (dropped != null) {
        // Still the dropped one: as if the household had this TV's, so only a change made here goes out
        household = local;
      }
      final step = decide(
          household: household, householdRemoved: state["state"] == "removed", local: local, known: known);
      switch (step) {
        case ParentPinStep.none:
          await _setKnown(local);
          await _settings.setHouseholdParentPinOffered(null);
        case ParentPinStep.take:
          if (hash != null) {
            await _settings.setParentPinHash(hash);
          } else {
            await _settings.setParentPinPending(household!);
          }
          await _setKnown(household);
          await _settings.setHouseholdParentPinOffered(null);
        case ParentPinStep.remove:
          await _settings.setParentPin(null);
          await _setKnown(null);
        case ParentPinStep.give:
          if (await _channel.publishHouseholdParentPin(local)) await _setKnown(local);
          await _settings.setHouseholdParentPinOffered(null);
        case ParentPinStep.ask:
          await _settings.setHouseholdParentPinOffered(household);
      }
    } catch (err) {
      noteFailure('household_parent_pin', err);
    }
  }

  /// The PIN this TV follows can't be checked by anyone left (never typed here, and the TV that set it is gone): this
  /// TV falls back to a PIN typed here before (the one it had before it followed this one, else the household's
  /// earlier one it learned from another TV, which the Java side keeps), else it has none of its own. [avoid]: the
  /// version being dropped.
  Future<void> _fallBack(Map<dynamic, dynamic> fromHa, String? avoid) async {
    final previous = _settings.parentPinPrevious;
    if (previous != null && ParentPinHash.tagOf(previous) != avoid) {
      await _settings.setParentPinHash(previous);
      return;
    }
    final learned = fromHa["learnedVersion"];
    if (learned is String &&
        ParentPinHash.isTag(learned) &&
        learned != avoid &&
        learned != _settings.parentPinVersion) {
      await _settings.setParentPinPending(learned);
      return;
    }
    await _settings.setParentPin(null);
  }

  /// What this TV last took or gave was remembered as the hash by older builds: now its version. (An unsalted one
  /// has no version: forgotten as "none", so this TV's own, salted since, goes out.)
  Future<void> _migrateKnown() async {
    final known = _prefs.getString(knownKey);
    if (known == null || known.isEmpty || ParentPinHash.isTag(known)) return;
    await _prefs.setString(knownKey, ParentPinHash.tagOf(known) ?? "");
  }

  /// Null is remembered as "" (known to be none), so a removal here isn't taken for "never synced".
  Future<void> _setKnown(String? version) => _prefs.setString(knownKey, version ?? "");
}
