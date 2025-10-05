package com.example.musiclab

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

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

    // NUOVO: Firebase Auth
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

    // Listeners per cambiamenti di stato login
    private val authStateListeners = mutableListOf<(Boolean, GoogleSignInAccount?) -> Unit>()

    fun initialize(context: Context) {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("830578813899-904297acp02tfboiujk01ahbl2v3133l.apps.googleusercontent.com")
            .requestEmail()
            .requestProfile()
            .requestId()
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, gso)

        // Controlla se c'è già un utente loggato
        currentUser = GoogleSignIn.getLastSignedInAccount(context)
        Log.d(TAG, "Initialized - Current user: ${currentUser?.email}")
        Log.d(TAG, "Firebase user: ${firebaseAuth.currentUser?.uid}")

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
        Log.d(TAG, "Firebase UID: ${firebaseAuth.currentUser?.uid}")
    }

    fun signIn(): Intent? {
        return googleSignInClient?.signInIntent
    }

    fun handleSignInResult(data: Intent?): Boolean {
        try {
            val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            currentUser = account
            Log.d(TAG, "Google Sign in successful - User: ${account.email}")

            // NUOVO: Autentica anche con Firebase
            firebaseAuthWithGoogle(account.idToken!!)

            return true

        } catch (e: ApiException) {
            Log.e(TAG, "Sign in failed with code: ${e.statusCode}", e)
            currentUser = null
            notifyAuthStateChanged()
            return false
        }
    }

    // NUOVO: Metodo per autenticare con Firebase usando il Google token
    private fun firebaseAuthWithGoogle(idToken: String) {
        Log.d(TAG, "Authenticating with Firebase...")

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "✅ Firebase authentication successful!")
                    Log.d(TAG, "Firebase UID: ${firebaseAuth.currentUser?.uid}")
                    notifyAuthStateChanged()
                } else {
                    Log.e(TAG, "❌ Firebase authentication failed", task.exception)
                    currentUser = null
                    notifyAuthStateChanged()
                }
            }
    }

    fun signOut(context: Context, onComplete: (Boolean) -> Unit) {
        // Sign out da Google
        googleSignInClient?.signOut()?.addOnCompleteListener { task ->
            val success = task.isSuccessful

            // Sign out da Firebase
            if (success) {
                firebaseAuth.signOut()
                currentUser = null
                Log.d(TAG, "Sign out successful (Google + Firebase)")
            } else {
                Log.e(TAG, "Sign out failed", task.exception)
            }

            notifyAuthStateChanged()
            onComplete(success)
        }
    }

    fun getCurrentUser(): GoogleSignInAccount? = currentUser

    fun isLoggedIn(): Boolean {
        // Verifica sia Google che Firebase
        return currentUser != null && firebaseAuth.currentUser != null
    }

    fun getUserEmail(): String? = currentUser?.email

    fun getUserName(): String? = currentUser?.displayName

    // NUOVO: Restituisce il Firebase UID invece del Google ID
    fun getUserId(): String? = firebaseAuth.currentUser?.uid

    // Per debugging
    fun getAuthStatus(): String {
        return if (isLoggedIn()) {
            "Logged in as: ${getUserName()} (${getUserEmail()})\nFirebase UID: ${getUserId()}"
        } else {
            "Not logged in"
        }
    }
}