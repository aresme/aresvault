package com.ares.vault.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val listSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromList(list: List<String>): String = Json.encodeToString(listSerializer, list)

    @TypeConverter
    fun toList(json: String): List<String> =
        if (json.isBlank()) emptyList() else Json.decodeFromString(listSerializer, json)
}
