package com.nabla.sdk.core.domain.entity

import com.benasher44.uuid.Uuid

/**
 * Url and metadata for a distant server-hosted file.
 */
public sealed class FileUpload {
    public abstract val fileUpload: BaseFileUpload

    public data class Image(
        val size: Size?,
        override val fileUpload: BaseFileUpload,
    ) : FileUpload()

    public data class Document(
        val thumbnail: Image?,
        override val fileUpload: BaseFileUpload,
    ) : FileUpload()
}

public data class Size(
    val width: Int,
    val height: Int,
)

public data class BaseFileUpload(
    val id: Uuid,
    val url: EphemeralUrl,
    val fileName: String,
    val mimeType: MimeType,
)
