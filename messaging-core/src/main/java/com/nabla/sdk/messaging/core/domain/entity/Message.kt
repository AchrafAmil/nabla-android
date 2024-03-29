package com.nabla.sdk.messaging.core.domain.entity

import com.benasher44.uuid.Uuid
import com.benasher44.uuid.uuid4
import com.nabla.sdk.core.domain.entity.FileUpload
import com.nabla.sdk.core.domain.entity.MimeType
import com.nabla.sdk.core.domain.entity.Uri
import com.nabla.sdk.messaging.core.domain.entity.MessageId.Local
import com.nabla.sdk.messaging.core.domain.entity.MessageId.Remote
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

internal data class BaseMessage(
    val id: MessageId,
    val sentAt: Instant,
    val sender: MessageSender,
    val sendStatus: SendStatus,
    val conversationId: ConversationId,
)

/**
 * Url and metadata for a local device-hosted file.
 */
public sealed interface FileLocal {
    /**
     * Local device-hosted file uri.
     *
     * This is typically provided by the OS file providers.
     */
    public val uri: Uri

    public data class Image(override val uri: Uri) : FileLocal
    public data class Document(
        override val uri: Uri,
        val documentName: String?,
        val mimeType: MimeType,
    ) : FileLocal
}

public sealed class FileSource<FileLocalType : FileLocal, FileUploadType : FileUpload> {
    public data class Local<FileLocalType : FileLocal, FileUploadType : FileUpload>(
        val fileLocal: FileLocalType,
    ) : FileSource<FileLocalType, FileUploadType>()

    public data class Uploaded<FileLocalType : FileLocal, FileUploadType : FileUpload>(
        val fileLocal: FileLocalType?,
        val fileUpload: FileUploadType,
    ) : FileSource<FileLocalType, FileUploadType>()
}

/**
 * Identifier for a message, with an optimistic-friendly architecture.
 *
 * If the message is not yet sent (e.g. is sending or failed) then it only has
 * a client-made [clientId] identifier, this state is represented with [Local].
 *
 * If the message is sent and exists server-side, it then necessarily has a server-made identifier
 * called [remoteId] and maybe also a client-made [clientId]. This state is represented with [Remote].
 *
 * Tip: Privilege using [stableId] if you want an identifier that remains the same before, during and after
 * sending a message. Typical use case is glitch-free optimistic sending.
 */
public sealed interface MessageId {
    public val clientId: Uuid?
    public val remoteId: Uuid?

    public val stableId: Uuid

    public data class Local internal constructor(override val clientId: Uuid) : MessageId {
        override val remoteId: Uuid? = null
        override val stableId: Uuid = clientId
    }

    public data class Remote internal constructor(override val clientId: Uuid?, override val remoteId: Uuid) : MessageId {
        override val stableId: Uuid = clientId ?: remoteId
    }

    public companion object {
        internal fun new(): MessageId = Local(uuid4())
    }
}

public sealed class Message {
    internal abstract val baseMessage: BaseMessage
    internal abstract fun modify(status: SendStatus): Message

    public val id: MessageId get() = baseMessage.id
    public val sentAt: Instant get() = baseMessage.sentAt
    public val sender: MessageSender get() = baseMessage.sender
    public val sendStatus: SendStatus get() = baseMessage.sendStatus
    public val conversationId: ConversationId get() = baseMessage.conversationId

    public data class Text internal constructor(override val baseMessage: BaseMessage, val text: String) : Message() {
        override fun modify(status: SendStatus): Message {
            return copy(baseMessage = baseMessage.copy(sendStatus = status))
        }

        public companion object {
            /**
             * Create a new [Text] message in conversation.
             */
            public fun new(
                conversationId: ConversationId,
                text: String,
            ): Text = Text(
                BaseMessage(MessageId.new(), Clock.System.now(), MessageSender.Patient, SendStatus.ToBeSent, conversationId),
                text,
            )
        }
    }

    public sealed class Media<FileLocalType : FileLocal, FileUploadType : FileUpload> : Message() {

        internal abstract val mediaSource: FileSource<FileLocalType, FileUploadType>

        public data class Image internal constructor(
            override val baseMessage: BaseMessage,
            override val mediaSource: FileSource<FileLocal.Image, FileUpload.Image>,
        ) : Media<FileLocal.Image, FileUpload.Image>() {
            val stableUri: Uri = when (mediaSource) {
                is FileSource.Local -> mediaSource.fileLocal.uri
                is FileSource.Uploaded -> mediaSource.fileLocal?.uri ?: mediaSource.fileUpload.fileUpload.url.url
            }

            override fun modify(status: SendStatus): Message {
                return copy(baseMessage = baseMessage.copy(sendStatus = status))
            }

            internal fun modify(mediaSource: FileSource<FileLocal.Image, FileUpload.Image>): Image {
                return copy(mediaSource = mediaSource)
            }

            public companion object {
                /**
                 * Create a new [Image] message in conversation.
                 *
                 * @param mediaSource local source of the image.
                 * @see FileLocal.Image
                 */
                public fun new(
                    conversationId: ConversationId,
                    mediaSource: FileSource.Local<FileLocal.Image, FileUpload.Image>,
                ): Image = Image(
                    BaseMessage(MessageId.new(), Clock.System.now(), MessageSender.Patient, SendStatus.ToBeSent, conversationId),
                    mediaSource
                )
            }
        }

        public data class Document internal constructor(
            override val baseMessage: BaseMessage,
            override val mediaSource: FileSource<FileLocal.Document, FileUpload.Document>,
        ) : Media<FileLocal.Document, FileUpload.Document>() {
            val uri: Uri = when (mediaSource) {
                is FileSource.Local -> mediaSource.fileLocal.uri
                is FileSource.Uploaded -> mediaSource.fileUpload.fileUpload.url.url
            }
            val mimeType: MimeType = when (mediaSource) {
                is FileSource.Local -> mediaSource.fileLocal.mimeType
                is FileSource.Uploaded -> mediaSource.fileUpload.fileUpload.mimeType
            }
            val documentName: String? = when (mediaSource) {
                is FileSource.Local -> mediaSource.fileLocal.documentName
                is FileSource.Uploaded -> mediaSource.fileUpload.fileUpload.fileName
            }
            val thumbnailUri: Uri? = (mediaSource as? FileSource.Uploaded)?.fileUpload?.thumbnail?.fileUpload?.url?.url
            override fun modify(status: SendStatus): Message {
                return copy(baseMessage = baseMessage.copy(sendStatus = status))
            }

            public companion object {
                /**
                 * Create a new [Document] message in conversation.
                 *
                 * @param mediaSource local source of the document.
                 * @see FileLocal.Document
                 */
                public fun new(
                    conversationId: ConversationId,
                    mediaSource: FileSource.Local<FileLocal.Document, FileUpload.Document>,
                ): Document = Document(
                    BaseMessage(MessageId.new(), Clock.System.now(), MessageSender.Patient, SendStatus.ToBeSent, conversationId),
                    mediaSource,
                )
            }
        }
    }

    public data class Deleted internal constructor(override val baseMessage: BaseMessage) : Message() {
        override fun modify(status: SendStatus): Message {
            return copy(baseMessage = baseMessage.copy(sendStatus = status))
        }

        public companion object
    }

    public companion object
}
