package com.example.productapp

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ================= 1. DATA LAYER (Room Architecture) =================
@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val category: String,
    val price: Double
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY name ASC")
    fun getAllProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE name LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%'")
    fun searchProducts(query: String): Flow<List<ProductEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)

    @Query("DELETE FROM products")
    suspend fun clearAll()
}

@Database(entities = [ProductEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

// ================= 2. REPOSITORY LAYER =================
class ProductRepository(private val dao: ProductDao) {
    fun getProducts(): Flow<List<ProductEntity>> = dao.getAllProducts()
    fun searchProducts(query: String): Flow<List<ProductEntity>> = dao.searchProducts(query)

    suspend fun refreshCache() {
        val newProducts = listOf(
            ProductEntity(name = "Laptop", category = "Electronics", price = 1500.0),
            ProductEntity(name = "Mouse", category = "Accessories", price = 25.0),
            ProductEntity(name = "Keyboard", category = "Accessories", price = 50.0)
        )
        dao.clearAll()
        dao.insertAll(newProducts)
    }
}

// ================= 3. PRESENTATION LAYER (ViewModel + Flow) =================
class ProductViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getInstance(application).productDao()
    private val repository = ProductRepository(dao)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val products: StateFlow<List<ProductEntity>> = _query
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) repository.getProducts()
            else repository.searchProducts(query)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch { repository.refreshCache() }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }
}

// ================= 4. UI LAYER (Jetpack Compose) =================
@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Пошук") },
        modifier = Modifier.fillMaxWidth().padding(8.dp)
    )
}

@Composable
fun ProductListScreen(viewModel: ProductViewModel = viewModel()) {
    val query by viewModel.query.collectAsState()
    val products by viewModel.products.collectAsState()

    Column {
        SearchBar(query, viewModel::onQueryChange)
        LazyColumn {
            items(products) { product ->
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(product.name, style = MaterialTheme.typography.titleMedium)
                        Text("${product.category} - ${product.price}₴")
                    }
                }
            }
        }
    }
}

// ================= 5. ENTRY POINT =================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ProductListScreen()
            }
        }
    }
}
