package app.enso.browser

import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Step 1 engine check: one GeckoView showing a bundled test page. */
class MainActivity : ComponentActivity() {
    private val session = GeckoSession()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        session.open((application as EnsoApplication).geckoRuntime)

        val geckoView = GeckoView(this).also { it.setSession(session) }
        val root = FrameLayout(this).apply { addView(geckoView) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        if (savedInstanceState == null) {
            session.loadUri(TEST_PAGE)
        }
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }

    private companion object {
        const val TEST_PAGE = "resource://android/assets/test.html"
    }
}
