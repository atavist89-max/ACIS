package com.llmtest

import java.io.File

object GhostPaths {
    val BASE_DIR = File("/storage/emulated/0/Download/GhostModels")
    val MODEL_FILE = File(BASE_DIR, "gemma-4-e2b.litertlm")
    val SANCTIONS_DB = File(BASE_DIR, "CounterpartyProject/sanctions_data/opensanctions.sqlite")
    val ENTITIES_JSON = File(BASE_DIR, "CounterpartyProject/sanctions_data/entities.ftm.json")
    
    fun isModelAvailable(): Boolean = MODEL_FILE.exists() && MODEL_FILE.length() > 1_000_000_000L
    fun isSanctionsDbAvailable(): Boolean = SANCTIONS_DB.exists() && SANCTIONS_DB.length() > 10_000_000L
    fun isEntitiesJsonAvailable(): Boolean = ENTITIES_JSON.exists()
}
