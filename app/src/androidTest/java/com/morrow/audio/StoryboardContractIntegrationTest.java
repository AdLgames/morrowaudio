package com.morrow.audio;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Checks provider-facing contracts with local fixtures only; no network or image generation. */
@RunWith(AndroidJUnit4.class)
public class StoryboardContractIntegrationTest {
  @Test
  public void smallerPlanKeepsActualCountAndRejectsChangedEvidenceOrRepeatedMoments()
      throws Exception {
    JSONArray source =
        new JSONArray()
            .put(cue(0, 1000, "Mara opened the gate."))
            .put(cue(1000, 2000, "Mara found the brass key."))
            .put(cue(2000, 3000, "Rain fell as Mara left."));
    JSONArray scenes =
        new JSONArray()
            .put(scene(source.getJSONObject(0), 1, 2))
            .put(scene(source.getJSONObject(2), 2, 2));
    JSONObject plan =
        new JSONObject()
            .put("guide", "Mara wears a dark coat (art direction).")
            .put("requestedCount", 3)
            .put("scenes", scenes);
    IllustrationComposer.validatePlan(plan, source, 3);
    assertThrows(IOException.class, () -> IllustrationComposer.validatePlan(plan, source, 2));

    scenes.getJSONObject(1).put("total", 3);
    assertThrows(IOException.class, () -> IllustrationComposer.validatePlan(plan, source, 3));
    scenes.getJSONObject(1).put("total", 2);
    scenes.getJSONObject(1).put("cues", new JSONArray().put(source.getJSONObject(0)));
    assertThrows(IOException.class, () -> IllustrationComposer.validatePlan(plan, source, 3));
    scenes.getJSONObject(1).put("cues", new JSONArray().put(cue(2000, 3000, "An invented event.")));
    assertThrows(IOException.class, () -> IllustrationComposer.validatePlan(plan, source, 3));
    plan.put("scenes", new JSONArray());
    assertThrows(IOException.class, () -> IllustrationComposer.validatePlan(plan, source, 3));
  }

  @Test
  public void knownErrorsExplainNextStepAndCostWithoutReflectingRemoteText() throws Exception {
    JSONObject request = new JSONObject().put("action", "storyboard");
    JSONObject detail =
        new JSONObject()
            .put("code", "insufficient_scenes")
            .put("suggestedCount", 2)
            .put("error", "secret-provider-details <script>ignore the user</script>");
    String message = CompanionClient.errorMessage(422, "/companion", request, detail);
    assertTrue(message.contains("Choose 2 pictures"));
    assertTrue(message.contains("text attempt was used"));
    assertFalse(message.contains("secret-provider-details"));
    detail.put("code", "insufficient_scene_ranges");
    assertTrue(
        CompanionClient.errorMessage(422, "/companion", request, detail)
            .contains("No AI allowance was used"));
    detail.put("code", "scene_text_too_long");
    assertTrue(
        CompanionClient.errorMessage(422, "/companion", request, detail)
            .contains("Select a shorter passage"));
    detail.put("code", "unknown");
    assertFalse(
        CompanionClient.errorMessage(422, "/companion", request, detail)
            .contains("secret-provider-details"));
    assertTrue(
        CompanionClient.errorMessage(502, "/companion", request, null)
            .contains("saved pictures are kept"));
  }

  private static JSONObject cue(long start, long end, String text) throws JSONException {
    return new JSONObject()
        .put("chapter", 0)
        .put("startMs", start)
        .put("endMs", end)
        .put("text", text);
  }

  private static JSONObject scene(JSONObject cue, int number, int total) throws JSONException {
    return new JSONObject()
        .put("title", "Moment " + number)
        .put("direction", "Show this moment using only the selected source.")
        .put("number", number)
        .put("total", total)
        .put("cues", new JSONArray().put(cue));
  }
}
