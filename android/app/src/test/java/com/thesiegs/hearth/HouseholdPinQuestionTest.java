package com.thesiegs.hearth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A TV asking another TV of its household about a Parent PIN, over a real socket (docs/household-sync.md, Parent PIN):
 * the question rides in an exchange, so only between members, and sealed; the other TV answers from what it knows, in
 * the asking TV's own count of wrong tries; and the asking TV learns a PIN that was confirmed.
 */
public class HouseholdPinQuestionTest {
    private static final long T0 = 1_800_000_000_000L;
    private static final String PIN = "2468";
    private static final String WRONG = "1357";

    private HouseholdTvs.Tv den;
    private HouseholdTvs.Tv kitchen;
    /** The Kitchen TV's preferences: it is the one that knows the PIN. */
    private FakeContext kitchenPrefs;
    private String version;

    @Before
    public void twoTvsOfOneHousehold_thePinSetOnTheKitchenTv() throws Exception {
        HaParentPins.sKeys = HouseholdTvs.memoryKeys();
        kitchen = new HouseholdTvs.Tv();
        kitchen.store.create("Kitchen", T0);
        den = new HouseholdTvs.Tv();
        assertTrue(den.store.join(kitchen.store.invite("Kitchen", T0), "Den", T0));
        ask(den, kitchen, null, null, null);
        kitchenPrefs = new FakeContext("com.thesiegs.hearth");
        String hash = ParentPinHash.make(PIN);
        version = ParentPinHash.tagOf(hash);
        kitchenPrefs.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
                .putString("flutter." + HaParentPins.OWN_HASH, hash)
                .putString("flutter." + HaParentPins.OWN_VERSION, version).commit();
    }

    private HouseholdSync.PinDesk kitchenDesk() {
        return (peer, scope, pin) -> HaParentPins.answerPeer(kitchenPrefs, peer, scope, pin, T0 + 500);
    }

    /**
     * One exchange, {@code from} connecting to {@code to}, with a question (or none) and the desk that answers on the
     * accepting side (or none); what crossed the wire is added to {@code wire}.
     */
    private static JSONObject ask(HouseholdTvs.Tv from, HouseholdTvs.Tv to, HouseholdSync.PinQuestion question,
            HouseholdSync.PinDesk desk, ByteArrayOutputStream wire) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            Future<HouseholdSync.Result> accepting = pool.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    HouseholdSync.Accepted opened = HouseholdSync.accept(socket.getInputStream(),
                            socket.getOutputStream(), to.store.tvId(), to.store.householdId());
                    return HouseholdSync.run(opened, to.store, null, "to", T0 + 100, desk);
                }
            });
            try (Socket socket = new Socket(server.getInetAddress(), server.getLocalPort())) {
                socket.setSoTimeout(5000);
                InputStream in = wire == null ? socket.getInputStream() : tapped(socket.getInputStream(), wire);
                OutputStream out = wire == null ? socket.getOutputStream() : tapped(socket.getOutputStream(), wire);
                HouseholdSync.run(in, out, true, from.store, null, "from", T0 + 100, null, question);
            }
            accepting.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return question == null ? null : question.await(0);
    }

    private static InputStream tapped(InputStream in, ByteArrayOutputStream wire) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) wire.write(b);
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0) wire.write(buffer, offset, n);
                return n;
            }
        };
    }

    private static OutputStream tapped(OutputStream out, ByteArrayOutputStream wire) {
        return new FilterOutputStream(out) {
            @Override
            public void write(int b) throws IOException {
                out.write(b);
                wire.write(b);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                out.write(buffer, offset, length);
                wire.write(buffer, offset, length);
            }
        };
    }

    private HouseholdSync.PinQuestion question(String pin) {
        return new HouseholdSync.PinQuestion(HaParentPins.SCOPE_TV + version, pin);
    }

    @Test
    public void theTvThatKnowsThePinSaysYesToTheRightOneAndNoToAWrongOne() throws Exception {
        assertEquals("yes", ask(den, kitchen, question(PIN), kitchenDesk(), null).optString("a"));
        assertEquals("no", ask(den, kitchen, question(WRONG), kitchenDesk(), null).optString("a"));
    }

    @Test
    public void neitherThePinNorTheQuestionIsReadableOnTheWire() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        assertEquals("yes", ask(den, kitchen, question(PIN), kitchenDesk(), wire).optString("a"));
        String seen = new String(wire.toByteArray(), StandardCharsets.ISO_8859_1);
        assertFalse(seen.contains("pin_ask"));
        assertFalse(seen.contains("pin_answer"));
        assertFalse(seen.contains(version));
        assertFalse(seen.contains("\"pin\":\"" + PIN));
    }

    @Test
    public void theAskingTvLearnsWhatWasConfirmed_andThenNeedsNoOtherTv() throws Exception {
        FakeContext denPrefs = new FakeContext("com.thesiegs.hearth");
        denPrefs.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
                .putString("flutter." + HaParentPins.OWN_VERSION, version).commit();
        boolean[] kitchenOn = {true};
        HaParentPins.Asker viaTheKitchen = new HaParentPins.Asker() {
            @Override
            public boolean paired(Context context) {
                return false;
            }

            @Override
            public HaParentPins.Verdict ha(Context context, String pin) {
                return HaParentPins.Verdict.UNKNOWN;
            }

            @Override
            public HaParentPins.PeerAnswer peers(Context context, String scope, String pin) {
                if (!kitchenOn[0]) return HaParentPins.PeerAnswer.UNKNOWN;
                try {
                    return HaParentPins.peerAnswer(
                            ask(den, kitchen, new HouseholdSync.PinQuestion(scope, pin), kitchenDesk(), null));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        // The TVs can't reach each other, and the PIN was never typed on the Den TV: not taken
        kitchenOn[0] = false;
        assertEquals(HaParentPins.Check.NO_TVS, HaParentPins.check(denPrefs, PIN, viaTheKitchen));
        // They can: a wrong one is refused there, the right one confirmed and learned here
        kitchenOn[0] = true;
        assertEquals(HaParentPins.Check.NO, HaParentPins.check(denPrefs, WRONG, viaTheKitchen));
        assertEquals(HaParentPins.Check.YES, HaParentPins.check(denPrefs, PIN, viaTheKitchen));
        // They can't again: the adult still gets in on the Den TV, and a wrong PIN still doesn't
        kitchenOn[0] = false;
        assertEquals(HaParentPins.Check.YES, HaParentPins.check(denPrefs, PIN, viaTheKitchen));
        assertEquals(HaParentPins.Check.NO, HaParentPins.check(denPrefs, WRONG, viaTheKitchen));
        // And the Den TV can now vouch for it to a third
        assertEquals("yes", HaParentPins.answerPeer(denPrefs, "tv-third", HaParentPins.SCOPE_TV + version, PIN, T0)
                .optString("a"));
    }

    @Test
    public void wrongTriesFromOneTvLockThatTvOutThere_theRightPinIncluded() throws Exception {
        JSONObject said = null;
        for (int i = 0; i < ParentPinPolicy.FREE_TRIES; i++) {
            said = ask(den, kitchen, question(WRONG), kitchenDesk(), null);
            assertEquals("no", said.optString("a"));
        }
        assertEquals(30, said.optInt("retry"));
        JSONObject held = ask(den, kitchen, question(PIN), kitchenDesk(), null);
        assertEquals("wait", held.optString("a"));
        assertTrue(held.optInt("retry") > 0);
        assertEquals(HaParentPins.PeerAnswer.Kind.WAIT, HaParentPins.peerAnswer(held).kind);
    }

    @Test
    public void aTvOnABuildThatDoesntAnswer_orWithNothingToCheckAgainst_cantSay() throws Exception {
        // No desk: as a build from before this
        assertEquals("unknown", ask(den, kitchen, question(PIN), null, null).optString("a"));
        // The Den TV knows nothing of the PIN: asked by the Kitchen TV, it can't say, and that's no wrong try
        FakeContext denPrefs = new FakeContext("com.thesiegs.hearth");
        HouseholdSync.PinDesk denDesk = (peer, scope, pin) -> HaParentPins.answerPeer(denPrefs, peer, scope, pin, T0);
        for (int i = 0; i < 8; i++) {
            assertEquals("unknown", ask(kitchen, den, question(WRONG), denDesk, null).optString("a"));
        }
        assertEquals(HaParentPins.PeerAnswer.Kind.UNKNOWN,
                HaParentPins.peerAnswer(ask(kitchen, den, question(PIN), denDesk, null)).kind);
    }

    @Test
    public void anExchangeWithoutAQuestionCarriesNoAnswer_andEntriesStillMove() throws Exception {
        kitchen.store.log().put("look", "alex", "", "blue", T0 + 50);
        assertNull(ask(den, kitchen, null, kitchenDesk(), null));
        assertEquals("blue", den.store.log().get("look", "alex", "").value);
        // With a question too
        kitchen.store.log().put("look", "sam", "", "green", T0 + 60);
        assertEquals("yes", ask(den, kitchen, question(PIN), kitchenDesk(), null).optString("a"));
        assertEquals("green", den.store.log().get("look", "sam", "").value);
    }

    @Test
    public void aTvThatIsNotInTheHouseholdGetsNoAnswer_andNoGuess() throws Exception {
        HouseholdTvs.Tv stranger = new HouseholdTvs.Tv();
        stranger.store.create("Elsewhere", T0);
        HouseholdSync.PinQuestion asked = question(PIN);
        boolean[] deskAsked = {false};
        HouseholdSync.PinDesk desk = (peer, scope, pin) -> {
            deskAsked[0] = true;
            return kitchenDesk().answer(peer, scope, pin);
        };
        try {
            ask(stranger, kitchen, asked, desk, null);
            fail("A TV outside the household took part in an exchange");
        } catch (Exception expected) {
            // Refused before the question was read
        }
        assertNull(asked.await(0));
        assertFalse(deskAsked[0]);
    }

    @Test
    public void aRemovedTvGetsNoAnswer() throws Exception {
        assertTrue(kitchen.store.remove(den.store.tvId(), T0 + 200));
        boolean[] deskAsked = {false};
        HouseholdSync.PinDesk desk = (peer, scope, pin) -> {
            deskAsked[0] = true;
            return kitchenDesk().answer(peer, scope, pin);
        };
        HouseholdSync.PinQuestion asked = question(PIN);
        try {
            ask(den, kitchen, asked, desk, null);
        } catch (Exception toldItWasRemoved) {
            // Either way round, nothing was asked
        }
        assertNull(asked.await(0));
        assertFalse(deskAsked[0]);
    }
}
