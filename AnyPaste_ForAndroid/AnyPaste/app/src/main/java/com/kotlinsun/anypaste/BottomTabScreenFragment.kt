package com.kotlinsun.anypaste

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment

interface BottomTabScreenHost {
    fun onBottomTabScreenViewCreated(@LayoutRes layoutRes: Int, root: View)
    fun onBottomTabScreenViewDestroyed(@LayoutRes layoutRes: Int, root: View)
}

class BottomTabScreenFragment : Fragment() {
    @get:LayoutRes
    private val layoutRes: Int
        get() = requireArguments().getInt(ARG_LAYOUT_RES)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(layoutRes, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? BottomTabScreenHost)?.onBottomTabScreenViewCreated(layoutRes, view)
    }

    override fun onDestroyView() {
        view?.let { root ->
            (activity as? BottomTabScreenHost)?.onBottomTabScreenViewDestroyed(layoutRes, root)
        }
        super.onDestroyView()
    }

    companion object {
        private const val ARG_LAYOUT_RES = "layout_res"

        fun newInstance(@LayoutRes layoutRes: Int): BottomTabScreenFragment =
            BottomTabScreenFragment().apply {
                arguments = Bundle().apply { putInt(ARG_LAYOUT_RES, layoutRes) }
            }
    }
}
