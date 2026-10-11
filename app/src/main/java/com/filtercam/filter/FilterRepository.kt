package com.filtercam.filter

import android.content.Context

class FilterRepository(private val context: Context) {
    fun loadAll(): List<FilterParams> =
        (context.assets.list("filters") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .sorted()
            .map { f -> FilterParams.fromJson(context.assets.open("filters/$f").bufferedReader().use { it.readText() }) }
}
