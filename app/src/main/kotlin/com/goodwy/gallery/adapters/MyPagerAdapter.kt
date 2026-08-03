package com.goodwy.gallery.adapters

import android.os.Bundle
import android.os.Parcelable
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.viewpager.widget.PagerAdapter
import com.goodwy.gallery.activities.ViewPagerActivity
import com.goodwy.gallery.fragments.PhotoFragment
import com.goodwy.gallery.fragments.VideoFragment
import com.goodwy.gallery.fragments.ViewPagerFragment
import com.goodwy.gallery.helpers.MEDIUM
import com.goodwy.gallery.helpers.SHOULD_INIT_FRAGMENT
import com.goodwy.gallery.models.Medium

class MyPagerAdapter(val activity: ViewPagerActivity, fm: FragmentManager, val media: MutableList<Medium>) : FragmentStatePagerAdapter(fm) {
    private val fragments = HashMap<String, ViewPagerFragment>()
    var shouldInitFragment = true

    override fun getCount() = media.size

    override fun getItem(position: Int): Fragment {
        val medium = media[position]
        val bundle = Bundle()
        bundle.putSerializable(MEDIUM, medium)
        bundle.putBoolean(SHOULD_INIT_FRAGMENT, shouldInitFragment)
        val fragment = if (medium.isVideo()) {
            VideoFragment()
        } else {
            PhotoFragment()
        }
        fragment.arguments = bundle
        return fragment
    }

    // Antes: sempre retornava POSITION_NONE, o que forçava o FragmentStatePagerAdapter a
    // destruir e recriar TODOS os fragments instanciados (atual + até 4 vizinhos, já que
    // offscreenPageLimit=2) toda vez que notifyDataSetChanged() rodava — inclusive depois de
    // cada exclusão/movimentação de arquivo. Isso destruía VideoFragments com ExoPlayer ativo
    // desnecessariamente, causando a demora/instabilidade ao excluir ou mover.
    // Agora: identifica cada fragment pelo path do Medium que foi originalmente vinculado a ele
    // (guardado nos arguments em getItem()) e só reporta POSITION_NONE para o que realmente sumiu.
    override fun getItemPosition(item: Any): Int {
        val medium = (item as? Fragment)?.arguments?.getSerializable(MEDIUM) as? Medium
            ?: return PagerAdapter.POSITION_NONE
        val newIndex = media.indexOfFirst { it.path == medium.path }
        return if (newIndex == -1) PagerAdapter.POSITION_NONE else newIndex
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val fragment = super.instantiateItem(container, position) as ViewPagerFragment
        fragment.listener = activity
        val medium = fragment.arguments?.getSerializable(MEDIUM) as? Medium
        if (medium != null) {
            fragments[medium.path] = fragment
        }
        return fragment
    }

    override fun destroyItem(container: ViewGroup, position: Int, any: Any) {
        val fragment = any as? ViewPagerFragment
        val medium = fragment?.arguments?.getSerializable(MEDIUM) as? Medium
        if (medium != null) {
            fragments.remove(medium.path)
        }
        super.destroyItem(container, position, any)
    }

    fun getCurrentFragment(position: Int): ViewPagerFragment? {
        val medium = media.getOrNull(position) ?: return null
        return fragments[medium.path]
    }

    fun toggleFullscreen(isFullscreen: Boolean) {
        for ((pos, fragment) in fragments) {
            fragment.fullscreenToggled(isFullscreen)
        }
    }

    override fun saveState(): Parcelable? {
        val bundle = super.saveState() as Bundle?
        bundle?.putParcelableArray("states", null)
        return bundle
    }
}
