package com.maticcm.openwebuiclient

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.maticcm.openwebuiclient.databinding.ActivityIpInputBinding

class IpInputActivity : AppCompatActivity() {
    private lateinit var binding: ActivityIpInputBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check for saved URL before inflating layout
        val savedUrl = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .getString("SAVED_URL", null)
        val setupComplete = getSharedPreferences("OpenWebUIClient", MODE_PRIVATE)
            .getBoolean("setup_complete", false)

        if (savedUrl != null && setupComplete) {
            startWebViewActivity(savedUrl)
            return
        }

        binding = ActivityIpInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
    }

    private fun setupViews() {
        // Show all views immediately
        binding.titleTextView.alpha = 1f
        binding.ipInputLayout.alpha = 1f
        binding.submitButton.alpha = 1f

        binding.submitButton.setOnClickListener {
            val url = binding.ipInputEditText.text.toString().trim()
            
            if (url.isEmpty()) {
                showError("Please enter a URL")
                return@setOnClickListener
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                showError("URL must start with http:// or https://")
                return@setOnClickListener
            }

            // Save the URL and mark setup as complete
            getSharedPreferences("AppPrefs", MODE_PRIVATE)
                .edit()
                .putString("SAVED_URL", url)
                .apply()

            getSharedPreferences("OpenWebUIClient", MODE_PRIVATE)
                .edit()
                .putBoolean("setup_complete", true)
                .apply()

            startWebViewActivity(url)
        }
    }

    private fun startWebViewActivity(url: String) {
        startActivity(Intent(this, WebViewActivity::class.java).apply {
            putExtra("URL", url)
        })
        finish()
    }

    private fun showError(message: String) {
        binding.errorTextView.text = message
        binding.errorTextView.isVisible = true
    }

    override fun onBackPressed() {
        // Go back to welcome screen
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
} 