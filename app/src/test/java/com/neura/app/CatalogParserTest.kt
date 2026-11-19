package com.neura.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogParserTest {
    @Test fun preservesRouterModelIdsAndRemovesDuplicates() {
        val models = CatalogParser.parse("openai", JSONObject("""{"data":[{"id":"vendor/model"},{"id":"vendor/model"}]}"""), false)
        assertEquals(1, models.size)
        assertEquals("vendor/model", models.single().id)
        assertEquals(MediaRoute.CHAT, models.single().route)
    }

    @Test fun specializedModelsAreNotSentToChat() {
        val models = CatalogParser.parse("openai", JSONObject("""{"data":[{"id":"text-embedding-3-small"},{"id":"rerank-v3"},{"id":"omni-moderation-latest"}]}"""), false)
        assertTrue(models.all { it.route == MediaRoute.UNSUPPORTED })
    }

    @Test fun usesGeminiDisplayNamesAndGenerationMethods() {
        val models = CatalogParser.parse("gemini", JSONObject("""{"models":[
            {"name":"models/gemini-test","displayName":"Gemini Test","supportedGenerationMethods":["generateContent"]},
            {"name":"models/embed-test","supportedGenerationMethods":["embedContent"]}]}"""), false)
        assertEquals("gemini-test", models[0].id)
        assertEquals("Gemini Test", models[0].name)
        assertEquals(MediaRoute.UNSUPPORTED, models[1].route)
    }

    @Test fun descriptionsDoNotAccidentallyClassifyChatAsTranscription() {
        val model = CatalogParser.parse("openai", JSONObject("""{"data":[{"id":"chat-model","description":"Can discuss transcription and text-to-speech systems","reasoning":false}]}"""), false).single()
        assertEquals(MediaRoute.CHAT, model.route)
        assertFalse(model.reasoning)
    }

    @Test fun imagePricingDoesNotImplyFreeGeneration() {
        val model = CatalogParser.parse("openrouter", JSONObject("""{"data":[{"id":"paint","architecture":{"output_modalities":["image"]},"pricing":{"prompt":"0","completion":"0","image":"0.04"}}]}"""), false).single()
        assertFalse(model.free)
        assertEquals(MediaRoute.IMAGE, model.route)
    }

    @Test fun supportsTopLevelRouterCapabilities() {
        val model = CatalogParser.parse("openai", JSONObject("""{"data":[{"id":"vision","input_modalities":["text","image"],"output_modalities":["text"]}]}"""), false).single()
        assertTrue(model.inputImage)
    }
}
