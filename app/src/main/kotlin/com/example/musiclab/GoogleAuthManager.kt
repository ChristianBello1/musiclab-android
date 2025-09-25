package com.example.musiclab

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task

class GoogleAuthManager private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: GoogleAuthManager? = null

        fun getInstance(): GoogleAuthManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GoogleAuthManager().also { INSTANCE = it }
            }
        }

        private const val TAG = "GoogleAuthManager"
    }

    private var googleSignInClient: GoogleSignInClient? = null
    private var currentUser: GoogleSignInAccount? = null

    // Listeners per cambiamenti di stato login
    private val authStateListeners = mutableListOf<(Boolean, GoogleSignInAccount?) -> Unit>()

    fun initialize(context: Context) {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestId()
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, gso)

        // Controlla se c'è già un utente loggato
        currentUser = GoogleSignIn.getLastSignedInAccount(context)
        Log.d(TAG, "Initialized - Current user: ${currentUser?.email}")

        // Notifica lo stato iniziale
        notifyAuthStateChanged()
    }

    fun addAuthStateListener(listener: (Boolean, GoogleSignInAccount?) -> Unit) {
        authStateListeners.add(listener)
        // Notifica immediatamente lo stato corrente
        listener.invoke(isLoggedIn(), currentUser)
    }

    fun removeAuthStateListener(listener: (Boolean, GoogleSignInAccount?) -> Unit) {
        authStateListeners.remove(listener)
    }

    private fun notifyAuthStateChanged() {
        val isLoggedIn = isLoggedIn()
        authStateListeners.forEach { listener ->
            listener.invoke(isLoggedIn, currentUser)
        }
        Log.d(TAG, "Auth state changed - Logged in: $isLoggedIn, User: ${currentUser?.email}")
    }

    fun signIn(): Intent? {
        return googleSignInClient?.signInIntent
    }

    fun handleSignInResult(data: Intent?): Boolean {
        try {
            val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            currentUser = account
            Log.d(TAG, "Sign in successful - User: ${account.email}")

            notifyAuthStateChanged()
            return true

        } catch (e: ApiException) {
            Log.e(TAG, "Sign in failed with code: ${e.statusCode}", e)
            currentUser = null
            notifyAuthStateChanged()
            return false
        }
    }

    fun signOut(context: Context, onComplete: (Boolean) -> Unit) {
        googleSignInClient?.signOut()?.addOnCompleteListener { task ->
            val success = task.isSuccessful
            if (success) {
                currentUser = null
                Log.d(TAG, "Sign out successful")
            } else {
                Log.e(TAG, "Sign out failed", task.exception)
            }
            notifyAuthStateChanged()
            onComplete(success)
        }
    }

    fun getCurrentUser(): GoogleSignInAccount? = currentUser

    fun isLoggedIn(): Boolean = currentUser != null

    fun getUserEmail(): String? = currentUser?.email

    fun getUserName(): String? = currentUser?.displayName

    fun getUserId(): String? = currentUser?.id

    // Per debugging
    fun getAuthStatus(): String {
        return if (isLoggedIn()) {
            "Logged in as: ${getUserName()} (${getUserEmail()})"
        } else {
            "Not logged in"
        }
    }
}