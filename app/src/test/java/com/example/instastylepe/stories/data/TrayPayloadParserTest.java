package com.example.instastylepe.stories.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.instastylepe.stories.model.Story;
import com.example.instastylepe.stories.model.StoryCircle;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Covers the two custom key-value schemas a marketer can author a tray in, and the ways a
 * hand-typed payload can be wrong.
 *
 * <p>Worth having in a demo project: the parser is the seam between the CleverTap dashboard and
 * the app, and it is the one place where a marketer's typo lands. These tests are the evidence
 * that a typo costs one circle rather than the whole screen.</p>
 */
public class TrayPayloadParserTest {

    private static final String UNIT_ID = "1234567_20260828";

    // --------------------------------------------------- schema A: one JSON key-value

    @Test
    public void parsesSingleJsonKeyValue() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("st_tray", "{\"circles\":["
                + "{\"id\":\"sale\",\"name\":\"Summer Sale\",\"order\":2,\"ring\":\"#F77737\","
                + "\"stories\":[{\"id\":\"s1\",\"image\":\"https://i/1.jpg\",\"duration\":6,"
                + "\"caption\":\"Flat 50%\",\"deeplink\":\"instastyle://sale\",\"likes\":900}]},"
                + "{\"id\":\"new\",\"name\":\"New\",\"order\":1,"
                + "\"stories\":[{\"image\":\"https://i/2.jpg\"},{\"image\":\"https://i/3.jpg\"}]}"
                + "]}");

        List<StoryCircle> circles = TrayPayloadParser.parse(kv, UNIT_ID);

        assertEquals(2, circles.size());
        // Sorted by the marketer's order field, not by payload order.
        assertEquals("new", circles.get(0).id);
        assertEquals("sale", circles.get(1).id);

        StoryCircle sale = circles.get(1);
        assertEquals("Summer Sale", sale.name);
        assertEquals("#F77737", sale.ringColor);
        assertEquals(UNIT_ID, sale.unitId);
        assertEquals("1234567", sale.campaignId);

        Story story = sale.storyAt(0);
        assertNotNull(story);
        assertEquals("s1", story.id);
        assertEquals(6, story.durationSeconds);
        assertEquals("Flat 50%", story.caption);
        assertEquals("instastyle://sale", story.deeplink);
        assertEquals(900, story.baseLikeCount);
        assertTrue(story.likeEnabled);
        assertTrue(story.shareEnabled);
    }

    @Test
    public void generatesStoryIdsWhenMarketerOmitsThem() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("st_tray",
                "{\"circles\":[{\"id\":\"c\",\"stories\":[{\"image\":\"https://i/1.jpg\"}]}]}");

        List<StoryCircle> circles = TrayPayloadParser.parse(kv, UNIT_ID);

        assertEquals("c_s1", circles.get(0).storyAt(0).id);
    }

    @Test
    public void acceptsBareArrayUnderAliasKey() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("stories", "[{\"id\":\"x\",\"stories\":[{\"img\":\"https://i/1.jpg\"}]}]");

        assertEquals(1, TrayPayloadParser.parse(kv, UNIT_ID).size());
    }

    // ------------------------------------------------ schema B: flat indexed key-values

    @Test
    public void parsesFlatIndexedKeyValues() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("c1_id", "diwali");
        kv.put("c1_name", "Diwali Edit");
        kv.put("c1_ring", "E1306C");
        kv.put("c1_order", "2");
        kv.put("c1_s1_img", "https://i/d1.jpg");
        kv.put("c1_s1_dur", "7");
        kv.put("c1_s1_cap", "Festive drop");
        kv.put("c1_s1_link", "instastyle://diwali");
        kv.put("c1_s2_image", "https://i/d2.jpg");
        kv.put("c1_s2_duration", "3");
        kv.put("c1_s2_share", "false");
        kv.put("circle_2_id", "clearance");
        kv.put("circle_2_order", "1");
        kv.put("circle_2_story_1_img", "https://i/c1.jpg");
        kv.put("unrelated_key", "ignored");

        List<StoryCircle> circles = TrayPayloadParser.parse(kv, UNIT_ID);

        assertEquals(2, circles.size());
        assertEquals("clearance", circles.get(0).id);

        StoryCircle diwali = circles.get(1);
        assertEquals("Diwali Edit", diwali.name);
        assertEquals("E1306C", diwali.ringColor);
        assertEquals(2, diwali.storyCount());
        assertEquals(7, diwali.storyAt(0).durationSeconds);
        assertEquals("Festive drop", diwali.storyAt(0).caption);
        assertEquals("instastyle://diwali", diwali.storyAt(0).deeplink);
        assertEquals(3, diwali.storyAt(1).durationSeconds);
        assertFalse(diwali.storyAt(1).shareEnabled);
    }

    @Test
    public void flatKeysAreCaseInsensitive() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("C1_ID", "upper");
        kv.put("C1_S1_IMG", "https://i/1.jpg");

        List<StoryCircle> circles = TrayPayloadParser.parse(kv, UNIT_ID);

        assertEquals(1, circles.size());
        assertEquals("upper", circles.get(0).id);
    }

    // --------------------------------------------------------- marketer mistakes

    @Test
    public void dropsCircleWithNoUsableImage() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("c1_id", "good");
        kv.put("c1_s1_img", "https://i/1.jpg");
        kv.put("c2_id", "broken");
        kv.put("c2_name", "Forgot the images");

        List<StoryCircle> circles = TrayPayloadParser.parse(kv, UNIT_ID);

        assertEquals(1, circles.size());
        assertEquals("good", circles.get(0).id);
    }

    @Test
    public void clampsOutOfRangeDurations() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("c1_s1_img", "https://i/1.jpg");
        kv.put("c1_s1_dur", "99");
        kv.put("c1_s2_img", "https://i/2.jpg");
        kv.put("c1_s2_dur", "-4");
        kv.put("c1_s3_img", "https://i/3.jpg");
        kv.put("c1_s3_dur", "not a number");

        StoryCircle circle = TrayPayloadParser.parse(kv, UNIT_ID).get(0);

        assertEquals(30, circle.storyAt(0).durationSeconds);
        assertEquals(Story.DEFAULT_DURATION_SECONDS, circle.storyAt(1).durationSeconds);
        assertEquals(Story.DEFAULT_DURATION_SECONDS, circle.storyAt(2).durationSeconds);
    }

    @Test
    public void malformedJsonYieldsNoCircles() {
        Map<String, String> kv = new LinkedHashMap<>();
        kv.put("st_tray", "{not json at all");

        assertTrue(TrayPayloadParser.parse(kv, UNIT_ID).isEmpty());
    }

    @Test
    public void emptyAndNullPayloadsYieldNoCircles() {
        assertTrue(TrayPayloadParser.parse(null, UNIT_ID).isEmpty());
        assertTrue(TrayPayloadParser.parse(new LinkedHashMap<>(), UNIT_ID).isEmpty());
    }

    // ------------------------------------------------------------- campaign id

    @Test
    public void splitsCampaignIdOffDisplayUnitId() {
        assertEquals("1234567", TrayPayloadParser.campaignIdFromUnitId("1234567_20260828"));
        assertEquals("abc", TrayPayloadParser.campaignIdFromUnitId("abc"));
        assertEquals("", TrayPayloadParser.campaignIdFromUnitId(null));
        assertEquals("", TrayPayloadParser.campaignIdFromUnitId(""));
    }

    // --------------------------------------------------------- the bundled sample

    /**
     * The bundled fallback payload doubles as the copy-paste reference a marketer pastes into the
     * dashboard, so it has to stay parseable.
     */
    @Test
    public void bundledSampleTrayParses() throws Exception {
        File asset = new File("src/main/assets/sample_tray.json");
        if (!asset.exists()) {
            asset = new File("app/src/main/assets/sample_tray.json");
        }
        assertTrue("sample_tray.json not found from " + new File(".").getAbsolutePath(),
                asset.exists());

        String json = new String(Files.readAllBytes(asset.toPath()), StandardCharsets.UTF_8);
        List<StoryCircle> circles = TrayPayloadParser.parseJson(json, UNIT_ID, "1234567");

        assertEquals(4, circles.size());
        assertEquals("new_arrivals", circles.get(0).id);
        assertEquals("loyalty", circles.get(3).id);

        int totalStories = 0;
        for (StoryCircle circle : circles) {
            totalStories += circle.storyCount();
        }
        assertEquals(10, totalStories);
        assertFalse(circles.get(3).storyAt(0).shareEnabled);
    }
}
