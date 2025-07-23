package com.maticcm.openwebuiclient

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.os.Handler
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.maticcm.openwebuiclient.databinding.ActivityWebviewBinding
import com.maticcm.openwebuiclient.databinding.DialogSettingsBinding
import com.maticcm.openwebuiclient.databinding.LongPressIndicatorBinding
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.FileProvider
import java.util.regex.Pattern
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest

class WebViewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebviewBinding
    private lateinit var baseUrl: String
    private var isWebViewReady = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val CONNECTION_TIMEO = 30000L // 30 seconds timeout
    private val timeoutRunnable = Runnable {
        if (!isWebViewReady) {
            Log.e("WebViewActivity", "Connection timeout")
            binding.progressBar.isVisible = false
            showConnectionError()
        }
    }

    // Gesture detection
    private var longPressStartTime = 0L
    private var isLongPressing = false
    private val LONG_PRESS_DURATION = 3000L // 3 seconds
    private val REQUIRED_FINGERS = 4
    private var longPressIndicator: AlertDialog? = null
    private var progressUpdateRunnable: Runnable? = null
    private var activePointers = mutableSetOf<Int>()

    // Validation patterns
    private val YOUTUBE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{11}$")
    private val MODEL_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$")
    private val TOOL_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$")
    private val QUERY_PATTERN = Pattern.compile("^[\\w\\s\\p{Punct}]+$")
    private val HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9.-]+$")

    // URL validation
    private val URL_PATTERN = Pattern.compile(
        "^https?://" + // http:// or https://
        "([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+" + // domain
        "[a-zA-Z]{2,}" + // TLD
        "(/[a-zA-Z0-9-._~:/?#\\[\\]@!\\$&'\\(\\)\\*\\+,;=]*)?$" // path and query
    )

    // Permission handling
    private val CAMERA_PERMISSION_REQUEST = 100
    private val MICROPHONE_PERMISSION_REQUEST = 101
    private var pendingCameraCapture = false
    private var pendingMicrophoneAccess = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("WebViewActivity", "Permission granted")
            if (pendingCameraCapture) {
                pendingCameraCapture = false
                launchCamera()
            }
            if (pendingMicrophoneAccess) {
                pendingMicrophoneAccess = false
                // Inject JavaScript to notify the web page that permission was granted
                injectMicrophonePermissionGranted()
            }
        } else {
            Log.e("WebViewActivity", "Permission denied")
            if (pendingCameraCapture) {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
            if (pendingMicrophoneAccess) {
                injectMicrophonePermissionDenied()
            }
        }
    }

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            filePathCallback?.onReceiveValue(uris.toTypedArray())
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val singleFileChooserLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            filePathCallback?.onReceiveValue(arrayOf(it))
        } ?: run {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraImageUri != null) {
            filePathCallback?.onReceiveValue(arrayOf(cameraImageUri!!))
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
        cameraImageUri = null
    }

    private fun createImageFile(): File {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "JPEG_" + timeStamp + "_"
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            if (storageDir == null) {
                Log.e("WebViewActivity", "Failed to get external files directory")
                throw IllegalStateException("Failed to get external files directory")
            }
            return File.createTempFile(
                imageFileName,
                ".jpg",
                storageDir
            )
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error creating image file", e)
            throw e
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize WebView settings globally for better performance
        WebView.enableSlowWholeDocumentDraw()
        
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request microphone permission immediately
        if (!checkMicrophonePermission()) {
            requestMicrophonePermission()
        }

        // Get the saved URL
        baseUrl = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .getString("SAVED_URL", null) ?: return

        setupWebView()
        handleIntent(intent)

        // Handle back button press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            allowContentAccess = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // Note: These settings are deprecated but still needed for some functionality
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            @Suppress("DEPRECATION")
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccess = true
            @Suppress("DEPRECATION")
            setAllowFileAccess(true)
            @Suppress("DEPRECATION")
            setAllowContentAccess(true)
            // Enable media permissions
            @Suppress("DEPRECATION")
            mediaPlaybackRequiresUserGesture = false
        }

        // Enable mixed content for Android 5.0 and above
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            binding.webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Add JavaScript interface for microphone permission
        binding.webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun requestMicrophonePermission() {
                runOnUiThread {
                    Log.d("WebViewActivity", "Microphone permission requested from JavaScript")
                    if (checkMicrophonePermission()) {
                        Log.d("WebViewActivity", "Microphone permission already granted")
                        injectMicrophonePermissionGranted()
                    } else {
                        Log.d("WebViewActivity", "Requesting microphone permission")
                        pendingMicrophoneAccess = true
                        requestMicrophonePermission()
                    }
                }
            }

            @JavascriptInterface
            fun log(message: String) {
                Log.d("WebViewActivity", "JavaScript: $message")
            }

            @JavascriptInterface
            fun openLink(url: String) {
                runOnUiThread {
                    Log.d("WebViewActivity", "Opening link: $url")
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("WebViewActivity", "Error opening link: $url", e)
                    }
                }
            }
        }, "Android")

        binding.webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        // Set up touch event handling
        binding.webView.setOnTouchListener { _, event ->
            @Suppress("DEPRECATION")
            handleTouchEvent(event)
            false // Return false to allow WebView to handle the event as well
        }

        // Set up WebChromeClient with media permissions
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                Log.d("WebViewActivity", "Permission request received: ${request.resources.joinToString()}")
                // Always grant microphone permission if we have it
                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    if (checkMicrophonePermission()) {
                        Log.d("WebViewActivity", "Granting microphone permission")
                        // Inject JavaScript to notify the web page that permission was granted
                        injectMicrophonePermissionGranted()
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        Log.d("WebViewActivity", "Requesting microphone permission")
                        pendingMicrophoneAccess = true
                        requestMicrophonePermission()
                    }
                } else {
                    // Grant all other permissions
                    Log.d("WebViewActivity", "Granting other permissions: ${request.resources.joinToString()}")
                    request.grant(request.resources)
                }
            }

            @Deprecated("Deprecated in Java", ReplaceWith("onShowFileChooser(webView, filePathCallback, fileChooserParams)"))
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                try {
                    Log.d("WebViewActivity", "onShowFileChooser called")
                    this@WebViewActivity.filePathCallback?.onReceiveValue(null)
                    this@WebViewActivity.filePathCallback = filePathCallback

                    val captureEnabled = fileChooserParams?.isCaptureEnabled ?: false
                    val multiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                    Log.d("WebViewActivity", "Capture enabled: $captureEnabled, Multiple: $multiple")

                    if (captureEnabled) {
                        if (checkCameraPermission()) {
                            launchCamera()
                        } else {
                            pendingCameraCapture = true
                            requestCameraPermission()
                        }
                    } else {
                        // Handle file selection
                        if (multiple) {
                            fileChooserLauncher.launch("*/*")
                        } else {
                            singleFileChooserLauncher.launch("*/*")
                        }
                    }
                    return true
                } catch (e: Exception) {
                    Log.e("WebViewActivity", "Error in onShowFileChooser", e)
                    filePathCallback?.onReceiveValue(null)
                    this@WebViewActivity.filePathCallback = null
                    return false
                }
            }
        }

        // Set up WebViewClient
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                timeoutHandler.removeCallbacks(timeoutRunnable)
                Log.e("WebViewActivity", "WebView error: ${error?.description}")
                showConnectionError()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                binding.progressBar.isVisible = false
                binding.webView.alpha = 1f
                isWebViewReady = true
                Log.d("WebViewActivity", "WebView loaded: $url")
                injectImageHandlingScript()
                injectLinkHandlingScript()
                
                // Inject JavaScript to handle microphone access
                val microphoneScript = """
                    window.Android.log('Page loaded, setting up microphone access');
                    
                    // Create a global function to handle microphone access
                    window.handleMicrophoneAccess = function() {
                        window.Android.log('handleMicrophoneAccess called');
                        return new Promise(function(resolve, reject) {
                            window.Android.log('Requesting microphone permission');
                            window.Android.requestMicrophonePermission();
                            resolve();
                        });
                    };
                    
                    // Override getUserMedia if available
                    if (window.navigator.mediaDevices) {
                        window.Android.log('Media devices available');
                        const originalGetUserMedia = window.navigator.mediaDevices.getUserMedia;
                        window.navigator.mediaDevices.getUserMedia = function(constraints) {
                            window.Android.log('getUserMedia called with constraints: ' + JSON.stringify(constraints));
                            return window.handleMicrophoneAccess();
                        };
                    } else {
                        window.Android.log('Media devices not available');
                    }
                    
                    // Add error handling
                    window.addEventListener('error', function(e) {
                        window.Android.log('JavaScript error: ' + e.message);
                    });
                """.trimIndent()
                binding.webView.evaluateJavascript(microphoneScript, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url?.startsWith(baseUrl) == true) {
                    return false
                }
                url?.let {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                    startActivity(intent)
                }
                return true
            }
        }

        // Set up download listener
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                
                request.apply {
                    setTitle(filename)
                    setDescription("Downloading file...")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    addRequestHeader("User-Agent", userAgent)
                }

                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = downloadManager.enqueue(request)
                Log.d("WebViewActivity", "Started download: $downloadId for file: $filename")

                // Register broadcast receiver to track download completion
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                            if (id == downloadId) {
                                val query = DownloadManager.Query().setFilterById(downloadId)
                                val cursor = downloadManager.query(query)
                                if (cursor.moveToFirst()) {
                                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                        Log.d("WebViewActivity", "Download completed successfully: $filename")
                                    } else {
                                        Log.e("WebViewActivity", "Download failed: $filename")
                                    }
                                }
                                cursor.close()
                                unregisterReceiver(this)
                            }
                        }
                    }
                }
                registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            } catch (e: Exception) {
                Log.e("WebViewActivity", "Error starting download", e)
            }
        }

        binding.progressBar.isVisible = true
        
        // Start timeout timer
        timeoutHandler.postDelayed(timeoutRunnable, CONNECTION_TIMEO)
        
        // Load the URL
        binding.webView.loadUrl(baseUrl)
    }

    private fun injectCompatibilityScript() {
        val javascript = """
            // Universal button click handler
            function handleButtonClick(e) {
                const target = e.target;
                if (target.tagName === 'BUTTON' || 
                    target.type === 'button' || 
                    target.getAttribute('role') === 'button' ||
                    target.classList.contains('button')) {
                    e.preventDefault();
                    const clickEvent = new MouseEvent('click', {
                        view: window,
                        bubbles: true,
                        cancelable: true
                    });
                    target.dispatchEvent(clickEvent);
                }
            }

            // Universal form submission handler
            function handleFormSubmit(e) {
                if (e.target.tagName === 'FORM') {
                    e.preventDefault();
                    const submitEvent = new Event('submit', {
                        bubbles: true,
                        cancelable: true
                    });
                    e.target.dispatchEvent(submitEvent);
                }
            }

            // Add event listeners
            document.addEventListener('click', handleButtonClick, true);
            document.addEventListener('submit', handleFormSubmit, true);

            // Fix for dynamically added elements
            const observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(mutation) {
                    if (mutation.addedNodes.length) {
                        mutation.addedNodes.forEach(function(node) {
                            if (node.nodeType === 1) { // Element node
                                if (node.tagName === 'BUTTON' || 
                                    node.type === 'button' || 
                                    node.getAttribute('role') === 'button' ||
                                    node.classList.contains('button')) {
                                    node.addEventListener('click', handleButtonClick);
                                }
                                if (node.tagName === 'FORM') {
                                    node.addEventListener('submit', handleFormSubmit);
                                }
                            }
                        });
                    }
                });
            });

            observer.observe(document.body, {
                childList: true,
                subtree: true
            });
        """.trimIndent()
        
        binding.webView.evaluateJavascript(javascript, null)
    }

    private fun injectImageHandlingScript() {
        val javascript = """
            (function() {
                let longPressTimer;
                let longPressedImage = null;

                // Function to handle image long press
                function handleImageLongPress(e) {
                    const img = e.target;
                    if (img.tagName === 'IMG') {
                        longPressedImage = img;
                        
                        // Create download button if it doesn't exist
                        let downloadBtn = document.getElementById('imageDownloadBtn');
                        if (!downloadBtn) {
                            downloadBtn = document.createElement('button');
                            downloadBtn.id = 'imageDownloadBtn';
                            downloadBtn.innerHTML = 'Download';
                            downloadBtn.style.cssText = `
                                position: fixed;
                                top: 20px;
                                right: 20px;
                                z-index: 9999;
                                padding: 10px 20px;
                                background-color: #4CAF50;
                                color: white;
                                border: none;
                                border-radius: 5px;
                                cursor: pointer;
                                font-size: 16px;
                                box-shadow: 0 2px 5px rgba(0,0,0,0.2);
                            `;
                            document.body.appendChild(downloadBtn);
                        }
                        
                        // Update download button click handler
                        downloadBtn.onclick = function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            if (longPressedImage) {
                                window.location.href = longPressedImage.src;
                            }
                            this.remove();
                            longPressedImage = null;
                        };
                        
                        // Show download button
                        downloadBtn.style.display = 'block';
                    }
                }

                // Add touch event listeners for long press
                document.addEventListener('touchstart', function(e) {
                    if (e.target.tagName === 'IMG') {
                        longPressTimer = setTimeout(function() {
                            handleImageLongPress(e);
                        }, 500); // 500ms for long press
                    }
                });

                document.addEventListener('touchend', function() {
                    clearTimeout(longPressTimer);
                });

                document.addEventListener('touchmove', function() {
                    clearTimeout(longPressTimer);
                });

                // Remove download button when clicking elsewhere
                document.addEventListener('click', function(e) {
                    const downloadBtn = document.getElementById('imageDownloadBtn');
                    if (downloadBtn && e.target !== downloadBtn) {
                        downloadBtn.remove();
                        longPressedImage = null;
                    }
                }, true);
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(javascript, null)
    }

    private fun injectLinkHandlingScript() {
        val javascript = """
            (function() {
                window.Android.log('Setting up link handling');
                
                // Function to handle link clicks
                function handleLinkClick(e) {
                    const target = e.target;
                    let link = null;
                    
                    // Check if clicked element is a link or inside a link
                    if (target.tagName === 'A') {
                        link = target;
                    } else if (target.closest('a')) {
                        link = target.closest('a');
                    }
                    
                    if (link && link.href) {
                        const url = link.href;
                        window.Android.log('Link clicked: ' + url);
                        
                        // Check if it's an external link (not same origin)
                        const currentOrigin = window.location.origin;
                        const linkUrl = new URL(url, window.location.href);
                        
                        if (linkUrl.origin !== currentOrigin) {
                            e.preventDefault();
                            e.stopPropagation();
                            window.Android.openLink(url);
                            return false;
                        }
                    }
                    
                    return true;
                }
                
                // Add click listener to document
                document.addEventListener('click', handleLinkClick, true);
                
                // Handle dynamically added links
                const observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(mutation) {
                        if (mutation.addedNodes.length) {
                            mutation.addedNodes.forEach(function(node) {
                                if (node.nodeType === 1) { // Element node
                                    // Check if the added node is a link
                                    if (node.tagName === 'A') {
                                        node.addEventListener('click', handleLinkClick, true);
                                    }
                                    // Check for links inside the added node
                                    const links = node.querySelectorAll ? node.querySelectorAll('a') : [];
                                    for (let i = 0; i < links.length; i++) {
                                        links[i].addEventListener('click', handleLinkClick, true);
                                    }
                                }
                            });
                        }
                    });
                });
                
                observer.observe(document.body, {
                    childList: true,
                    subtree: true
                });
                
                window.Android.log('Link handling setup complete');
            })();
        """.trimIndent()
        
        binding.webView.evaluateJavascript(javascript, null)
    }

    private fun showConnectionError() {
        Log.e("WebViewActivity", "Connection failed or timed out")
        binding.webView.loadUrl("about:blank")
    }

    private fun handleIntent(intent: Intent) {
        Log.d("WebViewActivity", "Handling intent: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    Log.d("WebViewActivity", "Received URL: $uri")
                    if (isWebViewReady) {
                        loadUrl(uri)
                    } else {
                        binding.webView.post {
                            loadUrl(uri)
                        }
                    }
                }
            }
            Intent.ACTION_SEND -> {
                when {
                    intent.type?.startsWith("image/") == true -> {
                        val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM, Uri::class.java)
                        imageUri?.let { uri ->
                            Log.d("WebViewActivity", "Received image URI: $uri")
                            if (isWebViewReady) {
                                sendImageToWebView(uri)
                            } else {
                                binding.webView.post {
                                    sendImageToWebView(uri)
                                }
                            }
                        }
                    }
                    intent.type == "text/plain" -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        text?.let {
                            Log.d("WebViewActivity", "Received text: $it")
                            if (isWebViewReady) {
                                sendTextToWebView(it)
                            } else {
                                binding.webView.post {
                                    sendTextToWebView(it)
                                }
                            }
                        }
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM, Uri::class.java)
                imageUris?.let { uris ->
                    Log.d("WebViewActivity", "Received multiple images: ${uris.size}")
                    if (isWebViewReady) {
                        sendMultipleImagesToWebView(uris)
                    } else {
                        binding.webView.post {
                            sendMultipleImagesToWebView(uris)
                        }
                    }
                }
            }
        }
    }

    private fun loadUrl(uri: Uri) {
        try {
            val processedUrl = when (uri.scheme) {
                "openwebui" -> {
                    // Handle openwebui:// scheme
                    val host = uri.host
                    if (host != null && HOST_PATTERN.matcher(host).matches()) {
                        "https://$host${uri.path}${uri.query?.let { "?$it" } ?: ""}"
                    } else {
                        Log.e("WebViewActivity", "Invalid host in openwebui:// URL: $host")
                        return
                    }
                }
                else -> {
                    // Use the URL exactly as provided
                    uri.toString()
                }
            }

            // Validate and process URL parameters
            val finalUrl = processUrlParameters(processedUrl)
            Log.d("WebViewActivity", "Loading final URL: $finalUrl")
            
            // Set up WebViewClient with error handling
            binding.webView.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    Log.e("WebViewActivity", "WebView error: ${error?.description}")
                    showConnectionError()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    binding.progressBar.isVisible = false
                    binding.webView.alpha = 1f
                    isWebViewReady = true
                    Log.d("WebViewActivity", "WebView loaded: $url")
                    injectImageHandlingScript()
                    injectLinkHandlingScript()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url?.startsWith(baseUrl) == true) {
                        return false
                    }
                    url?.let {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                        startActivity(intent)
                    }
                    return true
                }
            }
            
            binding.webView.loadUrl(finalUrl)
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error loading URL", e)
        }
    }

    private fun processUrlParameters(url: String): String {
        try {
            val uri = Uri.parse(url)
            val queryParams = uri.queryParameterNames
            val processedParams = mutableMapOf<String, String>()

            // Process each parameter
            for (param in queryParams) {
                when (param) {
                    "models", "model" -> {
                        // Validate model names
                        val models = uri.getQueryParameter(param)?.split(",")?.filter { it.isNotBlank() }
                        if (!models.isNullOrEmpty()) {
                            val validModels = models.filter { MODEL_NAME_PATTERN.matcher(it).matches() }
                            if (validModels.isNotEmpty()) {
                                processedParams[param] = validModels.joinToString(",")
                                Log.d("WebViewActivity", "Validated models: $validModels")
                            } else {
                                Log.w("WebViewActivity", "No valid models found in: $models")
                            }
                        }
                    }
                    "youtube" -> {
                        // Validate YouTube video ID
                        val videoId = uri.getQueryParameter(param)
                        if (!videoId.isNullOrBlank() && YOUTUBE_ID_PATTERN.matcher(videoId).matches()) {
                            processedParams[param] = videoId
                            Log.d("WebViewActivity", "Validated YouTube ID: $videoId")
                        } else {
                            Log.w("WebViewActivity", "Invalid YouTube ID: $videoId")
                        }
                    }
                    "web-search" -> {
                        // Validate web-search parameter
                        val webSearch = uri.getQueryParameter(param)
                        if (webSearch == "true") {
                            processedParams[param] = "true"
                            Log.d("WebViewActivity", "Web search enabled")
                        } else {
                            Log.w("WebViewActivity", "Invalid web-search value: $webSearch")
                        }
                    }
                    "tools", "tool-ids" -> {
                        // Validate tool IDs
                        val tools = uri.getQueryParameter(param)?.split(",")?.filter { it.isNotBlank() }
                        if (!tools.isNullOrEmpty()) {
                            val validTools = tools.filter { TOOL_ID_PATTERN.matcher(it).matches() }
                            if (validTools.isNotEmpty()) {
                                processedParams[param] = validTools.joinToString(",")
                                Log.d("WebViewActivity", "Validated tools: $validTools")
                            } else {
                                Log.w("WebViewActivity", "No valid tools found in: $tools")
                            }
                        }
                    }
                    "call" -> {
                        // Validate call parameter
                        val call = uri.getQueryParameter(param)
                        if (call == "true") {
                            processedParams[param] = "true"
                            Log.d("WebViewActivity", "Call overlay enabled")
                        } else {
                            Log.w("WebViewActivity", "Invalid call value: $call")
                        }
                    }
                    "q" -> {
                        // Validate query parameter
                        val query = uri.getQueryParameter(param)
                        if (!query.isNullOrBlank() && QUERY_PATTERN.matcher(query).matches()) {
                            processedParams[param] = query
                            Log.d("WebViewActivity", "Validated query: $query")
                        } else {
                            Log.w("WebViewActivity", "Invalid query: $query")
                        }
                    }
                    "temporary-chat" -> {
                        // Validate temporary-chat parameter
                        val tempChat = uri.getQueryParameter(param)
                        if (tempChat == "true") {
                            processedParams[param] = "true"
                            Log.d("WebViewActivity", "Temporary chat enabled")
                        } else {
                            Log.w("WebViewActivity", "Invalid temporary-chat value: $tempChat")
                        }
                    }
                }
            }

            // Rebuild the URL with processed parameters
            val baseUrl = uri.buildUpon().clearQuery()
            for ((key, value) in processedParams) {
                baseUrl.appendQueryParameter(key, value)
            }

            val finalUrl = baseUrl.build().toString()
            Log.d("WebViewActivity", "Final processed URL: $finalUrl")
            return finalUrl
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error processing URL parameters", e)
            return url
        }
    }

    private fun sendImageToWebView(uri: Uri) {
        try {
            val shareData = JSONObject().apply {
                put("type", "image")
                put("uri", uri.toString())
                put("model", getSelectedModel())
                put("prefilledMessage", getPrefilledMessage("image"))
            }
            Log.d("WebViewActivity", "Sending image data: ${shareData.toString()}")
            injectShareData(shareData)
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error sending image to WebView", e)
        }
    }

    private fun sendTextToWebView(text: String) {
        try {
            val shareData = JSONObject().apply {
                put("type", "text")
                put("content", text)
                put("model", getSelectedModel())
                put("prefilledMessage", getPrefilledMessage("text"))
            }
            Log.d("WebViewActivity", "Sending text data: ${shareData.toString()}")
            injectShareData(shareData)
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error sending text to WebView", e)
        }
    }

    private fun sendMultipleImagesToWebView(uris: ArrayList<Uri>) {
        try {
            val uriStrings = uris.map { it.toString() }
            val shareData = JSONObject().apply {
                put("type", "multiple_images")
                put("uris", uriStrings)
                put("model", getSelectedModel())
                put("prefilledMessage", getPrefilledMessage("image"))
            }
            Log.d("WebViewActivity", "Sending multiple images data: ${shareData.toString()}")
            injectShareData(shareData)
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error sending multiple images to WebView", e)
        }
    }

    private fun injectShareData(shareData: JSONObject) {
        try {
            val javascript = """
                if (window.handleSharedContent) {
                    window.handleSharedContent(${shareData.toString()});
                } else {
                    console.error('handleSharedContent function not found');
                }
            """.trimIndent()
            
            Log.d("WebViewActivity", "Injecting JavaScript: $javascript")
            binding.webView.evaluateJavascript(javascript) { result ->
                Log.d("WebViewActivity", "JavaScript evaluation result: $result")
            }
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error injecting share data", e)
        }
    }

    private fun getSelectedModel(): String {
        return getSharedPreferences("OpenWebUIClient", MODE_PRIVATE)
            .getString("selected_model", "default") ?: "default"
    }

    private fun getPrefilledMessage(type: String): String {
        return getSharedPreferences("OpenWebUIClient", MODE_PRIVATE)
            .getString("prefilled_message_$type", "") ?: ""
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d("WebViewActivity", "New intent received")
        intent?.let { handleIntent(it) }
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        binding.webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java", ReplaceWith("handleTouchEvent(event)"))
    private fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerId = event.getPointerId(event.actionIndex)
                activePointers.add(pointerId)
                Log.d("WebViewActivity", "Pointer down: $pointerId, Total pointers: ${activePointers.size}")
                
                if (activePointers.size == REQUIRED_FINGERS) {
                    longPressStartTime = System.currentTimeMillis()
                    isLongPressing = true
                    showLongPressIndicator()
                    startProgressUpdate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                activePointers.remove(pointerId)
                Log.d("WebViewActivity", "Pointer up: $pointerId, Total pointers: ${activePointers.size}")
                
                if (activePointers.size < REQUIRED_FINGERS) {
                    isLongPressing = false
                    hideLongPressIndicator()
                    timeoutHandler.removeCallbacksAndMessages(null)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointers.clear()
                isLongPressing = false
                hideLongPressIndicator()
                timeoutHandler.removeCallbacksAndMessages(null)
            }
        }
        return false
    }

    private fun showLongPressIndicator() {
        val indicatorBinding = LongPressIndicatorBinding.inflate(layoutInflater)
        longPressIndicator = AlertDialog.Builder(this)
            .setView(indicatorBinding.root)
            .setCancelable(false)
            .create()
        longPressIndicator?.show()
    }

    private fun hideLongPressIndicator() {
        longPressIndicator?.dismiss()
        longPressIndicator = null
        progressUpdateRunnable?.let { timeoutHandler.removeCallbacks(it) }
    }

    private fun startProgressUpdate() {
        val indicatorBinding = LongPressIndicatorBinding.inflate(layoutInflater)
        progressUpdateRunnable = object : Runnable {
            override fun run() {
                if (isLongPressing) {
                    val elapsed = System.currentTimeMillis() - longPressStartTime
                    val progress = (elapsed * 100 / LONG_PRESS_DURATION).toInt()
                    indicatorBinding.progressBar.progress = progress.coerceAtMost(100)
                    
                    if (elapsed >= LONG_PRESS_DURATION) {
                        showSettingsDialog()
                        hideLongPressIndicator()
                    } else {
                        timeoutHandler.postDelayed(this, 16) // Update every 16ms
                    }
                }
            }
        }
        timeoutHandler.post(progressUpdateRunnable!!)
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        val currentUrl = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .getString("SAVED_URL", "") ?: ""
        
        dialogBinding.serverUrlInput.setText(currentUrl)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.saveButton.setOnClickListener {
            val newUrl = dialogBinding.serverUrlInput.text.toString().trim()
            if (newUrl.isNotEmpty()) {
                saveServerUrl(newUrl)
                dialog.dismiss()
                // Reload WebView with new URL
                binding.webView.loadUrl(newUrl)
                // Reset WebView state
                isWebViewReady = false
                binding.progressBar.isVisible = true
                binding.webView.alpha = 0f
            }
        }

        dialog.show()
    }

    private fun validateUrl(url: String): Boolean {
        return URL_PATTERN.matcher(url).matches()
    }

    private fun saveServerUrl(url: String) {
        getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .edit()
            .putString("SAVED_URL", url)
            .apply()
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        try {
            val imageFile = createImageFile()
            Log.d("WebViewActivity", "Created image file: ${imageFile.absolutePath}")

            cameraImageUri = FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                imageFile
            )
            Log.d("WebViewActivity", "Created camera URI: $cameraImageUri")

            // Grant URI permissions
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }

            // Check if there's a camera app available
            if (takePictureIntent.resolveActivity(packageManager) != null) {
                cameraLauncher.launch(cameraImageUri)
                Log.d("WebViewActivity", "Launched camera")
            } else {
                Log.e("WebViewActivity", "No camera app available")
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        } catch (e: Exception) {
            Log.e("WebViewActivity", "Error launching camera", e)
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private fun injectMicrophonePermissionGranted() {
        val javascript = """
            window.Android.log('Microphone permission granted');
            if (window.handleMicrophoneAccess) {
                window.handleMicrophoneAccess().then(function() {
                    window.Android.log('Microphone access resolved');
                }).catch(function(error) {
                    window.Android.log('Microphone access error: ' + error);
                });
            }
        """.trimIndent()
        binding.webView.evaluateJavascript(javascript, null)
    }

    private fun injectMicrophonePermissionDenied() {
        val javascript = """
            window.Android.log('Microphone permission denied');
            if (window.handleMicrophoneAccess) {
                window.handleMicrophoneAccess().catch(function(error) {
                    window.Android.log('Microphone access error: ' + error);
                });
            }
        """.trimIndent()
        binding.webView.evaluateJavascript(javascript, null)
    }

    private fun checkMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicrophonePermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
} 