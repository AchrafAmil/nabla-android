package com.nabla.sdk.messaging.core.data.conversation

import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.api.Optional
import com.apollographql.apollo3.cache.normalized.FetchPolicy
import com.apollographql.apollo3.cache.normalized.fetchPolicy
import com.apollographql.apollo3.cache.normalized.watch
import com.nabla.sdk.core.data.apollo.CacheUpdateOperation
import com.nabla.sdk.core.data.apollo.updateCache
import com.nabla.sdk.core.domain.entity.PaginatedList
import com.nabla.sdk.graphql.ConversationListQuery
import com.nabla.sdk.graphql.ConversationsEventsSubscription
import com.nabla.sdk.graphql.MaskAsSeenMutation
import com.nabla.sdk.graphql.fragment.ConversationFragment
import com.nabla.sdk.graphql.type.OpaqueCursorPage
import com.nabla.sdk.messaging.core.data.apollo.GqlMapper
import com.nabla.sdk.messaging.core.data.apollo.GqlTypeHelper.modify
import com.nabla.sdk.messaging.core.domain.entity.Conversation
import com.nabla.sdk.messaging.core.domain.entity.ConversationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn

internal class GqlConversationDataSource constructor(
    private val coroutineScope: CoroutineScope,
    private val apolloClient: ApolloClient,
    private val mapper: GqlMapper,
) {
    private val conversationsEventsFlow = apolloClient.subscription(ConversationsEventsSubscription())
        .toFlow()
        .map {
            it.dataAssertNoErrors
        }.onEach {
            it.conversations?.event?.onConversationCreatedEvent?.conversation?.conversationFragment?.let {
                insertConversationToConversationsListCache(it)
            }
        }.shareIn(
            scope = coroutineScope,
            replay = 0,
            started = SharingStarted.WhileSubscribed(replayExpirationMillis = 0)
        )

    private suspend fun insertConversationToConversationsListCache(
        conversation: ConversationFragment,
    ) {
        val query = FIRST_CONVERSATIONS_PAGE_QUERY
        apolloClient.updateCache(query) { cachedQueryData ->
            if (cachedQueryData == null) return@updateCache CacheUpdateOperation.Ignore()
            val newItem = ConversationListQuery.Conversation(
                conversation.__typename,
                conversation
            )
            val mergedConversations = listOf(newItem) + cachedQueryData.conversations.conversations
            val mergedQueryData = cachedQueryData.modify(mergedConversations)
            CacheUpdateOperation.Write(mergedQueryData)
        }
    }

    suspend fun loadMoreConversationsInCache() {
        val firstPageQuery = FIRST_CONVERSATIONS_PAGE_QUERY
        apolloClient.updateCache(firstPageQuery) { cachedQueryData ->
            if (cachedQueryData == null || !cachedQueryData.conversations.hasMore) {
                return@updateCache CacheUpdateOperation.Ignore()
            }
            val updatedQuery = firstPageQuery.copy(
                pageInfo = OpaqueCursorPage(
                    cursor = Optional.presentIfNotNull(cachedQueryData.conversations.nextCursor)
                )
            )
            val freshQueryData = apolloClient.query(updatedQuery)
                .fetchPolicy(FetchPolicy.NetworkOnly)
                .execute()
                .dataAssertNoErrors
            val mergedConversations =
                (cachedQueryData.conversations.conversations + freshQueryData.conversations.conversations)
                    .distinctBy { it.conversationFragment.id }
            val mergedQueryData = freshQueryData.modify(mergedConversations)
            CacheUpdateOperation.Write(mergedQueryData)
        }
    }

    fun watchConversations(): Flow<PaginatedList<Conversation>> {
        val query = FIRST_CONVERSATIONS_PAGE_QUERY
        val dataFlow = apolloClient.query(query)
            .watch()
            .map { response -> response.dataAssertNoErrors }
            .map { queryData ->
                val items = queryData.conversations.conversations.map {
                    mapper.mapToConversation(it.conversationFragment)
                }
                return@map PaginatedList(items, queryData.conversations.hasMore)
            }
        return flowOf(conversationsEventsFlow, dataFlow)
            .flattenMerge()
            .filterIsInstance()
    }

    suspend fun markConversationAsRead(conversationId: ConversationId) {
        apolloClient.mutation(MaskAsSeenMutation(conversationId.value)).execute()
    }

    companion object {
        private val FIRST_CONVERSATIONS_PAGE_QUERY = ConversationListQuery(OpaqueCursorPage(cursor = Optional.Absent))
    }
}