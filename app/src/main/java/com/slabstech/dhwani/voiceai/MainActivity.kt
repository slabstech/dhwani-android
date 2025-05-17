package com.slabstech.dhwani.voiceai

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isDarkTheme = prefs.getBoolean("dark_theme", false)
        setTheme(if (isDarkTheme) R.style.Theme_DhwaniVoiceAI_Dark else R.style.Theme_DhwaniVoiceAI_Light)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)

        // Handle bottom navigation
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_answer -> {
                    startActivity(Intent(this, AnswerActivity::class.java))
                    finish() // Finish MainActivity to prevent back navigation
                    true
                }/* TODO- add translate as menuView
                R.id.nav_translate -> {
                    startActivity(Intent(this, TranslateActivity::class.java))
                    true
                }*/
                R.id.nav_docs -> {
                    startActivity(Intent(this, DocsActivity::class.java))
                    true
                }
                R.id.nav_voice -> {
                    startActivity(Intent(this, VoiceDetectionActivity::class.java))
                    true
                }
                else -> false
            }
        }

        // Launch AnswerActivity by default and finish MainActivity
        if (savedInstanceState == null) {
            startActivity(Intent(this, VoiceDetectionActivity::class.java))
            finish()
        }

        bottomNavigation.selectedItemId = R.id.nav_voice
    }
}