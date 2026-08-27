package com.kotlinsun.anypaste

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment

class ScreenFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(
        requireArguments().getInt(ARG_LAYOUT_RES),
        container,
        false,
    )

    companion object {
        private const val ARG_LAYOUT_RES = "layout_res"

        fun newInstance(@LayoutRes layoutRes: Int): ScreenFragment = ScreenFragment().apply {
            arguments = Bundle().apply { putInt(ARG_LAYOUT_RES, layoutRes) }
        }
    }
}
