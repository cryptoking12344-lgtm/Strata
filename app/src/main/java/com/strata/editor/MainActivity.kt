package com.strata.editor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.strata.editor.data.ProStore
import com.strata.editor.data.ProjectStore
import com.strata.editor.engine.ProxyMaker
import com.strata.editor.model.Project
import com.strata.editor.model.newId
import com.strata.editor.ui.EditorScreen
import com.strata.editor.ui.ExportScreen
import com.strata.editor.ui.HomeScreen
import com.strata.editor.ui.LocalStrata
import com.strata.editor.ui.NewProjectSpec
import com.strata.editor.ui.ProScreen
import com.strata.editor.ui.StrataTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data class Editor(val projectId: String, val importUris: List<Uri> = emptyList()) : Screen
    data class Export(val projectId: String) : Screen
    data object Pro : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var store: ProjectStore
    private lateinit var proStore: ProStore
    private lateinit var proxies: ProxyMaker

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        proStore = ProStore(this)
        proxies = ProxyMaker(this)
        setContent { App() }
    }

    override fun onDestroy() {
        proxies.cancelAll()
        super.onDestroy()
    }

    @Composable
    private fun App() {
        var pro by remember { mutableStateOf(proStore.pro) }
        val setPro: (Boolean) -> Unit = { pro = it; proStore.pro = it }
        val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
        var projects by remember { mutableStateOf(store.list()) }
        var pendingSpec by remember { mutableStateOf<NewProjectSpec?>(null) }

        fun push(s: Screen) { stack.add(s) }
        fun pop() {
            if (stack.size > 1) stack.removeAt(stack.lastIndex)
            if (stack.last() == Screen.Home) projects = store.list()
        }

        val pickForNew = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val spec = pendingSpec ?: return@rememberLauncherForActivityResult
            pendingSpec = null
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            val name = "Project " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
            val p = Project(name = name, canvasW = spec.w, canvasH = spec.h, fps = spec.fps)
            store.save(p)
            push(Screen.Editor(p.id, uris))
        }

        StrataTheme(pro) {
            val c = LocalStrata.current
            Box(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding()) {
                when (val s = stack.last()) {
                    Screen.Home -> HomeScreen(
                        projects = projects,
                        pro = pro,
                        onTogglePro = setPro,
                        onNew = { spec -> pendingSpec = spec; pickForNew.launch(arrayOf("video/*", "image/*")) },
                        onOpen = { push(Screen.Editor(it.id)) },
                        onDelete = { store.delete(it.id); projects = store.list() },
                        onDuplicate = { p ->
                            store.save(p.copy(id = newId(), name = p.name + " copy", updatedAt = System.currentTimeMillis()))
                            projects = store.list()
                        },
                        onOpenPro = { push(Screen.Pro) },
                    )
                    is Screen.Editor -> {
                        val p = remember(s.projectId) { store.load(s.projectId) }
                        if (p == null) { pop(); return@Box }
                        EditorScreen(
                            initial = p,
                            pendingImport = s.importUris,
                            onImportConsumed = { if (stack.lastOrNull() == s) stack[stack.lastIndex] = Screen.Editor(s.projectId) },
                            store = store,
                            proxies = proxies,
                            pro = pro,
                            onBack = { pop() },
                            onExport = { push(Screen.Export(it.id)) },
                            onOpenPro = { push(Screen.Pro) },
                            onTogglePro = setPro,
                        )
                    }
                    is Screen.Export -> {
                        val p = remember(s.projectId) { store.load(s.projectId) }
                        if (p == null) { pop(); return@Box }
                        ExportScreen(p, pro, onBack = { pop() }, onTogglePro = setPro, onOpenPro = { push(Screen.Pro) })
                    }
                    Screen.Pro -> {
                        BackHandler { pop() }
                        ProScreen(pro, setPro) { pop() }
                    }
                }
            }
        }
    }
}
