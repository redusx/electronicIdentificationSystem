package com.example.countercamtest

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.countercamtest.ui.theme.CounterCamTestTheme

class NFCActivity : ComponentActivity() {
    
    companion object {
        const val TAG = "NFCActivity"
        const val EXTRA_MRZ_RESULT = "mrz_result"
    }
    
    private var nfcAdapter: NfcAdapter? = null
    private var mrzResult: TCMRZReader.TCMRZResult? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Get MRZ result from intent
        mrzResult = intent.getSerializableExtra(EXTRA_MRZ_RESULT) as? TCMRZReader.TCMRZResult
        
        if (mrzResult == null) {
            Log.e(TAG, "MRZ result not found in intent")
            finish()
            return
        }
        
        // Initialize NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        setContent {
            CounterCamTestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NFCNavigation(
                        mrzResult = mrzResult!!,
                        onFinish = { finish() }
                    )
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        enableNFCForegroundDispatch()
    }
    
    override fun onPause() {
        super.onPause()
        disableNFCForegroundDispatch()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNFCIntent(intent)
    }
    
    private fun enableNFCForegroundDispatch() {
        nfcAdapter?.let { adapter ->
            val intent = Intent(applicationContext, this.javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 
                0, 
                intent, 
                PendingIntent.FLAG_MUTABLE
            )
            
            adapter.enableForegroundDispatch(this, pendingIntent, null, null)
        }
    }
    
    private fun disableNFCForegroundDispatch() {
        nfcAdapter?.disableForegroundDispatch(this)
    }
    
    private fun handleNFCIntent(intent: Intent) {
        val action = intent.action
        if (NfcAdapter.ACTION_TECH_DISCOVERED == action || 
            NfcAdapter.ACTION_TAG_DISCOVERED == action) {
            
            val tag = intent.extras?.getParcelable<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let { nfcTag ->
                Log.d(TAG, "NFC tag detected: $nfcTag")
                // Trigger NFC reading process
                mrzResult?.let { result ->
                    handleNFCTagReading(nfcTag, result)
                }
            }
        }
    }
    
    private fun handleNFCTagReading(tag: Tag, mrzResult: TCMRZReader.TCMRZResult) {
        // This will be handled by a shared state or callback mechanism
        // For now, we'll use a simple approach with a mutable state
        nfcReadingCallback?.invoke(tag)
    }
    
    private var nfcReadingCallback: ((Tag) -> Unit)? = null
    
    fun setNFCReadingCallback(callback: (Tag) -> Unit) {
        nfcReadingCallback = callback
    }
}

@Composable
fun NFCNavigation(
    mrzResult: TCMRZReader.TCMRZResult,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? NFCActivity
    
    val navController = rememberNavController()
    var nfcResult by remember { mutableStateOf<NFCReadResult?>(null) }
    var detectedTag by remember { mutableStateOf<Tag?>(null) }
    
    // Set up NFC callback
    LaunchedEffect(Unit) {
        activity?.setNFCReadingCallback { tag ->
            detectedTag = tag
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = "nfc_reading"
    ) {
        composable("nfc_reading") {
            NFCReadingScreenWithCallback(
                mrzResult = mrzResult,
                detectedTag = detectedTag,
                onNavigateBack = onFinish,
                onNFCReadComplete = { result ->
                    nfcResult = result
                    navController.navigate("nfc_result")
                },
                onTagProcessed = {
                    detectedTag = null // Reset the tag after processing
                }
            )
        }
        
        composable("nfc_result") {
            nfcResult?.let { result ->
                NFCResultScreen(
                    nfcResult = result,
                    onNavigateBack = {
                        navController.navigateUp()
                    },
                    onNavigateToCamera = onFinish
                )
            }
        }
    }
}