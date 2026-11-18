import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';

/// How the TV keeps the Parent PIN: not the PIN, a salted slow hash of it, as `v2$<iterations>$<salt>$<hash>` (hex).
/// A PIN of four digits can't be made unguessable; the salt and the cost only keep the stored value from being read
/// back in an instant, and the lockout after wrong guesses does the rest. So the hash stays on the TVs where the PIN
/// was typed, and what TVs tell each other is only a [version](isTag).
///
/// The old form (an unsalted SHA-256 with a fixed prefix, 64 hex digits) is no longer verified: a four-digit PIN kept
/// that way is the PIN for whoever holds it. A value still stored so is worked out once ([pinOfLegacy]) and kept in
/// the salted form (SettingsService).
class ParentPinHash {
  const ParentPinHash._();

  static const int iterations = 10000;
  static const int _saltBytes = 16;
  static const int _hashBytes = 32;
  static const String _prefix = "v2";
  static const String _tagPrefix = r"v2$0$";

  /// What a stored value looks like (a version too).
  static final RegExp pattern = RegExp(r'^v2\$[0-9]{1,7}\$[0-9a-f]{32}\$[0-9a-f]{64}$');

  /// A new stored value for [pin], with a random salt (or the [salt] given, for tests).
  static String hash(String pin, {List<int>? salt, int cost = iterations}) {
    final s = salt ?? _randomSalt();
    final derived = pbkdf2(utf8.encode(pin), s, cost, _hashBytes);
    return "$_prefix\$$cost\$${_hex(s)}\$${_hex(derived)}";
  }

  /// Whether [pin] is the PIN [stored] was made from; false for anything that isn't a stored value (the old unsalted
  /// form and a version included).
  static bool verify(String pin, String? stored) {
    if (stored == null || !pattern.hasMatch(stored)) return false;
    final parts = stored.split(r"$");
    final cost = int.parse(parts[1]);
    if (cost < 1 || cost > 200000) return false;
    final derived = pbkdf2(utf8.encode(pin), _unhex(parts[2]), cost, _hashBytes);
    return _same(_hex(derived), parts[3]);
  }

  /// A value in the old unsalted form: not a stored value any more, to be replaced ([pinOfLegacy]).
  static bool isLegacy(String? stored) => stored != null && RegExp(r'^[0-9a-f]{64}$').hasMatch(stored);

  /// The PIN an old unsalted value was made from (four digits, as every pad took), or null when it isn't one. This is
  /// the migration for values still stored that way, and why the form went: it takes a moment.
  static String? pinOfLegacy(String? stored) {
    if (!isLegacy(stored)) return null;
    for (var i = 0; i < 10000; i++) {
      final pin = i.toString().padLeft(4, "0");
      if (_same(_legacy(pin), stored!)) return pin;
    }
    return null;
  }

  /// Whether [value] is a version: the stored shape with 0 iterations, so it verifies no PIN on any build. It says
  /// which Parent PIN a TV follows, and is what the household's TVs tell each other in place of the hash.
  static bool isTag(String? value) => value != null && value.startsWith(_tagPrefix) && pattern.hasMatch(value);

  /// The version of a stored salted hash (a version is its own): the same on every TV that holds that hash, and of no
  /// use for guessing the PIN without it (the hash's random salt is hashed in). Null for anything else. The Java
  /// side works out the same (ParentPinHash.tagOf).
  static String? tagOf(String? stored) {
    if (stored == null || !pattern.hasMatch(stored)) return null;
    if (isTag(stored)) return stored;
    final a = sha256.convert(utf8.encode("hearth-parent-pin-version:$stored")).toString();
    final b = sha256.convert(utf8.encode("hearth-parent-pin-version-2:$stored")).toString();
    return "$_tagPrefix${a.substring(0, 32)}\$$b";
  }

  static String _legacy(String pin) => sha256.convert(utf8.encode("ltv-parent-pin:$pin")).toString();

  static Uint8List _randomSalt() {
    final random = Random.secure();
    return Uint8List.fromList(List<int>.generate(_saltBytes, (_) => random.nextInt(256)));
  }

  /// PBKDF2-HMAC-SHA256 (RFC 8018).
  @visibleForTesting
  static Uint8List pbkdf2(List<int> password, List<int> salt, int cost, int length) {
    final hmac = Hmac(sha256, password);
    final out = BytesBuilder();
    for (var block = 1; out.length < length; block++) {
      var u = hmac.convert([...salt, (block >> 24) & 255, (block >> 16) & 255, (block >> 8) & 255, block & 255]).bytes;
      final t = List<int>.from(u);
      for (var i = 1; i < cost; i++) {
        u = hmac.convert(u).bytes;
        for (var j = 0; j < t.length; j++) {
          t[j] ^= u[j];
        }
      }
      out.add(t);
    }
    return Uint8List.fromList(out.toBytes().sublist(0, length));
  }

  static bool _same(String a, String b) {
    if (a.length != b.length) return false;
    var diff = 0;
    for (var i = 0; i < a.length; i++) {
      diff |= a.codeUnitAt(i) ^ b.codeUnitAt(i);
    }
    return diff == 0;
  }

  static String _hex(List<int> bytes) => bytes.map((b) => b.toRadixString(16).padLeft(2, "0")).join();

  static Uint8List _unhex(String hex) =>
      Uint8List.fromList([for (var i = 0; i < hex.length; i += 2) int.parse(hex.substring(i, i + 2), radix: 16)]);
}
