import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flauncher/providers/parent_pin_hash.dart';
import 'package:flauncher/providers/settings_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  test("a PIN verifies against the value made from it, and a wrong one doesn't", () {
    final stored = ParentPinHash.hash("2468");
    expect(ParentPinHash.verify("2468", stored), isTrue);
    expect(ParentPinHash.verify("2469", stored), isFalse);
    expect(ParentPinHash.verify("", stored), isFalse);
    expect(ParentPinHash.isLegacy(stored), isFalse);
  });

  test("two values for the same PIN differ (a random salt), and both verify", () {
    final a = ParentPinHash.hash("2468");
    final b = ParentPinHash.hash("2468");
    expect(a, isNot(b));
    expect(ParentPinHash.verify("2468", a), isTrue);
    expect(ParentPinHash.verify("2468", b), isTrue);
  });

  test("the stored value looks like v2, the cost, a salt and a hash, and says nothing of the PIN", () {
    final stored = ParentPinHash.hash("2468");
    expect(ParentPinHash.pattern.hasMatch(stored), isTrue);
    expect(stored.startsWith("v2\$${ParentPinHash.iterations}\$"), isTrue);
    // No field of it is the PIN (the hex of a random salt or hash may happen to hold the digits "2468")
    expect(stored.split("\$"), isNot(contains("2468")));
  });

  test("PBKDF2-HMAC-SHA256 matches published test vectors", () {
    String hex(List<int> b) => b.map((x) => x.toRadixString(16).padLeft(2, "0")).join();
    expect(hex(ParentPinHash.pbkdf2(utf8.encode("password"), utf8.encode("salt"), 1, 32)),
        "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b");
    expect(hex(ParentPinHash.pbkdf2(utf8.encode("password"), utf8.encode("salt"), 2, 32)),
        "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43");
  });

  test("the old unsalted form no longer verifies; its PIN is worked out once, to keep it salted", () {
    final legacy = sha256.convert(utf8.encode("ltv-parent-pin:1357")).toString();
    expect(ParentPinHash.isLegacy(legacy), isTrue);
    expect(ParentPinHash.verify("1357", legacy), isFalse);
    expect(ParentPinHash.pinOfLegacy(legacy), "1357");
    expect(ParentPinHash.pinOfLegacy(ParentPinHash.hash("1357", cost: 2)), isNull);
    expect(ParentPinHash.pinOfLegacy(null), isNull);
  });

  test("a version verifies no PIN, and is the same on every TV that holds the hash (and in Java)", () {
    // As Java's ParentPinHashTest has them: "2468", salt 00..0f, a cost of 3, and its version
    const salted =
        r"v2$3$000102030405060708090a0b0c0d0e0f$9a3485228d1d796b879fd845db14495a013a59a0a41ac147f4151299fdaa0f1a";
    const tag =
        r"v2$0$160da7869ef6e3229bb14789f7d6db40$031d4dc76d5acff2a0bfac0d61f77a005b2c025d59cda8cd1f462b711aa32dd1";
    expect(ParentPinHash.verify("2468", salted), isTrue);
    expect(ParentPinHash.tagOf(salted), tag);
    expect(ParentPinHash.tagOf(tag), tag);
    expect(ParentPinHash.isTag(tag), isTrue);
    expect(ParentPinHash.isTag(salted), isFalse);
    expect(ParentPinHash.verify("2468", tag), isFalse);
    // Two hashes of one PIN have different versions (a random salt); an unsalted value has none
    expect(ParentPinHash.tagOf(ParentPinHash.hash("2468", cost: 2)),
        isNot(ParentPinHash.tagOf(ParentPinHash.hash("2468", cost: 2))));
    expect(ParentPinHash.tagOf(sha256.convert(utf8.encode("ltv-parent-pin:2468")).toString()), isNull);
  });

  group("kept by older builds, moved once at start-up", () {
    Future<SettingsService> start(Map<String, Object> stored) async {
      SharedPreferences.setMockInitialValues(stored);
      final settings = SettingsService(await SharedPreferences.getInstance());
      await pumpEventQueue();
      return settings;
    }

    test("an unsalted PIN is kept salted, with its version", () async {
      final settings =
          await start({"device_parent_pin_hash": sha256.convert(utf8.encode("ltv-parent-pin:1357")).toString()});
      expect(settings.parentPinHash, startsWith("v2\$${ParentPinHash.iterations}\$"));
      expect(settings.verifyParentPin("1357"), isTrue);
      expect(settings.parentPinVersion, ParentPinHash.tagOf(settings.parentPinHash));
    });

    test("another TV's version an older build took for a hash is a version with no hash", () async {
      final tag = ParentPinHash.tagOf(ParentPinHash.hash("2468", cost: 2))!;
      final settings = await start({"device_parent_pin_hash": tag});
      expect(settings.parentPinHash, isNull);
      expect(settings.parentPinVersion, tag);
      expect(settings.hasParentPin, isTrue);
    });

    test("a salted hash from before versions gets the version every TV works out", () async {
      final hash = ParentPinHash.hash("2468", cost: 2);
      final settings = await start({"device_parent_pin_hash": hash});
      expect(settings.parentPinHash, hash);
      expect(settings.parentPinVersion, ParentPinHash.tagOf(hash));
    });
  });

  test("anything that isn't a stored value never verifies", () {
    expect(ParentPinHash.verify("1234", null), isFalse);
    expect(ParentPinHash.verify("1234", ""), isFalse);
    expect(ParentPinHash.verify("1234", "1234"), isFalse);
    expect(ParentPinHash.verify("1234", "v2\$0\$00\$00"), isFalse);
    expect(ParentPinHash.verify("1234", "v2\$0\$${"0" * 32}\$${"0" * 64}"), isFalse);
  });
}
