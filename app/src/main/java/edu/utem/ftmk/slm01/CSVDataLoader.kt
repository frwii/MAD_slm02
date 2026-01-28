package edu.utem.ftmk.slm01

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object CsvDatasetLoader {

    private const val ITEMS_PER_SET = 10

    fun loadDatasets(context: Context): List<List<FoodItem>> {

        val allItems = mutableListOf<FoodItem>()

        val reader = BufferedReader(
            InputStreamReader(context.assets.open("foodpreprocessed.csv"))
        )

        // Skip header
        reader.readLine()

        reader.forEachLine { line ->
            // ✅ CSV-safe split (handles commas in quotes)
            val tokens = parseCsvLine(line)

            if (tokens.size >= 6) {
                allItems.add(
                    FoodItem(
                        id = tokens[0].trim(),
                        name = tokens[1].trim(),
                        ingredients = tokens[2].trim(),
                        allergensRaw = tokens[3].trim(),
                        allergensMapped = tokens[4].trim()
                    )
                )
            }
        }

        reader.close()

        return allItems
            .take(200)
            .chunked(ITEMS_PER_SET)
            .take(20)
    }

    // ✅ Proper CSV parser
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        for (c in line) {
            when (c) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (inQuotes) sb.append(c) else {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
        }
        result.add(sb.toString())
        return result
    }
}
