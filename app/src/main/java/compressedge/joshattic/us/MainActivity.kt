package compressedge.joshattic.us

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import compressedge.joshattic.us.ui.CompressorApp
import compressedge.joshattic.us.ui.theme.CompressorTheme
import compressedge.joshattic.us.viewmodel.CompressorViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<CompressorViewModel>()

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("video/") == true) {
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (uri != null) {
                viewModel.updateSelectedUris(this, listOf(uri))
            }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.type?.startsWith("video/") == true) {
            val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }

            if (!uris.isNullOrEmpty()) {
                viewModel.updateSelectedUris(this, uris.filterNotNull())
            }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val isTablet = resources.getBoolean(R.bool.is_tablet)
        if (!isTablet) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        enableEdgeToEdge()

        // Handle the initial share intent. Later shares arrive through onNewIntent.
        handleShareIntent(intent)

        setContent {
            CompressorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CompressorApp(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }
}
