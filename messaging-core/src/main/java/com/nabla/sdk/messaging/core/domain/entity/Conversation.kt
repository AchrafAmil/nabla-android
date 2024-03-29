package com.nabla.sdk.messaging.core.domain.entity

import com.benasher44.uuid.Uuid
import kotlinx.datetime.Instant

@JvmInline
public value class ConversationId internal constructor(public val value: Uuid)

public fun Uuid.toConversationId(): ConversationId = ConversationId(this)

public data class Conversation(
    val id: ConversationId,
    val title: String?,
    val description: String?,
    val inboxPreviewTitle: String,
    val lastMessagePreview: String?,
    val lastModified: Instant,
    val patientUnreadMessageCount: Int,
    val providersInConversation: List<ProviderInConversation>,
) {
    public companion object
}
