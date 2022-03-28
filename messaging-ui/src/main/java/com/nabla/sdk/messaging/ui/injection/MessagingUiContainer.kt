package com.nabla.sdk.messaging.ui.injection

import com.nabla.sdk.messaging.core.injection.MessagingContainer
import com.nabla.sdk.messaging.ui.scene.ConversationListViewModel

class MessagingUiContainer(
    private val messageContainer: MessagingContainer
) {
    fun createConversationListViewModel(): ConversationListViewModel {
        return ConversationListViewModel(messageContainer.conversationRepository)
    }
}