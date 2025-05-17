package com.slabstech.dhwani.voiceai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.material.bottomnavigation.BottomNavigationView

object NavigationUtils {
    private const val TAG = "NavigationUtils"

    fun setupBottomNavigation(context: Context, bottomNavigation: BottomNavigationView, currentItemId: Int) {
        // Set the currently selected item
        bottomNavigation.selectedItemId = currentItemId
        Log.d(TAG, "Setting up bottom navigation, current item: $currentItemId")

        bottomNavigation.setOnItemSelectedListener { item ->
            Log.d(TAG, "Navigating to item: ${item.itemId}")
            when (item.itemId) {
                R.id.nav_voice -> {
                    if (currentItemId != R.id.nav_voice) {
                        Log.d(TAG, "Switching to VoiceDetectionActivity")
                        val intent = Intent(context, VoiceDetectionActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } else {
                        Log.d(TAG, "Already on VoiceDetectionActivity, no action needed")
                    }
                    true
                }/* TODO - add translate as menuView
                R.id.nav_translate -> {
                    if (currentItemId != R.id.nav_translate) {
                        Log.d(TAG, "Switching to TranslateActivity")
                        val intent = Intent(context, TranslateActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } else {
                        Log.d(TAG, "Already on TranslateActivity, no action needed")
                    }
                    true
                }*/
                R.id.nav_answer -> {
                    if (currentItemId != R.id.nav_answer) {
                        Log.d(TAG, "Switching to AnswerActivity")
                        val intent = Intent(context, AnswerActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } else {
                        Log.d(TAG, "Already on AnswerActivity, no action needed")
                    }
                    true
                }
                R.id.nav_docs -> {
                    if (currentItemId != R.id.nav_docs) {
                        Log.d(TAG, "Switching to DocsActivity")
                        val intent = Intent(context, DocsActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    } else {
                        Log.d(TAG, "Already on DocsActivity, no action needed")
                    }
                    true
                }
                else -> {
                    Log.w(TAG, "Unknown navigation item selected: ${item.itemId}")
                    false
                }
            }
        }
    }
}