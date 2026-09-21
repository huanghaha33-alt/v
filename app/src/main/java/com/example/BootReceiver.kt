package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast: $action")
        
        if (Intent.ACTION_BOOT_COMPLETED == action || "android.intent.action.QUICKBOOT_POWERON" == action) {
            val sharedPrefs = context.getSharedPreferences("TV_LIVE_PREFS", Context.MODE_PRIVATE)
            val isAutoStartEnabled = sharedPrefs.getBoolean("AUTO_START_ENABLED", true)
            
            if (isAutoStartEnabled) {
                try {
                    val launchIntent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(launchIntent)
                    Log.i("BootReceiver", "Successfully launched MainActivity on boot!")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start MainActivity: ${e.message}", e)
                }
            } else {
                Log.i("BootReceiver", "Auto-start is disabled in settings.")
            }
        }
    }
}
