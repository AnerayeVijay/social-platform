package com.example.social.application

import com.example.social.domain.UserId
import java.util.UUID

enum class ThemeMode { LIGHT, DARK, SYSTEM }
enum class MemberImageKind { AVATAR, BACKGROUND }
data class CustomizationPreferences(val theme: ThemeMode = ThemeMode.SYSTEM, val bio: String = "", val avatarVersion: UUID? = null, val backgroundVersion: UUID? = null)
/** Always a normalized JPEG. */
data class MemberImage(val version: UUID, val bytes: ByteArray)
interface MemberCustomizations {
    fun preferences(owner: UserId): CustomizationPreferences
    fun saveTheme(owner: UserId, theme: ThemeMode)
    fun saveBio(owner: UserId, bio: String)
    fun image(owner: UserId, kind: MemberImageKind): MemberImage?
    fun saveImage(owner: UserId, kind: MemberImageKind, image: MemberImage)
    fun removeImage(owner: UserId, kind: MemberImageKind)
}
fun interface MemberImageProcessor { fun process(bytes: ByteArray, kind: MemberImageKind): ByteArray }

class ImageTooLarge : IllegalArgumentException("Images must be 5 MB or smaller")
class ImageProcessingBusy : RuntimeException("Image processing is busy. Please try again.")

class CustomizationService(private val store: MemberCustomizations, private val processor: MemberImageProcessor) {
    private val imageProcessing = java.util.concurrent.Semaphore(1)
    fun preferences(owner: UserId): CustomizationPreferences = store.preferences(owner)

    /** Accepts only the exact lowercase names light, dark and system. */
    fun saveTheme(owner: UserId, theme: String?) {
        val mode = ThemeMode.entries.firstOrNull { it.name.lowercase() == theme } ?: throw IllegalArgumentException("Choose light, dark or system")
        store.saveTheme(owner, mode)
    }

    /** Trims and validates without writing, so callers can check before any other write. */
    fun validBio(bio: String?): String {
        val value = bio.orEmpty().trim()
        require(value.length <= MAX_BIO) { "Bio must contain at most $MAX_BIO characters" }
        require(value.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) { "Bio must be plain text" }
        return value
    }

    fun saveBio(owner: UserId, bio: String?) = store.saveBio(owner, validBio(bio))

    fun image(owner: UserId, kind: MemberImageKind): MemberImage? = store.image(owner, kind)

    /** Normalizes the upload through the processor before anything is stored. */
    fun uploadImage(owner: UserId, kind: MemberImageKind, bytes: ByteArray) {
        if (bytes.size > MAX_UPLOAD_BYTES) throw ImageTooLarge()
        require(bytes.isNotEmpty()) { "Choose a JPEG or PNG image" }
        if (!imageProcessing.tryAcquire()) throw ImageProcessingBusy()
        try { store.saveImage(owner, kind, MemberImage(UUID.randomUUID(), processor.process(bytes, kind))) }
        finally { imageProcessing.release() }
    }

    fun removeImage(owner: UserId, kind: MemberImageKind) = store.removeImage(owner, kind)

    companion object {
        const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024
        const val MAX_BIO = 300
    }
}
