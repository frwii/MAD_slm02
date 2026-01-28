package edu.utem.ftmk.slm01

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("native-lib")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("llama")
        }
    }

    external fun inferAllergens(input: String): String

    private val allowedAllergens = setOf(
        "milk", "egg", "peanut", "tree nut",
        "wheat", "soy", "fish", "shellfish", "sesame"
    )

    private fun buildPrompt(ingredients: String): String {
        return """
        Analyze these ingredients and identify allergens.

        Ingredients: $ingredients

        Allowed allergens: milk, egg, peanut, tree nut, wheat, soy, fish, shellfish, sesame

        Output ONLY comma-separated allergens or EMPTY.
        Allergens:
        """.trimIndent()
    }

    private fun wrapWithChatTemplate(prompt: String): String {
        return "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        copyModelIfNeeded(this)

        val spinner = findViewById<Spinner>(R.id.spinnerDataset)
        val btnPredict = findViewById<Button>(R.id.btnPredict)
        val tvLogs = findViewById<TextView>(R.id.tvLogs)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar2)

        tvLogs.movementMethod = LinkMovementMethod.getInstance()

        val datasets = CsvDatasetLoader.loadDatasets(this)

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            (1..datasets.size).map { "Dataset $it" }
        )

        btnPredict.setOnClickListener {
            val selectedDataset = datasets[spinner.selectedItemPosition]

            btnPredict.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 0
            tvLogs.text = ""

            thread {
                val styled = SpannableStringBuilder()

                selectedDataset.forEachIndexed { index, item ->

                    val promptContent = buildPrompt(item.ingredients)
                    val prompt = wrapWithChatTemplate(promptContent)

                    val javaBefore = MemoryReader.javaHeapKb()
                    val nativeBefore = MemoryReader.nativeHeapKb()
                    val pssBefore = MemoryReader.totalPssKb()

                    val startNs = System.nanoTime()
                    val rawResult = inferAllergens(prompt)
                    val latencyMs = max((System.nanoTime() - startNs) / 1_000_000, 1)

                    val javaAfter = MemoryReader.javaHeapKb()
                    val nativeAfter = MemoryReader.nativeHeapKb()
                    val pssAfter = MemoryReader.totalPssKb()

                    val parts = rawResult.split("|", limit = 2)
                    val meta = parts[0]
                    val rawOutput = if (parts.size > 1) parts[1] else ""

                    val metricsMap = parseMetrics(meta)

                    val metrics = InferenceMetrics(
                        latencyMs = latencyMs,
                        javaHeapKb = javaAfter - javaBefore,
                        nativeHeapKb = nativeAfter - nativeBefore,
                        totalPssKb = pssAfter - pssBefore,
                        ttft = metricsMap["TTFT_MS"] ?: -1L,
                        itps = metricsMap["ITPS"] ?: -1L,
                        otps = metricsMap["OTPS"] ?: -1L,
                        oet = metricsMap["OET_MS"] ?: -1L
                    )

                    // ================= FIXED MODEL LOGIC =================

                    val predictedAllergens = rawOutput
                        .replace("Ġ", "")              // remove token artifact
                        .lowercase()
                        .split(",")
                        .map { it.trim() }
                        .filter { it in allowedAllergens }

                    val predictedText = if (predictedAllergens.isEmpty()) {
                        "EMPTY"
                    } else {
                        predictedAllergens.joinToString(", ")
                    }

                    // ====================================================

                    val start = styled.length
                    styled.append("🍽${item.name}\n")
                    styled.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        styled.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    styled.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            val intent = Intent(this@MainActivity, FoodDetailActivity::class.java)
                            intent.putExtra("name", item.name)
                            intent.putExtra("ingredients", item.ingredients)
                            intent.putExtra("raw", item.allergensRaw)
                            intent.putExtra("mapped", item.allergensMapped)
                            intent.putExtra("predicted", predictedText)

                            intent.putExtra("latencyMs", metrics.latencyMs)
                            intent.putExtra("javaHeapKb", metrics.javaHeapKb)
                            intent.putExtra("nativeHeapKb", metrics.nativeHeapKb)
                            intent.putExtra("totalPssKb", metrics.totalPssKb)
                            intent.putExtra("ttft", metrics.ttft)
                            intent.putExtra("itps", metrics.itps)
                            intent.putExtra("otps", metrics.otps)
                            intent.putExtra("oet", metrics.oet)

                            startActivity(intent)
                        }
                    }, start, styled.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                    styled.append("Predicted Allergens : $predictedText\n\n")

                    saveToFirebase(
                        item.id,
                        item.name,
                        item.ingredients,
                        item.allergensRaw,
                        item.allergensMapped,
                        predictedText,
                        metrics
                    )

                    runOnUiThread {
                        progressBar.progress = ((index + 1) * 100) / selectedDataset.size
                        tvLogs.text = styled
                    }
                }

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnPredict.isEnabled = true
                }
            }
        }
    }

    private fun parseMetrics(meta: String): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        meta.split(";").forEach {
            val kv = it.split("=")
            if (kv.size == 2) {
                map[kv[0]] = kv[1].toLongOrNull() ?: -1L
            }
        }
        return map
    }

    private fun saveToFirebase(
        dataId: String,
        name: String,
        ingredients: String,
        allergens: String,
        mappedAllergens: String,
        predictedAllergens: String,
        metrics: InferenceMetrics
    ) {
        FirebaseFirestore.getInstance()
            .collection("predictions")
            .add(
                PredictionRecord(
                    dataId,
                    name,
                    ingredients,
                    allergens,
                    mappedAllergens,
                    predictedAllergens,
                    System.currentTimeMillis(),
                    metrics
                )
            )
    }

    private fun copyModelIfNeeded(context: Context) {
        val modelName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        val outFile = File(context.filesDir, modelName)
        if (!outFile.exists()) {
            context.assets.open(modelName).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
