package com.tibaes.dado

import androidx.databinding.Bindable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class MainViewModel : ViewModel() {

    val currentRandomFaces: LiveData<String>
        get() = FakeRepository.currentRandomFaces

    @Bindable
    val editTextContent = MutableLiveData<String>()

    fun onDisplayedEditTextContentClick() {
        randomFace(editTextContent)
    }

    fun randomFace(editTextContent_: MutableLiveData<String>){
        if(!editTextContent_.value.isNullOrEmpty()){
            val vRandom = editTextContent_.value.toString().toInt()
            when (vRandom) {
                in 2..32 -> FakeRepository.getRandomFace(vRandom)
                0, 1 -> FakeRepository.getRandomFace(2)
                else -> FakeRepository.getRandomFace(32)
            }
        }else{
            FakeRepository.getRandomFace(6)
        }
    }
}