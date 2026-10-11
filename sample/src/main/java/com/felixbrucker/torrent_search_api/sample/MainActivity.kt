package com.felixbrucker.torrent_search_api.sample

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.felixbrucker.torrent_search_api.Category
import com.felixbrucker.torrent_search_api.CloudflareWebViewResolver
import com.felixbrucker.torrent_search_api.ExtToProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var bottomSheetDialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val queryEdit = findViewById<EditText>(R.id.queryEdit)
        val searchButton = findViewById<Button>(R.id.searchButton)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val listView = findViewById<ListView>(R.id.resultsListView)

        val resolver = CloudflareWebViewResolver(
            context = this,
            onPromoteToBottomSheet = { webView ->
                runOnUiThread {
                    if (bottomSheetDialog?.isShowing == true) return@runOnUiThread

                    val parent = webView.parent as? ViewGroup
                    parent?.removeView(webView)

                    val dialog = BottomSheetDialog(this)
                    dialog.setContentView(
                        webView, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            800
                        )
                    )
                    bottomSheetDialog = dialog
                    dialog.show()
                }
            },
            onDismissBottomSheet = {
                runOnUiThread {
                    bottomSheetDialog?.dismiss()
                    bottomSheetDialog = null
                }
            }
        )

        val provider = ExtToProvider(resolver)

        searchButton.setOnClickListener {
            val query = queryEdit.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, "Please enter a search term", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressBar.visibility = View.VISIBLE
            searchButton.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                val result = provider.search(query, category = Category.TV_SHOWS)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    searchButton.isEnabled = true

                    result.fold(
                        onSuccess = { searchResult ->
                            val items = searchResult.results.map {
                                "${it.name}\nSize: ${it.sizeInBytes / 1024 / 1024}MB | Seeds: ${it.seeder} | Leechers: ${it.leecher}"
                            }
                            listView.adapter = ArrayAdapter(
                                this@MainActivity,
                                android.R.layout.simple_list_item_1,
                                items
                            )
                            Toast.makeText(
                                this@MainActivity,
                                "Found ${searchResult.results.size} results",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(
                                this@MainActivity,
                                "Error: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
        }
    }
}
