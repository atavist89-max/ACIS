package com.llmtest

data class EntityInfo(
    val id: String,
    val caption: String,
    val schema: String,
    val datasets: List<String>,
    val properties: Map<String, Any>,
    val target: Boolean
)
