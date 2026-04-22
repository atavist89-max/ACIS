package com.llmtest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class MainActivity : ComponentActivity() {
    private var engine: Engine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BugLogger.initialize(this)
        BugLogger.log("MainActivity.onCreate() started")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GemmaDemoScreen()
                }
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        BugLogger.log("hasStoragePermission() = $result")
        return result
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private suspend fun ensureEngineInitialized(): Engine = withContext(Dispatchers.IO) {
        engine?.let { return@withContext it }

        BugLogger.log("Initializing engine...")
        val modelFile = GhostPaths.MODEL_FILE
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.GPU(),
            maxNumTokens = 2048,
            cacheDir = cacheDir.path
        )
        val newEngine = Engine(config)
        newEngine.initialize()
        engine = newEngine
        BugLogger.log("Engine initialized")
        newEngine
    }

    private suspend fun runInference(prompt: String): Pair<String, Double> = withContext(Dispatchers.IO) {
        val currentEngine = ensureEngineInitialized()

        val fullPrompt = buildString {
            appendLine("You are a helpful assistant running on-device via Gemma 4 E2B.")
            appendLine()
            appendLine(prompt)
        }

        BugLogger.log("Prompt length: ${fullPrompt.length} chars")

        val startTime = System.currentTimeMillis()
        try {
            val conversation = currentEngine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        temperature = 0.7,
                        topK = 40,
                        topP = 0.9
                    )
                )
            )
            conversation.use {
                BugLogger.log("Sending to LLM...")
                val response = it.sendMessage(Message.of(fullPrompt))
                val text = response.toString()
                val duration = (System.currentTimeMillis() - startTime) / 1000.0
                BugLogger.log("Response received: ${text.length} chars in ${duration}s")
                text to duration
            }
        } catch (e: Exception) {
            val duration = (System.currentTimeMillis() - startTime) / 1000.0
            BugLogger.logError("LLM failed", e)
            "Error: ${e.message}" to duration
        }
    }

    private suspend fun testContextLimit(targetTokenCount: Int): String = withContext(Dispatchers.IO) {
        val logPrefix = "[TEST-$targetTokenCount]"
        BugLogger.log("$logPrefix Starting context window test")

        val filler = "This is test padding text to increase token count. The secret code is NIGHT-OWL-734. "
        val targetChars = (targetTokenCount * 3.5).toInt()
        val repeats = (targetChars / filler.length) + 1
        val paddingText = filler.repeat(repeats).take(targetChars)

        val prompt = buildString {
            appendLine("You are a helpful assistant.")
            appendLine("Summarize the following text in one sentence.")
            appendLine("If you see the text 'NIGHT-OWL-734', include it in your response.")
            appendLine()
            appendLine("---BEGIN TEXT---")
            appendLine(paddingText)
            appendLine("---END TEXT---")
        }

        val estimatedTokens = (prompt.length / 3.5).toInt()
        BugLogger.log("$logPrefix Prompt: ${prompt.length} chars ≈ $estimatedTokens tokens")

        val currentEngine = ensureEngineInitialized()
        val startTime = System.currentTimeMillis()

        return@withContext try {
            val conversation = currentEngine.createConversation()
            conversation.use {
                val response = it.sendMessage(Message.of(prompt))
                val responseText = response.toString()
                val duration = (System.currentTimeMillis() - startTime) / 1000.0
                val foundNeedle = responseText.contains("NIGHT-OWL-734")

                BugLogger.log("$logPrefix SUCCESS: ${duration}s, needle=$foundNeedle")
                "TEST $targetTokenCount: SUCCESS | Time: ${duration}s | Tokens est: $estimatedTokens | Response: ${responseText.length} chars | Needle: $foundNeedle"
            }
        } catch (e: Exception) {
            val duration = (System.currentTimeMillis() - startTime) / 1000.0
            BugLogger.logError("$logPrefix FAILED", e)
            "TEST $targetTokenCount: FAILED | Time: ${duration}s | Error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Composable
    fun GemmaDemoScreen() {
        var promptText by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Enter a prompt and tap Run") }
        var isAnalyzing by remember { mutableStateOf(false) }
        var hasPermission by remember { mutableStateOf(hasStoragePermission()) }
        val scope = rememberCoroutineScope()

        // Re-check permission on resume
        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    val newPermission = hasStoragePermission()
                    BugLogger.log("ON_RESUME - permission: $newPermission")
                    if (newPermission != hasPermission) {
                        hasPermission = newPermission
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Gemma 4 E2B On-Device",
                style = MaterialTheme.typography.headlineMedium
            )

            // Status indicator
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(
                        color = when {
                            isAnalyzing -> Color.Yellow
                            result.contains("Error", ignoreCase = true) -> Color.Red
                            result.isNotEmpty() -> Color.Green
                            else -> Color.Gray
                        },
                        shape = CircleShape
                    )
            )

            Text(status, style = MaterialTheme.typography.bodyMedium)

            if (!hasPermission) {
                Text(
                    "This app needs 'All files access' permission to read the model from external storage.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Button(onClick = {
                    BugLogger.log("Opening permission settings...")
                    requestStoragePermission()
                }) {
                    Text("Grant Storage Permission")
                }
                Text(
                    "Tap button → Toggle permission → Return to app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Prompt input
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text("Prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    maxLines = 6
                )

                // Run button
                Button(
                    onClick = {
                        if (promptText.isBlank()) return@Button
                        scope.launch {
                            isAnalyzing = true
                            status = "Running inference..."
                            result = ""

                            val (response, duration) = runInference(promptText)
                            result = response
                            status = "Done in ${"%.2f".format(duration)}s"
                            isAnalyzing = false
                        }
                    },
                    enabled = !isAnalyzing && promptText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Run Inference")
                    }
                }

                // Context test button
                Button(
                    onClick = {
                        scope.launch {
                            isAnalyzing = true
                            result = "Starting context tests...\n"
                            val testLevels = listOf(500, 1000, 1500, 2000)

                            testLevels.forEach { targetTokens ->
                                status = "Testing $targetTokens tokens..."
                                val testResult = testContextLimit(targetTokens)
                                result += "\n$testResult\n"
                                if (targetTokens != testLevels.last()) {
                                    BugLogger.log("Cooling down 10s...")
                                    delay(10000)
                                }
                            }

                            status = "All tests complete"
                            isAnalyzing = false
                        }
                    },
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test Context Limits")
                }

                // Results
                if (result.isNotEmpty()) {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                "Result",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                result,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Debug buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { result = BugLogger.readLog() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("View Logs")
                    }

                    Button(
                        onClick = {
                            val logs = BugLogger.readLog()
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Gemma Logs", logs)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@MainActivity, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy Logs")
                    }

                    Button(
                        onClick = {
                            result = ""
                            status = "Enter a prompt and tap Run"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        BugLogger.log("MainActivity.onDestroy()")
        engine?.close()
    }
}
