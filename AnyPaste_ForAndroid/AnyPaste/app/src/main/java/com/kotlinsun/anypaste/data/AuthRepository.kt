package com.kotlinsun.anypaste.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.kotlinsun.anypaste.model.AuthUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

interface AuthRepository {
    val currentUser: AuthUser?
    val authState: Flow<AuthUser?>

    suspend fun createAccountWithEmail(
        email: String,
        password: String,
        displayName: String? = null,
    ): AuthUser

    suspend fun signInWithEmail(email: String, password: String): AuthUser
    suspend fun signInWithGoogleIdToken(idToken: String): AuthUser
    suspend fun sendPasswordResetEmail(email: String)
    suspend fun updateDisplayName(displayName: String): AuthUser
    suspend fun reloadCurrentUser(): AuthUser?
    fun signOut()
}

class FirebaseAuthRepository(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AuthRepository {
    override val currentUser: AuthUser?
        get() = firebaseAuth.currentUser?.toAuthUser()

    override val authState: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            trySend(auth.currentUser?.toAuthUser())
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    override suspend fun createAccountWithEmail(
        email: String,
        password: String,
        displayName: String?,
    ): AuthUser {
        require(email.isNotBlank()) { "이메일을 입력해 주세요." }
        require(password.length >= MIN_PASSWORD_LENGTH) {
            "비밀번호는 ${MIN_PASSWORD_LENGTH}자 이상이어야 합니다."
        }

        val result = firebaseAuth
            .createUserWithEmailAndPassword(email.trim(), password)
            .awaitResult()
        val user = result.user
            ?: throw IllegalStateException("생성된 사용자 정보를 가져오지 못했습니다.")

        val normalizedDisplayName = displayName?.trim().orEmpty()
        if (normalizedDisplayName.isNotEmpty()) {
            val profile = UserProfileChangeRequest.Builder()
                .setDisplayName(normalizedDisplayName)
                .build()
            user.updateProfile(profile).awaitResult()
            user.reload().awaitResult()
        }

        return (firebaseAuth.currentUser ?: user).toAuthUser()
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthUser {
        require(email.isNotBlank()) { "이메일을 입력해 주세요." }
        require(password.isNotEmpty()) { "비밀번호를 입력해 주세요." }

        val result = firebaseAuth
            .signInWithEmailAndPassword(email.trim(), password)
            .awaitResult()
        return result.user?.toAuthUser()
            ?: throw IllegalStateException("로그인된 사용자 정보를 가져오지 못했습니다.")
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): AuthUser {
        require(idToken.isNotBlank()) { "Google ID 토큰이 비어 있습니다." }

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = firebaseAuth.signInWithCredential(credential).awaitResult()
        return result.user?.toAuthUser()
            ?: throw IllegalStateException("로그인된 사용자 정보를 가져오지 못했습니다.")
    }

    override suspend fun sendPasswordResetEmail(email: String) {
        require(email.isNotBlank()) { "이메일을 입력해 주세요." }
        firebaseAuth.sendPasswordResetEmail(email.trim()).awaitResult()
    }

    override suspend fun updateDisplayName(displayName: String): AuthUser {
        val user = firebaseAuth.currentUser
            ?: throw IllegalStateException("로그인이 필요합니다.")
        val normalizedDisplayName = displayName.trim()
        require(normalizedDisplayName.isNotEmpty()) { "표시 이름을 입력해 주세요." }

        val profile = UserProfileChangeRequest.Builder()
            .setDisplayName(normalizedDisplayName)
            .build()
        user.updateProfile(profile).awaitResult()
        user.reload().awaitResult()
        return (firebaseAuth.currentUser ?: user).toAuthUser()
    }

    override suspend fun reloadCurrentUser(): AuthUser? {
        val user = firebaseAuth.currentUser ?: return null
        user.reload().awaitResult()
        return firebaseAuth.currentUser?.toAuthUser()
    }

    override fun signOut() {
        firebaseAuth.signOut()
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}

private fun FirebaseUser.toAuthUser(): AuthUser = AuthUser(
    uid = uid,
    email = email,
    displayName = displayName,
    photoUrl = photoUrl?.toString(),
    isEmailVerified = isEmailVerified,
    providers = providerData
        .map { it.providerId }
        .filterNot { it == FirebaseAuthProviderIds.FIREBASE },
)

private object FirebaseAuthProviderIds {
    const val FIREBASE = "firebase"
}
