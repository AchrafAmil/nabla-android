package com.nabla.sdk.core.data.auth

import com.auth0.android.jwt.JWT
import com.nabla.sdk.core.domain.boundary.Logger
import com.nabla.sdk.core.domain.boundary.PatientRepository
import com.nabla.sdk.core.domain.boundary.SessionTokenProvider
import com.nabla.sdk.core.domain.boundary.TokenRepository
import com.nabla.sdk.core.domain.entity.AuthTokens
import com.nabla.sdk.core.kotlin.runCatchingCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TokenRepositoryImpl(
    private val tokenLocalDataSource: TokenLocalDataSource,
    private val tokenRemoteDataSource: TokenRemoteDataSource,
    private val sessionTokenProvider: SessionTokenProvider,
    private val patientRepository: PatientRepository,
    private val logger: Logger,
) : TokenRepository {

    private val refreshLock = Mutex()

    override fun initSession(refreshToken: String, accessToken: String?) {
        tokenLocalDataSource.setRefreshToken(refreshToken)
        tokenLocalDataSource.setAccessToken(accessToken)
    }

    override suspend fun getFreshAccessToken(forceRefreshAccessToken: Boolean): String {
        return refreshLock.withLock {
            getFreshAccessTokenUnsafe(forceRefreshAccessToken)
        }
    }

    private suspend fun getFreshAccessTokenUnsafe(
        forceRefreshAccessToken: Boolean
    ): String {
        logger.debug(
            message = "get fresh access token with forceRefreshAccessToken=$forceRefreshAccessToken...",
            tag = Logger.AUTH_TAG
        )
        val accessToken = tokenLocalDataSource.getAccessToken()
        if (accessToken.isValid() && !forceRefreshAccessToken) {
            logger.debug(
                message = "using still valid access token",
                tag = Logger.AUTH_TAG
            )
            return accessToken.toString()
        }
        val refreshToken = tokenLocalDataSource.getRefreshToken()
        if (refreshToken.isValid()) {
            logger.debug(
                message = "using still valid refresh token to refresh tokens",
                tag = Logger.AUTH_TAG
            )
            runCatchingCancellable {
                val freshTokens = tokenRemoteDataSource.refresh(refreshToken.toString())
                tokenLocalDataSource.setAuthTokens(freshTokens)
                logger.debug(
                    message = "tokens refreshed, using refreshed access token",
                    tag = Logger.AUTH_TAG
                )
                return freshTokens.accessToken
            }.onFailure { refreshTokenError ->
                // Fallback to refreshing session
                logger.debug(
                    message = "fail refresh tokens, fallback to refresh session",
                    tag = Logger.AUTH_TAG
                )

                return runCatchingCancellable {
                    renewSessionAuthTokens().accessToken.also {
                        logger.debug(message = "success refresh session", tag = Logger.AUTH_TAG)
                    }
                }.getOrElse { renewSessionError ->
                    // Ensure no exception is suppressed
                    logger.debug(message = "fail refreshing session", tag = Logger.AUTH_TAG)
                    throw renewSessionError.apply { addSuppressed(refreshTokenError) }
                }
            }
        }
        // no access or refresh tokens are valid, fallback to refreshing session
        return renewSessionAuthTokens().accessToken
    }

    override suspend fun clearSession() {
        refreshLock.withLock {
            tokenLocalDataSource.clear()
        }
    }

    private suspend fun renewSessionAuthTokens(): AuthTokens {
        val patientId = patientRepository.getPatientId()
            ?: throw IllegalStateException("No patient set")
        return sessionTokenProvider.fetchNewSessionAuthTokens(patientId).getOrThrow().also {
            tokenLocalDataSource.setAuthTokens(it)
        }
    }
}

private fun JWT?.isValid(): Boolean {
    return this != null && !this.isExpired(1)
}
