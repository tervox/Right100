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
    // Posicao em que o fragmento foi criado. Vai nos arguments (e nao num mapa em memoria)
    // para sobreviver a recriacao da Activity.
    private val ORIGINAL_POSITION = "original_position"

    private val fragments = HashMap<String, ViewPagerFragment>()
    var shouldInitFragment = true

    override fun getCount() = media.size

    override fun getItem(position: Int): Fragment {
        val medium = media[position]
        val bundle = Bundle()
        bundle.putSerializable(MEDIUM, medium)
        bundle.putBoolean(SHOULD_INIT_FRAGMENT, shouldInitFragment)
        bundle.putInt(ORIGINAL_POSITION, position)
        val fragment = if (medium.isVideo()) {
            VideoFragment()
        } else {
            PhotoFragment()
        }
        fragment.arguments = bundle
        return fragment
    }

    // O PagerAdapter so aceita POSITION_UNCHANGED ou POSITION_NONE aqui.
    // Devolver o INDICE novo do Medium parecia elegante, mas o FragmentStatePagerAdapter
    // guarda os fragmentos por posicao (mFragments) e usa a posicao na tag do
    // FragmentManager; ele nao move a referencia quando o ViewPager aceita o indice novo.
    // Depois de excluir, a pagina seguinte recebia um fragmento que ja estava em outra
    // posicao e ficava vazia - era a unica pagina preta, logo depois da que assumiu o lugar.
    override fun getItemPosition(item: Any): Int {
        val args = (item as? Fragment)?.arguments ?: return PagerAdapter.POSITION_NONE
        val medium = args.getSerializable(MEDIUM) as? Medium ?: return PagerAdapter.POSITION_NONE
        val newIndex = media.indexOfFirst { it.path == medium.path }
        if (newIndex == -1) return PagerAdapter.POSITION_NONE
        val createdAt = args.getInt(ORIGINAL_POSITION, -1)
        return if (createdAt == newIndex) PagerAdapter.POSITION_UNCHANGED else PagerAdapter.POSITION_NONE
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

    override fun setPrimaryItem(container: ViewGroup, position: Int, obj: Any) {
        super.setPrimaryItem(container, position, obj)
        val current = obj as? ViewPagerFragment
        // FragmentStatePagerAdapter pode entregar setMenuVisibility alguns frames
        // depois da troca. Sincronize pelo item primário para que o ExoPlayer novo
        // receba a superfície e o antigo pare imediatamente.
        fragments.values.forEach { fragment ->
            if (fragment is VideoFragment) {
                if (fragment === current) fragment.onBecameVisible()
                else fragment.onBecameHidden()
            }
        }
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
