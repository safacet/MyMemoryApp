package com.safacet.mymemory.models

import com.google.firebase.firestore.PropertyName

data class UserImageList(
    @PropertyName("accessCount") val accessCount: Int? = null,
    @PropertyName("images") val images: List<String>? = null,
    @PropertyName("userID") val userID: String? = null
)
