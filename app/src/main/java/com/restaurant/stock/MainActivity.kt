package com.restaurant.stock

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween

private const val DEFAULT_SERVER_URL = "http://192.168.1.216:3000"

private data class Location(val id: String, val name: String)
private data class StockItem(
    val id: String,
    val locationId: String,
    val name: String,
    val unit: String,
    val stock: Double,
    val recommendedStock: Double
)
private data class LoginResult(val token: String, val displayName: String, val role: String)
private data class StockMovement(
    val id: String,
    val itemName: String,
    val locationId: String,
    val type: String,
    val quantity: Double,
    val unit: String,
    val stockAfter: Double,
    val comment: String,
    val actorName: String,
    val createdAt: String
)

private val PuriRed = Color(0xFFC65A51)
private val PuriInk = Color(0xFF211C1B)
private val PuriMuted = Color(0xFF756D68)
private val PuriCream = Color(0xFFF8F6F2)
private val PuriBlush = Color(0xFFF8EAE6)
private val PuriSoft = Color(0xFFF1EEEA)
private val PuriWarningBg = Color(0xFFFFF0E1)
private val PuriWarning = Color(0xFF9B4C1D)

private val PuriColorScheme = lightColorScheme(
    primary = PuriRed,
    onPrimary = Color.White,
    primaryContainer = PuriBlush,
    onPrimaryContainer = PuriInk,
    secondary = Color(0xFF7A332F),
    onSecondary = Color.White,
    background = PuriCream,
    onBackground = PuriInk,
    surface = Color.White,
    onSurface = PuriInk,
    outline = Color(0xFFB9AAA5),
    error = Color(0xFFB3261E),
    onError = Color.White
)

private class StockApi(var baseUrl: String) {
    var token: String? = null

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val result = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (code !in 200..299) throw IllegalStateException(result.optString("error", "Ошибка сервера ($code)"))
            result
        } finally { connection.disconnect() }
    }

    suspend fun login(username: String, password: String): LoginResult {
        val response = request("POST", "/api/login", JSONObject().put("username", username).put("password", password))
        token = response.getString("token")
        return LoginResult(token!!, response.optString("displayName", username), response.optString("role", "viewer"))
    }

    suspend fun locations(): List<Location> = request("GET", "/api/locations").getJSONArray("locations").toLocations()

    suspend fun items(locationId: String? = null): List<StockItem> {
        val locationQuery = locationId?.let { "?locationId=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return request("GET", "/api/items$locationQuery").getJSONArray("items").toItems()
    }

    suspend fun commentedMovements(): List<StockMovement> =
        request("GET", "/api/movements?withComment=true").getJSONArray("movements").toMovements()

    suspend fun move(itemId: String, type: String, quantity: Double, comment: String): StockItem {
        val response = request("POST", "/api/movements", JSONObject()
            .put("itemId", itemId).put("type", type).put("quantity", quantity).put("comment", comment))
        return response.getJSONObject("item").toStockItem()
    }

    suspend fun logout() { runCatching { request("POST", "/api/logout") }; token = null }
}

private fun JSONArray.toLocations() = (0 until length()).map { i ->
    val obj = getJSONObject(i); Location(obj.getString("id"), obj.getString("name"))
}
private fun JSONArray.toItems() = (0 until length()).map { getJSONObject(it).toStockItem() }
private fun JSONArray.toMovements() = (0 until length()).map { getJSONObject(it).toStockMovement() }
private fun JSONObject.toStockItem() = StockItem(
    getString("id"), getString("locationId"), getString("name"), getString("unit"),
    getDouble("stock"), getDouble("recommendedStock")
)
private fun JSONObject.toStockMovement() = StockMovement(
    getString("id"), getString("itemName"), getString("locationId"), getString("type"),
    getDouble("quantity"), getString("unit"), getDouble("stockAfter"), getString("comment"),
    optString("actorName", "Сотрудник"), getString("createdAt")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(248, 246, 242)
        window.navigationBarColor = android.graphics.Color.rgb(248, 246, 242)
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { MaterialTheme(colorScheme = PuriColorScheme) { StockApp() } }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun StockApp() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val locations = remember { mutableStateListOf<Location>() }
    val stockItems = remember { mutableStateListOf<StockItem>() }
    val historyEntries = remember { mutableStateListOf<StockMovement>() }
    var api by remember { mutableStateOf(StockApi(DEFAULT_SERVER_URL)) }
    var signedIn by remember { mutableStateOf(false) }
    var showingOrder by remember { mutableStateOf(false) }
    var showingHistory by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("viewer") }
    var selectedLocation by remember { mutableStateOf<Location?>(null) }
    var selectedItem by remember { mutableStateOf<StockItem?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(DEFAULT_SERVER_URL) }
    var search by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf("receipt") }
    var busy by remember { mutableStateOf(false) }
    var historyBusy by remember { mutableStateOf(false) }
    var historyMessage by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun loadLocation(location: Location) {
        selectedLocation = location
        selectedItem = null
        search = ""
        scope.launch {
            busy = true
            try { stockItems.clear(); stockItems.addAll(api.items()) }
            catch (e: Exception) { message = e.message ?: "Не удалось загрузить товары" }
            finally { busy = false }
        }
    }

    fun loadHistory() {
        scope.launch {
            historyBusy = true
            historyMessage = ""
            try {
                historyEntries.clear()
                historyEntries.addAll(api.commentedMovements())
            } catch (e: Exception) {
                historyMessage = e.message ?: "Не удалось загрузить историю"
            } finally {
                historyBusy = false
            }
        }
    }

    Surface(color = PuriCream, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        if (!signedIn) {
            LoginScreen(serverUrl, { serverUrl = it }, username, { username = it }, password, { password = it }, busy, message) {
                scope.launch {
                    busy = true; message = ""
                    try {
                        api = StockApi(serverUrl.trim())
                        val result = api.login(username.trim(), password)
                        user = result.displayName; role = result.role
                        val loaded = api.locations(); locations.clear(); locations.addAll(loaded); signedIn = true
                        loaded.firstOrNull()?.let { loadLocation(it) }
                    } catch (e: Exception) { api.token = null; message = e.message ?: "Не удалось подключиться к серверу" }
                    finally { busy = false }
                }
            }
        } else if (showingOrder) {
            val orderItems = stockItems.filter { it.stock < it.recommendedStock }
            OrderScreen(
                orderItems = orderItems,
                locations = locations,
                onBack = { showingOrder = false },
                onCopy = {
                    val text = buildString {
                        appendLine("Заказ хозтоваров — ПуриПури")
                        orderItems.forEach { item ->
                            val locationName = locations.firstOrNull { it.id == item.locationId }?.name.orEmpty()
                            appendLine("${item.name} — ${formatQty(item.recommendedStock - item.stock)} ${item.unit} · $locationName")
                        }
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Заказ хозтоваров", text))
                    Toast.makeText(context, "Список заказа скопирован", Toast.LENGTH_SHORT).show()
                }
            )
        } else if (showingHistory) {
            HistoryScreen(
                entries = historyEntries,
                locations = locations,
                busy = historyBusy,
                message = historyMessage,
                onBack = { showingHistory = false },
                onRefresh = { loadHistory() }
            )
        } else {
            Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("ПуриПури", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = PuriRed)
                        Text("ОСТАТКИ · $user", color = PuriMuted, fontSize = 12.sp, letterSpacing = 1.sp)
                    }
                    TextButton(onClick = { scope.launch { api.logout(); signedIn = false; showingOrder = false; showingHistory = false; selectedItem = null; stockItems.clear(); locations.clear(); historyEntries.clear() } }) { Text("Выйти") }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    NavigationTile(
                        title = "Заказ",
                        subtitle = positionsCountLabel(stockItems.count { it.stock < it.recommendedStock }),
                        modifier = Modifier.weight(1f),
                        onClick = { showingOrder = true }
                    )
                    NavigationTile(
                        title = "История",
                        subtitle = "Операции с комментарием",
                        modifier = Modifier.weight(1f),
                        onClick = { showingHistory = true; loadHistory() }
                    )
                }
                Spacer(Modifier.height(12.dp))
                LocationPicker(locations, selectedLocation) { loadLocation(it) }
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    leadingIcon = { Image(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(20.dp), colorFilter = ColorFilter.tint(PuriMuted)) },
                    trailingIcon = if (search.isNotEmpty()) ({
                        IconButton(onClick = { search = "" }) {
                            Image(painterResource(R.drawable.ic_clear), contentDescription = "Очистить поиск", modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(PuriMuted))
                        }
                    }) else null,
                    placeholder = { Text("Найти товар по всем местам") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (busy && stockItems.isEmpty()) {
                    PomegranateLoader(Modifier.align(Alignment.CenterHorizontally).padding(20.dp), size = 42.dp)
                } else {
                    val query = search.trim()
                    val filtered = stockItems.filter {
                        it.name.contains(query, ignoreCase = true) &&
                            (query.isNotBlank() || it.locationId == selectedLocation?.id)
                    }
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filtered, key = { it.id }) { item ->
                            val locationName = if (query.isNotBlank()) locations.firstOrNull { it.id == item.locationId }?.name else null
                            ItemRow(item, selectedItem?.id == item.id, locationName) {
                                selectedItem = item; operation = "receipt"; quantity = ""; comment = ""; message = ""
                            }
                        }
                    }
                }
            }
        }
        if (signedIn && !showingOrder && !showingHistory) {
            selectedItem?.let { item ->
                ModalBottomSheet(
                    onDismissRequest = { selectedItem = null; message = "" },
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                    containerColor = Color.White
                ) {
                    OperationPanel(
                        item = item,
                        locationName = locations.firstOrNull { it.id == item.locationId }?.name.orEmpty(),
                        operation = operation, onOperation = { operation = it }, quantity = quantity,
                        onQuantity = { quantity = it }, comment = comment, onComment = { comment = it }, busy = busy,
                        canWrite = role != "viewer", message = message,
                        onClose = { selectedItem = null; message = "" },
                        onSubmit = {
                            val parsed = quantity.trim().replace(',', '.').toDoubleOrNull()
                            if (parsed == null || parsed <= 0) { message = "Введите количество больше нуля" }
                            else if (operation == "writeoff" && parsed > item.stock) { message = "Сейчас доступно только ${formatQty(item.stock)} ${item.unit}" }
                            else scope.launch {
                                busy = true; message = ""
                                try {
                                    val updated = api.move(item.id, operation, parsed, comment)
                                    val index = stockItems.indexOfFirst { it.id == updated.id }
                                    if (index >= 0) stockItems[index] = updated
                                    selectedItem = null; quantity = ""; comment = ""; message = ""
                                    Toast.makeText(context, "Операция проведена. Остаток: ${formatQty(updated.stock)} ${updated.unit}", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) { message = e.message ?: "Не удалось сохранить операцию" }
                                finally { busy = false }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    server: String, onServer: (String) -> Unit, username: String, onUsername: (String) -> Unit,
    password: String, onPassword: (String) -> Unit, busy: Boolean, message: String, onLogin: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("ПуриПури", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = PuriRed)
        Spacer(Modifier.height(2.dp))
        Text("УЧЁТ ХОЗТОВАРОВ", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = PuriMuted)
        Spacer(Modifier.height(6.dp))
        Text("Войдите, чтобы увидеть общие остатки ресторана", color = PuriMuted)
        Spacer(Modifier.height(24.dp))
        TextField(
            value = server, onValueChange = onServer, label = { Text("Адрес сервера") }, singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedIndicatorColor = PuriRed, unfocusedIndicatorColor = PuriSoft),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        TextField(
            value = username, onValueChange = onUsername, label = { Text("Логин") }, singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedIndicatorColor = PuriRed, unfocusedIndicatorColor = PuriSoft),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        TextField(
            value = password, onValueChange = onPassword, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedIndicatorColor = PuriRed, unfocusedIndicatorColor = PuriSoft),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onLogin, enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (busy) PomegranateLoader(size = 20.dp, tint = Color.White) else Text("Войти")
        }
        if (message.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(message, color = Color(0xFFB3261E)) }
    }
}

@Composable
private fun LocationPicker(locations: List<Location>, selected: Location?, onSelect: (Location) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("МЕСТО ХРАНЕНИЯ", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = PuriMuted)
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 1.dp
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected?.name ?: "Выберите место хранения", modifier = Modifier.weight(1f), color = PuriInk, fontWeight = FontWeight.Medium)
                Text("⌄", color = PuriMuted, fontSize = 18.sp)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 260.dp, max = 320.dp).padding(6.dp)
        ) {
            Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("ВЫБЕРИТЕ МЕСТО", color = PuriMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                    locations.forEach { location ->
                        val isSelected = location.id == selected?.id
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { expanded = false; onSelect(location) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) PuriBlush else Color.Transparent
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(location.name, modifier = Modifier.weight(1f), color = if (isSelected) PuriRed else PuriInk, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                                if (isSelected) Text("✓", color = PuriRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: StockItem, selected: Boolean, locationName: String?, onClick: () -> Unit) {
    val low = item.stock < item.recommendedStock
    val containerColor by animateColorAsState(if (selected) PuriBlush else Color.White, animationSpec = tween(180), label = "item-surface")
    val scale by animateFloatAsState(if (selected) 0.985f else 1f, animationSpec = tween(140), label = "item-scale")
    Card(
        modifier = Modifier.fillMaxWidth().graphicsLayer(scaleX = scale, scaleY = scale).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, color = PuriInk)
                locationName?.let { Text(it, fontSize = 12.sp, color = PuriMuted) }
                if (low) {
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Surface(color = PuriWarningBg, shape = RoundedCornerShape(50)) {
                            Text("Мало", color = PuriWarning, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        Text("Норма ${formatQty(item.recommendedStock)} ${item.unit}", fontSize = 12.sp, color = PuriMuted)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(formatQty(item.stock), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (low) PuriWarning else PuriInk)
                Text(item.unit, fontSize = 12.sp, color = PuriMuted)
            }
        }
    }
}

@Composable
private fun NavigationTile(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 1.dp
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = PuriInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = PuriMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = PuriRed, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

@Composable
private fun HistoryScreen(
    entries: List<StockMovement>,
    locations: List<Location>,
    busy: Boolean,
    message: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("‹ Остатки") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !busy) { Text("Обновить") }
        }
        Text("История", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = PuriInk)
        Text("Поступления и списания с комментарием", color = PuriMuted, fontSize = 14.sp)
        Spacer(Modifier.height(5.dp))
        Text("${entries.size} операций", color = PuriRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        when {
            busy && entries.isEmpty() -> PomegranateLoader(Modifier.align(Alignment.CenterHorizontally).weight(1f), size = 44.dp)
            message.isNotBlank() && entries.isEmpty() -> Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(message, color = PuriWarning, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRefresh) { Text("Попробовать ещё раз") }
            }
            entries.isEmpty() -> Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Пока нет записей", color = PuriInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Здесь появятся операции, к которым добавили комментарий", color = PuriMuted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries, key = { it.id }) { entry ->
                    val locationName = locations.firstOrNull { it.id == entry.locationId }?.name.orEmpty()
                    HistoryRow(entry, locationName)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: StockMovement, locationName: String) {
    val isReceipt = entry.type == "receipt"
    val actionColor = if (isReceipt) Color(0xFF39745D) else PuriWarning
    val actionBackground = if (isReceipt) Color(0xFFE7F1EB) else PuriWarningBg
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(entry.itemName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PuriInk)
                    Text(locationName, color = PuriMuted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Surface(color = actionBackground, shape = RoundedCornerShape(50)) {
                        Text(
                            if (isReceipt) "Поступление" else "Списание",
                            color = actionColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        )
                    }
                    Text(
                        "${if (isReceipt) "+" else "−"}${formatQty(entry.quantity)} ${entry.unit}",
                        color = actionColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }
            Surface(color = PuriCream, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("КОММЕНТАРИЙ", color = PuriMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(entry.comment, color = PuriInk, fontSize = 14.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.actorName, color = PuriInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(formatMovementDate(entry.createdAt), color = PuriMuted, fontSize = 11.sp)
            }
            Text("Остаток после операции: ${formatQty(entry.stockAfter)} ${entry.unit}", color = PuriMuted, fontSize = 11.sp)
        }
    }
}

private fun formatMovementDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("ru"))
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)

@Composable
private fun PomegranateLoader(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 28.dp, tint: Color? = null) {
    val transition = rememberInfiniteTransition(label = "pomegranate-loader")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
        label = "pomegranate-rotation"
    )
    Image(
        painter = painterResource(R.drawable.ic_pomegranate_spinner),
        contentDescription = "Загрузка",
        colorFilter = tint?.let { ColorFilter.tint(it) },
        modifier = modifier.size(size).graphicsLayer { rotationZ = rotation }
    )
}

@Composable
private fun OrderScreen(
    orderItems: List<StockItem>,
    locations: List<Location>,
    onBack: () -> Unit,
    onCopy: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("‹ Остатки") }
            Spacer(Modifier.weight(1f))
            Text("Заказ", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = PuriInk)
        }
        Text("Подборка по текущим остаткам", color = PuriMuted, fontSize = 14.sp)
        Spacer(Modifier.height(5.dp))
        Text(positionsCountLabel(orderItems.size), color = PuriRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(14.dp))
        if (orderItems.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Все остатки в норме", color = PuriInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Сейчас ничего не нужно заказывать", color = PuriMuted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(orderItems, key = { it.id }) { item ->
                    OrderItemRow(item, locations.firstOrNull { it.id == item.locationId }?.name.orEmpty())
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onCopy,
            enabled = orderItems.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = PuriInk),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Image(painterResource(R.drawable.ic_clipboard), contentDescription = null, modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(Color.White))
                Text("Скопировать заказ")
            }
        }
    }
}

@Composable
private fun OrderItemRow(item: StockItem, locationName: String) {
    val needed = (item.recommendedStock - item.stock).coerceAtLeast(0.0)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, color = PuriInk)
                Text(locationName, color = PuriMuted, fontSize = 12.sp)
                Text("Сейчас ${formatQty(item.stock)} · норма ${formatQty(item.recommendedStock)} ${item.unit}", color = PuriMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("К ЗАКАЗУ", color = PuriMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
                Text("${formatQty(needed)} ${item.unit}", color = PuriRed, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OperationPanel(
    item: StockItem, locationName: String, operation: String, onOperation: (String) -> Unit, quantity: String, onQuantity: (String) -> Unit,
    comment: String, onComment: (String) -> Unit, busy: Boolean, canWrite: Boolean, message: String,
    onClose: () -> Unit, onSubmit: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PuriInk)
                Text("$locationName · сейчас ${formatQty(item.stock)} ${item.unit}", color = PuriMuted, fontSize = 13.sp)
            }
            IconButton(onClick = onClose, enabled = !busy) { Text("×", color = PuriMuted, fontSize = 24.sp) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(PuriSoft).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OperationSegment("receipt", "Приход", operation, onOperation, Modifier.weight(1f))
            OperationSegment("writeoff", "Списание", operation, onOperation, Modifier.weight(1f))
        }
        TextField(
            value = quantity,
            onValueChange = { input -> if (input.all { it.isDigit() || it == ',' || it == '.' }) onQuantity(input) },
            label = { Text("Количество (${item.unit})") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = PuriCream, unfocusedContainerColor = PuriCream, focusedIndicatorColor = PuriRed, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth()
        )
        TextField(
            value = comment, onValueChange = onComment,
            label = { Text("Комментарий · необязательно") }, singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = PuriCream, unfocusedContainerColor = PuriCream, focusedIndicatorColor = PuriRed, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth()
        )
        if (message.isNotBlank()) Text(message, color = Color(0xFFB3261E), fontSize = 13.sp)
        Button(
            onClick = onSubmit,
            enabled = canWrite && !busy,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) PomegranateLoader(size = 20.dp, tint = Color.White)
            else Text(if (operation == "receipt") "Провести поступление" else "Провести списание")
        }
        if (!canWrite) Text("У вашей учётной записи есть доступ только для просмотра.", color = PuriMuted, fontSize = 12.sp)
    }
}

@Composable
private fun OperationSegment(value: String, title: String, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable { onSelect(value) },
        shape = RoundedCornerShape(12.dp),
        color = if (selected == value) Color.White else Color.Transparent,
        shadowElevation = if (selected == value) 1.dp else 0.dp
    ) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
            Text(title, color = if (selected == value) PuriRed else PuriMuted, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun positionsCountLabel(count: Int): String = when {
    count % 100 in 11..14 -> "$count позиций к заказу"
    count % 10 == 1 -> "$count позиция к заказу"
    count % 10 in 2..4 -> "$count позиции к заказу"
    else -> "$count позиций к заказу"
}

private fun formatQty(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString().trimEnd('0').trimEnd('.')



