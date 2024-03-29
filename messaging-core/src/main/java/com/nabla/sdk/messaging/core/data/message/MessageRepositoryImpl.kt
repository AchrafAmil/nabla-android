package com.nabla.sdk.messaging.core.data.message

import com.nabla.sdk.core.domain.boundary.FileUploadRepository
import com.nabla.sdk.core.domain.entity.NablaException
import com.nabla.sdk.core.domain.entity.PaginatedConversationWithMessages
import com.nabla.sdk.core.kotlin.SharedSingle
import com.nabla.sdk.core.kotlin.sharedSingleIn
import com.nabla.sdk.messaging.core.domain.boundary.MessageRepository
import com.nabla.sdk.messaging.core.domain.entity.ConversationId
import com.nabla.sdk.messaging.core.domain.entity.FileSource
import com.nabla.sdk.messaging.core.domain.entity.Message
import com.nabla.sdk.messaging.core.domain.entity.MessageId
import com.nabla.sdk.messaging.core.domain.entity.SendStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MessageRepositoryImpl(
    private val repoScope: CoroutineScope,
    private val localMessageDataSource: LocalMessageDataSource,
    private val gqlMessageDataSource: GqlMessageDataSource,
    private val fileUploadRepository: FileUploadRepository,
) : MessageRepository {

    private val loadMoreConversationMessagesSharedSingleLock = Mutex()
    private val loadMoreConversationMessagesSharedSingleMap = mutableMapOf<ConversationId, SharedSingle<Unit>>()

    override fun watchConversationMessages(conversationId: ConversationId): Flow<PaginatedConversationWithMessages> {
        val gqlConversationAndMessagesFlow = gqlMessageDataSource.watchRemoteConversationWithMessages(conversationId)
        val localMessagesFlow = localMessageDataSource.watchLocalMessages(conversationId)
        val messageFlow = gqlConversationAndMessagesFlow.combine(localMessagesFlow) { gqlConversationAndMessages, localMessages ->
            return@combine combineGqlAndLocalInfo(localMessages, gqlConversationAndMessages)
        }
        return messageFlow
    }

    private fun combineGqlAndLocalInfo(
        localMessages: Collection<Message>,
        gqlPaginatedConversationAndMessages: PaginatedConversationWithMessages
    ): PaginatedConversationWithMessages {
        val (localMessagesToMerge, localMessagesToAdd) = localMessages.partition {
            it.baseMessage.id.stableId in gqlPaginatedConversationAndMessages.conversationWithMessages.messages.map { it.baseMessage.id.stableId }
        }.let { Pair(it.first.associateBy { it.baseMessage.id.stableId }, it.second) }
        val mergedMessages = gqlPaginatedConversationAndMessages.conversationWithMessages.messages.map { gqlMessage ->
            val mergeResult = localMessagesToMerge[gqlMessage.baseMessage.id.stableId]?.let { localMessage ->
                mergeMessage(gqlMessage, localMessage)
            }
            mergeResult ?: gqlMessage
        }
        val allMessages = (mergedMessages + localMessagesToAdd).sortedByDescending { it.baseMessage.sentAt }
        return gqlPaginatedConversationAndMessages.copy(
            conversationWithMessages = gqlPaginatedConversationAndMessages.conversationWithMessages.copy(
                messages = allMessages,
            ),
        )
    }

    private fun mergeMessage(gqlMessage: Message, localMessage: Message): Message? {
        if (gqlMessage is Message.Media.Image && localMessage is Message.Media.Image) {
            val gqlMessageMediaSource = gqlMessage.mediaSource
            if (gqlMessageMediaSource !is FileSource.Uploaded) return null
            val localMessageMediaSource = localMessage.mediaSource
            if (localMessageMediaSource !is FileSource.Local) return null
            return gqlMessage.modify(
                mediaSource = FileSource.Uploaded(
                    fileLocal = localMessageMediaSource.fileLocal,
                    fileUpload = gqlMessageMediaSource.fileUpload
                )
            )
        }
        return null
    }

    override suspend fun loadMoreMessages(conversationId: ConversationId) {
        val loadMoreConversationMessagesSharedSingle = loadMoreConversationMessagesSharedSingleLock.withLock {
            loadMoreConversationMessagesSharedSingleMap.getOrPut(conversationId) {
                sharedSingleIn(repoScope) {
                    gqlMessageDataSource.loadMoreConversationMessagesInCache(conversationId)
                }
            }
        }
        loadMoreConversationMessagesSharedSingle.await()
    }

    override suspend fun sendMessage(message: Message) {
        val messageId = message.baseMessage.id
        if (messageId !is MessageId.Local) {
            throw NablaException.InvalidMessage("Can't send a message with an id that is not Local: $messageId")
        }

        if (message.sendStatus !in setOf(SendStatus.ToBeSent, SendStatus.ErrorSending)) {
            throw NablaException.InvalidMessage("Can't send a message with status: ${message.sendStatus}")
        }

        localMessageDataSource.putMessage(message.modify(SendStatus.Sending))

        runCatching { // we're interested in cancellations
            when (message) {
                is Message.Deleted -> throw NablaException.InvalidMessage("Can't send a deleted message")
                is Message.Media.Document -> sendMediaMessageOp(message, messageId)
                is Message.Media.Image -> sendMediaMessageOp(message, messageId)
                is Message.Text -> sendTextMessageOp(message, messageId)
            }
        }.onFailure { throwable ->
            when (throwable) {
                is CancellationException ->
                    localMessageDataSource
                        .removeMessage(message.conversationId, messageId)
                else ->
                    localMessageDataSource
                        .putMessage(message.modify(SendStatus.ErrorSending))
            }

            throw throwable
        }.onSuccess {
            val sentMessage = message.modify(SendStatus.Sent)
            localMessageDataSource.putMessage(
                sentMessage
            )
        }
    }

    override suspend fun retrySendingMessage(conversationId: ConversationId, localMessageId: MessageId.Local) {
        val localMessage = localMessageDataSource.getMessage(conversationId, localMessageId)
        if (localMessage != null) {
            sendMessage(localMessage)
        } else {
            throw NablaException.MessageNotFound(conversationId, localMessageId)
        }
    }

    override suspend fun setTyping(conversationId: ConversationId, isTyping: Boolean) {
        gqlMessageDataSource.setTyping(conversationId, isTyping)
    }

    override suspend fun deleteMessage(conversationId: ConversationId, messageId: MessageId) {
        when (messageId) {
            is MessageId.Local -> localMessageDataSource.removeMessage(conversationId, messageId)
            is MessageId.Remote -> gqlMessageDataSource.deleteMessage(conversationId, messageId)
        }
    }

    private suspend fun sendMediaMessageOp(
        mediaMessage: Message.Media<*, *>,
        messageId: MessageId.Local
    ) {
        val mediaSource = mediaMessage.mediaSource
        if (mediaSource !is FileSource.Local) {
            throw NablaException.InvalidMessage("Can't send a media message with a media source that is not local")
        }
        val fileName = if (mediaMessage is Message.Media.Document) {
            mediaMessage.documentName
        } else null
        val fileUploadId = fileUploadRepository.uploadFile(mediaSource.fileLocal.uri, fileName)
        when (mediaMessage) {
            is Message.Media.Document -> {
                gqlMessageDataSource.sendDocumentMessage(
                    mediaMessage.baseMessage.conversationId,
                    messageId.clientId,
                    fileUploadId
                )
            }
            is Message.Media.Image -> {
                gqlMessageDataSource.sendImageMessage(
                    mediaMessage.baseMessage.conversationId,
                    messageId.clientId,
                    fileUploadId
                )
            }
        }
    }

    private suspend fun sendTextMessageOp(message: Message.Text, messageId: MessageId.Local) {
        gqlMessageDataSource.sendTextMessage(
            message.baseMessage.conversationId,
            messageId.clientId,
            message.text
        )
    }
}
