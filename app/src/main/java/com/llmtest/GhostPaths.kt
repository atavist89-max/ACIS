package com.llmtest

import java.io.File

object GhostPaths {
    val BASE_DIR = File("/storage/emulated/0/Download/GhostModels")
    val MODEL_FILE = File(BASE_DIR, "gemma-4-e2b.litertlm")

    fun isModelAvailable(): Boolean = MODEL_FILE.exists() && MODEL_FILE.length() > 1_000_000_000L
}
