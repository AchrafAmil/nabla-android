package com.nabla.sdk.messaging.core.injection

import com.apollographql.apollo3.ApolloClient
import com.nabla.sdk.core.domain.boundary.FileUploadRepository
import com.nabla.sdk.messaging.core.data.apollo.GqlMapper
import com.nabla.sdk.messaging.core.data.conversation.ConversationRepositoryImpl
import com.nabla.sdk.messaging.core.data.conversation.GqlConversationDataSource
import com.nabla.sdk.messaging.core.data.message.GqlMessageDataSource
import com.nabla.sdk.messaging.core.data.message.LocalMessageDataSource
import com.nabla.sdk.messaging.core.data.message.MessageRepositoryImpl
import com.nabla.sdk.messaging.core.domain.boundary.ConversationRepository
import com.nabla.sdk.messaging.core.domain.boundary.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal class MessagingContainer(
    apolloClient: ApolloClient,
    fileUploadRepository: FileUploadRepository,
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gqlMapper = GqlMapper()
    private val localMessageDataSource = LocalMessageDataSource()
    private val gqlMessageDataSource = GqlMessageDataSource(
        apolloClient = apolloClient,
        mapper = gqlMapper,
        coroutineScope = repoScope
    )
    private val gqlConversationDataSource = GqlConversationDataSource(
        coroutineScope = repoScope,
        apolloClient = apolloClient,
        mapper = gqlMapper
    )

    private val conversationRepositoryImpl = ConversationRepositoryImpl(
        repoScope = repoScope,
        gqlConversationDataSource = gqlConversationDataSource,
    )

    private val messageRepositoryImpl = MessageRepositoryImpl(
        repoScope = repoScope,
        localMessageDataSource = localMessageDataSource,
        gqlMessageDataSource = gqlMessageDataSource,
        fileUploadRepository = fileUploadRepository,
    )

    val conversationRepository: ConversationRepository = conversationRepositoryImpl
    val messageRepository: MessageRepository = messageRepositoryImpl
}
