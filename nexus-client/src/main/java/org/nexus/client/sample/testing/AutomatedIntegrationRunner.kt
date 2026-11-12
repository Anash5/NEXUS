package org.nexus.client.sample.testing

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.nexus.client.sample.service.NexusClientManager
import org.nexus.interop.contract.NexusResponse

data class TestCaseResult(
    val testName: String,
    val isPassed: Boolean,
    val details: String,
    val durationMs: Long
)

class AutomatedIntegrationRunner(private val clientManager: NexusClientManager) {

    suspend fun runAllIntegrationTests(onProgress: (TestCaseResult) -> Unit): List<TestCaseResult> {
        val results = mutableListOf<TestCaseResult>()

        // Test 1: Capability Discovery (Correction 5: verify presence of all 4 expected, not requiring exactly 4)
        results.add(testDiscovery().also(onProgress))

        // Test 2: Uppercase Transformation
        results.add(testUppercase().also(onProgress))

        // Test 3: Lowercase Transformation
        results.add(testLowercase().also(onProgress))

        // Test 4: Word Count & Character JSON Metric (Correction 6: {"wordCount": 5, "characters": 23})
        results.add(testWordCount().also(onProgress))

        // Test 5: Reverse Text Transformation
        results.add(testReverse().also(onProgress))

        // Test 6: Concurrent Multi-Request Dispatch (10 parallel async requests)
        results.add(testConcurrentExecution().also(onProgress))

        // Test 7: Error Handling for Unregistered Capability (404)
        results.add(testUnknownCapability().also(onProgress))

        // Test 8: Large Payload Exceeding 64 KB (Correction 3: 413)
        results.add(testPayloadLimit().also(onProgress))

        return results
    }

    private suspend fun testDiscovery(): TestCaseResult {
        val start = System.currentTimeMillis()
        clientManager.refreshCapabilities()
        val caps = clientManager.discoveredCapabilities.value

        val expected = listOf(
            "nexus.text.uppercase",
            "nexus.text.lowercase",
            "nexus.text.wordcount",
            "nexus.text.reverse"
        )

        val missing = expected.filter { id -> caps.none { it.capabilityId == id } }
        val duration = System.currentTimeMillis() - start

        return if (missing.isEmpty()) {
            TestCaseResult(
                testName = "1. Capability Discovery",
                isPassed = true,
                details = "Verified presence of all 4 expected capabilities (${caps.size} total active)",
                durationMs = duration
            )
        } else {
            TestCaseResult(
                testName = "1. Capability Discovery",
                isPassed = false,
                details = "Missing expected capabilities: $missing",
                durationMs = duration
            )
        }
    }

    private suspend fun testUppercase(): TestCaseResult {
        val start = System.currentTimeMillis()
        val input = "nexus interop test"
        val resp = clientManager.executeCapability("nexus.text.uppercase", input)
        val duration = System.currentTimeMillis() - start

        val isPassed = resp.isSuccess && resp.statusCode == NexusResponse.STATUS_SUCCESS && resp.resultData == "NEXUS INTEROP TEST"
        return TestCaseResult(
            testName = "2. Uppercase Transformation",
            isPassed = isPassed,
            details = if (isPassed) "Expected & received: 'NEXUS INTEROP TEST'" else "Received: ${resp.resultData} (Status: ${resp.statusCode})",
            durationMs = duration
        )
    }

    private suspend fun testLowercase(): TestCaseResult {
        val start = System.currentTimeMillis()
        val input = "UNIVERSAL FRAMEWORK"
        val resp = clientManager.executeCapability("nexus.text.lowercase", input)
        val duration = System.currentTimeMillis() - start

        val isPassed = resp.isSuccess && resp.statusCode == NexusResponse.STATUS_SUCCESS && resp.resultData == "universal framework"
        return TestCaseResult(
            testName = "3. Lowercase Transformation",
            isPassed = isPassed,
            details = if (isPassed) "Expected & received: 'universal framework'" else "Received: ${resp.resultData} (Status: ${resp.statusCode})",
            durationMs = duration
        )
    }

    private suspend fun testWordCount(): TestCaseResult {
        val start = System.currentTimeMillis()
        // 5 words, 23 characters: "One two three four five"
        val input = "One two three four five"
        val resp = clientManager.executeCapability("nexus.text.wordcount", input)
        val duration = System.currentTimeMillis() - start

        var isPassed = false
        var details = ""

        val data = resp.resultData
        if (resp.isSuccess && data != null) {
            try {
                val json = JSONObject(data)
                val words = json.getInt("wordCount")
                val chars = json.getInt("characters")
                if (words == 5 && chars == 23) {
                    isPassed = true
                    details = "Validated JSON: {\"wordCount\": 5, \"characters\": 23}"
                } else {
                    details = "JSON mismatch: words=$words, chars=$chars (expected 5 and 23)"
                }
            } catch (e: Exception) {
                details = "Malformed JSON returned: ${e.message}"
            }
        } else {
            details = "Request failed with code: ${resp.statusCode}"
        }

        return TestCaseResult(
            testName = "4. Word Count JSON Metrics",
            isPassed = isPassed,
            details = details,
            durationMs = duration
        )
    }

    private suspend fun testReverse(): TestCaseResult {
        val start = System.currentTimeMillis()
        val input = "Interoperability"
        val resp = clientManager.executeCapability("nexus.text.reverse", input)
        val duration = System.currentTimeMillis() - start

        val isPassed = resp.isSuccess && resp.resultData?.trim() == "ytilibareporetnI"
        return TestCaseResult(
            testName = "5. Reverse Text Transformation",
            isPassed = isPassed,
            details = if (isPassed) "Expected & received: 'ytilibareporetnI'" else "Received: ${resp.resultData}",
            durationMs = duration
        )
    }

    private suspend fun testConcurrentExecution(): TestCaseResult = coroutineScope {
        val start = System.currentTimeMillis()
        val deferredList = (1..10).map { i ->
            async {
                clientManager.executeCapability("nexus.text.uppercase", "concurrent_payload_$i")
            }
        }

        val responses = deferredList.awaitAll()
        val duration = System.currentTimeMillis() - start

        val allSuccessful = responses.all { it.isSuccess && it.statusCode == 200 }
        TestCaseResult(
            testName = "6. Concurrent 10-Request Dispatch",
            isPassed = allSuccessful,
            details = if (allSuccessful) "10/10 parallel Binder transactions completed successfully" else "Some requests failed",
            durationMs = duration
        )
    }

    private suspend fun testUnknownCapability(): TestCaseResult {
        val start = System.currentTimeMillis()
        val resp = clientManager.executeCapability("nexus.unregistered.dummy", "test")
        val duration = System.currentTimeMillis() - start

        val isPassed = !resp.isSuccess && resp.statusCode == NexusResponse.STATUS_CAPABILITY_NOT_FOUND
        return TestCaseResult(
            testName = "7. Unknown Capability 404 Check",
            isPassed = isPassed,
            details = if (isPassed) "Correctly returned STATUS_CAPABILITY_NOT_FOUND (404)" else "Unexpected response: ${resp.statusCode}",
            durationMs = duration
        )
    }

    private suspend fun testPayloadLimit(): TestCaseResult {
        val start = System.currentTimeMillis()
        // Generate payload > 64 KB (e.g. 70 KB)
        val oversizedPayload = "A".repeat(70000)
        val resp = clientManager.executeCapability("nexus.text.uppercase", oversizedPayload)
        val duration = System.currentTimeMillis() - start

        val isPassed = !resp.isSuccess && resp.statusCode == NexusResponse.STATUS_PAYLOAD_TOO_LARGE
        return TestCaseResult(
            testName = "8. 64 KB Payload Limit Safety Check",
            isPassed = isPassed,
            details = if (isPassed) "Correctly blocked with STATUS_PAYLOAD_TOO_LARGE (413)" else "Unexpected response: ${resp.statusCode}",
            durationMs = duration
        )
    }
}
