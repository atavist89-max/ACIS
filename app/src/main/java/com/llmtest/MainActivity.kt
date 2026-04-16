package com.llmtest

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
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class MainActivity : ComponentActivity() {
    private var engine: Engine? = null
    private var entityList = mutableStateListOf<Pair<String, String>>() // id to caption
    private var selectedEntity by mutableStateOf<Pair<String, String>?>(null)
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BugLogger.initialize(this)
        BugLogger.log("MainActivity.onCreate() started")
        
        // Load entities from JSON
        loadEntityList()
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SanctionsScreen()
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
    
    private fun loadEntityList() {
        BugLogger.log("Loading entity list from ${GhostPaths.ENTITIES_JSON}")
        if (!GhostPaths.isEntitiesJsonAvailable()) {
            BugLogger.log("entities.ftm.json not found")
            return
        }
        
        try {
            BufferedReader(FileReader(GhostPaths.ENTITIES_JSON)).use { reader ->
                var count = 0
                reader.lineSequence().forEach { line ->
                    if (line.isNotBlank()) {
                        try {
                            val json = JSONObject(line)
                            val id = json.optString("id", "")
                            val caption = json.optString("caption", "")
                            val schema = json.optString("schema", "")
                            if (id.isNotEmpty() && caption.isNotEmpty() && (schema == "Person" || schema == "Company")) {
                                entityList.add(id to caption)
                                count++
                            }
                        } catch (e: Exception) {
                            // Skip invalid lines
                        }
                    }
                }
                BugLogger.log("Loaded $count entities")
            }
        } catch (e: Exception) {
            BugLogger.logError("Failed to load entities", e)
        }
    }
    
    private suspend fun getEntityDetails(entityId: String): String = withContext(Dispatchers.IO) {
        BugLogger.log("Getting details for entity: $entityId")
        if (!GhostPaths.isEntitiesJsonAvailable()) {
            return@withContext "No entity data available"
        }
        
        try {
            BufferedReader(FileReader(GhostPaths.ENTITIES_JSON)).use { reader ->
                reader.lineSequence().forEach { line ->
                    try {
                        val json = JSONObject(line)
                        if (json.optString("id") == entityId) {
                            BugLogger.log("Found entity data")
                            return@withContext json.toString(2) // Pretty print
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            BugLogger.logError("Error reading entity", e)
        }
        return@withContext "Entity data not found"
    }
    
    private suspend fun querySanctionsForEntity(entityId: String): List<String> = withContext(Dispatchers.IO) {
        BugLogger.log("Querying sanctions DB for: $entityId")
        val matches = mutableListOf<String>()
        
        if (!GhostPaths.isSanctionsDbAvailable()) {
            BugLogger.log("Sanctions DB not available")
            return@withContext matches
        }
        
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            GhostPaths.SANCTIONS_DB.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        
        try {
            // Try to match by entity ID in names field
            val cursor = db.rawQuery(
                "SELECT entity_id, names, programs FROM sanctions_fts WHERE entity_id = ? OR names LIKE ? LIMIT 5",
                arrayOf(entityId, "%$entityId%")
            )
            
            while (cursor.moveToNext()) {
                val foundId = cursor.getString(0)
                val names = cursor.getString(1)
                val programs = cursor.getString(2)
                matches.add("ID: $foundId | Names: $names | Programs: $programs")
                BugLogger.log("Found match: $foundId")
            }
            cursor.close()
            
            // If no direct match, try caption search
            if (matches.isEmpty() && selectedEntity != null) {
                val caption = selectedEntity!!.second
                val captionCursor = db.rawQuery(
                    "SELECT entity_id, names, programs FROM sanctions_fts WHERE names MATCH ? LIMIT 5",
                    arrayOf(caption)
                )
                while (captionCursor.moveToNext()) {
                    val foundId = captionCursor.getString(0)
                    val names = captionCursor.getString(1)
                    val programs = captionCursor.getString(2)
                    matches.add("ID: $foundId | Names: $names | Programs: $programs")
                }
                captionCursor.close()
            }
        } catch (e: Exception) {
            BugLogger.logError("DB query failed", e)
        } finally {
            db.close()
        }
        
        BugLogger.log("Total matches: ${matches.size}")
        matches
    }
    
    private suspend fun analyzeWithLLM(entityId: String, entityData: String, sanctionsMatches: List<String>): String = withContext(Dispatchers.IO) {
        BugLogger.log("Analyzing with LLM for: $entityId")
        
        if (engine == null) {
            BugLogger.log("Initializing engine first...")
            val modelFile = GhostPaths.MODEL_FILE
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.GPU(),
                maxNumTokens = 2048,
                cacheDir = cacheDir.path
            )
            engine = Engine(config)
            engine?.initialize()
            BugLogger.log("Engine initialized")
        }
        
        val currentEngine = engine ?: throw IllegalStateException("Engine not available")
        
        val prompt = buildString {
            appendLine("You are a sanctions screening analyst. Analyze ONLY the data provided below.")
            appendLine("Do not use any external knowledge. Base your entire analysis strictly on the provided local data.")
            appendLine()
            appendLine("=== ENTITY DATA FROM LOCAL DATABASE ===")
            appendLine(entityData)
            appendLine()
            appendLine("=== SANCTIONS DATABASE MATCHES ===")
            if (sanctionsMatches.isEmpty()) {
                appendLine("No direct matches found in sanctions database.")
            } else {
                sanctionsMatches.forEachIndexed { index, match ->
                    appendLine("${index + 1}. $match")
                }
            }
            appendLine()
            appendLine("=== ANALYSIS INSTRUCTIONS ===")
            appendLine("1. Identify the entity type (Person or Company)")
            appendLine("2. List any sanctions programs or restrictions mentioned in the data")
            appendLine("3. Note any aliases or related entities")
            appendLine("4. Provide a factual risk assessment based ONLY on the data above")
            appendLine("5. State clearly if no sanctions were found")
            appendLine()
            appendLine("Provide a concise, factual summary in 3-5 sentences.")
        }
        
        BugLogger.log("Prompt length: ${prompt.length}")
        
        try {
            val conversation = currentEngine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        temperature = 0.5,  // FACTUAL - as requested
                        topK = 40,
                        topP = 0.9
                    )
                )
            )
            
            conversation.use {
                BugLogger.log("Sending to LLM...")
                val response = it.sendMessage(Message.of(prompt))
                val text = response.toString()
                BugLogger.log("Response received: ${text.length} chars")
                text
            }
        } catch (e: Exception) {
            BugLogger.logError("LLM failed", e)
            "Error analyzing data: ${e.message}"
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SanctionsScreen() {
        var status by remember { mutableStateOf("Select entity to screen") }
        var isAnalyzing by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf("") }
        var hasPermission by remember { mutableStateOf(hasStoragePermission()) }
        val scope = rememberCoroutineScope()
        var expanded by remember { mutableStateOf(false) }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Sanctions Screening",
                style = MaterialTheme.typography.headlineMedium
            )
            
            // Status indicator
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(
                        color = when {
                            isAnalyzing -> Color.Yellow
                            result.contains("Error") -> Color.Red
                            result.isNotEmpty() -> Color.Green
                            else -> Color.Gray
                        },
                        shape = CircleShape
                    )
            )
            
            Text(status, style = MaterialTheme.typography.bodyMedium)
            
            if (!hasPermission) {
                Button(onClick = { requestStoragePermission() }) {
                    Text("Grant Storage Permission")
                }
            } else {
                // Entity Dropdown
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedEntity?.second ?: "Select entity...",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Entity") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        entityList.take(100).forEach { (id, caption) ->
                            DropdownMenuItem(
                                text = { Text(caption) },
                                onClick = {
                                    selectedEntity = id to caption
                                    expanded = false
                                    result = ""
                                    BugLogger.log("Selected: $caption ($id)")
                                }
                            )
                        }
                    }
                }
                
                // Analyze Button
                Button(
                    onClick = {
                        val entity = selectedEntity
                        if (entity != null) {
                            scope.launch {
                                isAnalyzing = true
                                status = "Loading entity data..."
                                result = ""
                                
                                val entityData = getEntityDetails(entity.first)
                                status = "Querying sanctions database..."
                                val sanctions = querySanctionsForEntity(entity.first)
                                status = "Analyzing with LLM (temp=0.5)..."
                                val analysis = analyzeWithLLM(entity.first, entityData, sanctions)
                                
                                result = analysis
                                isAnalyzing = false
                                status = "Analysis complete"
                            }
                        }
                    },
                    enabled = selectedEntity != null && !isAnalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text("Screen Entity")
                    }
                }
                
                // Results
                if (result.isNotEmpty()) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Analysis Results",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                result,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
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
                            selectedEntity = null
                            result = ""
                            status = "Select entity to screen"
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
