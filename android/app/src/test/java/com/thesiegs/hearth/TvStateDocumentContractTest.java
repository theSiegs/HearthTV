package com.thesiegs.hearth;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The Hearth TV contract (Hearth's docs/contracts/hearth-tv.md, "The state document"): the shared fixture's "document"
 * carries exactly the top-level keys the page lists, at the page's contract version. Home Assistant's side is
 * ha_tests/test_hearth_tv_contract.py in Hearth, which reads the list out of the page itself. A change to the contract
 * needs the page, both tests and both copies of the fixture changed together (the copies must stay identical).
 */
public class TvStateDocumentContractTest {
    // The page's marked block: contract, always, optional. Keep in step with docs/contracts/hearth-tv.md in Hearth
    static final int CONTRACT = 3;
    static final List<String> ALWAYS = Arrays.asList("profiles", "youtube_blocks", "commands", "people", "version", "contract");
    static final List<String> OPTIONAL = Arrays.asList(
            "adult_phones", "notices", "parent_pins_version", "parent_pins", "client_settings");

    static final String FIXTURE = "src/test/resources/contract_fixtures/tv_state_document.json";

    private static JSONObject document() throws Exception {
        return new JSONObject(PinFakes.file(FIXTURE)).getJSONObject("document");
    }

    @Test
    public void theFixtureCarriesExactlyThePageSKeys() throws Exception {
        TreeSet<String> want = new TreeSet<>(ALWAYS);
        want.addAll(OPTIONAL);
        TreeSet<String> got = new TreeSet<>();
        for (Iterator<String> keys = document().keys(); keys.hasNext(); ) got.add(keys.next());
        assertEquals(want, got);
    }

    @Test
    public void theFixtureIsAtThePageSContract() throws Exception {
        assertEquals(CONTRACT, document().getInt("contract"));
    }

    @Test
    public void theTvReadsTheFixtureSDocument() throws Exception {
        JSONObject state = document();
        // The keys the TV reads come through its own readers: profiles by key, the version as given
        JSONObject profiles = HaAllowance.profilesFromDocument(state);
        assertEquals("person.sam", state.getJSONObject("people").getString("user:10"));
        assertEquals(true, profiles.has("user:10"));
        assertEquals("fedcba9876543210", state.getString("version"));
    }
}
