package io.stamethyst.backend.workshop

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class BaiduAiTextTranslationClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun translate_sendsReferenceAsBaiduTranslationInstruction() {
        server.enqueue(successResponse("译文"))

        val translatedText = runBlocking {
            newClient().translate(
                text = "source text",
                sourceLanguage = "auto",
                targetLanguage = "zh",
                credentials = BaiduTranslationCredentials(
                    appId = "test-app-id",
                    apiKey = "test-api-key",
                ),
                reference = "使用 Steam 创意工坊模组说明语境翻译。",
            )
        }

        assertEquals("译文", translatedText)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/ait/api/aiTextTranslate", request.url.encodedPath)
        assertEquals("Bearer test-api-key", request.headers["Authorization"])

        val body = Json.parseToJsonElement(requireNotNull(request.body).utf8()).jsonObject
        assertEquals("test-app-id", body["appid"]?.jsonPrimitive?.content)
        assertEquals("auto", body["from"]?.jsonPrimitive?.content)
        assertEquals("zh", body["to"]?.jsonPrimitive?.content)
        assertEquals("source text", body["q"]?.jsonPrimitive?.content)
        assertEquals("llm", body["model_type"]?.jsonPrimitive?.content)
        assertEquals("使用 Steam 创意工坊模组说明语境翻译。", body["reference"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("prompt"))
    }

    @Test
    fun translate_acceptsBaiduSuccessErrorCode() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "error_code": "52000",
                      "trans_result": [
                        { "src": "apple", "dst": "苹果" }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val translatedText = runBlocking {
            newClient().translate(
                text = "apple",
                sourceLanguage = "en",
                targetLanguage = "zh",
                credentials = BaiduTranslationCredentials(
                    appId = "test-app-id",
                    apiKey = "test-api-key",
                ),
            )
        }

        assertEquals("苹果", translatedText)
    }

    private fun newClient(): BaiduAiTextTranslationClient =
        BaiduAiTextTranslationClient(
            client = OkHttpClient.Builder().build(),
            baseUrl = server.url("/"),
        )

    private fun successResponse(translatedText: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """
                {
                  "from": "en",
                  "to": "zh",
                  "trans_result": [
                    { "src": "source text", "dst": "$translatedText" }
                  ]
                }
                """.trimIndent(),
            )
            .build()
}
