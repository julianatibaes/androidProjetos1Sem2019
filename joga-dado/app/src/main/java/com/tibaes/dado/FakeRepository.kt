package com.tibaes.dado

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.*

object FakeRepository {

    private val faces: List<String> = listOf(
        "1 - Um", "2 - Dois", "3 - Três", "4 - Quatro", "5 - Cinco", "6 - Seis",
        "7 - Sete", "8 - Oito", "9 - Nove", "10 - Dez", "11 - Onze", "12 - Doze",
        "13 - Treze", "14 - Quatorze", "15 - Quinze", "16 - Dezesseis", "17 - Dezessete",
        "18 - Dezoito", "19 - Dezenove", "20 - Vinte", "21 - Vinte e Um", "22 - Vinte e Dois",
        "23 - Vinte e Três", "24 - Vinte e Quatro", "25 - Vinte e Cinco", "26 - Vinte e Seis",
        "27 - Vinte e Sete", "28 - Vinte e Oito", "29 - Vinte e Nove", "30 - Trinta",
        "31 - Trinta e Um", "32 - Trinta e Dois"
    )

    private val _currentRandomFaces = MutableLiveData<String>()
    val currentRandomFaces: LiveData<String>
        get()  = _currentRandomFaces

    init {
        getRandomFace(6)
    }

    fun getRandomFace(size: Int){
        val random = Random()
        _currentRandomFaces.value =  faces[random.nextInt(size)]
    }

}