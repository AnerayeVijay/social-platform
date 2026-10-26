package com.example.social.application

enum class Language(val code: String, val nativeName: String) {
    ENGLISH("en", "English"), MARATHI("mr", "मराठी"), HINDI("hi", "हिन्दी"),
    GUJARATI("gu", "ગુજરાતી"), TAMIL("ta", "தமிழ்"), MALAYALAM("ml", "മലയാളം");

    companion object {
        fun fromCode(code: String?): Language? = entries.find { it.code == code }
    }
}
