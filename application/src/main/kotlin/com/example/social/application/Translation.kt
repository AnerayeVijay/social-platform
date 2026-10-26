package com.example.social.application

data class Translation(val text: String, val sourceLanguage: String)

fun interface TextTranslation {
    /** Results correspond to inputs in order; null means translation is unavailable. */
    fun translate(texts: List<String>, target: Language): List<Translation?>
}
