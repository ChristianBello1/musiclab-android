package com.example.musiclab

import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class HelpActivity : AppCompatActivity() {

    private lateinit var backButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_help)

        Log.d("HelpActivity", "=== HELP ACTIVITY CREATED ===")

        setupViews()
    }

    private fun setupViews() {
        backButton = findViewById(R.id.btn_back_help)

        backButton.setOnClickListener {
            Log.d("HelpActivity", "Back button clicked")
            finish()
        }
    }
}