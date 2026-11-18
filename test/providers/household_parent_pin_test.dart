import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flauncher/flauncher_channel.dart';
import 'package:flauncher/providers/household_parent_pin.dart';
import 'package:flauncher/providers/parent_pin_hash.dart';
import 'package:flauncher/providers/settings_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// The household's side as the Java side reports it (a version, an older build's hash, or its unsalted form), what
/// this TV gave it, and what the Java side says of Home Assistant's parent PINs.
class _FakeChannel extends FLauncherChannel {
  bool member = true;
  // The household's entry can't be read just now
  bool unreadable = false;
  String state = "none";
  String? version;
  String? hash;
  String? legacy;
  final List<String?> given = [];
  bool haKept = false;
  bool haManaged = false;
  // The TV's own PIN can be checked with no other TV (a hash of it is kept here)
  bool ownKnown = true;

  @override
  Future<Map<dynamic, dynamic>?> getHaParentPins() async =>
      {"kept": haKept, "managed": haManaged, "ownKnown": ownKnown};

  @override
  Future<Map<dynamic, dynamic>?> getHouseholdParentPin() async {
    if (unreadable) return null;
    if (!member) return {"state": "out"};
    return {
      "state": state,
      if (version != null) "version": version,
      if (hash != null) "hash": hash,
      if (legacy != null) "legacy": legacy,
    };
  }

  @override
  Future<bool> publishHouseholdParentPin(String? version) async {
    if (!member) return false;
    given.add(version);
    state = version == null ? "removed" : "live";
    this.version = version;
    hash = null;
    legacy = null;
    return true;
  }
}

void main() {
  // Cheap hashes for the test (the cost is part of the stored value)
  final a = ParentPinHash.hash("2468", salt: List.filled(16, 1), cost: 2);
  final b = ParentPinHash.hash("1357", salt: List.filled(16, 2), cost: 2);
  final va = ParentPinHash.tagOf(a)!;
  final vb = ParentPinHash.tagOf(b)!;

  group("the rule", () {
    ParentPinStep decide({String? household, bool removed = false, String? local, String? known}) =>
        HouseholdParentPin.decide(household: household, householdRemoved: removed, local: local, known: known);

    test("a TV without a Parent PIN takes the household's", () {
      expect(decide(household: va), ParentPinStep.take);
      expect(decide(), ParentPinStep.none);
    });

    test("a TV with its own, different one asks before taking the household's", () {
      expect(decide(household: va, local: vb), ParentPinStep.ask);
      expect(decide(household: va, local: va), ParentPinStep.none);
    });

    test("the first TV with one gives it to a household that has none", () {
      expect(decide(local: va), ParentPinStep.give);
      expect(decide(local: va, known: va), ParentPinStep.give);
    });

    test("changed or removed here: it goes out", () {
      expect(decide(household: va, local: vb, known: va), ParentPinStep.give);
      expect(decide(household: va, local: null, known: va), ParentPinStep.give);
    });

    test("changed or removed on another TV: this TV follows", () {
      expect(decide(household: vb, local: va, known: va), ParentPinStep.take);
      expect(decide(removed: true, local: va, known: va), ParentPinStep.remove);
      expect(decide(removed: true, local: null, known: ""), ParentPinStep.none);
      // Removed, then set again elsewhere
      expect(decide(household: vb, local: null, known: ""), ParentPinStep.take);
    });
  });

  group("rounds", () {
    late SharedPreferences prefs;
    late SettingsService settings;
    late _FakeChannel channel;
    late HouseholdParentPin sync;

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      prefs = await SharedPreferences.getInstance();
      settings = SettingsService(prefs);
      channel = _FakeChannel();
      sync = HouseholdParentPin(channel, prefs, settings);
    });

    test("no Home Assistant, no household: the TV's own PIN, checked here", () async {
      channel.member = false;
      await settings.setParentPin("2468");
      await sync.tick();
      expect(await settings.checkParentPin("2468"), isTrue);
      expect(await settings.checkParentPin("1357"), isFalse);
      expect(channel.given, isEmpty);
    });

    test("what goes to the household is the version, never the hash", () async {
      await settings.setParentPin("2468");
      await sync.tick();
      final version = channel.given.single!;
      expect(ParentPinHash.isTag(version), isTrue);
      expect(version, settings.parentPinVersion);
      expect(version, isNot(settings.parentPinHash));
      // A version verifies no PIN
      expect(ParentPinHash.verify("2468", version), isFalse);
    });

    test("joined without a Parent PIN: follows the household's, with no hash until it is typed here", () async {
      channel
        ..state = "live"
        ..version = va;
      await sync.tick();
      expect(settings.parentPinHash, isNull);
      expect(settings.parentPinVersion, va);
      expect(settings.hasParentPin, isTrue);
      expect(channel.given, isEmpty);
      // The Java side asks the other TVs; none reached and never typed here: not taken, and the pad says why
      settings.pinChecker = (pin) async => PinNotChecked.otherTvs;
      await expectLater(Future.sync(() => settings.checkParentPin("2468")),
          throwsA(isA<PinNotChecked>().having((e) => e.reason, "reason", PinNotChecked.otherTvs)));
      // A peer said yes (the Java side keeps it): yes from then on
      settings.pinChecker = (pin) async => pin == "2468" ? "yes" : "no";
      expect(await settings.checkParentPin("2468"), isTrue);
      expect(await settings.checkParentPin("1357"), isFalse);
    });

    test("an older build's entry with the hash: taken with its version", () async {
      channel
        ..state = "live"
        ..version = va
        ..hash = a;
      await sync.tick();
      expect(settings.parentPinHash, a);
      expect(settings.parentPinVersion, va);
      expect(settings.verifyParentPin("2468"), isTrue);
    });

    test("an old build's unsalted entry is worked out, kept salted, and its version goes out in its place", () async {
      channel
        ..state = "live"
        ..legacy = sha256.convert(utf8.encode("ltv-parent-pin:2468")).toString();
      await sync.tick();
      expect(settings.verifyParentPin("2468"), isTrue);
      expect(settings.parentPinHash, startsWith(r"v2$10000$"));
      expect(channel.given.single, settings.parentPinVersion);
    });

    test("the household's PIN changed on another TV: only the new one", () async {
      await settings.setParentPin("2468");
      await sync.tick();
      final mine = channel.given.single;
      expect(mine, settings.parentPinVersion);
      channel.version = vb;
      await sync.tick();
      expect(settings.parentPinVersion, vb);
      // The old one's hash is gone: it no longer works here
      expect(settings.parentPinHash, isNull);
      expect(settings.verifyParentPin("2468"), isFalse);
      expect(channel.given, hasLength(1));
    });

    test("joined with its own: kept until the household's is entered here", () async {
      await settings.setParentPinHash(b);
      channel
        ..state = "live"
        ..version = va;
      await sync.tick();
      expect(settings.parentPinHash, b);
      expect(settings.householdParentPinOffered, va);
      // Changing this TV's own meanwhile doesn't reach the household
      await settings.setParentPin("9999");
      await sync.tick();
      expect(channel.given, isEmpty);
      expect(settings.verifyParentPin("9999"), isTrue);
      // The Java side checks the household's PIN with the other TVs
      settings.householdPinChecker = (pin) async => pin == "2468" ? "yes" : "no";
      expect(await settings.checkHouseholdParentPin("1357"), isFalse);
      expect(await settings.checkHouseholdParentPin("2468"), isTrue);
      await settings.takeHouseholdParentPin("2468");
      await sync.tick();
      expect(settings.parentPinVersion, va);
      expect(settings.verifyParentPin("2468"), isTrue);
      expect(settings.verifyParentPin("9999"), isFalse);
      expect(settings.householdParentPinOffered, isNull);
      expect(channel.given, isEmpty);
      // From now on a change here goes out
      await settings.setParentPin("1111");
      await sync.tick();
      expect(channel.given.single, settings.parentPinVersion);
    });

    test("the household's PIN can't be checked with no other TV reached: not taken", () async {
      await settings.setParentPin("9999");
      channel
        ..state = "live"
        ..version = va;
      await sync.tick();
      settings.householdPinChecker = (pin) async => PinNotChecked.otherTvs;
      await expectLater(Future.sync(() => settings.checkHouseholdParentPin("2468")), throwsA(isA<PinNotChecked>()));
      expect(settings.householdParentPinOffered, va);
    });

    test("removed here goes out; removed elsewhere is removed here", () async {
      channel
        ..state = "live"
        ..version = va;
      await sync.tick();
      await settings.setParentPin(null);
      await sync.tick();
      expect(channel.given, [null]);
      expect(channel.state, "removed");

      channel
        ..state = "live"
        ..version = vb;
      await sync.tick();
      expect(settings.parentPinVersion, vb);
      channel
        ..state = "removed"
        ..version = null;
      await sync.tick();
      expect(settings.hasParentPin, isFalse);
    });

    test("removed from the household: a PIN typed here stays, and what it knew is forgotten", () async {
      await settings.setParentPin("2468");
      await sync.tick();
      channel.member = false;
      await sync.tick();
      expect(prefs.getString(HouseholdParentPin.knownKey), isNull);
      expect(settings.verifyParentPin("2468"), isTrue);
      expect(settings.hasParentPin, isTrue);
    });

    test("removed from the household following a PIN never typed here: it goes, so the TV isn't locked", () async {
      channel
        ..state = "live"
        ..version = va;
      await sync.tick();
      channel
        ..member = false
        ..ownKnown = false;
      await sync.tick();
      expect(settings.hasParentPin, isFalse);
    });

    test("the household can't be read just now: nothing changes", () async {
      await settings.setParentPin("2468");
      await sync.tick();
      final known = prefs.getString(HouseholdParentPin.knownKey);
      expect(known, settings.parentPinVersion);
      channel.unreadable = true;
      await settings.setParentPin("1357");
      await sync.tick();
      expect(prefs.getString(HouseholdParentPin.knownKey), known);
      // Read again: the change made here meanwhile goes out (it isn't taken for a TV that just joined)
      channel.unreadable = false;
      await sync.tick();
      expect(channel.given.last, settings.parentPinVersion);
      expect(settings.householdParentPinOffered, isNull);
    });

    test("what an older build remembered as the hash is remembered as its version", () async {
      await settings.setParentPinHash(a);
      await prefs.setString(HouseholdParentPin.knownKey, a);
      channel
        ..state = "live"
        ..version = va;
      await sync.tick();
      expect(prefs.getString(HouseholdParentPin.knownKey), va);
      expect(channel.given, isEmpty);
    });

    test("paired with PINs in Home Assistant: its PINs only, checked on the Java side; the household's isn't synced",
        () async {
      await settings.setParentPin("9999");
      final asked = <String>[];
      settings.pinChecker = (pin) async {
        asked.add(pin);
        return pin == "2468" ? "yes" : "no";
      };
      channel
        ..state = "live"
        ..version = vb
        ..haKept = true
        ..haManaged = true;
      await sync.tick();
      expect(settings.haParentPinsManaged, isTrue);
      expect(await settings.checkParentPin("2468"), isTrue);
      expect(await settings.checkParentPin("9999"), isFalse);
      expect(asked, ["2468", "9999"]);
      expect(settings.householdParentPinOffered, isNull);
      expect(channel.given, isEmpty);
      // No hash of Home Assistant's is kept on this side
      expect(prefs.getKeys().where((k) => k.contains("ha_parent_pins") && prefs.get(k) is! bool), isEmpty);

      // Offline and not learned here: not taken, and the pad says Home Assistant wasn't reached
      settings.pinChecker = (pin) async => PinNotChecked.homeAssistant;
      await expectLater(Future.sync(() => settings.checkParentPin("1357")),
          throwsA(isA<PinNotChecked>().having((e) => e.reason, "reason", PinNotChecked.homeAssistant)));
    });

    test("Home Assistant has no PINs any more, or the TV is unpaired: learned ones stay, no longer managed", () async {
      settings.pinChecker = (pin) async => pin == "2468" ? "yes" : "no";
      channel
        ..haKept = true
        ..haManaged = true;
      await sync.tick();
      channel.haManaged = false;
      await sync.tick();
      expect(settings.haParentPinsManaged, isFalse);
      expect(settings.haParentPinsKept, isTrue);
      expect(await settings.checkParentPin("2468"), isTrue);

      // None learned (the Java side keeps none): its own or the household's again
      channel.haKept = false;
      channel
        ..state = "live"
        ..version = va;
      await sync.tick();
      expect(settings.haParentPinsKept, isFalse);
      expect(settings.parentPinVersion, va);

      // A PIN of the TV's own set here replaces Home Assistant's
      channel
        ..haKept = true
        ..member = false;
      await sync.tick();
      await settings.setParentPin("1111");
      expect(settings.haParentPinsKept, isFalse);
      expect(settings.verifyParentPin("1111"), isTrue);
    });
  });
}
