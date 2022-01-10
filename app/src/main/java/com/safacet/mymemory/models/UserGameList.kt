package com.safacet.mymemory.models

import com.google.firebase.firestore.PropertyName

data class UserGameList(
    @PropertyName("games") val games:List<String>? = null
)