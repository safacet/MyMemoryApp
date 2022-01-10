package com.safacet.mymemory

import android.accounts.AccountManager
import android.animation.ArgbEvaluator
import android.annotation.SuppressLint
import android.app.ActionBar
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.jinatonic.confetti.CommonConfetti
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.AccountPicker.newChooseAccountIntent
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.ktx.Firebase
import com.safacet.mymemory.models.AccountSession
import com.safacet.mymemory.models.BoardSize
import com.safacet.mymemory.models.UserGameList
import com.safacet.mymemory.models.UserImageList
import com.safacet.mymemory.utils.EXTRA_BOARD_SIZE
import com.safacet.mymemory.utils.EXTRA_GAME_NAME
import com.safacet.mymemory.utils.EXTRA_SESSION_NAME
import com.squareup.picasso.Picasso


class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SHARE_ACCOUNT_NAME_CONSENT = 35748
        private const val CREATE_REQUEST_CODE = 123
    }

    private lateinit var adapter: MemoryBoardAdapter
    private lateinit var rvBoard: RecyclerView
    private lateinit var tvNumMoves: TextView
    private lateinit var tvNumPairs: TextView
    private lateinit var clRoot: CoordinatorLayout

    private lateinit var memoryGame: MemoryGame
    private var boardSize: BoardSize = BoardSize.EASY
    private val db = Firebase.firestore
    private var gameName: String? = null
    private var customGameImages: List<String>? = null
    private var stillWon = false
    private var promptedForAccount = false
    private var accountSession: AccountSession? = null
    private var shouldShowPersonalCustomDialog = false

    private val createActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                onCreateActivityResult(data)
            }
        }

    @SuppressLint("HardwareIds")
    private val userDataConsentActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                val data: Intent? = it.data
                onUserDataConsentResult(data)
            } else if(it.resultCode == Activity.RESULT_CANCELED) {
                val accountName = "guest" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                Toast.makeText(this, "Session granted as guest user", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Account select dialog has been cancelled. Using device id as account name: $accountName")
                accountSession = AccountSession(accountName)
            }
            if (shouldShowPersonalCustomDialog) {
                shouldShowPersonalCustomDialog = false
                showPersonalCustomsDialog()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rvBoard = findViewById(R.id.rvBoard)
        tvNumMoves = findViewById(R.id.tvNumMoves)
        tvNumPairs = findViewById(R.id.tvNumPairs)
        clRoot = findViewById(R.id.clRoot)

        setupBoard()
    }


    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.mi_refresh -> {
                if (memoryGame.getNumMoves() > 0 && !memoryGame.haveWonGame()) {
                    showAlertDialog("Quit your current game?", null, View.OnClickListener {
                        setupBoard()
                    })
                } else {
                    setupBoard()
                }
                return true
            }
            R.id.mi_new_size -> {
                showNewSizeDialog()
                return true
            }
            R.id.mi_custom -> {
                if (!promptedForAccount){
                    promptedForAccount = true
                    createUserSession()
                }
                showCreationDialog()
                return true
            }
            R.id.mi_download -> {
                showDownloadDialog()
                return true
            }
            R.id.mi_your_custom -> {
                return if (!promptedForAccount){
                    promptedForAccount = true
                    shouldShowPersonalCustomDialog = true
                    createUserSession()
                    true
                } else {
                    showPersonalCustomsDialog()
                    true
                }
            }
            R.id.mi_discover -> {
                showDiscoveryDialog()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("InflateParams")
    private fun showDiscoveryDialog() {
        val gameList= mutableListOf<String>()
        db.collection("games").orderBy("accessCount", Query.Direction.DESCENDING).limit(10)
            .get().addOnSuccessListener { documents ->
                if (documents != null) {
                    for (document in documents) {
                        gameList.add(document.id)
                    }
                }
            }.addOnFailureListener {
                Log.e(TAG, "Encountered an error while fetching most played games: $it")
            }.addOnCompleteListener {
                val view = LayoutInflater.from(this).inflate(R.layout.personal_custom_games, null)
                val rg = view.findViewById<RadioGroup>(R.id.rg_personal)
                for ((index, game) in gameList.withIndex()) {
                    val radioButton = MaterialRadioButton(this)
                    radioButton.id = index
                    radioButton.text = game
                    rg.addView(radioButton,ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                rg.check(0)
                showAlertDialog("Select one of the most played games", view, View.OnClickListener {
                    downloadGame(gameList[rg.checkedRadioButtonId])
                })
            }
    }

    @SuppressLint("InflateParams")
    private fun showPersonalCustomsDialog() {
        var gamesList: MutableList<String>? = null
        if (accountSession != null)
        {
            db.collection("users").document(accountSession!!.name).get()
                .addOnSuccessListener { document ->
                    if (document != null && document.data != null) {
                        val userGameList = document.toObject(UserGameList::class.java)
                        gamesList = userGameList?.games as MutableList<String>
                    }
                }.addOnCompleteListener {
                    if (gamesList == null) {
                        showAlertDialog("You dont have any custom games do you want to create one?",
                            null,
                            View.OnClickListener { showCreationDialog() })
                    } else {
                        val view = LayoutInflater.from(this).inflate(R.layout.personal_custom_games, null)
                        val radioGroup = view.findViewById<RadioGroup>(R.id.rg_personal)
                        for ((index, game) in gamesList!!.withIndex()) {
                            val radioButton = MaterialRadioButton(this)
                            radioButton.id = index
                            radioButton.text = game
                            radioGroup.addView(radioButton, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                        radioGroup.check(0)
                        showAlertDialog("Choose one of your custom games", view,
                            View.OnClickListener {
                                val checkedGame = gamesList!![radioGroup.checkedRadioButtonId]
                                downloadGame(checkedGame)
                            })
                    }
                }
        } else {
            Toast.makeText(this, "Something went wrong when creating user session", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createUserSession() {
        val chooseAccountIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"),
                "Choose an account for reach your custom games",
                null, null, null
            )
        } else {
            AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"),
                true, "Choose an account for reach your custom games",
                null, null, null)
        }
        chooseAccountIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        userDataConsentActivity.launch(chooseAccountIntent)
        //startActivityForResult(chooseAccountIntent, 12345)
    }
    @SuppressLint("HardwareIds")
    private fun onUserDataConsentResult(data: Intent?) {
        val accountName  = data?.extras?.get(AccountManager.KEY_ACCOUNT_NAME) ?:
        "guest" + Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID )
        Log.d(TAG, "Selected account name is $accountName")
        accountSession = AccountSession(accountName as String)
    }

    private fun onCreateActivityResult(data: Intent?) {
        val customGameName = data?.getStringExtra(EXTRA_GAME_NAME)
        if (customGameName == null) {
            Log.e(TAG, "Encountered an error when getting the game data from Create Activity")
            return
        }
        downloadGame(customGameName)
    }

    private fun downloadGame(customGameName: String) {
        db.collection("games").document(customGameName).update(
            "accessCount", FieldValue.increment(1)
        )
        db.collection("games").document(customGameName).get().addOnSuccessListener { document ->
            val userImageList = document.toObject(UserImageList::class.java)
            if (userImageList?.images == null) {
                Log.e(TAG, "No images could fetched")
                Snackbar.make(
                    clRoot,
                    "Sorry, couldn't find such game, $customGameName",
                    Snackbar.LENGTH_SHORT
                ).show()
                return@addOnSuccessListener
            }
            for (imageUrl in userImageList.images) {
                Picasso.get().load(imageUrl).fetch()
            }
            Snackbar.make(clRoot, "You are now playing $customGameName", Snackbar.LENGTH_SHORT)
                .show()
            val numCards = userImageList.images.size * 2
            boardSize = BoardSize.getByValue(numCards)
            gameName = customGameName
            customGameImages = userImageList.images
            setupBoard()
        }.addOnFailureListener {
            Log.e(TAG, "Exception while fetching data from firebase:", it)
        }

    }

    @SuppressLint("InflateParams")
    private fun showCreationDialog() {
        val boardSizeView = LayoutInflater.from(this).inflate(R.layout.dialog_board_size, null)
        val radioGroupSize = boardSizeView.findViewById<RadioGroup>(R.id.radioGroup)

        showAlertDialog("Create your own memory board", boardSizeView, View.OnClickListener {
            val desiredBoardSize = when (radioGroupSize.checkedRadioButtonId) {
                R.id.rbEasy -> BoardSize.EASY
                R.id.rbMedium -> BoardSize.MEDIUM
                R.id.rbImpossible -> BoardSize.IMPOSSIBLE
                else -> BoardSize.HARD
            }
            val intent = Intent(this, CreateActivity::class.java)
            intent.putExtra(EXTRA_BOARD_SIZE, desiredBoardSize)
            intent.putExtra(EXTRA_SESSION_NAME, accountSession?.name)
            //new start activity for result
            createActivityResultLauncher.launch(intent)

            //old way of launching activity
            //startActivityForResult(intent, CREATE_REQUEST_CODE)
        })
    }

    @SuppressLint("InflateParams")
    private fun showNewSizeDialog() {
        val boardSizeView = LayoutInflater.from(this).inflate(R.layout.dialog_board_size, null)
        val radioGroupSize = boardSizeView.findViewById<RadioGroup>(R.id.radioGroup)
        when (boardSize) {
            BoardSize.EASY -> radioGroupSize.check(R.id.rbEasy)
            BoardSize.MEDIUM -> radioGroupSize.check(R.id.rbMedium)
            BoardSize.HARD -> radioGroupSize.check(R.id.rbHard)
            BoardSize.IMPOSSIBLE -> radioGroupSize.check(R.id.rbImpossible)
        }
        showAlertDialog("Choose new size", boardSizeView, View.OnClickListener {
            boardSize = when (radioGroupSize.checkedRadioButtonId) {
                R.id.rbEasy -> BoardSize.EASY
                R.id.rbMedium -> BoardSize.MEDIUM
                R.id.rbImpossible -> BoardSize.IMPOSSIBLE
                else -> BoardSize.HARD
            }
            gameName = null
            customGameImages = null
            setupBoard()
        })
    }

    @SuppressLint("InflateParams")
    private fun showDownloadDialog() {
        val boardDownloadView =
            LayoutInflater.from(this).inflate(R.layout.dialog_download_board, null)
        showAlertDialog(
            "Type the name of the game you want to download",
            boardDownloadView,
            View.OnClickListener {
                val etDownloadGame = boardDownloadView.findViewById<EditText>(R.id.etDownloadGame)
                val gameToDownload = etDownloadGame.text.toString().trim()
                downloadGame(gameToDownload)
            })
    }

    private fun showAlertDialog(
        title: String,
        view: View?,
        positiveClickListener: View.OnClickListener
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ ->
                positiveClickListener.onClick(null)
            }.show()
    }


    @SuppressLint("SetTextI18n")
    private fun setupBoard() {
        supportActionBar?.title = gameName ?: getString(R.string.app_name)
        stillWon = false
        when (boardSize) {
            BoardSize.EASY -> {
                tvNumMoves.text = "Easy 4 x 2"
                tvNumPairs.text = "Pairs: 0 / 4"
            }
            BoardSize.MEDIUM -> {
                tvNumMoves.text = "Medium 6 x 3"
                tvNumPairs.text = "Pairs: 0 / 9"
            }
            BoardSize.HARD -> {
                tvNumMoves.text = "Hard 6 x 4"
                tvNumPairs.text = "Pairs: 0 / 12"
            }
            BoardSize.IMPOSSIBLE -> {
                tvNumMoves.text = "Impossible 8 x 4"
                tvNumPairs.text = "Pairs: 0 / 16"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            tvNumPairs.setTextColor(getColor(R.color.color_progress_none))
        } else tvNumPairs.setTextColor(resources.getColor(R.color.color_progress_none))

        memoryGame = MemoryGame(boardSize, customGameImages)
        adapter = MemoryBoardAdapter(
            this,
            boardSize,
            memoryGame.cards,
            object : MemoryBoardAdapter.CardClickListener {
                override fun onCardClicked(position: Int) {
                    updateGameWithFlip(position)
                }
            })
        rvBoard.adapter = adapter
        rvBoard.layoutManager = GridLayoutManager(this, boardSize.getWidth())
        rvBoard.setHasFixedSize(true)
    }


    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    private fun updateGameWithFlip(position: Int) {
        //Error checking
        if (memoryGame.haveWonGame()) {
            Snackbar.make(clRoot, "You already won!", Snackbar.LENGTH_LONG).show()
            return
        }
        if (memoryGame.isCardFaceUp(position)) {
            Snackbar.make(clRoot, "Invalid move!", Snackbar.LENGTH_SHORT).show()
            return
        }
        //Actual flip over the card
        if (memoryGame.flipCard(position)) {
            Log.i(TAG, "Found a match! Num pairs found: ${memoryGame.numPairsFound}")
            val color = ArgbEvaluator().evaluate(
                memoryGame.numPairsFound.toFloat() / boardSize.getNumPairs().toFloat(),
                ContextCompat.getColor(this, R.color.color_progress_none),
                ContextCompat.getColor(this, R.color.color_progress_full)
            ) as Int
            tvNumPairs.setTextColor(color)
            tvNumPairs.text = "Pairs: ${memoryGame.numPairsFound} / ${boardSize.getNumPairs()}"
            if (memoryGame.haveWonGame()) {
                Snackbar.make(
                    clRoot,
                    "You won the game in ${memoryGame.getNumMoves()} moves! Congratulations!",
                    Snackbar.LENGTH_LONG
                ).show()
                stillWon = true

                CommonConfetti.rainingConfetti(
                    clRoot,
                    intArrayOf(Color.CYAN, Color.RED, Color.BLUE, Color.YELLOW)
                )
                    .stream(1000)

                Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
                    override fun run() {
                        if (stillWon) {
                            CommonConfetti.rainingConfetti(
                                clRoot,
                                intArrayOf(
                                    Color.CYAN,
                                    Color.RED,
                                    Color.BLUE,
                                    Color.YELLOW,
                                    Color.GREEN
                                )
                            )
                                .stream(1000)
                            Handler(Looper.getMainLooper()).postDelayed(this, 1000)
                        }
                    }
                }, 1000)
            }
        }
        tvNumMoves.text = "Moves: ${memoryGame.getNumMoves()}"
        adapter.notifyDataSetChanged()
    }
}