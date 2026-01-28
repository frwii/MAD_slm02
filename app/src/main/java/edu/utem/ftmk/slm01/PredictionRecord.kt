package edu.utem.ftmk.slm01

data class PredictionRecord(
    val dataId: String = "",
    val name: String = "",
    val ingredients: String = "",
    val allergens: String = "",
    val mappedAllergens: String = "",
    val predictedAllergens: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val inferenceMetrics: InferenceMetrics? = null
)
