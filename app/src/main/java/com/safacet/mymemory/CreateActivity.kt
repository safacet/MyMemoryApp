package com.safacet.mymemory

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.safacet.mymemory.models.AccountSession
import com.safacet.mymemory.models.BoardSize
import com.safacet.mymemory.models.UserGameList
import com.safacet.mymemory.utils.BitmapScaler
import com.safacet.mymemory.utils.EXTRA_BOARD_SIZE
import com.safacet.mymemory.utils.EXTRA_GAME_NAME
import com.safacet.mymemory.utils.EXTRA_SESSION_NAME
import java.io.ByteArrayOutputStream

class CreateActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CreateActivity"
        private const val READ_PHOTOS_PERMISSION = android.Manifest.permission.READ_EXTERNAL_STORAGE
        private const val READ_EXTERNAL_PHOTOS_CODE = 248
        private const val MINIMUM_GAME_LENGTH = 3
        private const val MAX_GAME_LENGTH = 14
    }

    private lateinit var adapter: ImagePickerAdapter
    private lateinit var rvImagePicker: RecyclerView
    private lateinit var etGameName: EditText
    private lateinit var btnSave: Button
    private lateinit var pbUploading: ProgressBar

    private lateinit var sessionName: String
    private lateinit var boardSize: BoardSize
    private var numImagesRequired = -1
    private val chosenImageUris = mutableListOf<Uri>()
    private val storage = Firebase.storage
    private val db = Firebase.firestore


    private val photoActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != RESULT_OK || it.data == null) {
            Log.w(
                TAG,
                "Did not get data back from the launched activity, user likely canceled flow"
            )
            return@registerForActivityResult
        }
        val intent = it.data
        resultImagePickerActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create)

        rvImagePicker = findViewById(R.id.rvImagePicker)
        etGameName = findViewById(R.id.etGameName)
        btnSave = findViewById(R.id.btnSave)
        pbUploading = findViewById(R.id.pbUploading)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        boardSize = intent.getSerializableExtra(EXTRA_BOARD_SIZE) as BoardSize
        sessionName = intent.getStringExtra(EXTRA_SESSION_NAME).toString()
        numImagesRequired = boardSize.getNumPairs()

        supportActionBar?.title = "Choose pics (0 / $numImagesRequired)"

        btnSave.setOnClickListener {
            saveDataToFirebase()
        }

        etGameName.filters = arrayOf(InputFilter.LengthFilter(MAX_GAME_LENGTH))
        etGameName.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

            override fun afterTextChanged(p0: Editable?) {
                btnSave.isEnabled = shouldEnableSaveButton()
            }

        })

        adapter = ImagePickerAdapter(this, chosenImageUris, boardSize, object : ImagePickerAdapter.ImageClickListener {
            override fun onPlaceHolderClicked() {
                if (isPermissionGranted(this@CreateActivity, READ_PHOTOS_PERMISSION)) {
                launchIntentForPhotos()
                } else {
                    requestPermission(this@CreateActivity, READ_PHOTOS_PERMISSION, READ_EXTERNAL_PHOTOS_CODE)
                }
            }
        })
        rvImagePicker.adapter = adapter
        rvImagePicker.setHasFixedSize(true)
        rvImagePicker.layoutManager = when (boardSize) {
            BoardSize.EASY -> GridLayoutManager(this, 2)
            BoardSize.MEDIUM -> GridLayoutManager(this, 3)
            BoardSize.HARD -> GridLayoutManager(this, 2)
            BoardSize.IMPOSSIBLE -> GridLayoutManager(this, 3)
        }


    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if(item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if(requestCode == READ_EXTERNAL_PHOTOS_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchIntentForPhotos()
            } else {
                Toast.makeText(this, "In order to create a custom game, you need to provide access to your gallery",
                    Toast.LENGTH_LONG).show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun saveDataToFirebase() {
        val customGameName = etGameName.text.toString()
        btnSave.isEnabled = false
        //Check if the game name can be used
        db.collection("games").document(customGameName).get().addOnSuccessListener { document ->
            if (document != null && document.data != null) {
                AlertDialog.Builder(this)
                    .setTitle("Name taken!")
                    .setMessage("There is another game named $customGameName please pick another!")
                    .setPositiveButton("OK", null)
                    .show()
                btnSave.isEnabled = true
            } else {
                handleImageUploading(customGameName)
            }
        }.addOnFailureListener {
            Log.e(TAG, "Encountered an error while checking game name: $it")
            Toast.makeText(this, "Encountered an error while saving game", Toast.LENGTH_SHORT).show()
            btnSave.isEnabled = true
        }
    }

    private fun handleImageUploading(customGameName: String) {
        Log.i(TAG, "Saving data to Firebase")
        pbUploading.visibility = View.VISIBLE
        pbUploading.max = chosenImageUris.size
        pbUploading.progress = 0
        var didEncounterError = false
        val uploadedImagesUrls = mutableListOf<String>()
        for ((index, photoUri) in chosenImageUris.withIndex()) {
            val imageByteArray = getImageByteArray(photoUri)
            val filePath = "images/$customGameName/${System.currentTimeMillis()}-$index.jpg"
            val photoReference = storage.reference.child(filePath)
            photoReference.putBytes(imageByteArray)
                .continueWithTask { photoUploadTask ->
                    Log.i(TAG, "Uploaded bytes: ${photoUploadTask.result?.bytesTransferred}")
                    photoReference.downloadUrl
                }.addOnCompleteListener { downloadUrlTask ->
                    if (!downloadUrlTask.isSuccessful) {
                        Log.e(TAG, "Exception with Firebase storage", downloadUrlTask.exception)
                        Toast.makeText(this, "Failed to upload image", Toast.LENGTH_SHORT).show()
                        didEncounterError = true
                        return@addOnCompleteListener
                    }
                    if (didEncounterError) {
                        btnSave.isEnabled = true
                        pbUploading.visibility = View.GONE
                        return@addOnCompleteListener
                    }
                    pbUploading.progress += 1
                    val downloadUrl = downloadUrlTask.result.toString()
                    uploadedImagesUrls.add(downloadUrl)
                    Log.i(TAG, "Finished uploading $photoUri, num uploaded: ${uploadedImagesUrls.size}")
                    if (uploadedImagesUrls.size == chosenImageUris.size) {
                        handleAllImagesUploaded(customGameName, uploadedImagesUrls)
                    }
                }
        }
    }

    private fun handleAllImagesUploaded(gameName: String, imageUrls: MutableList<String>) {
        //Update user collection
        var games = mutableListOf<String>()
        db.collection("users").document(sessionName).get().addOnSuccessListener { document ->
            if (document != null && document.data != null) {
                val userGameList = document.toObject(UserGameList::class.java)
                if(userGameList?.games != null) {
                    games = userGameList.games as MutableList<String>
                }
            }
            games.add(gameName)
            db.collection("users").document(sessionName)
                .set(mapOf("games" to games))
                .addOnCompleteListener {
                    if (!it.isSuccessful) {
                        Log.e(TAG, "User collection update exception", it.exception)
                    } else {
                        Log.i(TAG, "User collection update is successful!!")
                    }
                }
        }


        //Update games collection
        db.collection("games").document(gameName)
            .set(mapOf("images" to imageUrls, "userID" to sessionName, "accessCount" to 0))
            .addOnCompleteListener { gameCreationTask ->
                pbUploading.visibility = View.GONE
                if (!gameCreationTask.isSuccessful) {
                    Log.e(TAG, "Exception with game creation", gameCreationTask.exception)
                    Toast.makeText(this, "Game creation failed!", Toast.LENGTH_SHORT).show()
                    btnSave.isEnabled = true
                    return@addOnCompleteListener
                }
                Log.i(TAG, "Successfully created game $gameName")
                AlertDialog.Builder(this)
                    .setTitle("Upload complete! Let's play your game $gameName")
                    .setPositiveButton("OK") { _, _ ->
                        val resultData = Intent()
                        resultData.putExtra(EXTRA_GAME_NAME, gameName)
                        setResult(Activity.RESULT_OK, resultData)
                        finish()
                    }.show()
            }
    }

    private fun getImageByteArray(photoUri: Uri): ByteArray {
        val originalBitMap = if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, photoUri)
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(contentResolver, photoUri)
        }
        Log.i(TAG, "Original width: ${originalBitMap.width} and height: ${originalBitMap.height}")
        val scaledBitmap = BitmapScaler.scaleToFitHeight(originalBitMap, 250)
        Log.i(TAG, "Scaled width: ${scaledBitmap.width} and height: ${scaledBitmap.height}")
        val byteOutputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, byteOutputStream)
        return byteOutputStream.toByteArray()
    }

    private fun launchIntentForPhotos() {
        intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        //intent.setAction(Intent.ACTION_GET_CONTENT)
        photoActivityLauncher.launch(Intent.createChooser(intent, "Choose pics"))
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun resultImagePickerActivity(intent: Intent?) {
        val selectedUri = intent?.data
        val clipData = intent?.clipData

        if (clipData != null) {
            Log.i(TAG, "clipData NumImages: ${clipData.itemCount}: $clipData")
            for (i in 0 until clipData.itemCount) {
                val clipItem = clipData.getItemAt(i)
                if (chosenImageUris.size < numImagesRequired) {
                    chosenImageUris.add(clipItem.uri)
                }
            }
        } else if (selectedUri != null) {
            Log.i(TAG, "data: $selectedUri")
            chosenImageUris.add(selectedUri)
        }
        adapter.notifyDataSetChanged()
        supportActionBar?.title = "Choose pic (${chosenImageUris.size} / $numImagesRequired)"
        btnSave.isEnabled = shouldEnableSaveButton()
    }

    private fun shouldEnableSaveButton(): Boolean {
        if (chosenImageUris.size != numImagesRequired) return false

        if (etGameName.text.isBlank() || etGameName.text.length < MINIMUM_GAME_LENGTH) return false

        return true
    }
}
