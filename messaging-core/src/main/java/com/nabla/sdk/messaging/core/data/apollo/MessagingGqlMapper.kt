package com.nabla.sdk.messaging.core.data.apollo

import com.apollographql.apollo3.api.Optional
import com.nabla.sdk.core.domain.entity.BaseFileUpload
import com.nabla.sdk.core.domain.entity.EphemeralUrl
import com.nabla.sdk.core.domain.entity.FileUpload
import com.nabla.sdk.core.domain.entity.MimeType
import com.nabla.sdk.core.domain.entity.Uri
import com.nabla.sdk.core.domain.entity.User
import com.nabla.sdk.core.domain.entity.asUuid
import com.nabla.sdk.graphql.fragment.ConversationFragment
import com.nabla.sdk.graphql.fragment.DocumentFileUploadFragment
import com.nabla.sdk.graphql.fragment.EphemeralUrlFragment
import com.nabla.sdk.graphql.fragment.ImageFileUploadFragment
import com.nabla.sdk.graphql.fragment.MessageFragment
import com.nabla.sdk.graphql.fragment.ProviderFragment
import com.nabla.sdk.graphql.fragment.ProviderInConversationFragment
import com.nabla.sdk.graphql.type.SendMessageContentInput
import com.nabla.sdk.graphql.type.SendTextMessageInput
import com.nabla.sdk.messaging.core.domain.entity.BaseMessage
import com.nabla.sdk.messaging.core.domain.entity.Conversation
import com.nabla.sdk.messaging.core.domain.entity.Message
import com.nabla.sdk.messaging.core.domain.entity.MessageId
import com.nabla.sdk.messaging.core.domain.entity.MessageSender
import com.nabla.sdk.messaging.core.domain.entity.MessageStatus
import com.nabla.sdk.messaging.core.domain.entity.ProviderInConversation
import com.nabla.sdk.messaging.core.domain.entity.toConversationId
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days

internal class MessagingGqlMapper {
    fun mapToConversation(fragment: ConversationFragment): Conversation {
        return Conversation(
            id = fragment.id.toConversationId(),
            inboxPreviewTitle = "",
            inboxPreviewSubtitle = "",
            lastModified = Clock.System.now(),
            patientUnreadMessageCount = fragment.unreadMessageCount,
            providersInConversation = fragment.providers.map {
                mapToProviderInConversation(it.providerInConversationFragment)
            }
        )
    }

    private fun mapToProviderInConversation(
        fragment: ProviderInConversationFragment
    ): ProviderInConversation {
        return ProviderInConversation(
            provider = mapToProvider(fragment.provider.providerFragment),
            isTyping = fragment.isTyping,
            seenUntil = Clock.System.now(), // TODO we need Date custom-scalar config
        )
    }

    fun mapToMessage(messageFragment: MessageFragment): Message {
        val sender = mapToMessageSender(messageFragment.author)
        val baseMessage = BaseMessage(
            id = MessageId.Remote(
                clientId = messageFragment.clientId,
                remoteId = messageFragment.id
            ),
            sentAt = null,
            sender = sender,
            status = MessageStatus.Sent,
            conversationId = messageFragment.conversation.id.toConversationId()
        )
        messageFragment.content?.onTextMessageContent?.textMessageContentFragment?.let {
            return@let Message.Text(
                message = baseMessage,
                text = it.text
            )
        }
        messageFragment.content?.onImageMessageContent?.imageMessageContentFragment?.let {
            return@let Message.Media.Image(
                message = baseMessage,
                image = mapToFileUploadImage(it.fileUpload.imageFileUploadFragment),
            )
        }
        messageFragment.content?.onDocumentMessageContent?.documentMessageContentFragment?.let {
            return@let Message.Media.Document(
                message = baseMessage,
                document = mapToFileUploadDocument(it.fileUpload.documentFileUploadFragment)
            )
        }
        error {
            "Unknown message content mapping for $messageFragment"
        }
    }

    private fun mapToMessageSender(author: MessageFragment.Author): MessageSender {
        author.onPatient?.let { return@let MessageSender.Patient }
        author.onProvider?.providerFragment?.let { return@let mapToProvider(it) }
        error {
            "Unknown author mapping for $author"
        }
    }

    private fun mapToProvider(providerFragment: ProviderFragment): User.Provider {
        return User.Provider(
            id = providerFragment.id,
            avatar = null,
            firstName = "",
            lastName = "",
            title = null,
            prefix = null,
        )
    }

    private fun mapToEphemeralUrl(ephemeralUrlFragment: EphemeralUrlFragment): EphemeralUrl {
        return EphemeralUrl(
            expiresAt = Clock.System.now().plus(5.days), // TODO we need Date custom-scalar config,
            url = Uri(ephemeralUrlFragment.url)
        )
    }

    private fun mapToMimeType(value: String): MimeType {
        // TODO Add common mimetypes
        return MimeType.Generic(value)
    }

    private fun mapToFileUploadImage(
        imageFileUploadFragment: ImageFileUploadFragment
    ): FileUpload.Image {
        return FileUpload.Image(
            width = imageFileUploadFragment.width,
            height = imageFileUploadFragment.height,
            fileUpload = BaseFileUpload(
                id = imageFileUploadFragment.uuid.asUuid(),
                url = mapToEphemeralUrl(imageFileUploadFragment.url.ephemeralUrlFragment),
                fileName = imageFileUploadFragment.fileName,
                mimeType = mapToMimeType(imageFileUploadFragment.mimeType)
            )
        )
    }

    private fun mapToFileUploadDocument(
        documentFileUploadFragment: DocumentFileUploadFragment
    ): FileUpload.Document {
        return FileUpload.Document(
            thumbnail = documentFileUploadFragment.thumbnail?.imageFileUploadFragment?.let {
                mapToFileUploadImage(it)
            },
            fileUpload = BaseFileUpload(
                id = documentFileUploadFragment.uuid.asUuid(),
                url = mapToEphemeralUrl(documentFileUploadFragment.url.ephemeralUrlFragment),
                fileName = documentFileUploadFragment.fileName,
                mimeType = mapToMimeType(documentFileUploadFragment.mimeType)
            )
        )
    }

    fun mapToSendMessageContentInput(message: Message): SendMessageContentInput {
        return when (message) {
            is Message.Deleted -> TODO()
            is Message.Media.Document -> TODO()
            is Message.Media.Image -> TODO()
            is Message.Text -> mapToSendMessageContentInput(message)
        }
    }

    private fun mapToSendMessageContentInput(textMessage: Message.Text): SendMessageContentInput {
        return SendMessageContentInput(
            textInput = Optional.presentIfNotNull(
                SendTextMessageInput(
                    text = textMessage.text
                )
            )
        )
    }
}
