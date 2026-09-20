package com.chengjieli.medication

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import com.chengjieli.medication.ui.MedicationApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as MedicationApplication).graph
    private var openTodayRequest by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.data?.scheme == "medication") openTodayRequest++
        setContent { MedicationApp(graph, openTodayRequest) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data?.scheme == "medication") openTodayRequest++
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { runCatching { graph.refresh() } }
    }
}
