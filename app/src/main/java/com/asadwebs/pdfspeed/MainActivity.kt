package com.asadwebs.pdfspeed

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.asadwebs.pdfspeed.data.Doc
import com.asadwebs.pdfspeed.data.DocStore
import com.asadwebs.pdfspeed.scan.ReviewScreen
import com.asadwebs.pdfspeed.scan.ScannerScreen
import com.asadwebs.pdfspeed.ui.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PDFSpeedTheme {
                Surface(Modifier.fillMaxSize(), color = Cream) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val store = remember { DocStore(context) }
    var docs by remember { mutableStateOf(store.load()) }
    fun refresh() { docs = store.load() }
    var pendingCapture by remember { mutableStateOf<java.io.File?>(null) }
    var pendingTarget by remember { mutableStateOf("new") }
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val showBar = route in listOf("home", "library", "tools", "me")

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
            composable("home") { HomeScreen(docs.firstOrNull(), nav) }
            composable("library") { LibraryScreen(docs, store, { refresh() }, nav) }
            composable("tools") { ToolsScreen(nav) }
            composable("me") { MeScreen(docs.size) }
            composable("scanner/{docId}") { entry ->
                val docId = entry.arguments?.getString("docId") ?: "new"
                ScannerScreen(
                    onCaptured = { file ->
                        pendingCapture = file
                        pendingTarget = docId
                        nav.navigate("review")
                    },
                    onClose = { nav.popBackStack() }
                )
            }
            composable("review") {
                val file = pendingCapture
                if (file == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    ReviewScreen(
                        imageFile = file,
                        onDone = { processed ->
                            if (pendingTarget == "new") {
                                val name = "Scan " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
                                val doc = store.addPageToNewDoc(processed, name)
                                refresh()
                                pendingCapture = null
                                nav.navigate("doc/${doc.id}") { popUpTo("home") { inclusive = false } }
                            } else {
                                store.addPage(pendingTarget, processed)
                                refresh()
                                pendingCapture = null
                                nav.navigate("doc/$pendingTarget") { popUpTo("home") { inclusive = false } }
                            }
                        },
                        onRetake = { nav.popBackStack() }
                    )
                }
            }
            composable("doc/{docId}") { entry ->
                val docId = entry.arguments?.getString("docId")
                val doc = docs.firstOrNull { it.id == docId }
                if (doc != null) DocScreen(doc, store, { refresh() }, nav) else LaunchedEffect(Unit) { nav.popBackStack() }
            }
        }
        if (showBar) BottomBar(route, nav, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun BottomBar(route: String?, nav: NavController, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(bottom = 14.dp).widthIn(max = 430.dp).fillMaxWidth(0.94f),
        shape = RoundedCornerShape(999.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            BarItem("Home", route == "home") { nav.navigate("home") { launchSingleTop = true } }
            BarItem("Files", route == "library") { nav.navigate("library") { launchSingleTop = true } }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Button(
                    onClick = { nav.navigate("scanner/new") },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk),
                    modifier = Modifier.size(56.dp).offset(y = (-14).dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Scan", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
            }
            BarItem("Tools", route == "tools") { nav.navigate("tools") { launchSingleTop = true } }
            BarItem("Me", route == "me") { nav.navigate("me") { launchSingleTop = true } }
        }
    }
}

@Composable
fun RowScope.BarItem(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Text(label, color = if (active) Ink else Muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 12.5.sp)
    }
}

@Composable
fun Kicker(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall)

@Composable
fun HomeScreen(latest: Doc?, nav: NavController) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Kicker("PDF Speed")
            Text("Documents,\nat speed.", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(vertical = 14.dp))
        }
        if (latest != null) {
            item {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardBg),
                    elevation = CardDefaults.cardElevation(2.dp),
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate("doc/${latest.id}") }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(latest.pages.firstOrNull(), 56.dp, 72.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Continue reading", color = Muted, fontSize = 12.5.sp)
                            Text(latest.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${latest.pages.size} page${if (latest.pages.size == 1) "" else "s"} · ${whenText(latest.createdAt)}", color = Muted, fontSize = 12.5.sp)
                        }
                        Button(onClick = { nav.navigate("doc/${latest.id}") }, shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk)) { Text("Open") }
                    }
                }
                Spacer(Modifier.height(26.dp))
            }
        } else {
            item {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = CardBg),
                    elevation = CardDefaults.cardElevation(2.dp),
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate("scanner/new") }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📷", fontSize = 30.sp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Start here", color = Muted, fontSize = 12.5.sp)
                            Text("Scan your first page", style = MaterialTheme.typography.titleLarge)
                            Text("Camera scanner — more smarts in the next update", color = Muted, fontSize = 12.5.sp)
                        }
                        Button(onClick = { nav.navigate("scanner/new") }, shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk)) { Text("Scan") }
                    }
                }
                Spacer(Modifier.height(26.dp))
            }
        }
        item { Kicker("What would you like to do"); Spacer(Modifier.height(6.dp)) }
        val rows = listOf(
            Triple("01", "Scan a document", "scanner/new"),
            Triple("02", "Organize pages", "library"),
            Triple("03", "Sign and annotate", "tools"),
            Triple("04", "Convert and compress", "tools")
        )
        items(rows) { (num, name, dest) ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate(dest) }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(num, color = GoldDeep, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                Text(name, fontSize = 16.5.sp, modifier = Modifier.weight(1f))
                Text("→", color = Muted)
            }
            HorizontalDivider(color = Line)
        }
        item {
            Spacer(Modifier.height(18.dp))
            Text("Every document is created on this phone. Nothing is uploaded.", color = Muted, fontSize = 12.5.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun LibraryScreen(docs: List<Doc>, store: DocStore, onChanged: () -> Unit, nav: NavController) {
    var filter by remember { mutableStateOf("All") }
    val shown = docs.filter {
        when (filter) {
            "Scans" -> true
            "Edited" -> it.edited
            "Starred" -> it.starred
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Kicker("Library")
            Text("Your files", style = MaterialTheme.typography.displayMedium.copy(fontSize = 34.sp), modifier = Modifier.padding(vertical = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Scans", "Edited", "Starred").forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = GoldInk, containerColor = CardBg, labelColor = Muted),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == f, selectedBorderColor = Gold, borderColor = Line, selectedBorderWidth = 1.dp, borderWidth = 1.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (shown.isEmpty()) {
            item { Text(if (docs.isEmpty()) "Nothing here yet. Scan a document and it will appear in this library." else "Nothing in $filter yet.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp)) }
        }
        items(shown, key = { it.id }) { doc ->
            Row(Modifier.fillMaxWidth().clickable { nav.navigate("doc/${doc.id}") }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(doc.pages.firstOrNull(), 40.dp, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"} · ${whenText(doc.createdAt)}", color = Muted, fontSize = 12.5.sp)
                }
                IconButton(onClick = { store.toggleStar(doc.id); onChanged() }) {
                    Text(if (doc.starred) "★" else "☆", color = if (doc.starred) GoldDeep else Line, fontSize = 21.sp)
                }
            }
            HorizontalDivider(color = Line)
        }
    }
}

@Composable
fun ToolsScreen(nav: NavController) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Kicker("Tools")
            Text("All tools", style = MaterialTheme.typography.displayMedium.copy(fontSize = 34.sp), modifier = Modifier.padding(vertical = 10.dp))
        }
        val sections = listOf(
            "Scanner" to listOf("Scan to PDF" to true),
            "Studio" to listOf("PDF Editor" to false),
            "Organize" to listOf("Merge PDF" to false, "Split PDF" to false, "Rotate PDF" to false),
            "Compress" to listOf("Compress PDF" to false),
            "Convert" to listOf("Image to PDF" to false, "PDF to JPG" to false),
            "Secure" to listOf("Protect PDF" to false, "Sign PDF" to false)
        )
        sections.forEach { (title, tools) ->
            item { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp)) }
            items(tools) { (name, live) ->
                Row(Modifier.fillMaxWidth().then(if (live) Modifier.clickable { nav.navigate("scanner/new") } else Modifier).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 16.sp, modifier = Modifier.weight(1f), color = if (live) Ink else Muted)
                    if (live) Text("→", color = Muted) else Text("Next update", color = GoldDeep, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = Line)
            }
        }
    }
}

@Composable
fun MeScreen(docCount: Int) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Kicker("PDF Speed")
            Text("Me", style = MaterialTheme.typography.displayMedium.copy(fontSize = 34.sp), modifier = Modifier.padding(vertical = 10.dp))
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("PDF Speed — documents, at speed. A scanner-first PDF toolkit built by AsadWebs. Everything is created on this phone; nothing is uploaded.")
                    Spacer(Modifier.height(10.dp))
                    Text("Documents in library: $docCount", color = Muted, fontSize = 13.sp)
                    Text("Version 1.0", color = Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun DocScreen(doc: Doc, store: DocStore, onChanged: () -> Unit, nav: NavController) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { nav.popBackStack() }) { Text("← Back", color = GoldDeep, fontWeight = FontWeight.SemiBold) }
            }
            Text(doc.name, style = MaterialTheme.typography.headlineMedium)
            Text("${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"} · ${whenText(doc.createdAt)}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        }
        items(doc.pages) { path ->
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Thumb(path, null, 300.dp, Modifier.fillMaxWidth())
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { nav.navigate("scanner/${doc.id}") }, shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = GoldInk), modifier = Modifier.weight(1f)) { Text("Add page") }
                OutlinedButton(onClick = {
                    val pdf = store.exportPdf(doc)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share PDF"))
                }, shape = RoundedCornerShape(999.dp), modifier = Modifier.weight(1f)) { Text("Export PDF") }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete document", color = Color(0xFFB3352B)) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this document?") },
            text = { Text("“${doc.name}” and its ${doc.pages.size} page(s) will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { store.delete(doc.id); onChanged(); confirmDelete = false; nav.popBackStack() }) { Text("Delete", color = Color(0xFFB3352B)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
        )
    }
}

@Composable
fun Thumb(path: String?, width: androidx.compose.ui.unit.Dp?, height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val bmp = remember(path) {
        path?.let {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(it, opts)?.asImageBitmap()
            } catch (e: Exception) { null }
        }
    }
    Box(modifier.then(if (width != null) Modifier.size(width, height) else Modifier.height(height))
        .clip(RoundedCornerShape(6.dp)).background(CardBg), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text("📄", fontSize = 22.sp)
    }
}

fun whenText(ts: Long): String {
    val cal = Calendar.getInstance(); val now = cal.timeInMillis
    val dayMs = 24 * 60 * 60 * 1000L
    return when {
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts)) == SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now)) -> "Today"
        now - ts < 2 * dayMs && SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts)) ==
            SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now - dayMs)) -> "Yesterday"
        else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(ts))
    }
}
